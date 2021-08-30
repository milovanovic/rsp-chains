package rspChain

import chisel3._
import chisel3.util._
import chisel3.experimental._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy._

import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

import dsputils._
import fft._
import windowing._
import xWRDataPreProc._
import jtag2mm._
import magnitude._
import cfar._

// params.windAddress, params.windRamAddress, params.windParams, beatBytes
// address = params.fftAddress, params = params.fftParams, _beatBytes = params.beatBytes
case class RSPChainNoCRC_v3_Parameters (
  asyncQueueParams: AsyncQueueCustomParams,
  dspQueueParams  : DspQueueCustomParams,
  queueMaxPreProc : Int, // also mapped to distributed RAM, not visible Queue implementation with SyncReadMem if it is necessary, now it is visible so it can be used
  preProcParams   : AXI4XwrDataPreProcParams,
  fftParams       : FFTParams[FixedPoint],
  magParams       : MAGParams[FixedPoint],
  cfarParams      : CFARParams[FixedPoint],
  windParams      : WindowingParams[FixedPoint],
  fftAddress      : AddressSet,
  cfarAddress     : AddressSet,
  magAddress      : AddressSet,
  windAddress     : AddressSet,
  windRamAddress  : AddressSet,
  dspQueueAddress : AddressSet,
  preProcAddress  : AddressSet,
  jtag2mmAddress   : AddressSet,
  beatBytes       : Int
)

trait RSPChainNoCRC_v3_Standalone extends RSPChainNoCRC_v3 {
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
    cfar.streamNode

  val out = InModuleBody { ioOutNode.makeIO() }
}

