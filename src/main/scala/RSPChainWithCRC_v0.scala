package rspChain

import chisel3._
import chisel3.experimental._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy._

import utils._
import crc._
import jtag2mm._

trait RSPChainWithCRC_v0_Standalone extends RSPChainWithCRC_v0 {
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    radarCRC.streamNode

  val out = InModuleBody { ioOutNode.makeIO() }
}

class RSPChainWithCRC_v0(crcParams: RadarCRCParams, asyncQueueParams: AsyncQueueCustomParams, jtag2mmAddress: AddressSet, crcAddress: AddressSet, beatBytes: Int) extends LazyModule()(Parameters.empty) {
  val radarCRC = LazyModule(new AXI4RadarDataCrcChecker(crcParams, crcAddress, beatBytes))
  val asyncQueue = LazyModule(new AsyncQueueWithCrcLine(asyncQueueParams))

  val jtagModule = LazyModule(new JTAGToMasterAXI4(4, BigInt("0", 2), beatBytes, jtag2mmAddress, burstMaxNum=128){
  def makeIO2(): TopModuleIO = {
    val io2: TopModuleIO = IO(io.cloneType)
    io2.suggestName("ioJTAG")
    io2 <> io
    io2
  }
    val ioJTAG = InModuleBody { makeIO2() }
  })

  // define mem
  lazy val blocks = Seq(radarCRC) // only one, but in the future more than one for sure
  val bus = LazyModule(new AXI4Xbar)

  val mem = Some(bus.node)

  for (b <- blocks) {
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }
  // Connect mem node
  mem.get := jtagModule.node.get
  radarCRC.streamNode := asyncQueue.streamNode

  lazy val module = new LazyModuleImp(this) {
    val ioJTAG = IO(jtagModule.ioJTAG.cloneType)

    // replace this with one io
    val in_0_data = IO(Input(SInt(asyncQueueParams.radarDataWidth.W)))
    val in_0_valid = IO(Input(Bool()))
    val crc_on_line_in = IO(Input(Bool()))
    val crc_on_line_out = IO(Output(Bool()))
    val queueFull = if (asyncQueueParams.isFullFlag) Some(IO(Output(Bool()))) else None
    val write_clock  = IO(Input(Clock()))
    val write_reset = IO(Input(Bool()))

    asyncQueue.module.in_0_data := in_0_data
    asyncQueue.module.in_0_valid := in_0_valid
    asyncQueue.module.crc_on_line_in := crc_on_line_in
    crc_on_line_out := asyncQueue.module.crc_on_line_out
    asyncQueue.module.write_clock := write_clock
    asyncQueue.module.write_reset := write_reset

    if (asyncQueueParams.isFullFlag) {
      queueFull.get := asyncQueue.module.queueFull.get
    }

    ioJTAG <> jtagModule.ioJTAG
  }
}


object RSPChainWithCRC_v0_App extends App
{
  val paramsCRC: RadarCRCParams = RadarCRCParams()
  val crcAddress = AddressSet(0x30000000, 0xF)
  val jtagAddress = AddressSet(0x40000000, 0x7FFF)
  val jtag2mmAddress = AddressSet(0x40000000, 0x7FFF)
  val beatBytes = 4
  val paramsCustomQueue: AsyncQueueCustomParams = AsyncQueueCustomParams()

  implicit val p: Parameters = Parameters.empty
  val standaloneModule = LazyModule(new RSPChainWithCRC_v0(paramsCRC, paramsCustomQueue, crcAddress, jtag2mmAddress, beatBytes) with RSPChainWithCRC_v0_Standalone)

  chisel3.Driver.execute(Array("--target-dir", "./verilog/RSPChainWithCRC_v0", "--top-name", "RSPChainWithCRC_v0"), ()=> standaloneModule.module)
}
