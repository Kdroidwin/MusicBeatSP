package com.music.bitchord.data

/** Process-wide monotonic time source for URL and failure-cache expiry. */
object MonotonicClock {
    @Volatile
    var nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
}
