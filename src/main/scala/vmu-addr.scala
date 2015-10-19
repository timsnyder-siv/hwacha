package hwacha

import Chisel._

class AGUPipeEntry(implicit p: Parameters) extends VMUBundle()(p) {
  val addr = UInt(width = bPAddr - tlByteAddrBits)
  val meta = new VMUMetaAddr
}
class AGUPipeIO(implicit p: Parameters) extends DecoupledIO(new AGUPipeEntry()(p))

class VVAQ(implicit p: Parameters) extends VMUModule()(p) {
  val io = new Bundle {
    val enq = new VVAQIO().flip
    val deq = new VVAQIO
  }

  val q = Module(new Queue(io.enq.bits, nVVAQ))
  q.io.enq <> io.enq
  io.deq <> q.io.deq
}

class ABox0(implicit p: Parameters) extends VMUModule()(p) {
  val io = new VMUIssueIO {
    val vvaq = new VVAQIO().flip
    val vpaq = new VPAQIO
    val vcu = new VCUIO

    val tlb = new TLBIO
    val mask = new VMUMaskIO_0().flip
    val xcpt = new XCPTIO().flip
  }

  val op = Reg(new VMUDecodedOp)
  private val mask = io.mask.bits

  val stride_n = op.aux.vector().stride << mask.nonunit.shift
  val stride = Mux(op.mode.unit, UInt(pgSize), stride_n)

  val addr_offset = Mux(op.mode.indexed, io.vvaq.bits.addr, stride)
  val addr_result = op.base + addr_offset
  val addr = Mux(op.mode.indexed, addr_result, op.base)

  val pred = mask.pred

  io.tlb.req.bits.addr := addr
  io.tlb.req.bits.store := op.cmd.write
  io.tlb.req.bits.mt := op.mt
  io.vpaq.bits.addr := io.tlb.paddr()
  io.vcu.bits.ecnt := mask.ecnt

  val vvaq_valid = !op.mode.indexed || io.vvaq.valid
  val vpaq_ready = !pred || io.vpaq.ready
  val tlb_ready = !pred || io.tlb.req.ready

  private def fire(exclude: Bool, include: Bool*) = {
    val rvs = Seq(io.mask.valid, vvaq_valid, vpaq_ready, tlb_ready)
    (rvs.filter(_ ne exclude) ++ include).reduce(_ && _)
  }

  io.op.ready := Bool(false)
  io.mask.ready := Bool(false)
  io.vvaq.ready := Bool(false)
  io.vpaq.valid := Bool(false)
  io.vcu.valid := Bool(false)
  io.tlb.req.valid := Bool(false)

  val s_idle :: s_busy :: Nil = Enum(UInt(), 2)
  val state = Reg(init = s_idle)
  val stall = Reg(init = Bool(false))

  switch (state) {
    is (s_idle) {
      io.op.ready := Bool(true)
      when (io.op.valid) {
        state := s_busy
        op := io.op.bits
      }
    }

    is (s_busy) {
      unless (stall || io.xcpt.prop.vmu.stall) {
        io.mask.ready := fire(io.mask.valid)
        io.vvaq.ready := fire(vvaq_valid, op.mode.indexed)
        io.vpaq.valid := fire(vpaq_ready, pred, !io.tlb.resp.xcpt)
        io.tlb.req.valid := vvaq_valid && io.mask.valid && pred

        when (fire(null)) {
          unless (op.mode.indexed || (op.mode.unit && !mask.unit.page)) {
            op.base := addr_result
          }
          when (io.tlb.resp.xcpt && pred) {
            stall := Bool(true)
          } .otherwise {
            io.vcu.valid := Bool(true)
            when (mask.last) {
              state := s_idle
            }
          }
        }
      }
    }
  }
}

class VPAQ(implicit p: Parameters) extends VMUModule()(p) with SeqParameters {
  val io = new Bundle {
    val enq = new VPAQIO().flip
    val deq = new VPAQIO

    val vcu = Valid(new VCUEntry).flip
    val la = new CounterLookAheadIO().flip
  }

  val q = Module(new Queue(io.enq.bits, nVPAQ))
  q.io.enq <> io.enq
  io.deq <> q.io.deq

  val vcucntr = Module(new LookAheadCounter(0, maxVCU))
  vcucntr.io.inc.cnt := io.vcu.bits.ecnt
  vcucntr.io.inc.update := io.vcu.valid
  vcucntr.io.dec <> io.la
}

class ABox1(implicit p: Parameters) extends VMUModule()(p) {
  val io = new VMUIssueIO {
    val vpaq = new VPAQIO().flip
    val pipe = new AGUPipeIO

    val mask = new VMUMaskIO_1().flip
    val xcpt = new XCPTIO().flip
    val la = new CounterLookAheadIO().flip
  }

