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
import xWRDataPreProc._

case class RSPChainTestFFT_v1_Parameters (
  dspQueueParams  : DspQueueCustomParams,
  preProcParams   : AXI4XwrDataPreProcParams,
  fftParams       : FFTParams[FixedPoint],
  dspQueueAddress : AddressSet,
  fftAddress      : AddressSet,
  splitterAddress : AddressSet,
  preProcAddress  : AddressSet,
  beatBytes       : Int
)

trait RSPChainTestFFT_v1_Standalone extends RSPChainTestFFT_v1 {

   def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
   val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

    m :=
      BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
      ioMemNode

    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}


  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()
  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 2)))
  dspQueue.streamNode := BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 2)) := ioInNode

  ioOutNode := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := combiner.streamNode

  val in  = InModuleBody { ioInNode.makeIO() }
  val out = InModuleBody { ioOutNode.makeIO() }
}

class RSPChainTestFFT_v1(val params: RSPChainTestFFT_v1_Parameters) extends LazyModule()(Parameters.empty) {

  val dspQueue    = LazyModule(new AXI4DspQueueWithSyncReadMem(params.dspQueueParams, params.dspQueueAddress, params.beatBytes))
  val xAWRpreProc = LazyModule(new AXI4xWRdataPreProcBlock(params.preProcAddress, params.preProcParams, params.beatBytes))

  val splitter =  LazyModule(new AXI4Splitter(address = params.splitterAddress, beatBytes = params.beatBytes))
  val axi4StreamBuffer = AXI4StreamBuffer(BufferParams(params.fftParams.numPoints*3, false, false))
  val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes, configInterface = false))
  val combiner    = LazyModule(new AXI4StreamCustomCombiner(beatBytes = 4))

  lazy val blocks = Seq(xAWRpreProc, fft, dspQueue, splitter)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }

  xAWRpreProc.streamNode := dspQueue.streamNode
  splitter.streamNode := xAWRpreProc.streamNode
  combiner.streamNodein1 := axi4StreamBuffer
  combiner.streamNodein2 := fft.streamNode
  axi4StreamBuffer := splitter.streamNode
  fft.streamNode := splitter.streamNode

  lazy val module = new LazyModuleImp(this) {
  }
}

object RSPChainTestFFT_v1_App extends App
{
  val params = RSPChainTestFFT_v1_Parameters (
    preProcParams  = AXI4XwrDataPreProcParams(),
    dspQueueParams = DspQueueCustomParams(),
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
      minSRAMdepth = 64,
      binPoint = 0
    ),
    dspQueueAddress = AddressSet(0x30000000, 0xFF),
    preProcAddress  = AddressSet(0x30000100, 0xFF),
    splitterAddress = AddressSet(0x30000200, 0xFF),
    fftAddress      = AddressSet(0x30000300, 0xFF),
    beatBytes      = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainTestFFT_v1(params) with RSPChainTestFFT_v1_Standalone)

  chisel3.Driver.execute(Array("--target-dir", "./verilog/RSPChainTestFFT_v1", "--top-name", "RSPChainTestFFT_v1"), ()=> standaloneModule.module)
}
