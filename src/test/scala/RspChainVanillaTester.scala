// SPDX-License-Identifier: Apache-2.0

package rspChain

import dsptools._
import dsptools.numbers._

import chisel3._
import chisel3.util._
import chisel3.iotesters.Driver
import chisel3.experimental._

import chisel3.iotesters.PeekPokeTester

//import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

import org.scalatest.{FlatSpec, Matchers}

import breeze.math.Complex
import breeze.signal.{fourierTr, iFourierTr}
import breeze.linalg._
import breeze.plot._

import plfg._
import nco._
import fft._
import magnitude._
import cfar._

import java.io._

case class RunTimeRspChainParams(
  CFARAlgorithm         : Option[String] = Some("CA"),    // CFAR algorithm -> only valid when GOSCA algorithm is used
  CFARMode              : String = "Smallest Of",   // can be "Smallest Of", "Greatest Of", "Cell Averaging", "CASH"
  refWindowSize         : Int = 32,                 // number of cells inside 
  guardWindowSize       : Int = 4,                  // maximum number of guard cells
  subWindowSize         : Option[Int] = None,       // relevant only for CASH algoithm
  fftSize               : Int = 1024,               // fft size
  thresholdScaler       : Double = 1.5,             // thresholdScaler
  divSum                : Option[Int] = Some(5),    // divider used for CA algorithms
  peakGrouping          : Int = 0,                  // peak grouping is disabled by default
  indexLagg             : Option[Int] = None,       // index of cell inside lagging window
  indexLead             : Option[Int] = None,       // index of cell inside leading window
  magMode               : Int = 2,                  // calculate jpl mag by default
  logOrLinearMode       : Int = 1                   // by default linear mode is active
) {
  require(isPow2(refWindowSize) & isPow2(fftSize))
  require(refWindowSize > 0 & guardWindowSize > 0)
  require(refWindowSize > guardWindowSize)
  if (subWindowSize != None) {
    require(subWindowSize.get < refWindowSize)
  }
  if (indexLead != None) {
    require(indexLead.get < refWindowSize)
  }
  if (indexLagg != None) {
    require(indexLagg.get < refWindowSize)
  }
}

class RspChainVanillaTester
(
  dut: RspChainVanilla with RspChainVanillaPins,
  params: RspChainVanillaParameters,
  runTimeParams: RunTimeRspChainParams,
  silentFail: Boolean = false
) extends PeekPokeTester(dut.module) with AXI4MasterModel {

  def memAXI: AXI4Bundle = dut.ioMem.get
  val numFrames = 4
  
  // print fftSize
  println(runTimeParams.fftSize.toString)
  
  
  // plfg setup
  val segmentNumsArrayOffset = 6 * params.beatBytes
  val repeatedChirpNumsArrayOffset = segmentNumsArrayOffset + 4 * params.beatBytes
  val chirpOrdinalNumsArrayOffset = repeatedChirpNumsArrayOffset + 8 * params.beatBytes
  
  // configure plfg registers
  // peak is expected on frequency bin equal to startingPoint * (numOfPoints / (4*tableSize))
  memWriteWord(params.plfgRAM.base, 0x24000000)
  memWriteWord(params.plfgAddress.base + 2*params.beatBytes, numFrames*2)  // number of frames
  memWriteWord(params.plfgAddress.base + 4*params.beatBytes, 1)            // number of chirps
  memWriteWord(params.plfgAddress.base + 5*params.beatBytes, 16)           // start value
  memWriteWord(params.plfgAddress.base + segmentNumsArrayOffset, 1)        // number of segments for first chirp
  memWriteWord(params.plfgAddress.base + repeatedChirpNumsArrayOffset, 1)  // determines number of repeated chirps
  memWriteWord(params.plfgAddress.base + chirpOrdinalNumsArrayOffset, 0)
  memWriteWord(params.plfgAddress.base + params.beatBytes, 0)              // set reset bit to zero
  memWriteWord(params.plfgAddress.base, 1)                                 // enable bit becomes 1
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
  poke(dut.outStream.ready, true.B)
  
  var outSeq = Seq[Int]()
  var peekedVal: BigInt = 0
  var threshold = new Array[Double](runTimeParams.fftSize)
  var fftBin = new Array[Int](runTimeParams.fftSize)
  var peaks = new Array[Int](runTimeParams.fftSize)
  
  while (outSeq.length < runTimeParams.fftSize) {
    if (peek(dut.outStream.valid) == 1 && peek(dut.outStream.ready) == 1) {
      peekedVal = peek(dut.outStream.bits.data)
      outSeq = outSeq :+ peekedVal.toInt
    }
    step(1)
  }
  var idx = 0
  val fftBinWidth = log2Ceil(runTimeParams.fftSize)
  
  // split outSeq to fftBins, threshold and peaks
  while (idx < runTimeParams.fftSize) {
    threshold(idx) = outSeq(idx) >> (fftBinWidth + 1)
    peaks(idx) = outSeq(idx) & 0x00000001
    idx = idx + 1
  }

  // there is no need to plot those data
  
}

class RspChainVanillaSpec extends FlatSpec with Matchers {
  implicit val p: Parameters = Parameters.empty

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
      protoThreshold = FixedPoint(16.W, 3.BP),
      protoScaler = FixedPoint(16.W, 6.BP),
      sendCut = false,
      leadLaggWindowSize = 32,
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
  
  // use all default parameters
  val runTimeParams = RunTimeRspChainParams()

  behavior of "RspChain Vanilla"
  it should "work" in {
    val lazyDut = LazyModule(new RspChainVanilla(params) with RspChainVanillaPins)
    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv", "--target-dir", "test_run_dir/rspChainVanilla/", "--top-name", "RspChainVanilla"), () => lazyDut.module) {
      c => new RspChainVanillaTester(lazyDut, params, runTimeParams, true)
    } should be (true)
  }
}
