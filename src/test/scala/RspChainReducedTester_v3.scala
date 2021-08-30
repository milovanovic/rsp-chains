package rspChain

// perhaps a lot of unused inclusions
import chisel3._
import chisel3.util._
import chisel3.experimental._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._

import chisel3.iotesters.PeekPokeTester

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.io.Source
import breeze.plot._

import java.io._

import dsputils._
import fft._
import xWRDataPreProc._
import windowing._
import magnitude._
import cfar._

// subwindowSize in runtime needs to be equal
// case class RunTimeRspChainParams(
//   CFARAlgorithm         : Option[String] = Some("GOS"),   // CFAR algorithm -> only valid when GOSCA algorithm is used
//   CFARMode              : String = "Smallest Of",         // can be "Smallest Of", "Greatest Of", "Cell Averaging", "CASH"
//   refWindowSize         : Int = 11,                        // number of active cells inside leading/lagging window
//   guardWindowSize       : Int = 3,                        // number of active guard cells
//   subWindowSize         : Option[Int] = Some(8),          // relevant only for CASH algoithm
//   fftSize               : Int = 1024,                     // fft size
//   thresholdScaler       : Double = 4,                     // thresholdScaler - 4 je bilo za smallest of za gos je 3, za ca je bilo 2.5
//   divSum                : Option[Int] = Some(3),          // divider used for CA algorithms
//   peakGrouping          : Int = 1,                        // peak grouping is enabled by default
//   indexLagg             : Option[Int] = Some(6),          // index of cell inside lagging window
//   indexLead             : Option[Int] = Some(6),          // index of cell inside leading window
//   magMode               : Int = 1,                        // calculate jpl mag by default
//   logOrLinearMode       : Int = 1){
//   //require(isPow2(refWindowSize) & isPow2(fftSize))
//   //require(refWindowSize > 0 & guardWindowSize > 0)
//   require(refWindowSize > 0)
//   require(refWindowSize > guardWindowSize)
//   if (subWindowSize != None) {
//     require(subWindowSize.get <= refWindowSize)
//   }
//   if (indexLead != None) {
//     require(indexLead.get < refWindowSize)
//   }
//   if (indexLagg != None) {
//     require(indexLagg.get < refWindowSize)
//   }
// }


// Parameters of chain 32 : 20 magnitude squared
// case class RunTimeRspChainParams(
//   CFARAlgorithm         : Option[String] = Some("CA"),   // CFAR algorithm -> only valid when GOSCA algorithm is used
//   CFARMode              : String = "Greatest Of",         // can be "Smallest Of", "Greatest Of", "Cell Averaging", "CASH"
//   refWindowSize         : Int = 8,                        // number of active cells inside leading/lagging window
//   guardWindowSize       : Int = 3,                        // number of active guard cells
//   subWindowSize         : Option[Int] = Some(8),          // relevant only for CASH algoithm
//   fftSize               : Int = 1024,                     // fft size
//   thresholdScaler       : Double = 100,                     // thresholdScaler - 4 je bilo za smallest of za gos je 3, za ca je bilo 2.5
//   divSum                : Option[Int] = Some(4),          // divider used for CA algorithms
//   peakGrouping          : Int = 1,                        // peak grouping is enabled by default
//   indexLagg             : Option[Int] = Some(6),          // index of cell inside lagging window
//   indexLead             : Option[Int] = Some(6),          // index of cell inside leading window
//   magMode               : Int = 0,                        // calculate jpl mag by default-1, 0 is for squared magnitude
//   logOrLinearMode       : Int = 1){
//   //require(isPow2(refWindowSize) & isPow2(fftSize))
//   //require(refWindowSize > 0 & guardWindowSize > 0)
//   require(refWindowSize > 0)
//   require(refWindowSize > guardWindowSize)
//   if (subWindowSize != None) {
//     require(subWindowSize.get <= refWindowSize)
//   }
//   if (indexLead != None) {
//     require(indexLead.get < refWindowSize)
//   }
//   if (indexLagg != None) {
//     require(indexLagg.get < refWindowSize)
//   }
// }

