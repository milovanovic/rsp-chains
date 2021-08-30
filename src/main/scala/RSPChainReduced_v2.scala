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


case class RSPChainReduced_v2_Parameters (
  dspQueueParams  : DspQueueCustomParams,
  queueMaxPreProc : Int,
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
  beatBytes       : Int
)

trait RSPChainReduced_v2_Standalone extends RSPChainReduced_v2 {
  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)

  val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

    m :=
      BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
      ioMemNode

    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}

  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 2)))
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

  dspQueue.streamNode :=
    BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 2)) :=
    ioInNode

  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    mag.streamNode

  val out = InModuleBody { ioOutNode.makeIO() }
  val in = InModuleBody { ioInNode.makeIO() }
}

class RSPChainReduced_v2(val params: RSPChainReduced_v2_Parameters, val addDebugNodes: Boolean = false) extends LazyModule()(Parameters.empty) {
  val dspQueue    = LazyModule(new AXI4DspQueueWithSyncReadMem(params.dspQueueParams, params.dspQueueAddress, params.beatBytes))
  val xAWRpreProc = LazyModule(new AXI4xWRdataPreProcBlock(params.preProcAddress, params.preProcParams, params.beatBytes))
  val windowing   = LazyModule(new WindowingBlock(params.windAddress, params.windRamAddress, params.windParams, params.beatBytes))
  val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes, configInterface = false))
  //val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes))

  val mag         = LazyModule(new AXI4LogMagMuxBlock(params.magParams, params.magAddress))

  // define mem
  lazy val blocks = Seq(xAWRpreProc, fft, dspQueue, mag, windowing)
  val bus = LazyModule(new AXI4Xbar)

  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }

  mag.streamNode := AXI4StreamBuffer() := fft.streamNode := AXI4StreamBuffer() := windowing.streamNode := AXI4StreamBuffer() := xAWRpreProc.streamNode := AXI4StreamBuffer() := dspQueue.streamNode

  lazy val module = new LazyModuleImp(this) {
  }
}

object RSPChainReduced_v2_App extends App
{
  val params = RSPChainReduced_v2_Parameters (
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
    dspQueueAddress = AddressSet(0x20000100, 0xFF), // just a debug core
    preProcAddress  = AddressSet(0x30000100, 0xFF),
    windAddress     = AddressSet(0x30000200, 0xFF),
    fftAddress      = AddressSet(0x30000300, 0xFF),
    magAddress      = AddressSet(0x30000500, 0xFF),
    windRamAddress  = AddressSet(0x30001000, 0xFFF),
    beatBytes      = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainReduced_v2(params) with RSPChainReduced_v2_Standalone)

  chisel3.Driver.execute(Array("--target-dir", "./verilog/RSPChainReduced_v2", "--top-name", "RSPChainReduced_v2"), ()=> standaloneModule.module)
}
