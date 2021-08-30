package rspChain

import chisel3._
import chisel3.experimental._
import chisel3.util._

import dsptools._
import dsptools.numbers._

import fft._
import magnitude._

import scala.math.pow
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

case class RspNativeParams_v0 (
  fftParams       : FFTParams[FixedPoint],
  magParams       : MAGParams[FixedPoint]
)

/**
 * Interface of the RSP simple
 */
class RSPNativeIO (params: RspNativeParams_v0) extends Bundle {
  val in = Flipped(Decoupled(params.fftParams.protoIQ))
  val out = Decoupled(params.magParams.protoOut)
  val lastOut = Output(Bool())
  val lastIn = Input(Bool())
  // control registers
  val fftSize = if (params.fftParams.runTime) Some(Input(UInt((log2Up(params.fftParams.numPoints)).W))) else None
  val keepMSBorLSBReg = if (params.fftParams.keepMSBorLSBReg) Some(Input(Vec(log2Up(params.fftParams.numPoints),Bool()))) else None
  val fftDirReg = if (params.fftParams.fftDirReg) Some(Input(Bool())) else None
  val sel = Input(UInt(2.W))
  // status registers
  val busy = Output(Bool())
  val overflow = if (params.fftParams.overflowReg) Some(Output(Vec(log2Up(params.fftParams.numPoints),Bool()))) else None

  override def cloneType: this.type = RSPNativeIO(params).asInstanceOf[this.type]
}

object RSPNativeIO {
  def apply(params: RspNativeParams_v0): RSPNativeIO = new RSPNativeIO(params)
}

class RspChainNative_v0(val params: RspNativeParams_v0) extends Module {
  val io = IO(RSPNativeIO(params))

  val fft = Module(new SDFFFT(params.fftParams))
  val mag = Module(new LogMagMuxGenerator(params.magParams))


  // connect fft decoupled to mag decoupled
  fft.io.in <> io.in
  mag.io.in <> fft.io.out
  mag.io.sel := io.sel

  fft.io.lastIn := io.lastIn
  mag.io.lastIn.get := fft.io.lastOut
  io.out <> mag.io.out
  io.lastOut := mag.io.lastOut.get

  io.busy := fft.io.busy
}

object RSPChainNative_v0_App extends App
{
  val params = RspNativeParams_v0 (
    fftParams = FFTParams.fixed(
      dataWidth = 12,
      twiddleWidth = 16,
      numPoints = 1024,
      useBitReverse  = true,
      runTime = false,
      numAddPipes = 1,
      numMulPipes = 1,
      expandLogic = Array.fill(log2Up(1024))(1).zipWithIndex.map { case (e,ind) => if (ind < 4) 1 else 0 }, // expand first four stages, other do not grow
      keepMSBorLSB = Array.fill(log2Up(1024))(true),
      minSRAMdepth = 64, // memories larger than 64 should be mapped on block ram
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
    )
   )
   chisel3.Driver.execute(args,()=>new RspChainNative_v0(params))
}




