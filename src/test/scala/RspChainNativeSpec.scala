package rspChain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import breeze.plot._
import breeze.linalg._
import breeze.math.Complex
import breeze.signal.{fourierTr, iFourierTr}

import chisel3._
import chisel3.util._
import chisel3.experimental._

import scala.math.pow
import scala.util.{Random}

import dsptools._
import dsptools.numbers._

import fft._
import magnitude._

class FixedPointRspChainNativeTester(c: RspChainNative_v0) extends DspTester(c) {

   def simpleNativeRspChainTest(tol: Int = 6, testSignal: Seq[Complex], params: RspNativeParams_v0): (Seq[Double], Seq[Double]) = {
    val cyclesWait = 5 * params.fftParams.numPoints
    val fftSize = params.fftParams.numPoints
    val numStages = log2Up(fftSize)
    val inp =  testSignal //if (params.fftParams.decimType == DITDecimType) bitrevorder_data(testSignal) else testSignal
    val outComplex = fourierTr(DenseVector(testSignal.toArray)).toScalaVector //if (params.fftParams.decimType == DITDecimType) fourierTr(DenseVector(testSignal.toArray)).toScalaVector else bitrevorder_data(fourierTr(DenseVector(inp.toArray)).toScalaVector)
    //val out = outComplex.map(c => c.abs)
    val out = outComplex.map(e => e.real * e.real + e.imag * e.imag)

    val dataWidthIn = params.fftParams.protoIQ.real.getWidth
    val dataWidthOut= params.fftParams.protoIQOut.real.getWidth
    val div2Num = numStages - (dataWidthOut - dataWidthIn)

    val magSqrImpact = 1*2

    val trimEnableDiv = if (div2Num > 0) pow(2, div2Num) else 1

    val scalingFactor = if (params.fftParams.trimEnable) trimEnableDiv else pow(2, params.fftParams.expandLogic.filter(_ == 0).size + magSqrImpact).toInt
    println("Scaling factor is:")
    println(scalingFactor.toString)

    val input = inp.iterator
    var output = Seq[Double]()

    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)
    step(2)
    poke(c.io.out.ready, 1)
    poke(c.io.in.valid, 1)

    while (output.length < fftSize) {
      if (input.hasNext && peek(c.io.in.ready)) {
        poke(c.io.in.bits, input.next())
      }
      if (peek(c.io.out.valid)) {
       /* params.fftParams.protoIQ.real match {
        //  case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, out(output.length)/scalingFactor) }
         // case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, out(output.length)/scalingFactor) }
        }*/
        output = output :+ peek(c.io.out.bits)
      }
      step(1)
    }
    poke(c.io.in.valid, 0)
    step(inp.length)
    reset(2)
    //val bitrevorderOutput = if (params.decimType == DIFDecimType) bitrevorder_data(output.toVector) else output
    //val scalafftMag = fourierTr(DenseVector(testSignal.toArray)).toScalaVector
    output.foreach {e => println((e*scalingFactor).toString)}
    println()
    out.foreach{ e=> println(e.toString)}

    (output.map(e => e*scalingFactor), out)
  }
}


class RspChainNativeSpec extends AnyFlatSpec with Matchers {

  def calc_sqnr(chiselMAG: Seq[Double], scalaMAG: Seq[Double]): Double = {
    import breeze.signal._
    import breeze.linalg._
    import scala.math._

    val scaler = 1
    val signal  = scalaMAG.map { c=> pow(c/scaler, 2) }
    val noise = chiselMAG.zip(scalaMAG).map { case (cMAG, sMAG) => pow(math.abs((cMAG/scaler - sMAG/scaler)), 2) }
    val sumSignal = signal.foldLeft(0.0)(_ + _)
    val noiseSum = noise.foldLeft(0.0)(_ + _)
    10*math.log10(sumSignal/noiseSum)
  }

  /**
   * Generates random complex input data
   */
  def genRandSignal(numSamples: Int, scalingFactor: Int): Seq[Complex] = {
    import scala.math.sqrt
    import scala.util.Random

    (0 until numSamples).map(x => Complex(Random.nextDouble(), Random.nextDouble()))
  }

