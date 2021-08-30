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
import jtag2mm._

case class RSPChainTestFFT_v2_Parameters (
  asyncQueueParams: AsyncQueueCustomParams,
  preProcParams   : AXI4XwrDataPreProcParams,
  fftParams       : FFTParams[FixedPoint],
  jtag2mmAddress  : AddressSet,
  fftAddress      : AddressSet,
  splitterAddress : AddressSet,
  preProcAddress  : AddressSet,
  beatBytes       : Int
)

trait RSPChainTestFFT_v2_Standalone extends RSPChainTestFFT_v2 {
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()
  ioOutNode := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := combiner.streamNode

  val out = InModuleBody { ioOutNode.makeIO() }
}

class RSPChainTestFFT_v2(val params: RSPChainTestFFT_v2_Parameters) extends LazyModule()(Parameters.empty) {
  val asyncQueue  = LazyModule(new AsyncQueueAXI4StreamOut(params.asyncQueueParams))
  val xAWRpreProc = LazyModule(new AXI4xWRdataPreProcBlock(params.preProcAddress, params.preProcParams, params.beatBytes))
  val splitter =  LazyModule(new AXI4Splitter(address = params.splitterAddress, beatBytes = params.beatBytes))
  val axi4StreamBuffer = AXI4StreamBuffer(BufferParams(params.fftParams.numPoints*3, false, false))
  val fft         = LazyModule(new AXI4FFTBlock(params.fftParams, params.fftAddress, _beatBytes = params.beatBytes, configInterface = false))
  val combiner    = LazyModule(new AXI4StreamCustomCombiner(beatBytes = 4))


  val jtagModule = LazyModule(new JTAGToMasterAXI4(4, BigInt("0", 2), params.beatBytes, params.jtag2mmAddress, burstMaxNum=128){
  def makeIO2(): TopModuleIO = {
    val io2: TopModuleIO = IO(io.cloneType)
    io2.suggestName("ioJTAG")
    io2 <> io
    io2
  }
    val ioJTAG = InModuleBody { makeIO2() }
  })

  lazy val blocks = Seq(xAWRpreProc, fft, splitter)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }
  mem.get := jtagModule.node.get

  xAWRpreProc.streamNode := asyncQueue.streamNode
  splitter.streamNode := xAWRpreProc.streamNode
  combiner.streamNodein1 := axi4StreamBuffer
  combiner.streamNodein2 := fft.streamNode
  axi4StreamBuffer := splitter.streamNode
  fft.streamNode := splitter.streamNode

  lazy val module = new LazyModuleImp(this) {
    val ioJTAG = IO(jtagModule.ioJTAG.cloneType)

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

object RSPChainTestFFT_v2_App extends App
{
  val params = RSPChainTestFFT_v2_Parameters (
    asyncQueueParams = AsyncQueueCustomParams(),
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
      minSRAMdepth = 64,
      binPoint = 0
    ),
    preProcAddress  = AddressSet(0x30000100, 0xFF),
    splitterAddress = AddressSet(0x30000200, 0xFF),
    fftAddress      = AddressSet(0x30000300, 0xFF),
    jtag2mmAddress  = AddressSet(0x30010000, 0x7FFF),
    beatBytes      = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainTestFFT_v2(params) with RSPChainTestFFT_v2_Standalone)

  chisel3.Driver.execute(Array("--target-dir", "./verilog/RSPChainTestFFT_v2", "--top-name", "RSPChainTestFFT_v2"), ()=> standaloneModule.module)
}
