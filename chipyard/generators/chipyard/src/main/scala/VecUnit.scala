package chipyard.example

import chisel3._
import chisel3.util._

case class VecUnitParams(
  d: Int = 8,          // vector dimension
  elemWidth: Int = 32  // bits per element
) {
  require(d > 0 && elemWidth > 0)
}

object MyVecISA {
  val NOP     = 0x00
  val VEC_VEC_ADD = 0x01
  
}

class VecUnitIO(p: VecUnitParams) extends Bundle {
  // One vector in, with the slot (0 or 1) it should be stored into
  val in = Flipped(Decoupled(new Bundle {
    val vec  = Vec(p.d, UInt(p.elemWidth.W))
    val slot = UInt(1.W)
  }))
  // One vector out
  val out = Decoupled(Vec(p.d, UInt(p.elemWidth.W)))
  // Status: which of the two storage slots hold valid data
  val slotValid = Output(Vec(2, Bool()))
}

class VecUnit(p: VecUnitParams) extends Module {
  val io = IO(new VecUnitIO(p))

  // Storage for two vectors
  val store = Reg(Vec(2, Vec(p.d, UInt(p.elemWidth.W))))
  val valid = RegInit(VecInit(Seq.fill(2)(false.B)))
  io.slotValid := valid

  // Write path: store the incoming vector into the selected slot
  io.in.ready := true.B
  when(io.in.fire) {
    store(io.in.bits.slot) := io.in.bits.vec
    valid(io.in.bits.slot) := true.B
  }

  // Output path: runs once both slots are filled
  val outReg      = Reg(Vec(p.d, UInt(p.elemWidth.W)))
  val outValid    = RegInit(false.B)
  val bothValid   = valid(0) && valid(1)

  when(bothValid && !outValid) {
    // TODO: replace with your real operation on store(0) and store(1)
    for (i <- 0 until p.d) {
      outReg(i) := store(0)(i) + store(1)(i)   // placeholder: elementwise add
    }
    outValid := true.B
  }

  io.out.valid := outValid
  io.out.bits  := outReg
  when(io.out.fire) {
    outValid := false.B
    valid(0) := false.B   // free both slots once the result is consumed
    valid(1) := false.B
  }
}