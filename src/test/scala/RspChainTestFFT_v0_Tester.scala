package rspChain

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
//import breeze.plot._
//import java.io._

import dsputils._
import fft._
import xWRDataPreProc._

class RSPChainTestFFT_v0_Tester(
  dut: RSPChainTestFFT_v0 with RSPChainTestFFT_v0_Standalone,
  params: RSPChainTestFFT_v0_Parameters,
  inFileName: String,
  beatBytes: Int = 4,
  silentFail: Boolean = false,
) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel {

  override def memAXI: AXI4Bundle = dut.ioMem.get
  val mod     = dut.module

  // Connect AXI4StreamModel to DUT
  val master = bindMaster(dut.in)
  val fileName = inFileName

  // xAWR module has a default values of registers - there is no need to change things
  // configure fft size as a number of stages
  memWriteWord(params.fftAddress.base, log2Ceil(params.fftParams.numPoints))

  // mode is dsp on
  // no zerro padding
  // numPoints is equal to fftSize

  // write to splitter - default behavior is AND
  // if fft is not ready, data will not be sent to buffer, buffer has enough space to support that in.ready is always ready to accept new data

  step(40)
  poke(dut.out.ready, true.B) // make output always ready to accept data
  val radarDataComplex = Source.fromFile(fileName).getLines.toArray.map { br => br.toInt }

  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))
  master.addTransactions((0 until radarDataComplex.size).map(i => AXI4StreamTransaction(data = radarDataComplex(i))))

  var outSeq = Seq[BigInt]()
  var peekedVal: BigInt = 0

  while (outSeq.length < 2*params.fftParams.numPoints) {
    if (peek(dut.out.valid) == 1 && peek(dut.out.ready) == 1) {
      peekedVal = peek(dut.out.bits.data)
      outSeq = outSeq :+ peekedVal//.toInt
    }
    step(1)
  }
  // it works good!

  step(5000)
}


class RSPChainTestFFT_v0_Spec extends AnyFlatSpec with Matchers {

  val params = RSPChainTestFFT_v0_Parameters (
    preProcParams  = AXI4XwrDataPreProcParams(),
    fftParams = FFTParams.fixed(
      dataWidth = 12,
      twiddleWidth = 16,
      numPoints = 1024,
      useBitReverse  = true,
      runTime = true,
      numAddPipes = 1,
      numMulPipes = 1,
      expandLogic = Array.fill(log2Up(1024))(1).zipWithIndex.map { case (e,ind) => if (ind < 4) 1 else 0 },
      keepMSBorLSB = Array.fill(log2Up(1024))(true),
      minSRAMdepth = 64,
      binPoint = 0
    ),
    preProcAddress  = AddressSet(0x30000100, 0xFF),
    splitterAddress = AddressSet(0x30000200, 0xFF),
    fftAddress      = AddressSet(0x30000300, 0xFF),
    beatBytes      = 4)

    val inFileName : String = "iladata_signed3.txt"
    val testModule = LazyModule(new RSPChainTestFFT_v0(params) with RSPChainTestFFT_v0_Standalone)

    it should "Test simple rsp chain with combiner and splitter" in {
      chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => testModule.module) {
        c => new RSPChainTestFFT_v0_Tester(dut = testModule,
                                            beatBytes = 4,
                                            params = params,
                                            inFileName = inFileName,
                                            silentFail  = false)
      } should be (true)
    }
}
