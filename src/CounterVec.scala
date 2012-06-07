package hwacha

import Chisel._
import Node._
import scala.math._

class io_counter_vec(ADDR_SIZE: Int) extends Bundle {
  val flush = Bool(INPUT)

  val enq = new FIFOIO()( Bits(width=1) ).flip()
  val deq = new FIFOIO()( Bits(width=1) )

  val update_from_issue = new io_update_num_cnt().flip()
  val update_from_seq = new io_update_num_cnt().flip()
  val update_from_evac = new io_update_num_cnt().flip()

  val markLast = Bool(INPUT)
  val deq_last = Bool(OUTPUT)
  val rtag = Bits(ADDR_SIZE, OUTPUT)
}

class CounterVec(DEPTH: Int) extends Component {

  val ADDR_SIZE = log2Up(DEPTH)
  val io = new io_counter_vec(ADDR_SIZE)

  val next_write_ptr = UFix(width = ADDR_SIZE)
  val write_ptr = Reg(next_write_ptr, resetVal = UFix(0, ADDR_SIZE))

  val next_last_write_ptr = UFix(width = ADDR_SIZE)
  val last_write_ptr = Reg(next_last_write_ptr, resetVal = UFix(0, ADDR_SIZE))

  val next_read_ptr = UFix(width = ADDR_SIZE)
  val read_ptr = Reg(next_read_ptr, resetVal = UFix(0, ADDR_SIZE))

  val next_full = Bool()
  val full = Reg(next_full, resetVal = Bool(false))

  next_write_ptr := write_ptr
  next_last_write_ptr := last_write_ptr
  next_read_ptr := read_ptr
  next_full := full

  val do_enq = io.enq.valid && io.enq.ready
  val do_deq = io.deq.ready && io.deq.valid

  when (do_deq) { next_read_ptr := read_ptr + UFix(1) }

  when(do_enq) 
  { 
    next_write_ptr := write_ptr + UFix(1) 
    next_last_write_ptr := write_ptr
  }

  when (io.flush) 
  {
    next_read_ptr := UFix(0, ADDR_SIZE)
    next_write_ptr := UFix(0, ADDR_SIZE)
    next_last_write_ptr := UFix(0, ADDR_SIZE)
  }

  when (io.flush)
  {
    next_full := Bool(false)
  }
  . elsewhen (do_enq && !do_deq && (next_write_ptr === read_ptr))
  {
    next_full := Bool(true)
  }
  . elsewhen (do_deq && full) 
  {
    next_full := Bool(false)
  }
  . otherwise 
  {
    next_full := full
  }

  val empty = !full && (read_ptr === write_ptr)

  io.enq.ready := !full
  io.deq.valid := !empty

  val inc_vec = Vec(DEPTH){ Bool() }
  val dec_vec = Vec(DEPTH){ Bool() }
  val empty_vec = Vec(DEPTH){ Bool() }

  val next_last = Vec(DEPTH){ Bool() }
  val array_last = Vec(DEPTH){ Reg(){ Bool() } }

  array_last := next_last
  next_last := array_last

  for(i <- 0 until DEPTH)
  {
    val counter = new qcnt(0, DEPTH, true)
    counter.io.flush := io.flush
    counter.io.inc := inc_vec(i)
    counter.io.dec := dec_vec(i)
    empty_vec(i) := counter.io.empty
  }

  //defaults
  for(i <- 0 until DEPTH)
  {
    inc_vec(i) := Bool(false)
    dec_vec(i) := Bool(false)
  }

  when (do_enq) { 
    // on an enq, a vf instruction will write a zero 
    inc_vec(write_ptr) := io.enq.bits.toBool 
  }

  when (do_enq) {
    // on an enq, a vf instruction will write a zero
    next_last(write_ptr) := io.enq.bits.toBool 
  }
  when (io.markLast) { next_last(last_write_ptr) := Bool(true) }

  when (io.update_from_issue.valid) { inc_vec(io.update_from_issue.bits) := Bool(true) }
  when (io.update_from_seq.valid) { dec_vec(io.update_from_seq.bits) := Bool(true) }
  when (io.update_from_evac.valid) { dec_vec(read_ptr) := Bool(true) }

  io.deq.bits := empty_vec(read_ptr)
  io.deq_last := array_last(read_ptr)

  io.rtag := write_ptr
}
