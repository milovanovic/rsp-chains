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

import plfg._
import nco._
import fft._
import magnitude._
import cfar._


case class RspChainVanillaParameters (
  plfgParams      : PLFGParams[FixedPoint],
  ncoParams       : NCOParams[FixedPoint],
  fftParams       : FFTParams[FixedPoint],
  magParams       : MAGParams[FixedPoint],
  cfarParams      : CFARParams[FixedPoint],
  plfgAddress     : AddressSet,
  plfgRAM         : AddressSet,
  ncoAddress      : AddressSet,
  fftAddress      : AddressSet,
  magAddress      : AddressSet,
  cfarAddress     : AddressSet,
  beatBytes       : Int
)

class RspChainVanilla(params: RspChainVanillaParameters) extends LazyModule()(Parameters.empty) {

  val plfg      = LazyModule(new PLFGDspBlockMem(params.plfgAddress, params.plfgRAM, params.plfgParams, params.beatBytes))  
  val nco       = LazyModule(new AXI4NCOLazyModuleBlock(params.ncoParams, params.ncoAddress, params.beatBytes))
  val fft       = LazyModule(new AXI4FFTBlock(address = params.fftAddress, params = params.fftParams, _beatBytes = params.beatBytes))
  val mag       = LazyModule(new AXI4LogMagMuxBlock(params.magParams, params.magAddress, _beatBytes = params.beatBytes))
  val cfar      = LazyModule(new AXI4CFARBlock(params = params.cfarParams, address = params.cfarAddress, _beatBytes = params.beatBytes))

  // define mem
  lazy val blocks = Seq(plfg, nco, fft, mag, cfar)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)
  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
    //b.mem.foreach { _ := bus.node }
  }

  // connect nodes
  nco.freq.get := plfg.streamNode
  cfar.streamNode := AXI4StreamBuffer() := mag.streamNode := AXI4StreamBuffer() := fft.streamNode := AXI4StreamBuffer() := nco.streamNode
  
  lazy val module = new LazyModuleImp(this) {}
}

trait RspChainVanillaPins extends RspChainVanilla {
  val beatBytes = 4
  def standaloneParams = AXI4BundleParameters(addrBits = beatBytes*8, dataBits = beatBytes*8, idBits = 1)
  
  val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))
    m := BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) := ioMemNode
    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}

  // Generate AXI-stream output
  val ioStreamNode = BundleBridgeSink[AXI4StreamBundle]()
  ioStreamNode := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := cfar.streamNode
  val outStream = InModuleBody { ioStreamNode.makeIO() }
}

object RspChainVanillaApp extends App
{
  // here just define parameters
  val params = RspChainVanillaParameters (
    plfgParams = FixedPLFGParams(
      maxNumOfSegments = 4,
      maxNumOfDifferentChirps = 8,
      maxNumOfRepeatedChirps = 8,
      maxChirpOrdinalNum = 4,
      maxNumOfFrames = 4,
      maxNumOfSamplesWidth = 8,
      outputWidthInt = 16,
      outputWidthFrac = 0
    ),
    ncoParams = FixedNCOParams(
      tableSize = 128,
      tableWidth = 16,
      phaseWidth = 9,
      rasterizedMode = false,
      nInterpolationTerms = 0,
      ditherEnable = false,
      syncROMEnable = false,
      phaseAccEnable = true,
      roundingMode = RoundHalfUp,
      pincType = Streaming,
      poffType = Fixed
	  ),
    fftParams = FFTParams.fixed(
      dataWidth = 16,
      twiddleWidth = 16,
      numPoints = 1024,
      useBitReverse  = false,
      runTime = true,
      numAddPipes = 1,
      numMulPipes = 1,
      expandLogic = Array.fill(log2Up(1024))(0),
      keepMSBorLSB = Array.fill(log2Up(1024))(true),
      minSRAMdepth = 1024,
      binPoint = 0
    ),
    magParams = MAGParams.fixed(
      dataWidth       = 16,
      binPoint        = 0,
      dataWidthLog    = 16,
      binPointLog     = 9,
      log2LookUpWidth = 9,
      useLast         = true,
      numAddPipes     = 1,
      numMulPipes     = 1
    ),
    cfarParams = CFARParams(
      protoIn = FixedPoint(16.W, 0.BP),
      protoThreshold = FixedPoint(16.W, 0.BP),
      protoScaler = FixedPoint(16.W, 0.BP),
      leadLaggWindowSize = 64,
      guardWindowSize = 4,
      fftSize = 1024,
      minSubWindowSize = None,
      includeCASH = false,
      CFARAlgorithm = CACFARType
    ),
    plfgAddress     = AddressSet(0x30000000, 0xFF),
    plfgRAM         = AddressSet(0x30001000, 0xFFF),
    ncoAddress      = AddressSet(0x30000300, 0xF),
    fftAddress      = AddressSet(0x30000100, 0xFF),
    magAddress      = AddressSet(0x30000200, 0xFF),
    cfarAddress     = AddressSet(0x30002000, 0xFFF),
    beatBytes       = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RspChainVanilla(params) with RspChainVanillaPins)
  chisel3.Driver.execute(Array("--target-dir", "verilog/RspChainVanilla", "--top-name", "RspChainVanilla"), ()=> standaloneModule.module) // generate verilog code
}
