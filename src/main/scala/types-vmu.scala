package hwacha

import Chisel._

abstract class VMUModule(clock: Clock = null, _reset: Bool = null)(implicit p: Parameters)
  extends HwachaModule(clock, _reset)(p) with VMUParameters
abstract class VMUBundle(implicit p: Parameters) extends HwachaBundle()(p) with VMUParameters

class VMUMemFn extends Bundle {
  val cmd = Bits(width = M_SZ)
  val mt = Bits(width = MT_SZ)
}

class VMUFn extends Bundle {
  val mode = Bits(width = SZ_VMU_MODE)
  val cmd = Bits(width = M_SZ)
  val mt = Bits(width = MT_SZ)
}

class VMUAuxVector(implicit p: Parameters) extends HwachaBundle()(p) {
  val stride = UInt(width = regLen)
}

object VMUAuxVector {
  def apply(stride: UInt)(implicit p: Parameters): VMUAuxVector = {
    val aux = new VMUAuxVector
    aux.stride := stride
    aux
  }
}

class VMUAuxScalar(implicit p: Parameters) extends HwachaBundle()(p) {
  val data = Bits(width = regLen)
  val id = UInt(width = log2Up(nSRegs))
}

object VMUAuxScalar {
def apply(data: Bits, id: UInt)(implicit p: Parameters): VMUAuxScalar = {
    val aux = new VMUAuxScalar
    aux.data := data
    aux.id := id
    aux
  }
}

class VMUAux(implicit p: Parameters) extends HwachaBundle()(p) {
  val union = Bits(width = math.max(
    new VMUAuxVector().toBits.getWidth,
    new VMUAuxScalar().toBits.getWidth))

  def vector(dummy: Int = 0) = new VMUAuxVector().fromBits(this.union)
  def scalar(dummy: Int = 0) = new VMUAuxScalar().fromBits(this.union)
}

class VMUOp(implicit p: Parameters) extends VMUBundle()(p) {
  val fn = new VMUFn
  val vlen = UInt(width = bVLen)
  val base = UInt(width = bVAddr)
  val aux = new VMUAux
}

class DecodedMemCommand extends Bundle {
  val load = Bool()
  val store = Bool()
  val amo = Bool()
  val pf = Bool()

  val read = Bool()
  val write = Bool()
}

object DecodedMemCommand {
  def apply[T <: UInt](cmd: T): DecodedMemCommand = {
    val dec = new DecodedMemCommand
    dec.load := (cmd === M_XRD)
    dec.store := (cmd === M_XWR)
    dec.amo := isAMO(cmd)
    dec.pf := isPrefetch(cmd)
    dec.read := (dec.load || dec.amo)
    dec.write := (dec.store || dec.amo)
    dec
  }
}

class DecodedMemType extends Bundle {
  val b = Bool() // byte
  val h = Bool() // halfword
  val w = Bool() // word
  val d = Bool() // doubleword
  val signed = Bool()

  def shift(dummy: Int = 0): UInt =
    Cat(this.w || this.d, this.h || this.d).toUInt
}

object DecodedMemType {
  def apply[T <: Data](mt: T): DecodedMemType = {
    val b = (mt === MT_B)
    val h = (mt === MT_H)
    val w = (mt === MT_W)
    val d = (mt === MT_D)
    val bu = (mt === MT_BU)
    val hu = (mt === MT_HU)
    val wu = (mt === MT_WU)

    val dec = new DecodedMemType
    dec.b := (b || bu)
    dec.h := (h || hu)
    dec.w := (w || wu)
    dec.d := d
    dec.signed := (b || h || w || d)
    dec
  }
}

/**********************************************************************/

class VVAQEntry(implicit p: Parameters) extends VMUBundle()(p) {
  val addr = UInt(width = bVAddrExtended)
}
class VVAQIO(implicit p: Parameters) extends DecoupledIO(new VVAQEntry()(p))

