package chipyard.example

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import org.chipsalliance.cde.config.{Config, Field, Parameters}

// ---------------------------------------------------------------------------
// Parameters and config key
// ---------------------------------------------------------------------------
//
// Implements the Gated DeltaNet state-update recurrence from the whiteboard
// doc "linear attention accel logic" (Drive, 2026-11-10), which supersedes
// the simpler plain-outer-product version this module started as (the
// 10/20 meeting notes' S_t = decay (.) S_{t-1} + k_t v_t^T):
//
//   e_t = b_t (.) k_t          z_t = w_t (.) v_t
//   Sbar_t = D_t S_{t-1}       D_t = diag(alpha_t)
//   sigma_t = z_t - Sbar_t^T e_t
//   S_t = Sbar_t + k_t sigma_t^T
//
// b_t/w_t are plain per-element gate vectors (not a nonlinearity) -- this
// formulation doesn't use a kernel feature map phi the way a plain linear-
// attention approximation of softmax would. That means the ReLU-vs-Swish
// activation-function question from the 10/20 notes is superseded here:
// this is a delta-rule (online least-squares) update, not a kernelized
// approximation, so no phi is applied to k/q anywhere below. Flagging this
// explicitly since it's an inference connecting two source docs, not
// something either one states outright -- worth confirming with the team.
//
// Query (o_t = S_t^T q_t) isn't on this whiteboard page at all; kept here
// since the 10/20 notes specify it as part of the overall algorithm and
// nothing in the new doc removes it.
case class OuterProductUnitParams(address: BigInt = 0x6000, lanes: Int = 16, elemBits: Int = 32)
case object OuterProductUnitKey extends Field[Option[OuterProductUnitParams]](None)

