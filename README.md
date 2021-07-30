
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

**Note**: Main difference is that reduced version does not contain `jtag` and `AsyncQueue` so that chain can be easily tested inside Chisel and Scala environment

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
