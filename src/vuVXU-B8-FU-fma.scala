package hwacha

import Chisel._
import Node._
import hardfloat._
import Constants._

class io_cp_fma(width: Int) extends Bundle
{
  val valid = Bool(OUTPUT)
  val cmd = Bits(FCMD_WIDTH, OUTPUT)
  val rm = Bits(3, OUTPUT)
  val in1 = Bits(width, OUTPUT)
  val in2 = Bits(width, OUTPUT)
  val in3 = Bits(width, OUTPUT)
  val out = Bits(width, INPUT)
  val exc = Bits(5, INPUT)
}

class io_cp_dfma extends io_cp_fma(65)
class io_cp_sfma extends io_cp_fma(33)

class vuVXU_Banked8_FU_fma extends Component
{
  val io = new Bundle
  {
    val valid = Bool(INPUT)
    val fn    = Bits(SZ_VAU1_FN, INPUT)
    val in0   = Bits(SZ_DATA, INPUT)
    val in1   = Bits(SZ_DATA, INPUT)
    val in2   = Bits(SZ_DATA, INPUT)
    val out   = Bits(SZ_DATA, OUTPUT)
    val exc   = Bits(SZ_EXC, OUTPUT)

    val cp_dfma = new io_cp_dfma()
    val cp_sfma = new io_cp_sfma()
  }

  if (HAVE_FMA)
  {
    // use in0 & in2 for a two operand flop (add,sub,mul)
    // use in0, in1, & in2 otherwise

    val fma_op = MuxCase(
      Bits("b00",2), Array(
        (io.fn(RG_VAU1_FN) === VAU1_SUB || io.fn(RG_VAU1_FN) === VAU1_MSUB) -> Bits("b01",2),
        (io.fn(RG_VAU1_FN) === VAU1_NMSUB) -> Bits("b10",2),
        (io.fn(RG_VAU1_FN) === VAU1_NMADD) -> Bits("b11",2)
      ))

    val one_dp = Bits("h8000000000000000", 65)
    val one_sp = Bits("h80000000", 65)
    val fma_multiplicand = io.in0
    val fma_multiplier = MuxCase(
      io.in1, Array(
        ((io.fn(RG_VAU1_FP) === Bits("b1",1)) && (io.fn(RG_VAU1_FN) === VAU1_ADD || io.fn(RG_VAU1_FN) === VAU1_SUB)) -> one_dp,
        ((io.fn(RG_VAU1_FP) === Bits("b0",1)) && (io.fn(RG_VAU1_FN) === VAU1_ADD || io.fn(RG_VAU1_FN) === VAU1_SUB)) -> one_sp,
        ((io.fn(RG_VAU1_FN) === VAU1_MUL)) -> io.in2
      ))

    val fma_addend = Mux(
      io.fn(RG_VAU1_FN) === VAU1_MUL, Bits(0,65),
      io.in2)

    val val_fma_dp = io.valid & (io.fn(RG_VAU1_FP) === Bits("b1",1))
    val val_fma_sp = io.valid & (io.fn(RG_VAU1_FP) === Bits("b0",1))

    val fma_dp = new mulAddSubRecodedFloat64_1()
    fma_dp.io.op := Fill(2,val_fma_dp) & fma_op
    fma_dp.io.a  := Fill(65,val_fma_dp) & fma_multiplicand
    fma_dp.io.b  := Fill(65,val_fma_dp) & fma_multiplier
    fma_dp.io.c  := Fill(65,val_fma_dp) & fma_addend
    fma_dp.io.roundingMode := Fill(3,val_fma_dp) & io.fn(RG_VAU1_RM)
    val result_dp = Cat(fma_dp.io.exceptionFlags, fma_dp.io.out)

    val fma_sp = new mulAddSubRecodedFloat32_1()
    fma_sp.io.op := Fill(2,val_fma_sp) & fma_op
    fma_sp.io.a  := Fill(33,val_fma_sp) & fma_multiplicand(32,0)
    fma_sp.io.b  := Fill(33,val_fma_sp) & fma_multiplier(32,0)
    fma_sp.io.c  := Fill(33,val_fma_sp) & fma_addend(32,0)
    fma_sp.io.roundingMode := Fill(3,val_fma_sp) & io.fn(RG_VAU1_RM)
    val result_sp = Cat(fma_sp.io.exceptionFlags, fma_sp.io.out)

    val result = Mux(
      io.fn(RG_VAU1_FP), result_dp,
      Cat(result_sp(37,33), Bits("hFFFFFFFF",32), result_sp(32,0)))

    val pipereg = ShiftRegister(FMA_STAGES-1, 70, io.valid, result)

    Match(pipereg, io.exc, io.out)

    io.cp_dfma.valid := Bool(false)
    io.cp_sfma.valid := Bool(false)
  }
  else
  {
    require(DFMA_STAGES >= SFMA_STAGES)

    val rocket_cmd = MuxLookup(
      io.fn(RG_VAU1_FN), FCMD_X, Array(
        VAU1_ADD -> FCMD_ADD,
        VAU1_SUB -> FCMD_SUB,
        VAU1_MUL -> FCMD_MUL,
        VAU1_MADD -> FCMD_MADD,
        VAU1_MSUB -> FCMD_MSUB,
        VAU1_NMSUB -> FCMD_NMSUB,
        VAU1_NMADD -> FCMD_NMADD
      ))

    val fn = io.fn(RG_VAU1_FN)
    val two_operands = fn === VAU1_ADD || fn === VAU1_SUB || fn === VAU1_MUL

    io.cp_dfma.valid := io.valid && io.fn(RG_VAU1_FP) === Bits(1)
    io.cp_dfma.cmd := rocket_cmd
    io.cp_dfma.rm := io.fn(RG_VAU1_RM)
    io.cp_dfma.in1 := io.in0
    io.cp_dfma.in2 := Mux(two_operands, io.in2, io.in1)
    io.cp_dfma.in3 := io.in2

    io.cp_sfma.valid := io.valid && io.fn(RG_VAU1_FP) === Bits(0)
    io.cp_sfma.cmd := rocket_cmd
    io.cp_sfma.rm := io.fn(RG_VAU1_RM)
    io.cp_sfma.in1 := io.in0
    io.cp_sfma.in2 := Mux(two_operands, io.in2, io.in1)
    io.cp_sfma.in3 := io.in2

    val dp = ShiftRegister(DFMA_STAGES-2, 1, Bool(true), io.fn(RG_VAU1_FP))

    io.out := Mux(dp, io.cp_dfma.out,
                  Cat(Bits("hFFFFFFFF",32), ShiftRegister(DFMA_STAGES-SFMA_STAGES-1, 33, Bool(true), io.cp_sfma.out)))
    io.exc := Mux(dp, io.cp_dfma.exc,
                  ShiftRegister(DFMA_STAGES-SFMA_STAGES-1, SZ_EXC, Bool(true), io.cp_sfma.exc))
  }
}
