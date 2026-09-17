package com.itantra.voicemesh.routing

/**
 * Fixed-size, time-bounded "have I seen this before" cache. Used to suppress
 * re-flooding a route request we've already processed (spec §7 "duplicate
 * suppression", applied here at the control-packet level; message-level duplicate
 * suppression for TTS playback lives in :core-reliability).
 */
class SeenCache<T>(
    private val maxEntries: Int = 512,
    private val entryLifetimeMillis: Long = 10_000,
) {
    private val seenAt = LinkedHashMap<T, Long>()

    @Synchronized
    fun observeAndCheckIfNew(key: T, nowMillis: Long): Boolean {
        purgeExpired(nowMillis)
        val alreadySeen = seenAt.containsKey(key)
        seenAt[key] = nowMillis
        if (seenAt.size > maxEntries) {
            val oldest = seenAt.keys.firstOrNull()
            if (oldest != null) seenAt.remove(oldest)
        }
        return !alreadySeen
    }

    private fun purgeExpired(nowMillis: Long) {
        seenAt.entries.removeAll { nowMillis - it.value > entryLifetimeMillis }
    }
}
