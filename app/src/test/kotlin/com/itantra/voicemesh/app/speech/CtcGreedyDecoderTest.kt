package com.itantra.voicemesh.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class CtcGreedyDecoderTest {
    // vocab: 0=blank, 1="a", 2="b", 3="▁" already mapped to " " by the caller in real
    // usage — here vocab strings are pre-substituted, matching what IndicConformerSpeechRecognizer
    // hands the decoder.
    private val vocab = mapOf(0 to "<blk>", 1 to "a", 2 to "b", 3 to " c")

    private fun oneHotFrame(vocabSize: Int, hot: Int): FloatArray =
        FloatArray(vocabSize) { if (it == hot) 1.0f else 0.0f }

    @Test
    fun `collapses consecutive repeats of the same raw token`() {
        val frames = arrayOf(
            oneHotFrame(4, 1), // a
            oneHotFrame(4, 1), // a (repeat, collapsed)
            oneHotFrame(4, 0), // blank
            oneHotFrame(4, 1), // a (new run after blank, emitted again)
        )
        val text = CtcGreedyDecoder.decode(frames, frames.size, blankIndex = 0, vocab = vocab)
        assertEquals("aa", text)
    }

    @Test
    fun `blank separates two emissions of the same symbol`() {
        val frames = arrayOf(
            oneHotFrame(4, 2), // b
            oneHotFrame(4, 0), // blank
            oneHotFrame(4, 2), // b again, not a repeat because blank intervened
        )
        val text = CtcGreedyDecoder.decode(frames, frames.size, blankIndex = 0, vocab = vocab)
        assertEquals("bb", text)
    }

    @Test
    fun `respects validLength and ignores frames beyond it`() {
        val frames = arrayOf(
            oneHotFrame(4, 1),
            oneHotFrame(4, 2),
            oneHotFrame(4, 1), // beyond validLength, must be ignored
        )
        val text = CtcGreedyDecoder.decode(frames, validLength = 2, blankIndex = 0, vocab = vocab)
        assertEquals("ab", text)
    }

    @Test
    fun `word-boundary marker already mapped to a space by the caller collapses whitespace`() {
        val frames = arrayOf(
            oneHotFrame(4, 1), // a
            oneHotFrame(4, 3), // " c"
        )
        val text = CtcGreedyDecoder.decode(frames, frames.size, blankIndex = 0, vocab = vocab)
        assertEquals("a c", text)
    }

    @Test
    fun `all-blank input decodes to empty string`() {
        val frames = arrayOf(oneHotFrame(4, 0), oneHotFrame(4, 0))
        val text = CtcGreedyDecoder.decode(frames, frames.size, blankIndex = 0, vocab = vocab)
        assertEquals("", text)
    }
}