  val numSamples = 1
  val fftSizeSeq = Seq(2, 4, 8, 16, 32)// 32, 64, 128, 256, 512, 1024) //Seq(128)

  // define input and output data widths
  val dataWidthIn = 12
  val binPointIn = 10
  val dataWidthOut = 16
  val binPointOut = 10
  val N_stages = 4

  var sqnr_results_N_stages_grow : Array[Double] = Array.fill(fftSizeSeq.length)(0)
  var sqnr_results_N_stages_grow_sqr_trim : Array[Double] = Array.fill(fftSizeSeq.length)(0)
  var sqnr_results_N_stages_grow_with_trim : Array[Double] = Array.fill(fftSizeSeq.length)(0)
  var sqnr_results_scale: Array[Double] = Array.fill(fftSizeSeq.length)(0)
  var sqnr_results_grow: Array[Double] = Array.fill(fftSizeSeq.length)(0)


  var sum_grow: Double = 0
  var sum_N_stages_grow : Double = 0
  var sum_N_stages_grow_sqr_trim: Double = 0
  var sum_N_stages_grow_with_trim : Double = 0
  var sum_scale: Double = 0

  val t1 = System.nanoTime

  for (i <- fftSizeSeq) {
    sum_N_stages_grow = 0
    sum_N_stages_grow_with_trim = 0
    sum_grow = 0
    sum_scale = 0

   // grow certain number of stages, provide 32 squared magnitude
   val paramsFixed1 = RspNativeParams_v0 (
     fftParams = FFTParams.fixed(
       numPoints = i,
       dataWidth = dataWidthIn,
       binPoint = binPointIn,
       twiddleWidth = 16,
       useBitReverse  = true,
       trimType = Convergent,
       expandLogic = Array.fill(log2Up(i))(1).zipWithIndex.map { case (e,ind) => if (ind < (N_stages)) 1 else 0 }, // can be simplified for sure
       keepMSBorLSB = Array.fill(log2Up(i))(true),
       sdfRadix = "2^2"),
     magParams = MAGParams(
       protoIn  = FixedPoint((dataWidthIn+N_stages).W, 10.BP),
       protoOut =  FixedPoint(32.W, 20.BP),
       magType  = MagJPLandSqrMag,
       binPointGrowth = 10,
       useLast = true,
       numAddPipes = 1,
       numMulPipes = 1
     )
   )

    dsptools.Driver.execute(
    () => new RspChainNative_v0(paramsFixed1), Array("-tbn", "verilator")){ c =>
    new FixedPointRspChainNativeTester(c) {
      var count = 0
      while (count < numSamples) {
        val testSignal = genRandSignal(i,i)
        updatableDspVerbose.withValue(false) {
          val (chiselFFT, scalaFFT) = this.simpleNativeRspChainTest(3, testSignal, paramsFixed1)
          val sqnr1 = calc_sqnr(chiselFFT, scalaFFT)
          sum_N_stages_grow = sum_N_stages_grow + sqnr1
          count = count + 1
        }
      }
      sqnr_results_N_stages_grow(log2Up(i)-1) = (sum_N_stages_grow/numSamples)
      }
    }
}
  val f1 = Figure()
  val p1 = f1.subplot(0)
  p1.legend_= (true)
  val xaxis = fftSizeSeq.toArray.map(e => log2Up(e).toDouble)
  p1.setXAxisIntegerTickUnits() // this gives 1 2 3 4 5 . . .

  // TODO: Rename those labels
  p1 += plot(xaxis, sqnr_results_N_stages_grow, name = s"Bit growth for first $N_stages stages")
  p1 += plot(xaxis, sqnr_results_N_stages_grow_sqr_trim, name = "Bit growth for first $N_stages stages and trimmed magnitude")

  p1.title_= (s"Signal-to-Quantization-Noise Ratio (SQNR)" + "\n" + " radix 2^2 - DIF")

  p1.xlabel = "Number of FFT stages (base 2 logarithm of the FFT window size)"
  p1.ylabel = "SQNR [dB]"
  f1.saveas(s"test_run_dir/sqnr_grow_stages_analysis.pdf")

  val durationTest1 = (System.nanoTime - t1) / 1e9d
  println(s"The execution time of the grow logic analysis sqnr analysis is $durationTest1 s")

}
