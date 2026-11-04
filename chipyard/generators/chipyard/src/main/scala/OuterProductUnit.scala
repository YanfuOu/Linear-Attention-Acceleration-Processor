package chipyard.example

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._

// ---------------------------------------------------------------------------
// Parameters and config key
// ---------------------------------------------------------------------------
//
// Implements the linear-attention state recurrence from the 10/20 meeting
// notes with Joe Devietti:
//
//   S_t = decay_t (.) S_{t-1}  +  k_t v_t^T      (state update, outer product)
//   o_t = S_t^T q_t                              (query)
//
// This is the "outer product unit" called out in the Lightning! slide
// addendum as the piece needed so the outer-product (and state-query) ops
// in the dataflow don't fall back to general-purpose, TPU-style compute.
case class OuterProductUnitParams(address: BigInt = 0x6000, lanes: Int = 16, elemBits: Int = 32)
case object OuterProductUnitKey extends Field[Option[OuterProductUnitParams]](None)

// ---------------------------------------------------------------------------
// The peripheral
//
// State S is an lanes x lanes register array, S(i)(j), row i = key dim,
// col j = value dim. All lanes-element vectors below are params.elemBits
// wide, two's-complement signed, except DECAY_VEC (see below).
//
// Register map (offsets from params.address), with the default lanes=16,
// elemBits=32 (each *_VEC window is 16 x 4B = 0x40 bytes):
//   0x000  K_VEC      (RW, 16 x 32-bit)  key vector k
//   0x040  V_VEC      (RW, 16 x 32-bit)  value vector v
//   0x080  Q_VEC      (RW, 16 x 32-bit)  query vector q
//   0x0C0  DECAY_VEC  (RW, 16 x 32-bit)  per-row decay, unsigned Q0.elemBits
//                                        fixed point: stored value v means
//                                        decay = v / 2^elemBits, so decay in
//                                        [0, 1). One decay scalar per state
//                                        row (key dim), broadcast across the
//                                        row's value-dim columns.
//   0x100  CMD        (W,   8-bit)  0=NOP 1=RESET 2=UPDATE 3=QUERY
//   0x104  BUSY       (R,   1-bit)  1 while the commanded op is committing
//   0x108  RETIRED    (R,  32-bit)  count of completed RESET/UPDATE/QUERY ops
//   0x10C  OUT_VEC    (R,  16 x 32-bit)  o = S^T q, valid after QUERY
//
// Activation function phi applied to K and Q at point of use is ReLU: easy
// to implement, explicitly flagged in the meeting notes as the "easy but
// bad" option versus Swish ("good but hard"). Swish is NOT implemented here
// -- it needs a sigmoid/exp approximation (LUT or piecewise-linear), and the
// team hadn't settled on one as of the 10/20 meeting. This is the swap
// point if/when that's decided.
// ---------------------------------------------------------------------------
class OuterProductUnit(params: OuterProductUnitParams, beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("outerproductunit", Seq("mylab,outerproductunit"))
  val node = TLRegisterNode(
    address   = Seq(AddressSet(params.address, 0xFFF)),
    device    = device,
    beatBytes = beatBytes)

  lazy val module = new LazyModuleImp(this) {
    val lanes    = params.lanes
    val elemBits = params.elemBits

    val kVec     = Reg(Vec(lanes, UInt(elemBits.W)))
    val vVec     = Reg(Vec(lanes, UInt(elemBits.W)))
    val qVec     = Reg(Vec(lanes, UInt(elemBits.W)))
    val decayVec = Reg(Vec(lanes, UInt(elemBits.W))) // unsigned Q0.elemBits fraction
    val outVec   = Reg(Vec(lanes, UInt(elemBits.W)))

    val state   = Reg(Vec(lanes, Vec(lanes, UInt(elemBits.W)))) // S(row=k-dim)(col=v-dim)
    val retired = RegInit(0.U(32.W))
    val busy    = RegInit(false.B)
    val cmdReg  = Reg(UInt(8.W))

    val sNop :: sReset :: sUpdate :: sQuery :: Nil = (0 to 3).map(_.U(8.W)).toList

    // phi = ReLU
    def relu(x: UInt): UInt = Mux(x.asSInt < 0.S, 0.U(elemBits.W), x)

    // The commanded op commits one cycle after CMD is written: the write
    // handler below latches the opcode into cmdReg and raises busy in the
    // same cycle (both are register writes, so both take effect on the next
    // edge); the switch below reads the now-stable cmdReg on that next
    // cycle, while busy is high, and clears busy again right after -- busy
    // is high for exactly the one cycle the op is committing.
    when (busy) {
      busy    := false.B
      retired := retired + 1.U

      switch (cmdReg) {
        is (sReset) {
          for (i <- 0 until lanes) {
            for (j <- 0 until lanes) {
              state(i)(j) := 0.U
            }
          }
        }
        is (sUpdate) {
          for (i <- 0 until lanes) {
            for (j <- 0 until lanes) {
              // fixed-point decay * previous state, rescaled back down by elemBits
              val decayed = ((decayVec(i).zext * state(i)(j).asSInt) >> elemBits).asUInt(elemBits - 1, 0)
              // k (after ReLU) outer v, truncated to elemBits like MyVec's VMUL
              val kv = (relu(kVec(i)).asSInt * vVec(j).asSInt).asUInt(elemBits - 1, 0)
              state(i)(j) := decayed + kv // two's-complement wraparound add, matches VADD
            }
          }
        }
        is (sQuery) {
          for (j <- 0 until lanes) {
            // dot product down column j of S against relu(q): plain truncating
            // UInt reduce, same overflow caveat as MyVec's VREDSUM.
            outVec(j) := (0 until lanes)
              .map(i => (state(i)(j).asSInt * relu(qVec(i)).asSInt).asUInt(elemBits - 1, 0))
              .reduce(_ + _)
          }
        }
      }
    }

    node.regmap(
      0x000 -> (0 until lanes).map(i => RegField(elemBits, kVec(i), RegFieldDesc(s"k$i", s"key vector element $i"))),
      0x040 -> (0 until lanes).map(i => RegField(elemBits, vVec(i), RegFieldDesc(s"v$i", s"value vector element $i"))),
      0x080 -> (0 until lanes).map(i => RegField(elemBits, qVec(i), RegFieldDesc(s"q$i", s"query vector element $i"))),
      0x0C0 -> (0 until lanes).map(i => RegField(elemBits, decayVec(i), RegFieldDesc(s"decay$i", s"decay[$i], Q0.$elemBits unsigned fraction"))),
      0x100 -> Seq(RegField.w(8, (valid: Bool, d: UInt) => {
                     when (valid && !busy) { cmdReg := d; busy := true.B }
                     true.B
                   })),
      0x104 -> Seq(RegField.r(1, busy)),
      0x108 -> Seq(RegField.r(32, retired)),
      0x10C -> (0 until lanes).map(j => RegField.r(elemBits, outVec(j), RegFieldDesc(s"out$j", s"S^T q element $j")))
    )
  }
}

// ---------------------------------------------------------------------------
// Trait that attaches the peripheral to the peripheral bus inside a clock domain
// ---------------------------------------------------------------------------
trait CanHavePeripheryOuterProductUnit { this: BaseSubsystem =>
  private val pbus = locateTLBusWrapper(PBUS)

  val outerProductUnitDomain = p(OuterProductUnitKey).map { params =>
    val domain = LazyModule(new ClockSinkDomain(take = None))
    val m = domain { LazyModule(new OuterProductUnit(params, pbus.beatBytes)(p)) }
    domain.clockNode := pbus.fixedClockNode
    pbus.coupleTo("outerproductunit") {
      m.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
    }
    m
  }
}

// ---------------------------------------------------------------------------
// Config fragment that enables the peripheral
// ---------------------------------------------------------------------------
class WithOuterProductUnit extends Config((site, here, up) => {
  case OuterProductUnitKey => Some(OuterProductUnitParams())
})
