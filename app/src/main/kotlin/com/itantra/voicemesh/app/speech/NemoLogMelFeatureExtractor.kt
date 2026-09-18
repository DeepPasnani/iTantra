package com.itantra.voicemesh.app.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Log-mel feature extraction for the ai4bharat/indicconformer_stt_*_hybrid_ctc_rnnt_large
 * checkpoints as exported to ONNX by github.com/istupakov/onnx-asr ("nemo-conformer-ctc"
 * in each model's config.json): 16kHz audio, 512-point FFT over a 400-sample (25ms) Hann
 * window centered in it, 160-sample (10ms) hop, 0.97 pre-emphasis, 80 mel bins on the
 * Slaney mel scale with Slaney-area-normalized filters, natural log with a 2^-24 zero
 * guard, then per-utterance per-mel-bin (not global-dataset) mean/variance normalization.
 *
 * Ported by hand from onnx-asr's `NemoPreprocessorNumpy` and `preprocessors/fbanks.py` —
 * there is no existing Kotlin/Java implementation of this preprocessing to reuse. This has
 * NOT been verified against the Python reference on real audio (no way to run NumPy/ONNX
 * ground truth from this environment to diff against); treat it as "compiles and produces
 * plausibly-shaped output", not "known correct", until someone runs the same WAV file
 * through both this and `onnx_asr` on a workstation and diffs the feature tensors.
 */
class NemoLogMelFeatureExtractor(private val numMels: Int = 80) {
    private val sampleRate = 16_000
    private val nFft = 512
    private val winLength = 400
    private val hopLength = 160
    private val preemphasis = 0.97
    private val logZeroGuard = 2.0.pow(-24)

    private val window: DoubleArray = paddedHannWindow()
    private val melFilterbank: Array<DoubleArray> = melScaleFbanks() // [nFreqs][numMels]

    /**
     * [waveform] is mono PCM scaled to [-1, 1]. Returns mel-major features
     * ([numMels][numFrames], ready to flatten row-major into an audio_signal tensor of
     * shape [1, numMels, numFrames]) plus the frame count to pass as the model's `length`.
     */
    fun extract(waveform: FloatArray): Pair<Array<FloatArray>, Int> {
        val n = waveform.size
        val numFrames = n / hopLength
        if (numFrames <= 0) return Array(numMels) { FloatArray(0) } to 0

        val preemphasized = DoubleArray(n)
        preemphasized[0] = waveform[0].toDouble()
        for (i in 1 until n) preemphasized[i] = waveform[i] - preemphasis * waveform[i - 1]

        val pad = nFft / 2
        val padded = DoubleArray(n + 2 * pad)
        System.arraycopy(preemphasized, 0, padded, pad, n)

        val nFreqs = nFft / 2 + 1
        val logMel = Array(numFrames) { DoubleArray(numMels) }
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)

        for (t in 0 until numFrames) {
            val start = t * hopLength
            for (i in 0 until nFft) {
                val sampleIndex = start + i
                re[i] = if (sampleIndex < padded.size) padded[sampleIndex] * window[i] else 0.0
                im[i] = 0.0
            }
            fft(re, im)

            val melEnergies = DoubleArray(numMels)
            for (f in 0 until nFreqs) {
                val power = re[f] * re[f] + im[f] * im[f]
                if (power == 0.0) continue
                val row = melFilterbank[f]
                for (m in 0 until numMels) melEnergies[m] += power * row[m]
            }
            for (m in 0 until numMels) logMel[t][m] = ln(melEnergies[m] + logZeroGuard)
        }

        val frameCount = numFrames.toDouble()
        val mean = DoubleArray(numMels)
        for (t in 0 until numFrames) for (m in 0 until numMels) mean[m] += logMel[t][m]
        for (m in 0 until numMels) mean[m] = mean[m] / frameCount

        val variance = DoubleArray(numMels)
        if (numFrames > 1) {
            for (t in 0 until numFrames) for (m in 0 until numMels) {
                val d = logMel[t][m] - mean[m]
                variance[m] += d * d
            }
            val denominator = frameCount - 1.0
            for (m in 0 until numMels) variance[m] = variance[m] / denominator
        }

        val features = Array(numMels) { FloatArray(numFrames) }
        for (m in 0 until numMels) {
            val denom = sqrt(variance[m]) + 1e-5
            for (t in 0 until numFrames) features[m][t] = ((logMel[t][m] - mean[m]) / denom).toFloat()
        }
        return features to numFrames
    }

    /** A `winLength`-sample Hann window, zero-padded to `nFft` and centered, matching onnx-asr's framing. */
    private fun paddedHannWindow(): DoubleArray {
        val window = DoubleArray(nFft)
        val padLeft = (nFft - winLength) / 2
        for (i in 0 until winLength) {
            window[padLeft + i] = 0.5 - 0.5 * cos(2.0 * PI * i / (winLength - 1))
        }
        return window
    }

    /** Port of torchaudio-style `melscale_fbanks(nFreqs, 0, sampleRate/2, numMels, sampleRate, "slaney", "slaney")`. */
    private fun melScaleFbanks(): Array<DoubleArray> {
        val nFreqs = nFft / 2 + 1
        val fMax = sampleRate / 2.0

        fun hzToMel(freq: Double) = if (freq < 1000.0) 3.0 * freq / 200.0 else 15.0 + 27.0 * ln(freq / 1000.0) / ln(6.4)
        fun melToHz(mel: Double) = if (mel < 15.0) 200.0 * mel / 3.0 else 1000.0 * 6.4.pow((mel - 15.0) / 27.0)

        val allFreqs = DoubleArray(nFreqs) { i -> i * fMax / (nFreqs - 1) }
        val mMin = hzToMel(0.0)
        val mMax = hzToMel(fMax)
        val hzPts = DoubleArray(numMels + 2) { i -> melToHz(mMin + (mMax - mMin) * i / (numMels + 1)) }

        val fb = Array(nFreqs) { DoubleArray(numMels) }
        for (f in 0 until nFreqs) {
            val freq = allFreqs[f]
            for (m in 0 until numMels) {
                val up = (freq - hzPts[m]) / (hzPts[m + 1] - hzPts[m])
                val down = (hzPts[m + 2] - freq) / (hzPts[m + 2] - hzPts[m + 1])
                fb[f][m] = max(0.0, min(up, down))
            }
        }
        for (m in 0 until numMels) {
            val areaNorm = 2.0 / (hzPts[m + 2] - hzPts[m])
            for (f in 0 until nFreqs) fb[f][m] *= areaNorm
        }
        return fb
    }

    companion object {
        /** In-place iterative radix-2 Cooley-Tukey FFT. `re`/`im` length must be a power of two. */
        internal fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                    tmp = im[i]; im[i] = im[j]; im[j] = tmp
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val ang = -2.0 * PI / len
                val wRe = cos(ang)
                val wIm = sin(ang)
                var i = 0
                while (i < n) {
                    var curRe = 1.0
                    var curIm = 0.0
                    for (k in 0 until half) {
                        val uRe = re[i + k]
                        val uIm = im[i + k]
                        val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                        val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                        re[i + k] = uRe + vRe
                        im[i + k] = uIm + vIm
                        re[i + k + half] = uRe - vRe
                        im[i + k + half] = uIm - vIm
                        val nextRe = curRe * wRe - curIm * wIm
                        val nextIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                        curIm = nextIm
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
