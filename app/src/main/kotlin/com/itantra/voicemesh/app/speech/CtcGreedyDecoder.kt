package com.itantra.voicemesh.app.speech

/**
 * Greedy CTC decoding matching onnx-asr's `_AsrWithCtcDecoding._decoding`: per-frame
 * argmax over the vocabulary, dropping the blank token, and collapsing a token that
 * repeats the immediately preceding frame's *raw* argmax (blank included in that
 * comparison) — the standard CTC collapse rule, not "collapse repeats of the last
 * emitted symbol", which would over-merge a intentionally repeated word.
 */
object CtcGreedyDecoder {
    fun decode(logProbsByFrame: Array<FloatArray>, validLength: Int, blankIndex: Int, vocab: Map<Int, String>): String {
        val sb = StringBuilder()
        var previousToken = blankIndex
        val limit = minOf(validLength, logProbsByFrame.size)
        for (t in 0 until limit) {
            val frame = logProbsByFrame[t]
            var bestIndex = 0
            var bestValue = frame[0]
            for (i in 1 until frame.size) {
                if (frame[i] > bestValue) {
                    bestValue = frame[i]
                    bestIndex = i
                }
            }
            val isRepeatOfPreviousFrame = bestIndex == previousToken
            previousToken = bestIndex
            if (bestIndex != blankIndex && !isRepeatOfPreviousFrame) {
                vocab[bestIndex]?.let(sb::append)
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }
}