  val op = Reg(new VMUDecodedOp)
  val shift = Reg(UInt(width = 2))

  private val limit = tlDataBytes >> 1
  private val lglimit = tlByteAddrBits - 1

  val lead = Reg(Bool())
  val beat = Reg(UInt(width = 1))
  private val beat_1 = (beat === UInt(1))

  private def offset(x: UInt) = x(tlByteAddrBits-1, 0)
  val pad_u_rear = Cat(op.mt.b && beat_1, UInt(0, tlByteAddrBits-1))
//val pad_u_rear = Mux(op.mt.b && beat_1, UInt(limit), UInt(0))
  val pad_u = Mux(lead, offset(op.base), pad_u_rear)
  val eoff = offset(io.vpaq.bits.addr) >> shift
  val epad = Mux(!op.mode.unit || lead, eoff, pad_u_rear)

  /* Equivalence: x modulo UInt(limit) */
  private def truncate(x: UInt) = x(tlByteAddrBits-2, 0)
  private def saturate(x: UInt) = {
    /* Precondition: x != UInt(0) */
    val v = truncate(x)
    Cat(v === UInt(0), v)
  }

  val ecnt_u_max = (UInt(tlDataBytes) - pad_u) >> shift
  val ecnt_u = saturate(ecnt_u_max)
  val ecnt_max = Mux(op.mode.unit, ecnt_u, UInt(1))

  val vlen_next = op.vlen.zext - ecnt_max
  val vlen_end = (vlen_next <= SInt(0))
  val ecnt_test = Mux(vlen_end, offset(op.vlen), ecnt_max)

  val xcpt = io.xcpt.prop.top.stall

  val valve = Reg(init = UInt(0, bVCU))
  val valve_off = (valve < ecnt_test)
  val valve_end = xcpt && (valve_off || (valve === ecnt_test))
  val ecnt = Mux(valve_off, offset(valve), ecnt_test)
  /* Track number of elements permitted to depart following VCU */
  val valve_add = Mux(io.la.reserve, io.la.cnt, UInt(0))
  val valve_sub = Mux(io.mask.fire(), ecnt, UInt(0))
  valve := valve + valve_add - valve_sub

  val en = !valve_off || xcpt
  val end = vlen_end || valve_end

  val blkidx = op.base(bPgIdx-1, tlByteAddrBits)
  val blkidx_next = blkidx + UInt(1)
  val blkidx_update = !op.mt.b || beat_1
  val blkidx_end = (blkidx_update && (blkidx_next === UInt(0)))

  val addr_ppn = io.vpaq.bits.addr(bPAddr-1, bPgIdx)
  val addr_pgidx = Mux(op.mode.unit,
    Cat(blkidx, Bits(0, tlByteAddrBits)),
    io.vpaq.bits.addr(bPgIdx-1, 0))
  val addr = Cat(addr_ppn, addr_pgidx)

  /* Clear predicates unrelated to the current request */
  val mask_ecnt = EnableDecoder(ecnt_max, limit)
  val mask_xcpt = EnableDecoder(valve, limit)
  val mask_aux_base = mask_ecnt & mask_xcpt
  val mask_aux = (mask_aux_base << truncate(epad))(limit-1, 0)
  val mask_data = io.mask.bits.data & mask_aux
  val pred = mask_data.orR
  val pred_hold = Reg(Bool())
  val pred_u = pred_hold || pred

  io.mask.meta.eoff := truncate(eoff)
  io.mask.meta.last := vlen_end

  io.pipe.bits.addr := addr(bPAddr-1, tlByteAddrBits)
  io.pipe.bits.meta.ecnt.encode(ecnt)
  io.pipe.bits.meta.epad := epad
  io.pipe.bits.meta.last := end
  io.pipe.bits.meta.mask := mask_data
  io.pipe.bits.meta.vsdq := io.mask.bits.vsdq

  val vpaq_deq_u = pred_u && (blkidx_end || vlen_end)
  val vpaq_deq = Mux(op.mode.unit, vpaq_deq_u, pred)
  val vpaq_valid = !pred || io.vpaq.valid

  private def fire(exclude: Bool, include: Bool*) = {
    val rvs = Seq(io.mask.valid, vpaq_valid, io.pipe.ready)
    (rvs.filter(_ ne exclude) ++ include).reduce(_ && _)
  }

  io.op.ready := Bool(false)
  io.mask.ready := Bool(false)
  io.vpaq.ready := Bool(false)
  io.pipe.valid := Bool(false)

  val s_idle :: s_busy :: Nil = Enum(UInt(), 2)
  val state = Reg(init = s_idle)

