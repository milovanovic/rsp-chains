package rspChain

import chisel3._
import chisel3.util._
import chisel3.experimental._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy._

import utils._
import fft._
import windowing._
import xWRDataPreProc._
import jtag2mm._

// params.windAddress, params.windRamAddress, params.windParams, beatBytes
// address = params.fftAddress, params = params.fftParams, _beatBytes = params.beatBytes
case class RSPChainNoCRC_v0_Parameters (
  asyncQueueParams: AsyncQueueCustomParams,
  dspQueueParams  : DspQueueCustomParams,
  queueMaxPreProc : Int, // also mapped to distributed RAM, not visible Queue implementation with SyncReadMem if it is necessary, now it is visible so it can be used
  preProcParams   : AXI4XwrDataPreProcParams,
  fftParams       : FFTParams[FixedPoint],
  dspQueueAddress : AddressSet,
  preProcAddress  : AddressSet,
  jtag2mmAddress  : AddressSet,
  beatBytes       : Int
)

trait RSPChainNoCRC_v0_Standalone extends RSPChainNoCRC_v0 {
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()
//   if (addDebugNodes) {
//     val ioOutWinNode = BundleBridgeSink[AXI4StreamBundle]()
//     ioOutWinNode :=
//       AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
//       windowing.streamNode
//     val out_win = InModuleBody { ioOutWinNode.makeIO() }
//   }

  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    xAWRpreProc.streamNode

  val out = InModuleBody { ioOutNode.makeIO() }
}

// make custom parameters
// for fft - think about radix 2 variant and full run time configurability while resources are not problem

class RSPChainNoCRC_v0(val params: RSPChainNoCRC_v0_Parameters, val addDebugNodes: Boolean = false) extends LazyModule()(Parameters.empty) {
  val asyncQueue  = LazyModule(new AsyncQueueAXI4StreamOut(params.asyncQueueParams))
  val dspQueue    = LazyModule(new AXI4DspQueueWithSyncReadMem(params.dspQueueParams, params.dspQueueAddress, params.beatBytes))
  val xAWRpreProc = LazyModule(new AXI4xWRdataPreProcBlock(params.preProcAddress,  params.preProcParams, params.beatBytes))


  val jtagModule = LazyModule(new JTAGToMasterAXI4(4, BigInt("0", 2), params.beatBytes, params.jtag2mmAddress, burstMaxNum=128){
  def makeIO2(): TopModuleIO = {
    val io2: TopModuleIO = IO(io.cloneType)
    io2.suggestName("ioJTAG")
    io2 <> io
    io2
  }
    val ioJTAG = InModuleBody { makeIO2() }
  })

  // define mem
  lazy val blocks = Seq(xAWRpreProc, dspQueue)
  val bus = LazyModule(new AXI4Xbar)

  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }
  // Connect mem node
  mem.get := jtagModule.node.get
  xAWRpreProc.streamNode := AXI4StreamBuffer() := dspQueue.streamNode := AXI4StreamBuffer() := asyncQueue.streamNode

  lazy val module = new LazyModuleImp(this) {
    val ioJTAG = IO(jtagModule.ioJTAG.cloneType)

    // replace this with one io
    val in_0_data = IO(Input(SInt(params.asyncQueueParams.radarDataWidth.W)))
    val in_0_valid = IO(Input(Bool()))
    val queueFull = if (params.asyncQueueParams.isFullFlag) Some(IO(Output(Bool()))) else None
    val write_clock  = IO(Input(Clock()))
    val write_reset = IO(Input(Bool()))

    asyncQueue.module.in_data := in_0_data
    asyncQueue.module.in_valid := in_0_valid
    asyncQueue.module.write_clock := write_clock
    asyncQueue.module.write_reset := write_reset

    if (params.asyncQueueParams.isFullFlag) {
      queueFull.get := asyncQueue.module.queueFull.get
    }

    ioJTAG <> jtagModule.ioJTAG
  }
}

object RSPChainNoCRC_v0_App extends App
{
  val params = RSPChainNoCRC_v0_Parameters (
    asyncQueueParams = AsyncQueueCustomParams(),
    dspQueueParams = DspQueueCustomParams(),
    queueMaxPreProc = 1024,
    preProcParams  = AXI4XwrDataPreProcParams(),
    fftParams = FFTParams.fixed(
      dataWidth = 12,
      twiddleWidth = 16,
      numPoints = 1024,
      useBitReverse  = true,
      runTime = true,
      numAddPipes = 1,
      numMulPipes = 1,
      expandLogic = Array.fill(log2Up(1024))(1).zipWithIndex.map { case (e,ind) => if (ind < 4) 1 else 0 }, // expand first four stages, other do not grow
      keepMSBorLSB = Array.fill(log2Up(1024))(true),
      minSRAMdepth = 64, // memories larger than 64 should be mapped on block ram
      binPoint = 0
    ),
    // in the future
    // crcAddress      = AddressSet(0x30000000, 0xFF),
    dspQueueAddress = AddressSet(0x20000100, 0xFF),
    preProcAddress  = AddressSet(0x30000100, 0xFF),
    jtag2mmAddress  = AddressSet(0x40000000, 0x7FFF), // Ask Vukan is it necessary to have a such a wide range of addresses
    beatBytes      = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainNoCRC_v0(params, addDebugNodes= true) with RSPChainNoCRC_v0_Standalone)

  chisel3.Driver.execute(Array("--target-dir", "./verilog/RSPChainNoCRC_v0", "--top-name", "RSPChainNoCRC_v0"), ()=> standaloneModule.module)
}
