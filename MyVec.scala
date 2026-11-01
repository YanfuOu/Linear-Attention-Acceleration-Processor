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
case class MyVecParams(
  ctrlAddress: BigInt = 0x5000,      // control registers (4 KiB window)
  sramAddress: BigInt = 0x10050000,  // local vector SRAM, CPU-visible
  sramBytes:   Int    = 4096,
  lanes:       Int    = 16,
  elemBits:    Int    = 32,
  nVRegs:      Int    = 8,           // vector registers v0..v7
  cmdDepth:    Int    = 8) {         // instruction FIFO depth
  val rowBytes = lanes * elemBits / 8 // one SRAM row = one vector register
  require(isPow2(rowBytes) && isPow2(sramBytes) && sramBytes > rowBytes)
  require(isPow2(nVRegs) && nVRegs <= 32)
  require(sramBytes <= (1 << 16), "SRAM offsets must fit the 16-bit DMA offset field")
}
case object MyVecKey extends Field[Option[MyVecParams]](None)

// ---------------------------------------------------------------------------
// Instruction set
//
// Every instruction is one 32-bit word:
//
//   R-type:  [31:26] opcode | [25:21] vd | [20:16] vs1 | [15:11] vs2 | [10:0] 0
//   I-type:  [31:26] opcode | [25:21] vd |            [20:0] imm
//   D-type:  [31:26] opcode | [25:16] nrows         | [15:0] SRAM byte offset
//
// With the default 16 x 32-bit lanes, one row (ROW below) is 64 bytes.
//
//   NOP                      no-op
//   VLD     vd, off   (I)    vd  <- sram[off]          off = SRAM byte offset, ROW aligned
//   VST     vs, off   (I)    sram[off] <- vs           (vs sits in the vd field)
//   VSPLAT  vd, imm   (I)    vd[i] <- sext(imm)        for every lane
//   VADD    vd,vs1,vs2       vd[i] <- vs1[i] + vs2[i]
//   VSUB    vd,vs1,vs2       vd[i] <- vs1[i] - vs2[i]
//   VMUL    vd,vs1,vs2       vd[i] <- low 32 bits of vs1[i] * vs2[i]
//   VAND    vd,vs1,vs2       vd[i] <- vs1[i] & vs2[i]
//   VOR     vd,vs1,vs2       vd[i] <- vs1[i] | vs2[i]
//   VXOR    vd,vs1,vs2       vd[i] <- vs1[i] ^ vs2[i]
//   VREDSUM vd,vs1           vd[0] <- sum of vs1[*], other lanes <- 0
//
//   DBASEL  imm       (I)    DMA base address bits [log2(ROW)+20 : log2(ROW)] <- imm
//   DBASEH  imm       (I)    DMA base address bits [log2(ROW)+41 : log2(ROW)+21] <- imm
//   DMALD   off, n    (D)    copy n rows from memory at base to sram[off]; base += n * ROW
//   DMAST   off, n    (D)    copy n rows from sram[off] to memory at base; base += n * ROW
//
// DBASEL/DBASEH hold the address in units of rows, so the DMA base is always
// ROW aligned. They run in order with everything else, so a queued DMA always
// uses the base set by the instructions before it. DMA addresses are physical.
//
// These set the sticky ERROR flag, and the instruction does not retire:
//   - an unknown opcode or a register index >= nVRegs
//   - a misaligned or out-of-range SRAM offset, or a DMA with n = 0 or one
//     that runs past the end of the SRAM
//   - a DMA to an address that doesn't support the access, or a denied or
//     corrupt response. The DMA stops there; earlier rows stay copied and base
//     stays at the failing row.
// ---------------------------------------------------------------------------
object MyVecISA {
  val NOP     = 0x00
  val VLD     = 0x01
  val VST     = 0x02
  val VSPLAT  = 0x03
  val VADD    = 0x08
  val VSUB    = 0x09
  val VMUL    = 0x0A
  val VAND    = 0x0B
  val VOR     = 0x0C
  val VXOR    = 0x0D
  val VREDSUM = 0x10
  val DBASEL  = 0x20
  val DBASEH  = 0x21
  val DMALD   = 0x22
  val DMAST   = 0x23

