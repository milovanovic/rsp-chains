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

// params.windAddress, params.windRamAddress, params.windParams, beatBytes
// address = params.fftAddress, params = params.fftParams, _beatBytes = params.beatBytes
case class RSPChainNoCRCNoPreProc_v0_Parameters (
  asyncQueueParams: AsyncQueueCustomParams,
  dspQueueParams  : DspQueueCustomParams,
  queueMaxPreProc : Int, // also mapped to distributed RAM, not visible Queue implementation with SyncReadMem if it is necessary, now it is visible so it can be used
  dspQueueAddress : AddressSet,
  jtag2mmAddress  : AddressSet,
  beatBytes       : Int
)

trait RSPChainNoCRCNoPreProc_v0_Standalone extends RSPChainNoCRCNoPreProc_v0 {
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()
  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    dspQueue.streamNode

  val out = InModuleBody { ioOutNode.makeIO() }
}

// make custom parameters
// for fft - think about radix 2 variant and full run time configurability while resources are not problem

class RSPChainNoCRCNoPreProc_v0(val params: RSPChainNoCRCNoPreProc_v0_Parameters, val addDebugNodes: Boolean = false) extends LazyModule()(Parameters.empty) {
  val asyncQueue  = LazyModule(new AsyncQueueAXI4StreamOut(params.asyncQueueParams))
  val dspQueue    = LazyModule(new AXI4DspQueueWithSyncReadMem(params.dspQueueParams, params.dspQueueAddress, params.beatBytes))

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
  lazy val blocks = Seq(dspQueue)
  val bus = LazyModule(new AXI4Xbar)

  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }
  // Connect mem node
  mem.get := jtagModule.node.get
  dspQueue.streamNode := AXI4StreamBuffer() := asyncQueue.streamNode

  lazy val module = new LazyModuleImp(this) {
    val ioJTAG = IO(jtagModule.ioJTAG.cloneType)

    // replace this with one io
    val in_0_data = IO(Input(SInt(params.asyncQueueParams.radarDataWidth.W)))
    val in_0_valid = IO(Input(Bool()))
    val queueFull = if (params.asyncQueueParams.isFullFlag) Some(IO(Output(Bool()))) else None
    val enProgFullReg = if (params.dspQueueParams.addEnProgFullOut) Some(IO(Output(Bool()))) else None
    val write_clock  = IO(Input(Clock()))
    val write_reset = IO(Input(Bool()))

    asyncQueue.module.in_data := in_0_data
    asyncQueue.module.in_valid := in_0_valid
    asyncQueue.module.write_clock := write_clock
    asyncQueue.module.write_reset := write_reset

    if (params.dspQueueParams.addEnProgFullOut) {
      enProgFullReg.get := dspQueue.module.enProgFullReg.get
    }
    if (params.asyncQueueParams.isFullFlag) {
      queueFull.get := asyncQueue.module.queueFull.get
    }

    ioJTAG <> jtagModule.ioJTAG
  }
}

object RSPChainNoCRCNoPreProc_v0_App extends App
{
  val params = RSPChainNoCRCNoPreProc_v0_Parameters (
    asyncQueueParams = AsyncQueueCustomParams(),
    dspQueueParams = DspQueueCustomParams(progFull = true, addEnProgFullOut = true),
    queueMaxPreProc = 1024,
    // in the future
    // crcAddress      = AddressSet(0x30000000, 0xFF),
    dspQueueAddress = AddressSet(0x20000100, 0xFF),
    jtag2mmAddress  = AddressSet(0x40000000, 0x7FFF), // Ask Vukan is it necessary to have a such a wide range of addresses
    beatBytes      = 4)

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainNoCRCNoPreProc_v0(params, addDebugNodes= true) with RSPChainNoCRCNoPreProc_v0_Standalone)

  chisel3.Driver.execute(Array("--target-dir", "./verilog/RSPChainNoCRCNoPreProc_v0", "--top-name", "RSPChainNoCRCNoPreProc_v0"), ()=> standaloneModule.module)
}
