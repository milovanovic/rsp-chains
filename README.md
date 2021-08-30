
Radar Signal Processing Chains
=======================================================

## Overview

This branch contains different variants of radar signal processing chains and it depends on following repositories:
 * dsp-utils (branch: master)
* crc (branch: master)
* xWrDataPreProc  (branch: master)
* windowing (branch: master)
* sdf-fft ( branch: development)
* logMagMux (branch: add-new-parameters)
* cfar( branch: master)
* jtag2mm (branch: master)

Project is work in process and it should be run with chipyard dependencies. Following chains can be found inside `src/main/scala`:

RspChainTestFFT_v0.scala

* xWRPreProc + Spliter(goes to AXI4Buffer and to FFT) + AXI4Buffer +  FFT + AXI4StreamCustomCombiner

RspChainTestFFT_v1.scala

* DspQueue + xWRPreProc + Spliter(goes to AXI4Buffer and to FFT) + AXI4Buffer +  FFT + AXI4StreamCustomCombiner

RspChainTestFFT_v2.scala

* AsyncQueueAXI4StreamOut + xWRPreProc + Spliter(goes to AXI4Buffer and to FFT) + AXI4Buffer +  FFT + AXI4StreamCustomCombiner + jtag

RspChainTestFFT_v3.scala

* AsyncQueueAXI4StreamOut + DspQueue + xWRPreProc + Spliter(goes to AXI4Buffer and to FFT) + AXI4Buffer +  FFT + AXI4StreamCustomCombiner + jtag

RspChainNative_v0.scala

* Native sdf-fft core + native magnitude core - used to test sqnr of this chain

RspChainNoCRC_v0.scala

* AsyncQueueAXI4StreamOut + DspQueue + xWRPreProc + jtag

RspChainNoCRC_v1.scala

- AsyncQueueAXI4StreamOut + DspQueue + xWRPreProc + Windowing + FFT + jtag

RspChainNoCRC_v2.scala

* AsyncQueueAXI4StreamOut + DspQueue + xWRPreProc + Windowing + FFT + MagSqrMag +  jtag

RspChainNoCRC_v3.scala
* AsyncQueueAXI4StreamOut + DspQueue + xWRPreProc + Windowing + FFT + MagSqrMag + CFAR + jtag

RspChainReduced_v2.scala
* DspQueue + xWRPreProc + Windowing + FFT + MagSqrMag

RspChainReduced_v3.scala
* DspQueue + xWRPreProc + Windowing + FFT + MagSqrMag + CFAR

**Note1**: Main difference between reduced version and not reduced is that reduced does not contain `jtag` and `AsyncQueue` so that chain can be easily tested with Chisel testers inside Scala environment

RspChainWithCRC_v0.scala

* AsyncQueueWithCrcLine + RadarDataCrcChecker + jtag

RSPChainNoCRCNoPreProc_v0.scala

* AsyncQueueAXI4StreamOut + DspQueue

Should be added in the future:

RspChainWithCRC_v1.scala

* AsyncQueueWithCrcLine + RadarDataCrcChecker + xWrDataPreProc + jtag

RspChainWithCRC_v2.scala

* AsyncQueueWithCrcLine + RadarDataCrcChecker + xWrDataPreProc + FFT + MagSqrMag + jtag

RspChainWithCRC_v3.scala

* AsyncQueueWithCrcLine + RadarDataCrcChecker + xWrDataPreProc + FFT + MagSqrMag + CFAR + jtag

RspChainWithCRC_v4

- (AsyncQueueWithCrcLine + RadarDataCrcChecker + xWrDataPreProc + FFT + MagSqrMag) x 4 + NonCoherentAdder + CFAR + jtag

Testers available inside `src/test/scala` are:

RspChainTestFFT_v0_Tester.scala

* Tests module RspChainTestFFT_v0 with real radar streaming data.

RspChainTestFFT_v1_Tester.scala

* Tests module RspChainTestFFT_v1 with real radar streaming data.

RspChainNativeSpec.scala

* Mostly used to get SQNR results of the chain fft + sqr-mag. Needs to be updated and tested more.

RspChainReducedTester_v3.scala

* Tests module RspChainReduced_v3 with real radar streaming data. Results are saved to txt files.

RspChainReducedTester_v2.scala

* Tests module RspChainReduced_v2 with real radar streaming data. Results are saved to txt files.