case class RunTimeRspChainParams(
  CFARAlgorithm         : Option[String] = Some("CA"),   // CFAR algorithm -> only valid when GOSCA algorithm is used
  CFARMode              : String = "Greatest Of",         // can be "Smallest Of", "Greatest Of", "Cell Averaging", "CASH"
  refWindowSize         : Int = 8,                        // number of active cells inside leading/lagging window
  guardWindowSize       : Int = 3,                        // number of active guard cells
  subWindowSize         : Option[Int] = Some(8),          // relevant only for CASH algoithm
  fftSize               : Int = 1024,                     // fft size
  thresholdScaler       : Double = 50,                     // thresholdScaler - 4 je bilo za smallest of za gos je 3, za ca je bilo 2.5
  divSum                : Option[Int] = Some(4),          // divider used for CA algorithms
  peakGrouping          : Int = 1,                        // peak grouping is enabled by default
  indexLagg             : Option[Int] = Some(6),          // index of cell inside lagging window
  indexLead             : Option[Int] = Some(6),          // index of cell inside leading window
  magMode               : Int = 0,                        // calculate jpl mag by default-1, 0 is for squared magnitude
  logOrLinearMode       : Int = 1){
  //require(isPow2(refWindowSize) & isPow2(fftSize))
  //require(refWindowSize > 0 & guardWindowSize > 0)
  require(refWindowSize > 0)
  require(refWindowSize > guardWindowSize)
  if (subWindowSize != None) {
    require(subWindowSize.get <= refWindowSize)
  }
  if (indexLead != None) {
    require(indexLead.get < refWindowSize)
  }
  if (indexLagg != None) {
    require(indexLagg.get < refWindowSize)
  }
}