trait VMUAddr extends VMUBundle {
  val addr = UInt(width = bPAddr)
}
class VPAQEntry(implicit p: Parameters) extends VMUAddr
class VPAQIO(implicit p: Parameters) extends DecoupledIO(new VPAQEntry()(p))


trait VMUData extends VMUBundle {
  val data = Bits(width = tlDataBits)
}

class VSDQEntry(implicit p: Parameters) extends VMUData
class VSDQIO(implicit p: Parameters) extends DecoupledIO(new VSDQEntry()(p))

class VCUEntry(implicit p: Parameters) extends VMUBundle()(p) {
  val ecnt = UInt(width = bVLen)
}
class VCUIO(implicit p: Parameters) extends ValidIO(new VCUEntry()(p))


trait VMUTag extends VMUBundle {
  val tag = UInt(width = bTag)
}

class VMLUData(implicit p: Parameters) extends VMUData with VMUTag {
  val last = Bool()
}

class VLTEntry(implicit p: Parameters) extends VMUBundle()(p) with VMUMetaIndex with VMUMetaPadding {
  val mask = Bits(width = tlDataBytes >> 1)
}

class VLDQEntry(implicit p: Parameters) extends VMUData {
  val meta = new VLTEntry {
    val last = Bool()
  }
}
class VLDQIO(implicit p: Parameters) extends DecoupledIO(new VLDQEntry()(p))

/**********************************************************************/

class PredEntry(implicit p: Parameters) extends HwachaBundle()(p) {
  val pred = Bits(width = nPredSet)
}

class VMUMaskEntry_0(implicit p: Parameters) extends VMUBundle()(p) {
  val pred = Bool()
  val ecnt = UInt(width = bVLen)
  val last = Bool()

  val unit = new Bundle {
    val page = Bool() /* Entry is final for current page */
  }
  val nonunit = new Bundle {
    val shift = UInt(width = log2Up(nPredSet))
  }
}
class VMUMaskIO_0(implicit p: Parameters) extends DecoupledIO(new VMUMaskEntry_0()(p))

class VMUMaskEntry_1(implicit p: Parameters) extends VMUBundle()(p) {
  val data = Bits(width = tlDataBytes >> 1)
  val vsdq = Bool()
}
class VMUMaskIO_1(implicit p: Parameters) extends DecoupledIO(new VMUMaskEntry_1()(p)) {
  val meta = new VMUBundle {
    val eoff = UInt(INPUT, tlByteAddrBits - 1)
    val last = Bool(INPUT)
  }
}

/**********************************************************************/

/* Encodes 2^n as 0; values 1 to (2^n-1) are represented as normal. */
class CInt(n: Int) extends Bundle {
  val raw = UInt(width = n)
  def encode[T <: UInt](x: T) {
    assert(x != UInt(0), "CInt: invalid value")
    raw := x
  }
  def decode(dummy: Int = 0): UInt = Cat(raw === UInt(0), raw)
}

trait VMUMetaCount extends VMUBundle {
  val ecnt = new CInt(tlByteAddrBits-1)
}
trait VMUMetaPadding extends VMUBundle {
  val epad = UInt(width = tlByteAddrBits)
}
trait VMUMetaIndex extends VMUBundle {
  val eidx = UInt(width = bVLen)
}
trait VMUMetaStore extends VMUBundle {
  val last = Bool()
  val vsdq = Bool()
}

/**********************************************************************/

trait VMUMemOp extends VMUAddr {
  val fn = new VMUMemFn
}

class VMUMetaAddr(implicit p: Parameters) extends VMUMetaCount
  with VMUMetaPadding with VMUMetaStore {
  val mask = UInt(width = tlDataBytes >> 1)
}

class AGUEntry(implicit p: Parameters) extends VMUMemOp {
  val meta = new VMUMetaAddr with VMUMetaIndex
}

class AGUIO(implicit p: Parameters) extends DecoupledIO(new AGUEntry()(p))
