package jp.pioneer.itp.android.stellanovaplayer.common

/** JNI surface exported by the optional Pioneer MSR DSP library. */
internal class MSRUtility {
    external fun execute0(output: DoubleArray, outputFrames: Int): Boolean
    external fun isWriteBuffer(sampleRateHz: Int): Boolean
    external fun reset0()
    external fun setBuffer(input: FloatArray, inputFrames: Int): Int
    external fun setMode0(mode: Int)
    external fun setSamplingRate0(sampleRateHz: Int): Boolean
}