class RSPChainReducedTester_v3(
  dut: RSPChainReduced_v3 with RSPChainReduced_v3_Standalone,
  params: RSPChainReduced_Parameters,
  runTimeParams: RunTimeRspChainParams,
  inFileName: String,
  beatBytes: Int = 4,
  silentFail: Boolean = false,
  writeOutToFile: Boolean = true,
) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel {

  override def memAXI: AXI4Bundle = dut.ioMem.get
  val mod     = dut.module
  val fftSize = 1024

  // Connect AXI4StreamModel to DUT
  val master = bindMaster(dut.in)
  val fileName = inFileName


  // dspQueue should output data after specific number of samples
  memWriteWord(params.dspQueueAddress.base, 64)
  memWriteWord(params.dspQueueAddress.base + beatBytes, 1)
  // xAWR default values of registers

  // fftSize is ok
  // configure fft size as a number of stages
  memWriteWord(params.fftAddress.base, log2Ceil(runTimeParams.fftSize))

  // mode is dsp on
  // no zerro padding

  // windowing default values of registers

  // windowing is disabled
  // numPoints is equal to fftSize

  // fft default values of registers
  // fftSize is ok

  memWriteWord(params.magAddress.base, runTimeParams.magMode)
  // configure CFAR module
  val cfarMode = runTimeParams.CFARMode match {
                   case "Cell Averaging" => 0
                   case "Greatest Of" => 1
                   case "Smallest Of" => 2
                   case "CASH" => 3
                   case _ => 0
                 }

  val binPointThr = (params.cfarParams.protoScaler match {
    case fp: FixedPoint => fp.binaryPoint.get
    case _ => 0
  })

  // if you are changing FFT size from higher to smaller value it is  important to first configure refWindowSize and then fftSize
  // If the case is opposite then configure first fft and after that refWindowSize
  memWriteWord(params.cfarAddress.base + 4 * params.beatBytes, runTimeParams.refWindowSize)
  memWriteWord(params.cfarAddress.base, runTimeParams.fftSize)
  memWriteWord(params.cfarAddress.base + params.beatBytes, (runTimeParams.thresholdScaler*math.pow(2.0, binPointThr)).toInt)
  println("Threshold scaler has value:")
  println((runTimeParams.thresholdScaler*math.pow(2.0,binPointThr)).toString)


  memWriteWord(params.cfarAddress.base + 2 * params.beatBytes, runTimeParams.peakGrouping)

  if  (params.cfarParams.CFARAlgorithm == GOSCACFARType) {
    require(runTimeParams.CFARAlgorithm != None)
    val cfarAlgorithm = runTimeParams.CFARAlgorithm.get match {
                          case "CA" => 0
                          case "GOS" => 1
                          case _ => 0
                        }
    memWriteWord(params.cfarAddress.base + 7 * params.beatBytes, cfarAlgorithm) // if it is GOSCA then CASH is excluded
  }

  memWriteWord(params.cfarAddress.base + 3 * params.beatBytes, cfarMode)
  memWriteWord(params.cfarAddress.base + 5 * params.beatBytes, runTimeParams.guardWindowSize)


  if (params.cfarParams.CFARAlgorithm != CACFARType) {
    require(runTimeParams.CFARAlgorithm != None)
    memWriteWord(params.cfarAddress.base + 8 * params.beatBytes, runTimeParams.indexLagg.get)
    memWriteWord(params.cfarAddress.base + 9 * params.beatBytes, runTimeParams.indexLead.get)
  }

  if (params.cfarParams.CFARAlgorithm != GOSCFARType) {
    require(runTimeParams.divSum != None)
    memWriteWord(params.cfarAddress.base + 6 * params.beatBytes, runTimeParams.divSum.get)
  }

  if (params.cfarParams.CFARAlgorithm == CACFARType && params.cfarParams.includeCASH == true) {
    require(runTimeParams.subWindowSize != None)
    memWriteWord(params.cfarAddress.base + 7 * params.beatBytes, runTimeParams.subWindowSize.get)
  }

  step(40)
  poke(dut.out.ready, true.B) // make output always ready to accept data
  val radarDataComplex = Source.fromFile(fileName).getLines.toArray.map { br => br.toInt }

  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))

  var outSeq = Seq[BigInt]()
  var peekedVal: BigInt = 0
  var threshold = new Array[BigInt](2*runTimeParams.fftSize)
  var fftBins = new Array[Int](2*runTimeParams.fftSize)
  var cut = new Array[BigInt](2*runTimeParams.fftSize)
  var peaks = new Array[Int](2*runTimeParams.fftSize)
  var indicesWithPeaks = Seq[Int]()

  while (outSeq.length < 2*runTimeParams.fftSize) {
    if (peek(dut.out.valid) == 1 && peek(dut.out.ready) == 1) {
      peekedVal = peek(dut.out.bits.data)
      outSeq = outSeq :+ peekedVal//.toInt
    }
    step(1)
  }
  //println("BigInt data is:")
  //println(outSeq(16).toString)
  // this data can be compared with ila result
  var idx = 0
  val fftBinWidth = log2Ceil(params.cfarParams.fftSize)
  val fileOut = new File("outputData.txt")
  val wout = new BufferedWriter(new FileWriter(fileOut))
  for (i <- 0 until outSeq.length ) {
    wout.write(f"${outSeq(i)}%04x" + "\n")
  }
  wout.close()

  val outputWidthMin = if (params.cfarParams.sendCut)
                          params.cfarParams.protoThreshold.getWidth + params.cfarParams.protoIn.getWidth + log2Ceil(params.cfarParams.fftSize) + 1
                       else
                          params.cfarParams.protoThreshold.getWidth + log2Ceil(params.cfarParams.fftSize) + 1
  val axiWidth = (((outputWidthMin + 8 - 1))/8)*8
  println(axiWidth.toString)

  // i guess that bigInt is represented with 64 bits
  val axiZeros = axiWidth - (fftBinWidth + 1 + params.cfarParams.protoThreshold.getWidth + params.cfarParams.protoIn.getWidth)
  val cutMask = ((2 << params.cfarParams.protoIn.getWidth-1) - 1)
  println(cutMask.toString)
  //  split outSeq to fftBins, threshold, cut and peaks
  while (idx < 2*params.cfarParams.fftSize) {//while (idx < params.cfarParams.fftSize) {
    fftBins(idx) = (outSeq(idx) >> 1).toInt & (params.cfarParams.fftSize-1)
    // println(fftBins(idx).toString) - works good
    cut(idx) = ((outSeq(idx) >> fftBinWidth + 1) & cutMask)//.toInt // or apply mask
    threshold(idx) = (outSeq(idx) >> (fftBinWidth + 1 + params.cfarParams.protoIn.getWidth))//.toInt
    peaks(idx) = (outSeq(idx) & 0x000000000001).toInt
    if (peaks(idx) == 1 && idx>1024)
      indicesWithPeaks = indicesWithPeaks :+ idx
    idx = idx + 1
  }

  println(cut(0).toString)

  val f = Figure()
  val p = f.subplot(0)
  p.legend_=(true)
  val xaxis = (0 until runTimeParams.fftSize*2).map(e => e.toDouble).toSeq.toArray
  p.xlabel = "Frequency bin"
  p.ylabel = "Amplitude"