// make custom parameters
// for fft - think about radix 2 variant and full run time configurability while resources are not problem
// params: CFARParams[T], address: AddressSet, _beatBytes: Int = 4
class RSPChainNoCRC_v3(val params: RSPChainNoCRC_v3_Parameters) extends LazyModule()(Parameters.empty) {
  val asyncQueue  = LazyModule(new AsyncQueueAXI4StreamOut(params.asyncQueueParams))
  val dspQueue    = LazyModule(new AXI4DspQueueWithSyncReadMem(params.dspQueueParams, params.dspQueueAddress, params.beatBytes))
  val xAWRpreProc = LazyModule(new AXI4xWRdataPreProcBlock(params.preProcAddress, params.preProcParams, params.beatBytes))
  val windowing   = LazyModule(new WindowingBlock(params.windAddress, params.windRamAddress, params.windParams, params.beatBytes))
  val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes, configInterface = false))
  //val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes))
  val mag         = LazyModule(new AXI4LogMagMuxBlock(params.magParams, params.magAddress))
  val cfar        = LazyModule(new AXI4CFARBlock(params.cfarParams, params.cfarAddress, _beatBytes = params.beatBytes))

  val jtagModule = LazyModule(new JTAGToMasterAXI4(3, BigInt("0", 2), params.beatBytes, params.jtag2mmAddress, burstMaxNum=128){
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
  lazy val blocks = Seq(xAWRpreProc, fft, dspQueue, mag, cfar, windowing)
  val bus = LazyModule(new AXI4Xbar)

  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }
  // Connect mem node
  mem.get := jtagModule.node.get
  cfar.streamNode := AXI4StreamBuffer() := mag.streamNode := AXI4StreamBuffer() := fft.streamNode := AXI4StreamBuffer() := windowing.streamNode := AXI4StreamBuffer() := xAWRpreProc.streamNode := AXI4StreamBuffer() := dspQueue.streamNode := AXI4StreamBuffer() := asyncQueue.streamNode

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

object TestCFAR_App extends App {
  val paramsCFAR = CFARParams(
    protoIn = FixedPoint(16.W, 0.BP),
    protoThreshold = FixedPoint(16.W, 0.BP),
    protoScaler = FixedPoint(16.W, 0.BP),
    leadLaggWindowSize = 64,
    guardWindowSize = 4,
    logOrLinReg = true,
    fftSize = 1024,
    sendCut = false,
    //minSubWindowSize = Some(8),
    includeCASH = false,
    CFARAlgorithm = CACFARType
  )

  val baseAddress = 0x500
  implicit val p: Parameters = Parameters.empty
  val cfarModule = LazyModule(new AXI4CFARBlock(paramsCFAR, AddressSet(baseAddress + 0x100, 0xFF), _beatBytes = 4) with dspblocks.AXI4StandaloneBlock {
    override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  })

  (new chisel3.stage.ChiselStage).execute(
  Array("-X", "verilog", "--target-dir", "verilog"),
  Seq(ChiselGeneratorAnnotation(() => cfarModule.module)))

}


object RSPChainNoCRC_v3_App extends App
{
  val params = RSPChainNoCRC_v3_Parameters (
    asyncQueueParams = AsyncQueueCustomParams(),
    dspQueueParams = DspQueueCustomParams(queueDepth = 2048),
    queueMaxPreProc = 1024,
    preProcParams = AXI4XwrDataPreProcParams(),
    fftParams = FFTParams.fixed(
      dataWidth = 12,
      twiddleWidth = 16,
      numPoints = 256, //1024,
      useBitReverse  = true,
      runTime = true,
      numAddPipes = 1,
      numMulPipes = 1,
      expandLogic = Array.fill(log2Up(256))(1).zipWithIndex.map { case (e,ind) => if (ind < 4) 1 else 0 }, //Array.fill(log2Up(1024))(1).zipWithIndex.map { case (e,ind) => if (ind < 4) 1 else 0 }, // expand first four stages, other do not grow
      keepMSBorLSB = Array.fill(log2Up(256))(true), // replace it with 256
      minSRAMdepth = 1024, // memories larger than 64 should be mapped on block ram
      binPoint = 10
    ),
    magParams = MAGParams(
      protoIn  = FixedPoint(16.W, 10.BP),
      protoOut = FixedPoint(24.W, 10.BP), // lets say identity node
      magType  = MagJPLandSqrMag,
      useLast = true,
      numAddPipes = 1,
      numMulPipes = 1
    ),
    cfarParams = CFARParams(
      protoIn = FixedPoint(24.W, 10.BP),
      protoThreshold = FixedPoint(24.W, 10.BP),
      protoScaler = FixedPoint(16.W, 4.BP), // this should be changed
      leadLaggWindowSize = 64, //64,
      guardWindowSize = 8,
      logOrLinReg = false,
      fftSize = 256,
      sendCut = true,
      minSubWindowSize = Some(8),
      includeCASH = true, //
      CFARAlgorithm = CACFARType
    ),
//    cfarParams =  CFARParams(
//      protoIn = FixedPoint(16.W, 0.BP),
//      protoThreshold = FixedPoint(16.W, 0.BP), // output threshold
//      protoScaler = FixedPoint(16.W, 8.BP),
//      CFARAlgorithm = CACFARType,
//      includeCASH = true,
//      logMode = false,
//      logOrLinReg = false,
//      minSubWindowSize = Some(16),
//      leadLaggWindowSize = 128,
//      guardWindowSize = 8,
//      sendCut = true,
//      fftSize = 1024,
//      retiming = true,
//      numAddPipes = 1,
//      numMulPipes = 1),
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
    cfarAddress     = AddressSet(0x30000600, 0xFF),
    windRamAddress  = AddressSet(0x30001000, 0xFFF),
    jtag2mmAddress  = AddressSet(0x40000000, 0x7FFF), // Ask Vukan is it necessary to have a such a wide range of addresses
    beatBytes      = 4)

  // when debug nodes are included then insert splitters
  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainNoCRC_v3(params) with RSPChainNoCRC_v3_Standalone)

  (new chisel3.stage.ChiselStage).execute(
  Array("-X", "verilog", "--target-dir", "./verilog/RSPChainNoCRC_v3"),
  Seq(ChiselGeneratorAnnotation(() => standaloneModule.module)))

}