  switch (state) {
    is (s_idle) {
      io.op.ready := Bool(true)
      when (io.op.valid) {
        state := s_busy
        op := io.op.bits
        shift := io.op.bits.mt.shift()
      }
      beat := io.op.bits.base(tlByteAddrBits-1)
      lead := Bool(true)
      pred_hold := Bool(false)
    }

    is (s_busy) {
      when (en) {
        io.mask.ready := fire(io.mask.valid)
        io.vpaq.ready := fire(vpaq_valid, vpaq_deq)
        io.pipe.valid := fire(io.pipe.ready)

        when (fire(null)) {
          lead := Bool(false)
          pred_hold := pred_u && !blkidx_end
          when (op.mt.b) {
            beat := beat + UInt(1)
          }
          when (op.mode.unit && blkidx_update) {
            blkidx := blkidx_next
          }
          op.vlen := vlen_next
          when (end) {
            state := s_idle
          }
        }
      }
    }
  }
}

class ABox2(implicit p: Parameters) extends VMUModule()(p) {
  val io = new VMUIssueIO {
    val inner = new AGUPipeIO().flip
    val outer = new AGUIO
    val store = Valid(new VMUStoreCtrl)
  }

  private val outer = io.outer.bits
  private val inner = io.inner.bits

  val op = Reg(new VMUDecodedOp)
  val mt = DecodedMemType(op.fn.mt)

  val eidx = Reg(UInt(width = bVLen))

  val offset = (inner.meta.epad << mt.shift())(tlByteAddrBits-1, 0)

  outer.addr := Cat(inner.addr, offset)
  outer.fn.cmd := op.fn.cmd
  outer.fn.mt := op.fn.mt
  outer.meta.eidx := eidx
  outer.meta.ecnt := inner.meta.ecnt
  outer.meta.epad := inner.meta.epad
  outer.meta.last := inner.meta.last
  outer.meta.mask := inner.meta.mask
  outer.meta.vsdq := inner.meta.vsdq

  io.store.bits.mode.unit := op.mode.unit
  io.store.bits.base := op.base(tlByteAddrBits-1, 0)
  io.store.bits.mt := mt

  private def fire(exclude: Bool, include: Bool*) = {
    val rvs = Seq(io.inner.valid, io.outer.ready)
    (rvs.filter(_ ne exclude) ++ include).reduce(_ && _)
  }

  io.op.ready := Bool(false)
  io.inner.ready := Bool(false)
  io.outer.valid := Bool(false)
  io.store.valid := Bool(false)

  val s_idle :: s_busy :: Nil = Enum(UInt(), 2)
  val state = Reg(init = s_idle)

  switch (state) {
    is (s_idle) {
      io.op.ready := Bool(true)
      when (io.op.valid) {
        state := s_busy
        op := io.op.bits
      }
      eidx := UInt(0)
    }

    is (s_busy) {
      io.inner.ready := fire(io.inner.valid)
      io.outer.valid := fire(io.outer.ready)
      io.store.valid := Bool(true)

      when (fire(null)) {
        eidx := eidx + inner.meta.ecnt.decode()
        when (inner.meta.last) {
          state := s_idle
        }
      }
    }
  }
}

class ABox(implicit p: Parameters) extends VMUModule()(p) {
  val io = new Bundle {
    val op = Vec.fill(3)(Decoupled(new VMUDecodedOp).flip)
    val lane = new VVAQIO().flip
    val mem = new AGUIO

    val tlb = new TLBIO
    val mask = new VMUMaskIO().flip
    val xcpt = new XCPTIO().flip
    val la = new CounterLookAheadIO().flip

    val store = Valid(new VMUStoreCtrl)
  }

  val vvaq = Module(new VVAQ)
  val vpaq = Module(new VPAQ)
  val abox0 = Module(new ABox0)
  val abox1 = Module(new ABox1)
  val abox2 = Module(new ABox2)

  vvaq.io.enq <> io.lane

  abox0.io.op <> io.op(0)
  abox0.io.mask <> io.mask.ante
  abox0.io.vvaq <> vvaq.io.deq
  abox0.io.xcpt <> io.xcpt
  io.tlb <> abox0.io.tlb

  vpaq.io.enq <> abox0.io.vpaq
  vpaq.io.vcu <> abox0.io.vcu
  vpaq.io.la <> io.la

  abox1.io.op <> io.op(1)
  abox1.io.mask <> io.mask.post
  abox1.io.vpaq <> vpaq.io.deq
  abox1.io.xcpt <> io.xcpt
  abox1.io.la <> io.la

  val pipe = Module(new Queue(abox1.io.pipe.bits, 2))
  pipe.io.enq <> abox1.io.pipe

  abox2.io.op <> io.op(2)
  abox2.io.inner <> pipe.io.deq
  io.mem <> abox2.io.outer

  io.store <> abox2.io.store
}
