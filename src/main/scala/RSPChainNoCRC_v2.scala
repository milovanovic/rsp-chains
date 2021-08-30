package rspChain

import chisel3._
import chisel3.util._
import chisel3.experimental._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy._

import dsputils._
import fft._
import windowing._
import xWRDataPreProc._
import jtag2mm._
import magnitude._

// params.windAddress, params.windRamAddress, params.windParams, beatBytes
// address = params.fftAddress, params = params.fftParams, _beatBytes = params.beatBytes
case class RSPChainNoCRC_v2_Parameters (
  asyncQueueParams: AsyncQueueCustomParams,
  dspQueueParams  : DspQueueCustomParams,
  queueMaxPreProc : Int, // also mapped to distributed RAM, not visible Queue implementation with SyncReadMem if it is necessary, now it is visible so it can be used
  preProcParams   : AXI4XwrDataPreProcParams,
  fftParams       : FFTParams[FixedPoint],
  magParams       : MAGParams[FixedPoint],
  windParams      : WindowingParams[FixedPoint],
  fftAddress      : AddressSet,
  magAddress      : AddressSet,
  windAddress     : AddressSet,
  windRamAddress  : AddressSet,
  dspQueueAddress : AddressSet,
  preProcAddress  : AddressSet,
  jtag2mmAddress    : AddressSet,
  beatBytes       : Int
)

trait RSPChainNoCRC_v2_Standalone extends RSPChainNoCRC_v2 {
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
    mag.streamNode

  val out = InModuleBody { ioOutNode.makeIO() }
}

// make custom parameters
// for fft - think about radix 2 variant and full run time configurability while resources are not problem

class RSPChainNoCRC_v2(val params: RSPChainNoCRC_v2_Parameters, val addDebugNodes: Boolean = false) extends LazyModule()(Parameters.empty) {
  val asyncQueue  = LazyModule(new AsyncQueueAXI4StreamOut(params.asyncQueueParams))
  val dspQueue    = LazyModule(new AXI4DspQueueWithSyncReadMem(params.dspQueueParams, params.dspQueueAddress, params.beatBytes))
  val xAWRpreProc = LazyModule(new AXI4xWRdataPreProcBlock(params.preProcAddress,  params.preProcParams, params.beatBytes))
  val windowing   = LazyModule(new WindowingBlock(params.windAddress, params.windRamAddress, params.windParams, params.beatBytes))
  val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes, configInterface = false))
  //val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes))

  val mag         = LazyModule(new AXI4LogMagMuxBlock(params.magParams, params.magAddress))

  val jtagModule = LazyModule(new JTAGToMasterAXI4(4, BigInt("0", 2), params.beatBytes, params.jtag2mmAddress, burstMaxNum=128){
  def makeIO2(): TopModuleIO = {
    val io2: TopModuleIO = IO(io.cloneType)
    io2.suggestName("ioJTAG")
    io2 <> io
    io2
  }
    val ioJTAG = InModuleBody { makeIO2() }
  })
  // mag has only one register

  // define mem
  lazy val blocks = Seq(xAWRpreProc, fft, dspQueue, mag, windowing)
  val bus = LazyModule(new AXI4Xbar)

  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }
  // Connect mem node
  mem.get := jtagModule.node.get
  mag.streamNode := fft.streamNode := AXI4StreamBuffer() := windowing.streamNode := AXI4StreamBuffer() := xAWRpreProc.streamNode := AXI4StreamBuffer() := dspQueue.streamNode := AXI4StreamBuffer() := asyncQueue.streamNode

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

object RSPChainNoCRC_v2_App extends App
{
  val params = RSPChainNoCRC_v2_Parameters (
    asyncQueueParams = AsyncQueueCustomParams(),
    dspQueueParams = DspQueueCustomParams(),
    preProcParams  = AXI4XwrDataPreProcParams(),
    queueMaxPreProc = 1024,
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
    magParams = MAGParams(
      protoIn  = FixedPoint(16.W, 0.BP),
      protoOut = FixedPoint(16.W, 0.BP), // lets say identity node
      magType  = MagJPLandSqrMag,
      useLast = true,
      numAddPipes = 1,
      numMulPipes = 1
    ),
    windParams = WindowingParams.fixed(
      dataWidth = 16,
      binPoint  = 0,
      numMulPipes = 1,
      dirName = "windowing",
      memoryFile = "./windowing/blacman.txt",
      windowFunc = windowing.WindowFunctionTypes.Blackman(dataWidth_tmp = 16)
    ),
    // in the future
    // crcAddress      = AddressSet(0x30000000, 0xFF),
    dspQueueAddress = AddressSet(0x20000100, 0xFF), // just a debug core
    preProcAddress  = AddressSet(0x30000100, 0xFF),
    windAddress     = AddressSet(0x30000200, 0xFF),
    fftAddress      = AddressSet(0x30000300, 0xFF),
    magAddress      = AddressSet(0x30000500, 0xFF),
    //cfarAddress   = AddressSet(0x30000600, 0xFF),
    windRamAddress  = AddressSet(0x30001000, 0xFFF),
    jtag2mmAddress  = AddressSet(0x40000000, 0x7FFF), // Ask Vukan is it necessary to have a such a wide range of addresses
    beatBytes      = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainNoCRC_v2(params, addDebugNodes= true) with RSPChainNoCRC_v2_Standalone)

  chisel3.Driver.execute(Array("--target-dir", "./verilog/RSPChainNoCRC_v2", "--top-name", "RSPChainNoCRC_v2"), ()=> standaloneModule.module)
}
