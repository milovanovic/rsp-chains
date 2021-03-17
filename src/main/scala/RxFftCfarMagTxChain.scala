package rspChain

import dspblocks._
import dsptools._
import dsptools.numbers._

import chisel3._
import chisel3.experimental._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import uart._
import fft._
import magnitude._
import cfar._


class RxFftMagCfarTxChain(paramsChain: FftMagCfarVanillaParameters, uartParams: UARTParams, divisorInit: Int) extends LazyModule()(Parameters.empty) {

  val fftMagCfar = LazyModule(new FftMagCfarChainVanilla(paramsChain))
  val uTx_adapt = AXI4StreamWidthAdapter.oneToN(paramsChain.beatBytes)
  val uRx_adapt = AXI4StreamWidthAdapter.nToOne(paramsChain.beatBytes)
 // val uTx_queue = LazyModule(new StreamBuffer(BufferParams(params.beatBytes), beatBytes = params.beatBytes)) // check is it necessary to use StreamBuffer

  val uart      = LazyModule(new AXI4UARTBlock(uartParams, AddressSet(uartParams.address,0xFF), divisorInit = divisorInit, _beatBytes = paramsChain.beatBytes){
    // Add interrupt bundle
    val ioIntNode = BundleBridgeSink[Vec[Bool]]()
    ioIntNode := IntToBundleBridge(IntSinkPortParameters(Seq(IntSinkParameters()))) := intnode
    val ioInt = InModuleBody {
      val io = IO(Output(ioIntNode.bundle.cloneType))
      io.suggestName("int")
      io := ioIntNode.bundle
      io
    }
  })
  
  // uTx_adapt := uTx_queue.node := fftMagCfar.streamNode
  uTx_adapt := fftMagCfar.streamNode //AXI4StreamBuffer() := fftMagCfar.streamNode
  uRx_adapt := uart.streamNode := uTx_adapt
  fftMagCfar.streamNode  := uRx_adapt                                      // uRx_adapt  -----> uRx_split

  lazy val blocks = Seq(uart)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)
  
  uart.mem.get := AXI4Buffer() := bus.node
  fftMagCfar.mem.get := AXI4Buffer() := bus.node
  
  
  // Generate AXI4 slave output
  def standaloneParams = AXI4BundleParameters(addrBits = paramsChain.beatBytes*8, dataBits = paramsChain.beatBytes*8, idBits = 1)
  val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))
    m := BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) := ioMemNode
    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}
  
  lazy val module = new LazyModuleImp(this) {
    // generate interrupt output
    val int = IO(Output(uart.ioInt.cloneType))
    int := uart.ioInt

    // generate uart input/output
    val uTx = IO(Output(uart.module.io.txd.cloneType))
    val uRx = IO(Input(uart.module.io.rxd.cloneType))

    uTx := uart.module.io.txd
    uart.module.io.rxd := uRx
  }

}

object RxFftMagCfarTxChainApp extends App {

  val paramsChain = FftMagCfarVanillaParameters (
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
      numMulPipes = 0,
      fftSize = 1024,
      minSubWindowSize = None,
      includeCASH = false,
      CFARAlgorithm = CACFARType
    ),
    fftAddress      = AddressSet(0x30000100, 0xFF),
    magAddress      = AddressSet(0x30000200, 0xFF),
    cfarAddress     = AddressSet(0x30002000, 0xFFF),
    beatBytes       = 4)
  
//   val paramsChain = FftMagCfarVanillaParameters (
//     fftParams = FFTParams.fixed(
//       dataWidth = 16,
//       twiddleWidth = 16,
//       numPoints = 1024,
//       useBitReverse  = true,
//       runTime = true,
//       numAddPipes = 1,
//       numMulPipes = 1,
//       expandLogic = Array.fill(log2Up(1024))(0),
//       keepMSBorLSB = Array.fill(log2Up(1024))(true),
//       minSRAMdepth = 1024,
//       binPoint = 0
//     ),
//     magParams = MAGParams.fixed(
//       dataWidth       = 16,
//       binPoint        = 0,
//       dataWidthLog    = 16,
//       binPointLog     = 9,
//       log2LookUpWidth = 9,
//       useLast         = true,
//       numAddPipes     = 1,
//       numMulPipes     = 1
//     ),
//     cfarParams = CFARParams(
//       protoIn = FixedPoint(16.W, 0.BP),
//       protoThreshold = FixedPoint(16.W, 0.BP),
//       protoScaler = FixedPoint(16.W, 0.BP),
//       leadLaggWindowSize = 64,
//       guardWindowSize = 4,
//       fftSize = 1024,
//       minSubWindowSize = None,
//       includeCASH = false,
//       CFARAlgorithm = CACFARType
//     ),
//     fftAddress      = AddressSet(0x30000100, 0xFF),
//     magAddress      = AddressSet(0x30000200, 0xFF),
//     cfarAddress     = AddressSet(0x30002000, 0xFFF),
//     beatBytes       = 4)
    val uartParams  = UARTParams(address = 0x30009000, nTxEntries = 16, nRxEntries = 16)
    val divisorInit = (865).toInt
    //val divisorInit = (173).toInt // baudrate = 115200 for 20MHz

  val chainModule = LazyModule(new RxFftMagCfarTxChain(paramsChain, uartParams, divisorInit) {})
  
  chisel3.Driver.execute(Array("--target-dir", "verilog/RxFftMagCfarTxChain", "--top-name", "RxFftMagCfarTxChain"), () => chainModule.module) // generate verilog code

}




