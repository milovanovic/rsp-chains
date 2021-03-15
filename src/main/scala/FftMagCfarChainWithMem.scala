package rspChain

import chisel3._
import chisel3.util._
import chisel3.experimental._

import dspblocks._
import dsptools.numbers._

import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

import fft._
import magnitude._
import cfar._

class ChainWithMem[T <: Data : Real : BinaryRepresentation](val chainParameters: FftMagCfarVanillaParameters, val memAddress: AddressSet, val protoMem: T)(implicit p: Parameters) extends LazyModule {
  
 // Instantiate lazy modules
  val fftMagCfar = LazyModule(new FftMagCfarChainVanilla(chainParameters))
  val memForTest = LazyModule(new AXI4MemForTestingFFT(DspComplex(protoMem), memAddress, chainParameters.beatBytes, chainParameters.fftParams.numPoints))
  
  fftMagCfar.streamNode := memForTest.streamNode
  
  val ioStreamNode = BundleBridgeSink[AXI4StreamBundle]()
        ioStreamNode := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := fftMagCfar.streamNode
        val outStream = InModuleBody { ioStreamNode.makeIO() }
  
  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)

  lazy val blocks = Seq(memForTest)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)
  
  memForTest.mem.get := bus.node
  fftMagCfar.mem.get := bus.node

  val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

    m :=
    BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
    ioMemNode

    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}
  
  lazy val module = new LazyModuleImp(this)
}

object ChainWithMemApp extends App
{
  val paramsRsp = FftMagCfarVanillaParameters (
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
    fftAddress      = AddressSet(0x30000100, 0xFF),
    magAddress      = AddressSet(0x30000200, 0xFF),
    cfarAddress     = AddressSet(0x30002000, 0xFFF),
    beatBytes       = 4)
  val memAddress      = AddressSet(0x60003000, 0xF)
  val protoMem        = FixedPoint(16.W, 0.BP)

  implicit val p: Parameters = Parameters.empty
  val testModule = LazyModule(new ChainWithMem(paramsRsp, memAddress, protoMem))
  
  chisel3.Driver.execute(Array("--target-dir", "verilog", "--top-name", "ChainWithMem"), ()=> testModule.module)
}
