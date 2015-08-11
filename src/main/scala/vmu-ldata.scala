package hwacha

import Chisel._

class MetadataBuffer extends VMUModule {
  val io = new Bundle {
    val r = new MetaReadIO(new VMULoadMetaEntry).flip
    val w = new MetaWriteIO(new VMULoadMetaEntry).flip
  }

  val valid = Reg(init = Bits(0, nVMDB))
  val data = Mem(io.r.data.clone, nVMDB)

  io.w.tag := PriorityEncoder(~valid)
  io.w.ready := !(valid.toBools.reduce(_&&_))

  val wen = io.w.valid && io.w.ready
  val valid_mask_r = UIntToOH(io.r.tag) & Fill(nVMDB, io.r.valid)
  val valid_mask_w = UIntToOH(io.w.tag) & Fill(nVMDB, wen)

  valid := (valid & (~valid_mask_r)) | valid_mask_w
  when (wen) {
    data(io.w.tag) := io.w.data
  }
  io.r.data := data(io.r.tag)
}

class VMULoadIO extends Bundle {
  val meta = new MetaWriteIO(new VMULoadMetaEntry)
  val load = Decoupled(new VMULoadData)
}

class LBox extends VMUModule {
  val io = new Bundle {
    val mbox = new VMULoadIO().flip
    val lane = new VLDQIO
  }

  val vldq = Module(new Queue(new VLDQEntry, nVLDQ))
  val vmdb = Module(new MetadataBuffer)

  vldq.io.enq.bits.data := io.mbox.load.bits.data
  vldq.io.enq.bits.meta := vmdb.io.r.data
  vldq.io.enq.bits.last := io.mbox.load.bits.last
  vldq.io.enq.valid := io.mbox.load.valid
  io.mbox.load.ready := vldq.io.enq.ready

  vmdb.io.w <> io.mbox.meta
  vmdb.io.r.tag := io.mbox.load.bits.tag
  vmdb.io.r.valid := vldq.io.enq.fire()

  io.lane <> vldq.io.deq
}
