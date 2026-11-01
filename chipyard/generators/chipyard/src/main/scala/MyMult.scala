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
case class MyMultParams(address: BigInt = 0x4000)
case object MyMultKey extends Field[Option[MyMultParams]](None)

// ---------------------------------------------------------------------------
// The peripheral
//
// Register map (offsets from params.address):
//   0x00  a       (RW, 32-bit)  operand a
//   0x04  b       (RW, 32-bit)  operand b
//   0x08  start   (W,   1-bit)  write anything to start the multiply
//   0x0C  done    (R,   1-bit)  1 when idle / result valid
//   0x10  result  (R,  64-bit)  a * b
// ---------------------------------------------------------------------------
class MyMult(params: MyMultParams, beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("mymult", Seq("mylab,mymult"))
  val node = TLRegisterNode(
    address   = Seq(AddressSet(params.address, 0xFFF)),
    device    = device,
    beatBytes = beatBytes)

  lazy val module = new LazyModuleImp(this) {
    val a      = Reg(UInt(32.W))
    val b      = Reg(UInt(32.W))
    val result = RegInit(0.U(64.W))
    val busy   = RegInit(false.B)
    val cnt    = RegInit(0.U(3.W))

    // 4-cycle fake latency so the done flag is meaningful
    when (busy) {
      cnt := cnt + 1.U
      when (cnt === 3.U) {
        result := a * b
        busy   := false.B
        cnt    := 0.U
      }
    }

    node.regmap(
      0x00 -> Seq(RegField(32, a, RegFieldDesc("a", "operand a"))),
      0x04 -> Seq(RegField(32, b, RegFieldDesc("b", "operand b"))),
      0x08 -> Seq(RegField.w(1, (valid: Bool, d: UInt) => {
                   when (valid && !busy) { busy := true.B; cnt := 0.U }
                   true.B
                 })),                       // write anything -> start
      0x0C -> Seq(RegField.r(1, !busy)),    // done flag
      0x10 -> Seq(RegField.r(64, result))   // result (64-bit read)
    )
  }
}

// ---------------------------------------------------------------------------
// Trait that attaches the peripheral to the peripheral bus inside a clock domain
// ---------------------------------------------------------------------------
trait CanHavePeripheryMyMult { this: BaseSubsystem =>
  private val pbus = locateTLBusWrapper(PBUS)

  val mymultDomain = p(MyMultKey).map { params =>
    val domain = LazyModule(new ClockSinkDomain(take = None))
    val m = domain { LazyModule(new MyMult(params, pbus.beatBytes)(p)) }
    domain.clockNode := pbus.fixedClockNode
    pbus.coupleTo("mymult") {
      m.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
    }
    m
  }
}

// ---------------------------------------------------------------------------
// Config fragment that enables the peripheral
// ---------------------------------------------------------------------------
class WithMyMult extends Config((site, here, up) => {
  case MyMultKey => Some(MyMultParams())
})