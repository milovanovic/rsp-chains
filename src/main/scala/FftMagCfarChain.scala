package rspChain

import dspblocks._
import dsptools._
import dsptools.numbers._

import chisel3._
import chisel3.experimental._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import fft._
import magnitude._
import cfar._

case class FftMagCfarVanillaParameters (
  fftParams       : FFTParams[FixedPoint],
  magParams       : MAGParams[FixedPoint],
  cfarParams      : CFARParams[FixedPoint],
  fftAddress      : AddressSet,
  magAddress      : AddressSet,
  cfarAddress     : AddressSet,
  beatBytes       : Int
)

class FftMagCfarChainVanilla(params: FftMagCfarVanillaParameters) extends LazyModule()(Parameters.empty) {

  val fft       = LazyModule(new AXI4FFTBlock(address = params.fftAddress, params = params.fftParams, _beatBytes = params.beatBytes))
  val mag       = LazyModule(new AXI4LogMagMuxBlock(params.magParams, params.magAddress, _beatBytes = params.beatBytes))
  val cfar      = LazyModule(new AXI4CFARBlock(params = params.cfarParams, address = params.cfarAddress, _beatBytes = params.beatBytes))
  
  val streamNode = NodeHandle(fft.streamNode, cfar.streamNode)
  
  // define mem
  lazy val blocks = Seq(fft, mag, cfar)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)
  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }

  cfar.streamNode := AXI4StreamBuffer() := mag.streamNode := AXI4StreamBuffer() := fft.streamNode
  lazy val module = new LazyModuleImp(this) {}
}

trait FftMagCfarChainVanillaPins extends FftMagCfarChainVanilla {
  val beatBytes = 4
  def standaloneParams = AXI4BundleParameters(addrBits = beatBytes*8, dataBits = beatBytes*8, idBits = 1)
  
  val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))
    m := BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) := ioMemNode
    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}
  
  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := 
    streamNode := 
    BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 4)) :=
    ioInNode

  val in = InModuleBody { ioInNode.makeIO() }
  val out = InModuleBody { ioOutNode.makeIO() }
}

object FftMagCfarChainVanillaApp extends App
{
   val params = FftMagCfarVanillaParameters (
    fftParams = FFTParams.fixed(
      dataWidth = 16,
      twiddleWidth = 16,
      numPoints = 1024,
      useBitReverse  = true,
      runTime = true,
      numAddPipes = 1,
      numMulPipes = 1,
      expandLogic = Array.fill(log2Up(1024))(0),
      keepMSBorLSB = Array.fill(log2Up(1024))(true),
      minSRAMdepth = 1024,
      binPoint = 12
    ),
    magParams = MAGParams.fixed(
      dataWidth       = 16,
      binPoint        = 12,
      dataWidthLog    = 16,
      binPointLog     = 9,
      log2LookUpWidth = 9,
      useLast         = true,
      numAddPipes     = 1,
      numMulPipes     = 1
    ),
    cfarParams = CFARParams(
      protoIn = FixedPoint(16.W, 12.BP),
      protoThreshold = FixedPoint(16.W, 12.BP),
      protoScaler = FixedPoint(16.W, 12.BP),
      leadLaggWindowSize = 64,
      guardWindowSize = 4,
      sendCut = false,
      fftSize = 1024,
      minSubWindowSize = None,
      includeCASH = false,
      CFARAlgorithm = CACFARType
    ),
    fftAddress      = AddressSet(0x30000100, 0xFF),
    magAddress      = AddressSet(0x30000200, 0xFF),
    cfarAddress     = AddressSet(0x30002000, 0xFFF),
    beatBytes       = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new FftMagCfarChainVanilla(params) with FftMagCfarChainVanillaPins)
  chisel3.Driver.execute(Array("--target-dir", "verilog/FftMagCfarChainVanilla", "--top-name", "FftMagCfarChainVanilla"), ()=> standaloneModule.module) // generate verilog code
}