// ---------------------------------------------------------------------------
// The peripheral
//
// State S is an lanes x lanes register array, S(i)(j), row i = key dim,
// col j = value dim. All lanes-element vectors below are params.elemBits
// wide, two's-complement signed, except DECAY_VEC (alpha_t -- see below).
//
// Register map (offsets from params.address), with the default lanes=16,
// elemBits=32 (each *_VEC window is 16 x 4B = 0x40 bytes):
//   0x000  K_VEC      (RW, 16 x 32-bit)  key vector k_t
//   0x040  V_VEC      (RW, 16 x 32-bit)  value vector v_t
//   0x080  Q_VEC      (RW, 16 x 32-bit)  query vector q_t
//   0x0C0  DECAY_VEC  (RW, 16 x 32-bit)  alpha_t, unsigned Q0.elemBits
//                                        fixed point: stored value v means
//                                        decay = v / 2^elemBits, in [0, 1).
//                                        One decay scalar per state row
//                                        (key dim), via D_t = diag(alpha_t).
//   0x100  B_VEC      (RW, 16 x 32-bit)  gate for k_t: e_t = b_t (.) k_t
//   0x140  W_VEC      (RW, 16 x 32-bit)  gate for v_t: z_t = w_t (.) v_t
//   0x180  CMD        (W,   8-bit)  0=NOP 1=RESET 2=UPDATE 3=QUERY
//   0x184  BUSY       (R,   1-bit)  1 while the commanded op is in flight
//   0x188  RETIRED    (R,  32-bit)  count of completed RESET/UPDATE/QUERY ops
//   0x18C  OUT_VEC    (R,  16 x 32-bit)  o = S^T q, valid after QUERY
//
// UPDATE is multi-cycle: one state *column* per cycle (lanes cycles total,
// plus one cycle to precompute e_t/z_t), not one shot across all lanes^2
// elements. This matches the whiteboard's "option 2: calculate columns"
// dataflow (same cycle count as the row-wise option there, ~lanes+2
// cycles) -- and since S lives in registers here (no SRAM load latency to
// hide), this register-based design needs no more than that: lanes cycles
// for the column loop plus the 1-cycle e_t/z_t prep, i.e. at or under the
// whiteboard's own lanes+2 estimate.
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
    val decayVec = Reg(Vec(lanes, UInt(elemBits.W))) // alpha_t, unsigned Q0.elemBits fraction
    val bVec     = Reg(Vec(lanes, UInt(elemBits.W))) // gate for k_t
    val wVec     = Reg(Vec(lanes, UInt(elemBits.W))) // gate for v_t
    val outVec   = Reg(Vec(lanes, UInt(elemBits.W)))

    val eVec = Reg(Vec(lanes, UInt(elemBits.W))) // b_t (.) k_t, latched at UPDATE start
    val zVec = Reg(Vec(lanes, UInt(elemBits.W))) // w_t (.) v_t, latched at UPDATE start

    val state   = Reg(Vec(lanes, Vec(lanes, UInt(elemBits.W)))) // S(row=k-dim)(col=v-dim)
    val retired = RegInit(0.U(32.W))
    val busy    = RegInit(false.B)
    val cmdReg  = Reg(UInt(8.W))

    val sNop :: sReset :: sUpdate :: sQuery :: Nil = (0 to 3).map(_.U(8.W)).toList

    // UPDATE's column-streaming sub-FSM
    val uIdle :: uCol :: Nil = Enum(2)
    val updSt = RegInit(uIdle)
    val col   = RegInit(0.U(log2Ceil(lanes + 1).W))

    // signed multiply, truncated to the low elemBits bits -- same "low N
    // bits of the product" convention MyVec's VMUL already established.
    def mulTrunc(a: SInt, b: SInt): UInt = (a * b).asUInt(elemBits - 1, 0)

    // fixed-point multiply: an elemBits-wide signed value times an
    // unsigned Q0.elemBits fraction, rescaled back down by elemBits bits.
    def fixedMulDecay(alpha: UInt, s: UInt): UInt =
      ((alpha.zext * s.asSInt) >> elemBits).asUInt(elemBits - 1, 0)

    // One column of the UPDATE recurrence per cycle while the sub-FSM is
    // active. Takes priority over the single-cycle dispatch below so a
    // fresh CMD write can't interrupt an in-flight UPDATE.
    //
    // The whole point of doing this column-at-a-time instead of
    // element-at-a-time: every `.map(i => ...)` below over `0 until lanes`
    // is lanes independent multiplier units, wired side by side, all
    // evaluated in the SAME cycle -- not a software loop. Each column-cycle
    // does lanes multiplies for the decay scale, lanes multiplies (feeding
    // an adder tree) for the dot product, and lanes more multiplies for
    // the final outer-product add -- 3*lanes products computed
    // simultaneously per cycle, not one at a time. That's what turns a
    // lanes^2-element state update into ~lanes cycles instead of lanes^2:
    // lanes columns, each one fully parallel across its lanes elements.
    when (updSt === uCol) {
      val sbar = VecInit((0 until lanes).map(i => fixedMulDecay(decayVec(i), state(i)(col)))) // lanes decay-multiplies, parallel
      val dotWide = (0 until lanes).map(i => mulTrunc(sbar(i).asSInt, eVec(i).asSInt)).reduce(_ + _) // lanes multiplies + adder tree, parallel
      val dot   = dotWide(elemBits - 1, 0) // Sbar_t^T e_t, element [col]
      val sigma = (zVec(col).asSInt - dot.asSInt).asUInt(elemBits - 1, 0) // z_t - Sbar_t^T e_t

      for (i <- 0 until lanes) {
        state(i)(col) := sbar(i) + mulTrunc(sigma.asSInt, kVec(i).asSInt) // lanes outer-product multiplies, parallel; col `col`
      }

      when (col === (lanes - 1).U) {
        updSt   := uIdle
        busy    := false.B
        retired := retired + 1.U
      } .otherwise {
        col := col + 1.U
      }
    }.elsewhen (busy) {
      // Single-cycle dispatch/commit for RESET, QUERY, NOP; UPDATE instead
      // precomputes e_t/z_t here and hands off to the column loop above.
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
        is (sQuery) {
          for (j <- 0 until lanes) {
            val dotWide = (0 until lanes).map(i => mulTrunc(state(i)(j).asSInt, qVec(i).asSInt)).reduce(_ + _)
            outVec(j) := dotWide(elemBits - 1, 0) // S^T q, element [j]
          }
        }
        is (sUpdate) {
          eVec    := VecInit((0 until lanes).map(i => mulTrunc(bVec(i).asSInt, kVec(i).asSInt)))
          zVec    := VecInit((0 until lanes).map(i => mulTrunc(wVec(i).asSInt, vVec(i).asSInt)))
          updSt   := uCol
          col     := 0.U
          busy    := true.B  // override the default above: not done yet
          retired := retired // override: don't count it until the column loop finishes
        }
      }
    }

    node.regmap(
      0x000 -> (0 until lanes).map(i => RegField(elemBits, kVec(i), RegFieldDesc(s"k$i", s"key vector element $i"))),
      0x040 -> (0 until lanes).map(i => RegField(elemBits, vVec(i), RegFieldDesc(s"v$i", s"value vector element $i"))),
      0x080 -> (0 until lanes).map(i => RegField(elemBits, qVec(i), RegFieldDesc(s"q$i", s"query vector element $i"))),
      0x0C0 -> (0 until lanes).map(i => RegField(elemBits, decayVec(i), RegFieldDesc(s"decay$i", s"decay[$i], Q0.$elemBits unsigned fraction"))),
      0x100 -> (0 until lanes).map(i => RegField(elemBits, bVec(i), RegFieldDesc(s"b$i", s"gate for k[$i]"))),
      0x140 -> (0 until lanes).map(i => RegField(elemBits, wVec(i), RegFieldDesc(s"w$i", s"gate for v[$i]"))),
      0x180 -> Seq(RegField.w(8, (valid: Bool, d: UInt) => {
                     when (valid && !busy) { cmdReg := d; busy := true.B }
                     true.B
                   })),
      0x184 -> Seq(RegField.r(1, busy)),
      0x188 -> Seq(RegField.r(32, retired)),
      0x18C -> (0 until lanes).map(j => RegField.r(elemBits, outVec(j), RegFieldDesc(s"out$j", s"S^T q element $j")))
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