  val aluOps = Seq(VADD, VSUB, VMUL, VAND, VOR, VXOR)
}

// ---------------------------------------------------------------------------
// The vector engine
//
// A 16-lane SIMD unit with 8 vector registers, its own SRAM, and a DMA that
// moves rows between the SRAM and the rest of memory. The CPU streams
// instructions into the CMD register; the engine pops them from a FIFO and
// executes them in order. Data reaches the SRAM by DMA or through the
// memory-mapped window at params.sramAddress.
//
// Control register map (offsets from params.ctrlAddress):
//   0x00  CMD      (W,  32-bit)  push one instruction; stalls the bus while the FIFO is full
//   0x04  IDLE     (R,   1-bit)  1 when the FIFO is empty and nothing is executing
//   0x08  ERROR    (RW,  1-bit)  sticky illegal-instruction flag; write 0 to clear
//   0x0C  RETIRED  (R,  32-bit)  number of instructions executed
//   0x10  QCOUNT   (R)           instructions waiting in the FIFO
//
// The SRAM is single-ported. CPU accesses stall only on the cycles where the
// engine is using it for a VLD, VST, or DMA row.
//
// DMA is one row at a time, one request outstanding, and blocks the
// instruction stream until it finishes.
// ---------------------------------------------------------------------------
class MyVec(params: MyVecParams, beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("myvec", Seq("mylab,myvec"))

  val ctrlNode = TLRegisterNode(
    address   = Seq(AddressSet(params.ctrlAddress, 0xFFF)),
    device    = device,
    beatBytes = beatBytes)

  val sramNode = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(
      address            = Seq(AddressSet(params.sramAddress, params.sramBytes - 1)),
      resources          = device.reg("mem"),
      regionType         = RegionType.IDEMPOTENT,
      executable         = false,
      supportsGet        = TransferSizes(1, params.rowBytes),
      supportsPutPartial = TransferSizes(1, params.rowBytes),
      supportsPutFull    = TransferSizes(1, params.rowBytes),
      fifoId             = Some(0))),
    beatBytes  = params.rowBytes,
    minLatency = 1)))

  val dmaNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name     = "myvec-dma",
    sourceId = IdRange(0, 1))))))

  lazy val module = new LazyModuleImp(this) {
    import MyVecISA._

    val (in, edge)     = sramNode.in(0)
    val (dma, dmaEdge) = dmaNode.out(0)

    val lanes    = params.lanes
    val elemBits = params.elemBits
    val rowBytes = params.rowBytes
    val nRows    = params.sramBytes / rowBytes
    val rowBits  = log2Ceil(nRows)
    val offBits  = log2Ceil(rowBytes)
    val vregBits = log2Ceil(params.nVRegs)

    // byte offset -> row index
    def rowOf(byteOff: UInt): UInt = byteOff(rowBits + offBits - 1, offBits)

    val mem = SyncReadMem(nRows, Vec(rowBytes, UInt(8.W)))

    // ----------------------------- engine state -----------------------------
    val cmdQ    = Module(new Queue(UInt(32.W), params.cmdDepth))
    val vregs   = Reg(Vec(params.nVRegs, Vec(lanes, UInt(elemBits.W))))
    val inst    = Reg(UInt(32.W))
    val err     = RegInit(false.B)
    val retired = RegInit(0.U(32.W))

    val dmaBase = RegInit(0.U(42.W))    // memory address, in rows
    val dmaRow  = Reg(UInt(rowBits.W))  // SRAM row of the current transfer
    val dmaCnt  = Reg(UInt(10.W))       // rows left
    val dmaData = Reg(UInt((rowBytes * 8).W))

    val sFetch :: sExec :: sLoadWB :: sDmaGet :: sDmaGetResp :: sDmaRead :: sDmaLatch :: sDmaPut :: sDmaPutResp :: Nil = Enum(9)
    val state = RegInit(sFetch)
    val idle  = state === sFetch && !cmdQ.io.deq.valid

    // ------------------------------- decode ---------------------------------
    val opcode = inst(31, 26)
    val vd     = inst(25, 21)
    val vs1    = inst(20, 16)
    val vs2    = inst(15, 11)
    val imm    = inst(20, 0)
    val nrows  = inst(25, 16)
    val offset = inst(15, 0)

    def regOk(r: UInt): Bool = r < params.nVRegs.U
    def vreg(r: UInt): Vec[UInt] = vregs(r(vregBits - 1, 0))

    val isLd    = opcode === VLD.U
    val isSt    = opcode === VST.U
    val isSplat = opcode === VSPLAT.U
    val isAlu   = aluOps.map(opcode === _.U).reduce(_ || _)
    val isRed   = opcode === VREDSUM.U
    val isBaseL = opcode === DBASEL.U
    val isBaseH = opcode === DBASEH.U
    val isDmaLd = opcode === DMALD.U
    val isDmaSt = opcode === DMAST.U
    val addrOk  = imm < params.sramBytes.U && imm(offBits - 1, 0) === 0.U
    val dmaOk   = nrows =/= 0.U && offset(offBits - 1, 0) === 0.U &&
                  (offset +& (nrows << offBits)) <= params.sramBytes.U

    val legal = (opcode === NOP.U) ||
      ((isLd || isSt) && regOk(vd) && addrOk) ||
      (isSplat && regOk(vd)) ||
      (isAlu && regOk(vd) && regOk(vs1) && regOk(vs2)) ||
      (isRed && regOk(vd) && regOk(vs1)) ||
      isBaseL || isBaseH ||
      ((isDmaLd || isDmaSt) && dmaOk)

    // ------------------------------ DMA port --------------------------------
    val addrBits = dmaEdge.bundle.addressBits
    val dmaFull  = Cat(dmaBase, 0.U(offBits.W))
    val dmaAddr  = dmaFull(addrBits - 1, 0)
    val dmaAddrOk = (dmaFull >> addrBits) === 0.U
    val dmaGetOk = dmaAddrOk && dmaEdge.manager.supportsGetSafe(dmaAddr, offBits.U)
    val dmaPutOk = dmaAddrOk && dmaEdge.manager.supportsPutFullSafe(dmaAddr, offBits.U)
    val dmaRespOk = !dma.d.bits.denied && !dma.d.bits.corrupt

    dma.a.valid := (state === sDmaGet && dmaGetOk) || state === sDmaPut
    dma.a.bits  := Mux(state === sDmaPut,
                       dmaEdge.Put(0.U, dmaAddr, offBits.U, dmaData)._2,
                       dmaEdge.Get(0.U, dmaAddr, offBits.U)._2)
    dma.d.ready := state === sDmaGetResp || state === sDmaPutResp

    dma.b.ready := true.B
    dma.c.valid := false.B
    dma.e.valid := false.B

    // Who owns the SRAM port this cycle
    val execMem = state === sExec && (isLd || isSt) && legal
    val dmaWrite = state === sDmaGetResp && dma.d.valid && dmaRespOk
    val dmaRead  = state === sDmaRead && dmaPutOk
    val engMem   = execMem || dmaWrite || dmaRead

    // ------------------------- CPU-side TL response -------------------------
    val aReq      = Reg(chiselTypeOf(in.a.bits))
    val rdPending = RegInit(false.B) // SRAM read issued last cycle
    val dValid    = RegInit(false.B)
    val dData     = Reg(UInt((rowBytes * 8).W))

    in.a.ready := !engMem && !rdPending && !dValid
    val cpuFire  = in.a.fire
    val cpuIsGet = in.a.bits.opcode === TLMessages.Get

    // ------------------------ single SRAM port mux --------------------------
    val engRow   = Mux(execMem, rowOf(imm), dmaRow)
    val memRow   = Mux(engMem, engRow, rowOf(in.a.bits.address - params.sramAddress.U))
    val memRen   = Wire(Bool())
    val memWen   = Wire(Bool())
    val memWData = Wire(Vec(rowBytes, UInt(8.W)))
    val memWMask = Wire(Vec(rowBytes, Bool()))
    memRen   := cpuFire && cpuIsGet
    memWen   := cpuFire && !cpuIsGet
    memWData := in.a.bits.data.asTypeOf(memWData)
    memWMask := in.a.bits.mask.asBools

    val memRData = mem.read(memRow, memRen && !memWen)
    when (memWen) { mem.write(memRow, memWData, memWMask) }
    val rVec = memRData.asTypeOf(Vec(lanes, UInt(elemBits.W)))

    // ------------------------------ CPU side --------------------------------
    when (cpuFire) {
      aReq := in.a.bits
      when (cpuIsGet) { rdPending := true.B } .otherwise { dValid := true.B }
    }
    when (rdPending) {
      dData     := memRData.asUInt
      rdPending := false.B
      dValid    := true.B
    }
    when (in.d.fire) { dValid := false.B }

    in.d.valid := dValid
    in.d.bits  := Mux(aReq.opcode === TLMessages.Get,
                      edge.AccessAck(aReq, dData),
                      edge.AccessAck(aReq))

    in.b.valid := false.B
    in.c.ready := true.B
    in.e.ready := true.B

    // ------------------------------- execute --------------------------------
    // Fetch takes one cycle; VLD takes two (SRAM read, then writeback),
    // DMA takes as long as memory does, everything else executes in one.
    def alu(a: UInt, b: UInt): UInt = MuxLookup(opcode, 0.U(elemBits.W))(Seq(
      VADD.U -> (a + b),
      VSUB.U -> (a - b),
      VMUL.U -> (a * b)(elemBits - 1, 0),
      VAND.U -> (a & b),
      VOR.U  -> (a | b),
      VXOR.U -> (a ^ b)))

    // One DMA row finished: step to the next row, or retire on the last one
    def dmaNextRow(next: UInt): Unit = {
      dmaBase := dmaBase + 1.U
      dmaRow  := dmaRow + 1.U
      dmaCnt  := dmaCnt - 1.U
      when (dmaCnt === 1.U) {
        retired := retired + 1.U
        state   := sFetch
      } .otherwise {
        state   := next
      }
    }

    cmdQ.io.deq.ready := state === sFetch

    switch (state) {
      is (sFetch) {
        when (cmdQ.io.deq.valid) {
          inst  := cmdQ.io.deq.bits
          state := sExec
        }
      }
      is (sExec) {
        state := sFetch
        when (!legal) {
          err := true.B
        } .elsewhen (isLd) {
          memRen := true.B
          state  := sLoadWB
        } .elsewhen (isDmaLd || isDmaSt) {
          dmaRow := rowOf(offset)
          dmaCnt := nrows
          state  := Mux(isDmaLd, sDmaGet, sDmaRead)
        } .otherwise {
          when (isSt) {
            memWen   := true.B
            memWData := vreg(vd).asUInt.asTypeOf(memWData)
            memWMask := VecInit(Seq.fill(rowBytes)(true.B))
          }
          when (isSplat) {
            vreg(vd) := VecInit(Seq.fill(lanes)(imm.asSInt.pad(elemBits).asUInt))
          }
          when (isAlu) {
            vreg(vd) := VecInit((0 until lanes).map(l => alu(vreg(vs1)(l), vreg(vs2)(l))))
          }
          when (isRed) {
            vreg(vd) := VecInit(vreg(vs1).reduce(_ + _) +: Seq.fill(lanes - 1)(0.U(elemBits.W)))
          }
          when (isBaseL) { dmaBase := Cat(dmaBase(41, 21), imm) }
          when (isBaseH) { dmaBase := Cat(imm, dmaBase(20, 0)) }
          retired := retired + 1.U
        }
      }
      is (sLoadWB) {
        vreg(vd) := rVec
        retired  := retired + 1.U
        state    := sFetch
      }

      // memory -> SRAM
      is (sDmaGet) {
        when (!dmaGetOk) {
          err   := true.B
          state := sFetch
        } .elsewhen (dma.a.ready) {
          state := sDmaGetResp
        }
      }
      is (sDmaGetResp) {
        when (dma.d.valid) {
          when (dmaRespOk) {
            memWen   := true.B
            memWData := dma.d.bits.data.asTypeOf(memWData)
            memWMask := VecInit(Seq.fill(rowBytes)(true.B))
            dmaNextRow(sDmaGet)
          } .otherwise {
            err   := true.B
            state := sFetch
          }
        }
      }

      // SRAM -> memory
      is (sDmaRead) {
        when (!dmaPutOk) {
          err   := true.B
          state := sFetch
        } .otherwise {
          memRen := true.B
          state  := sDmaLatch
        }
      }
      is (sDmaLatch) {
        dmaData := memRData.asUInt
        state   := sDmaPut
      }
      is (sDmaPut) {
        when (dma.a.ready) { state := sDmaPutResp }
      }
      is (sDmaPutResp) {
        when (dma.d.valid) {
          when (dmaRespOk) {
            dmaNextRow(sDmaRead)
          } .otherwise {
            err   := true.B
            state := sFetch
          }
        }
      }
    }

    ctrlNode.regmap(
      0x00 -> Seq(RegField.w(32, cmdQ.io.enq,   RegFieldDesc("cmd", "push one instruction"))),
      0x04 -> Seq(RegField.r(1, idle,           RegFieldDesc("idle", "FIFO empty and engine idle"))),
      0x08 -> Seq(RegField(1, err,              RegFieldDesc("error", "sticky illegal-instruction flag"))),
      0x0C -> Seq(RegField.r(32, retired,       RegFieldDesc("retired", "instructions executed"))),
      0x10 -> Seq(RegField.r(log2Ceil(params.cmdDepth + 1), cmdQ.io.count,
                                                RegFieldDesc("qcount", "instructions waiting in FIFO")))
    )
  }
}