/*  val cutDouble = cut.take(2*runTimeParams.fftSize).map(c => ((BigDecimal(c)/BigDecimal(math.pow(2,20))).toDouble).toDouble).toSeq
  val thresholdDouble = threshold.take(2*runTimeParams.fftSize).map(c => (BigDecimal(c)/BigDecimal(math.pow(2,20))).toDouble).toSeq
*/
  val cutDouble = cut.take(2*runTimeParams.fftSize).map(c => c.toDouble).toSeq
  println(cutDouble(0).toString)

  val thresholdDouble = threshold.take(2*runTimeParams.fftSize).map(c => c.toDouble).toSeq
  val plotMin = 0.0000000001


  p += plot(xaxis, cutDouble.toArray, name = "FFT input Signal")
  p += plot(xaxis, thresholdDouble.toArray, name = "CFAR threshold")
  p.title_=(s"Constant False Alarm Rate")

  f.saveas(s"test_run_dir/ThresholdPlot.pdf")

 // p.ylim(Seq(plotMin, cutDouble.min).max, Seq(cutDouble.max, thresholdDouble.max).max)

  if (writeOutToFile) {
      // write data to file
    val magString = if (runTimeParams.magMode == 0) "_sqr" else ""
    val fileCut = new File("cell_under_test" + magString + ".txt")
    val wCut = new BufferedWriter(new FileWriter(fileCut))
    val drop = 0
    val cutStore = if (drop == 1) cut.drop(1024) else cut.take(1024)

    for (i <- 0 until cutStore.length) {
      //wCut.write(f"${cut(i).toInt}%04x" + "\n") // for hex data with 4 digit
      wCut.write(f"${cutStore(i).toInt}" + "\n")
    }
    wCut.close()
    // TODO: Add directory where all those files are going to be stored
    val strEdgeMode = params.cfarParams.edgesMode match { // this is going to be replaced with runTimeParams
                        case Zero => "_zero"
                        case Cyclic => "_cyclic"
                        case NonCyclic => "_non_cyclic"
                        case _ => ""
                      }
    println("threshold_ca" + strEdgeMode + ".txt")
    val isGOS = if (params.cfarParams.CFARAlgorithm != CACFARType) "_gos" else ""
    // write data to file
    val fileThr = runTimeParams.CFARMode match {
                    case "Cell Averaging" => new File("threshold_ca" + strEdgeMode + isGOS + magString + ".txt")
                    case "Greatest Of" =>  new File("threshold_go" + strEdgeMode + isGOS + magString + ".txt")
                    case "Smallest Of" => new File("threshold_so" + strEdgeMode + isGOS + magString + ".txt")
                    case "CASH" => new File("threshold_cash" + strEdgeMode + isGOS + magString + ".txt")
                    case _ => new File("")
                  }
    val wthr = new BufferedWriter(new FileWriter(fileThr))
    val thrStore = if (drop == 1) threshold.drop(1024) else threshold.take(1024)
    for (i <- 0 until thrStore.length) {
      wthr.write(f"${thrStore(i).toInt}" + "\n")
    }
    wthr.close()

    val filePeaks =  runTimeParams.CFARMode match {
                      case "Cell Averaging" => new File("ca_peaks" + strEdgeMode + isGOS + magString + ".txt")
                      case "Greatest Of" =>  new File("go_peaks" + strEdgeMode + isGOS + magString + ".txt")
                      case "Smallest Of" => new File("so_peaks" + strEdgeMode + isGOS + magString + ".txt")
                      case "CASH" => new File("cash_peaks" + strEdgeMode + isGOS + magString + ".txt")
                      case _ => new File("")
                    }
    val wPeaks = new BufferedWriter(new FileWriter(filePeaks))
    val peaksStore = if (drop == 1) indicesWithPeaks.drop(1024) else indicesWithPeaks.take(1024)
    for (i <- 0 until peaksStore.length) {
      wPeaks.write(f"${(peaksStore(i) - 1024).toInt}" + "\n")
    }
    wPeaks.close()
  }

