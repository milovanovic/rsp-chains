// package rspChain
//
// import chisel3._
// import chisel3.util._
// import chisel3.experimental._
//
// import freechips.rocketchip.amba.axi4._
// import freechips.rocketchip.amba.axi4stream._
// import freechips.rocketchip.diplomacy._
//
// import chisel3.iotesters.PeekPokeTester
//
// import org.scalatest.flatspec.AnyFlatSpec
// import org.scalatest.matchers.should.Matchers
//
// import java.io.File
// import scala.io.Source
// import breeze.plot._
//
// import java.io._
//
// import utils._
// import fft._
// import windowing._
// import magnitude._
//
// class RSPChainReducedTester_v2(
//   dut: RSPChainReduced_v2 with RSPChainReduced_v2_Standalone,
//   params: RSPChainReduced_v2_Parameters,
//   beatBytes: Int = 4,
//   silentFail: Boolean = false
// ) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel {
//
//   override def memAXI: AXI4Bundle = dut.ioMem.get
//   val mod     = dut.module
//   val fftSize = 1024
//
//   // Connect AXI4StreamModel to DUT
//   val master = bindMaster(dut.in)
//   val fileName = "rsp_chain_test_lane1.txt"
//
//   // dspQueue should output data after specific number of samples
//   memWriteWord(params.dspQueueAddress.base, 64)
//   memWriteWord(params.dspQueueAddress.base + beatBytes, 1)
//   // xAWR default values of registers
//
//   // fftSize is ok
//   // mode is dsp on
//   // no zerro padding
//
//   // windowing default values of registers
//
//   // windowing is disabled
//   // numPoints is equal to fftSize
//
//   // fft default values of registers
//   // fftSize is ok
//
//   //memWriteWord(params.magAddress.base, 1) // configure jpl magnitude aproximation
//   memWriteWord(params.magAddress.base, 0) // configure sqr magnitude
//
//   poke(dut.out.ready, true.B) // make output always ready to accept data
//   val radarDataComplex = Source.fromFile(fileName).getLines.toArray.map { br => br.toInt }
//
//   master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
//   master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
//   //master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
//
//   var outSeq = Seq[BigInt]()
//   var peekedVal: BigInt = 0
//
//   while (outSeq.length < params.fftParams.numPoints*3) {
//     if (peek(dut.out.valid) == 1 && peek(dut.out.ready) == 1) {
//       peekedVal = peek(dut.out.bits.data)
//       outSeq = outSeq :+ peekedVal
//     }
//     step(1)
//   }
//
//   val fileOut = new File("outputData_v2.txt")
//   val wout = new BufferedWriter(new FileWriter(fileOut))
//   for (i <- 0 until outSeq.length ) {
//     wout.write(f"${outSeq(i)}%04x" + "\n")
//   }
//   wout.close()
//
//   val f = Figure()
//   val p = f.subplot(0)
//   p.legend_=(true)
//   val xaxis = (0 until params.fftParams.numPoints*3).map(e => e.toDouble).toSeq.toArray
//   p.xlabel = "Frequency bin"
//   p.ylabel = "Amplitude"
//
//   val outSeqDouble = outSeq.map(c => c.toDouble)
//   p += plot(xaxis, outSeqDouble.toArray, name = "FFT")
//   p.title_=(s"FFT magnitude")
//
//   f.saveas(s"test_run_dir/FFTMagnitude.pdf")
//
//   step(5000)
// }
//
//
// class RadarChainReduced_v2_Spec extends AnyFlatSpec with Matchers {
//   // define parameters
//   val params = RSPChainReduced_v2_Parameters (
//     dspQueueParams = DspQueueCustomParams(),
//     queueMaxPreProc = 1024,
//     fftParams = FFTParams.fixed(
//       dataWidth = 12,
//       twiddleWidth = 16,
//       numPoints = 1024,
//       useBitReverse  = true,
//       runTime = true,
//       numAddPipes = 1,
//       numMulPipes = 1,
//       expandLogic = Array.fill(log2Up(1024))(1).zipWithIndex.map { case (e,ind) => if (ind < 4) 1 else 0 }, // expand first four stages, other do not grow
//       keepMSBorLSB = Array.fill(log2Up(1024))(true),
//       minSRAMdepth = 64, // memories larger than 64 should be mapped on block ram
//       binPoint = 0
//     ),
//     magParams = MAGParams(
//       protoIn  = FixedPoint(16.W, 0.BP),
//       protoOut = FixedPoint(16.W, 0.BP), // lets say identity node
//       magType  = MagJPLandSqrMag,
//       useLast = true,
//       numAddPipes = 1,
//       numMulPipes = 1
//     ),
//     windParams = WindowingParams.fixed(
//       dataWidth = 16,
//       binPoint  = 0,
//       numMulPipes = 1,
//       dirName = "windowing",
//       memoryFile = "./windowing/blacman.txt",
//       windowFunc = windowing.WindowFunctionTypes.Blackman(dataWidth_tmp = 16)
//     ),
//     dspQueueAddress = AddressSet(0x20000100, 0xFF), // just a debug core
//     preProcAddress  = AddressSet(0x30000100, 0xFF),
//     windAddress     = AddressSet(0x30000200, 0xFF),
//     fftAddress      = AddressSet(0x30000300, 0xFF),
//     magAddress      = AddressSet(0x30000500, 0xFF),
//     windRamAddress  = AddressSet(0x30001000, 0xFFF),
//     beatBytes      = 4)
//
//   // run test
//   val testModule = LazyModule(new RSPChainReduced_v2(params) with RSPChainReduced_v2_Standalone)
//
//   it should "Test rsp chain version 1 without asyncQueue and jtag" in {
//     chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => testModule.module) {
//           c => new RSPChainReducedTester_v2(dut = testModule,
//                                 beatBytes = 4,
//                                 params = params,
//                                 silentFail  = false
//                                 )
//     } should be (true)
//   }
//
// }
