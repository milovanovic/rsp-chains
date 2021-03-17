// SPDX-License-Identifier: Apache-2.0

package rspChain

import dsptools._
import dsptools.numbers._

import chisel3._
import chisel3.util._
import chisel3.iotesters.Driver
import chisel3.experimental._

import chisel3.iotesters.PeekPokeTester

import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

import org.scalatest.{FlatSpec, Matchers}

import breeze.math.Complex
import breeze.signal.{fourierTr, iFourierTr}
import breeze.linalg._
import breeze.plot._
import scala.util.Random

import fft._
import magnitude._
import cfar._

import java.io._

class FftMagCfarChainVanillaTester
(
  dut: FftMagCfarChainVanilla with FftMagCfarChainVanillaPins,
  params: FftMagCfarVanillaParameters,
  runTimeParams: RunTimeRspChainParams,
  plotEn: Boolean = false,
  silentFail: Boolean = false
) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel {

  def memAXI: AXI4Bundle = dut.ioMem.get
  val numFrames = 4
  val master = bindMaster(dut.in)

  val binPointFFTData = (params.fftParams.protoIQ.real match {
    case fp: FixedPoint => fp.binaryPoint.get
    case _ => 0
  })
   //window.map(c => println(c))
  
  val inData = RspChainTesterUtils.getComplexTones(numSamples = runTimeParams.fftSize, 0.125, 0.25, 0.5, scale = 1, shiftRangeFactor = binPointFFTData) //RspChainTesterUtils.getTone(numSamples = runTimeParams.fftSize, 0.125)
  inData.foreach(c => println(c.toString))
  
  val fileInReal = new File("inputDataReal.txt")
  val winR = new BufferedWriter(new FileWriter(fileInReal))
  for (i <- 0 until inData.length ) {
    winR.write(f"${inData(i).real.toInt}%04x" + "\n")
  }
  winR.close()
  
  val fileInImag = new File("inputDataImag.txt")
  val winI = new BufferedWriter(new FileWriter(fileInImag))
  for (i <- 0 until inData.length ) {
    winI.write(f"${inData(i).imag.toInt}%04x" + "\n")
  }
  winI.close()
  
  //getTone()
  // RspChainTesterUtils.getComplexTones(numSamples = runTimeParams.fftSize, 1/8, 1/4, 1/2)
  // val axi4StreamIn = RspChainTesterUtils.formAXI4StreamRealData(inData, 16)
  //RspChainTesterUtils.formAXI4StreamComplexData(inData, 16)
    
  val axi4StreamIn = RspChainTesterUtils.formAXI4StreamComplexData(inData, 16)

  val fftSignal = fourierTr(DenseVector(inData.toArray)).toScalaVector.map(c => Complex(c.real/runTimeParams.fftSize, c.imag/runTimeParams.fftSize)).map(c => c.abs)
  
  // print fftSize
  println(runTimeParams.fftSize.toString)
  // configure registers inside fft
  memWriteWord(params.fftAddress.base, log2Up(runTimeParams.fftSize))               // define number of active stages
  // configure registers inside LogMagMux
  memWriteWord(params.magAddress.base, 2)                                  // configure jpl magnitude aproximation
  
  val cfarMode = runTimeParams.CFARMode match {
                   case "Cell Averaging" => 0
                   case "Greatest Of" => 1
                   case "Smallest Of" => 2
                   case "CASH" => 3
                   case _ => 0
                 }
  
  val binPointThr = (params.cfarParams.protoThreshold match {
    case fp: FixedPoint => fp.binaryPoint.get
    case _ => 0
  })
  
  // configure CFAR module
  memWriteWord(params.cfarAddress.base, runTimeParams.fftSize)
  memWriteWord(params.cfarAddress.base + params.beatBytes, (runTimeParams.thresholdScaler*math.pow(2.0, binPointThr)).toInt)
  println("Threshold scaler has value:")
  println((runTimeParams.thresholdScaler*math.pow(2.0,binPointThr)).toString)
  memWriteWord(params.cfarAddress.base + 2 * params.beatBytes, runTimeParams.logOrLinearMode)
  if (params.cfarParams.CFARAlgorithm != GOSCFARType) {
    require(runTimeParams.divSum != None)
    memWriteWord(params.cfarAddress.base + 3 * params.beatBytes, runTimeParams.divSum.get)
  }
  memWriteWord(params.cfarAddress.base + 4 * params.beatBytes, runTimeParams.peakGrouping)
  if  (params.cfarParams.CFARAlgorithm == GOSCACFARType) {
    require(runTimeParams.CFARAlgorithm != None)
    val cfarAlgorithm = runTimeParams.CFARAlgorithm.get match {
                          case "CA" => 0
                          case "GOS" => 1
                          case _ => 0
                        }
    memWriteWord(params.cfarAddress.base + 5 * params.beatBytes, cfarAlgorithm)
  }
  memWriteWord(params.cfarAddress.base + 6 * params.beatBytes, cfarMode)
  memWriteWord(params.cfarAddress.base + 7 * params.beatBytes, runTimeParams.refWindowSize)
  memWriteWord(params.cfarAddress.base + 8 * params.beatBytes, runTimeParams.guardWindowSize)
  
  if (params.cfarParams.CFARAlgorithm != CACFARType) {
    require(runTimeParams.CFARAlgorithm != None)
    memWriteWord(params.cfarAddress.base + 9 * params.beatBytes, runTimeParams.indexLagg.get)
    memWriteWord(params.cfarAddress.base + 10 * params.beatBytes, runTimeParams.indexLead.get)
  }

  if (params.cfarParams.CFARAlgorithm == CACFARType && params.cfarParams.includeCASH == true) {
    require(runTimeParams.subWindowSize != None)
    memWriteWord(params.cfarAddress.base + 11 * params.beatBytes, runTimeParams.subWindowSize.get)
  }
  
  step(40)
  poke(dut.out.ready, true.B)

  master.addTransactions(axi4StreamIn.zipWithIndex.map { case (data, idx) => AXI4StreamTransaction(data = data,  last = if (idx == axi4StreamIn.length - 1) true else false) })
  
  var outSeq = Seq[Int]()
  var peekedVal: BigInt = 0
  var threshold = new Array[Double](runTimeParams.fftSize)
  var fftBin = new Array[Int](runTimeParams.fftSize)
  var peaks = new Array[Int](runTimeParams.fftSize)
  
  while (outSeq.length < runTimeParams.fftSize) {
    if (peek(dut.out.valid) == 1 && peek(dut.out.ready) == 1) {
      peekedVal = peek(dut.out.bits.data)
      outSeq = outSeq :+ peekedVal.toInt
    }
    step(1)
  }
  var idx = 0
  val fftBinWidth = log2Ceil(runTimeParams.fftSize)
  
  val fileOut = new File("outputData.txt")
  val wout = new BufferedWriter(new FileWriter(fileOut))
  
  for (i <- 0 until outSeq.length ) {
    wout.write(f"${outSeq(i)}%04x" + "\n")
  }
  wout.close()
  // split outSeq to fftBins, threshold and peaks
  while (idx < runTimeParams.fftSize) {
    threshold(idx) = outSeq(idx) >> (fftBinWidth + 1)
    peaks(idx) = outSeq(idx) & 0x00000001
    idx = idx + 1
  }
  
  val fileThr = new File("thresholdData.txt")
  val wthr = new BufferedWriter(new FileWriter(fileThr))
  
  for (i <- 0 until outSeq.length ) {
    wthr.write(f"${threshold(i).toInt}%04x" + "\n")
  }
  wthr.close()
  
  if (plotEn == true) {
    val f = Figure()
    val p = f.subplot(0)
    p.legend_=(true)
    val xaxis = (0 until fftSignal.size).map(e => e.toDouble).toSeq.toArray
    p.xlabel = "Frequency bin"
    p.ylabel = "Amplitude"

    val thresholdPlot = threshold.toSeq

    p += plot(xaxis, fftSignal.toArray, name = "FFT input Signal")
    p += plot(xaxis, threshold.toArray, name = "CFAR threshold")
    p.title_=(s"Constant False Alarm Rate")

    f.saveas(s"test_run_dir/ThresholdPlot.pdf")
  }
}

class FftMagCfarChainVanillaSpec extends FlatSpec with Matchers {
  implicit val p: Parameters = Parameters.empty

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
      numMulPipes = 0,
      includeCASH = false,
      CFARAlgorithm = CACFARType
    ),
    fftAddress      = AddressSet(0x30000100, 0xFF),
    magAddress      = AddressSet(0x30000200, 0xFF),
    cfarAddress     = AddressSet(0x30002000, 0xFFF),
    beatBytes       = 4)
  
  // use all default parameters
  val runTimeParams = RunTimeRspChainParams()

  behavior of "chain fft -> mag -> cfar"
  it should "work" in {
    val lazyDut = LazyModule(new FftMagCfarChainVanilla(params) with FftMagCfarChainVanillaPins)
    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv", "--target-dir", "test_run_dir/FftMagCfarVanilla/", "--top-name", "FftMagCfarVanilla"), () => lazyDut.module) {
      c => new FftMagCfarChainVanillaTester(lazyDut, params, runTimeParams, true)
    } should be (true)
  }
}
