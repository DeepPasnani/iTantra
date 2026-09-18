package com.itantra.voicemesh.app.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NemoLogMelFeatureExtractorTest {

    @Test
    fun `fft matches brute-force DFT on random input`() {
        val n = 64
        val rng = java.util.Random(42)
        val re = DoubleArray(n) { rng.nextDouble() * 2 - 1 }
        val im = DoubleArray(n)

        val expectedRe = DoubleArray(n)
        val expectedIm = DoubleArray(n)
        for (k in 0 until n) {
            var sumRe = 0.0
            var sumIm = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                sumRe += re[t] * kotlin.math.cos(angle)
                sumIm += re[t] * kotlin.math.sin(angle)
            }
            expectedRe[k] = sumRe
            expectedIm[k] = sumIm
        }

        NemoLogMelFeatureExtractor.fft(re, im)

        for (k in 0 until n) {
            assertTrue("re[$k] expected ${expectedRe[k]} got ${re[k]}", abs(re[k] - expectedRe[k]) < 1e-6)
            assertTrue("im[$k] expected ${expectedIm[k]} got ${im[k]}", abs(im[k] - expectedIm[k]) < 1e-6)
        }
    }

    @Test
    fun `extract produces one frame per hop_length samples with no NaNs`() {
        val sampleRate = 16_000
        val durationSeconds = 1.0
        val n = (sampleRate * durationSeconds).toInt()
        // A clean 440Hz tone - not real speech, just something with energy across frames
        // to sanity-check shapes and numeric stability, not recognition accuracy.
        val waveform = FloatArray(n) { i -> sin(2.0 * PI * 440.0 * i / sampleRate).toFloat() * 0.5f }

        val extractor = NemoLogMelFeatureExtractor(numMels = 80)
        val (features, numFrames) = extractor.extract(waveform)

        assertEquals(n / 160, numFrames)
        assertEquals(80, features.size)
        for (melRow in features) {
            assertEquals(numFrames, melRow.size)
            for (value in melRow) {
                assertFalse("feature value was NaN", value.isNaN())
                assertFalse("feature value was infinite", value.isInfinite())
            }
        }
    }

    @Test
    fun `per-utterance normalization centers each mel bin near zero mean`() {
        val sampleRate = 16_000
        val n = sampleRate // 1 second
        val waveform = FloatArray(n) { i -> sin(2.0 * PI * 220.0 * i / sampleRate).toFloat() * 0.3f }

        val extractor = NemoLogMelFeatureExtractor(numMels = 80)
        val (features, numFrames) = extractor.extract(waveform)

        for (melRow in features) {
            val mean = melRow.sum() / numFrames
            assertTrue("mel bin mean should be ~0 after normalization, was $mean", abs(mean) < 1e-3)
        }
    }

    @Test
    fun `too-short waveform yields zero frames instead of throwing`() {
        val extractor = NemoLogMelFeatureExtractor()
        val (features, numFrames) = extractor.extract(FloatArray(10))
        assertEquals(0, numFrames)
        assertEquals(80, features.size)
    }
}