// ---------------------------------------------------------------------------
// Trait that attaches the engine: control regs + SRAM on the peripheral bus,
// DMA master on the front bus
// ---------------------------------------------------------------------------
trait CanHavePeripheryMyVec { this: BaseSubsystem =>
  private val pbus = locateTLBusWrapper(PBUS)
  private val fbus = locateTLBusWrapper(FBUS)

  val myvecDomain = p(MyVecKey).map { params =>
    // The whole engine runs on the pbus clock and has no crossing to the fbus
    require(pbus.dtsFrequency == fbus.dtsFrequency, "MyVec needs pbus and fbus at the same frequency")

    val domain = LazyModule(new ClockSinkDomain(take = None))
    val v = domain { LazyModule(new MyVec(params, pbus.beatBytes)(p)) }
    domain.clockNode := pbus.fixedClockNode
    pbus.coupleTo("myvec_ctrl") {
      v.ctrlNode := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
    }
    pbus.coupleTo("myvec_sram") {
      v.sramNode := TLFragmenter(params.rowBytes, pbus.blockBytes) :=
        TLWidthWidget(pbus.beatBytes) := _
    }
    fbus.coupleFrom("myvec_dma") {
      _ := TLWidthWidget(params.rowBytes) := TLBuffer() := v.dmaNode
    }
    v
  }
}

// ---------------------------------------------------------------------------
// Config fragment that enables the engine
// ---------------------------------------------------------------------------
class WithMyVec extends Config((site, here, up) => {
  case MyVecKey => Some(MyVecParams())
})