//   println("outSeq")
//   outSeq.take(10).map(c => println(c.toString))
//   println("Threshold:")
//   threshold.take(10).map(c => println((c).toString))
//   println("fftBin:")
//   fftBins.take(10).map(c => println((c.toString)))
//   println("Cut:")
//   cut.take(10).map(c => println((c.toString)))

  step(5000)
}


class RadarChainReduced_v3_Spec extends AnyFlatSpec with Matchers {
  // define parameters
  val params = RSPChainReduced_Parameters (
    dspQueueParams = DspQueueCustomParams(progFull = true),
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
      use4Muls = true,
      //sdfRadix = "2",
      expandLogic = Array.fill(log2Up(1024))(1).zipWithIndex.map { case (e,ind) => if (ind < 4) 1 else 0 }, // expand first four stages, other do not grow
      keepMSBorLSB = Array.fill(log2Up(1024))(true),
      minSRAMdepth = 1024, // memories larger than 64 should be mapped on block ram
      binPoint = 10
    ),
    magParams = MAGParams(
      protoIn  = FixedPoint(16.W, 10.BP),
      protoOut =  FixedPoint(32.W, 20.BP),
      magType  = MagJPLandSqrMag,
      binPointGrowth = 10,
      useLast = true,
      numAddPipes = 1,
      numMulPipes = 1
    ),
    cfarParams = CFARParams(
      protoIn =  FixedPoint(32.W, 20.BP), // FixedPoint(32.W, 20.BP), //FixedPoint(24.W, 10.BP),
      protoThreshold = FixedPoint(32.W, 20.BP),//FixedPoint(24.W, 10.BP),
      protoScaler = FixedPoint(16.W, 6.BP),// for squared magnitude it requires higher values in general
      leadLaggWindowSize = 128,
      guardWindowSize = 8,
      logOrLinReg = false,
      fftSize = 1024,
      sendCut = true,
      minSubWindowSize = Some(8),
      includeCASH = true, //false
      CFARAlgorithm = CACFARType,//GOSCACFARType, //CACFARType,
      edgesMode = Zero // cyclic
    ),
    windParams = WindowingParams.fixed(
      numPoints = 1024,
      dataWidth = 16,
      binPoint  = 10,
      numMulPipes = 1,
      dirName = "test_run_dir",
      memoryFile = "./test_run_dir/blacman.txt",
      windowFunc = windowing.WindowFunctionTypes.Blackman(dataWidth_tmp = 16)
    ),
    // crcAddress      = AddressSet(0x30000000, 0xFF),
    dspQueueAddress = AddressSet(0x20000100, 0xFF), // just a debug core
    preProcAddress  = AddressSet(0x30000100, 0xFF),
    windAddress     = AddressSet(0x30000200, 0xFF),
    fftAddress      = AddressSet(0x30000300, 0xFF),
    magAddress      = AddressSet(0x30000500, 0xFF),
    cfarAddress     = AddressSet(0x30000600, 0xFF),
    windRamAddress  = AddressSet(0x30001000, 0xFFF),
    beatBytes       = 4)
    //val inFileName : String = "adc_256_tx1.txt"
    //val inFileName : String = "adc_256_tx2_with_reflector_on_floor.txt"
    //val inFileName : String = "adc_1024_tx1_duty45.txt"
    val inFileName : String = "iladata_signed3.txt"
    //val inFileName : String = "adc_1024_tx1_duty_94.txt"


  // run test
  val testModule = LazyModule(new RSPChainReduced_v3(params) with RSPChainReduced_v3_Standalone)
  //val runTimeParams = RunTimeRspChainParams()                      // no cash
  val runTimeParams = RunTimeRspChainParams(subWindowSize = Some(8)) // when cash is included

  // Change here parameters

  it should "Test rsp chain without asyncQueue and jtag" in {
    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => testModule.module) {
    //chisel3.iotesters.Driver.execute(Array( "-tbn", "firrtl"), () => testModule.module) {
          c => new RSPChainReducedTester_v3(dut = testModule,
                                beatBytes = 4,
                                params = params,
                                runTimeParams = runTimeParams,
                                inFileName = inFileName,
                                silentFail  = false
                                )
    } should be (true)
  }
}
