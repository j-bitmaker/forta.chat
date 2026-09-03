package com.forta.chat.plugins.calls

/**
 * Ring buffer of audio-stack events for the current call.
 *
 * Bug reports today carry a *snapshot* — the audio mode at the moment the user
 * opened the form. That is enough to see that a device is stuck in
 * MODE_RINGTONE, but not how it got there, which is the actual question for
 * the two largest report clusters ("no audio" and "stuck after the call").
 * A device that ends a call in MODE_RINGTONE and one that never left it look
 * identical in a snapshot and need opposite fixes.
 *
 * Recording is deliberately cheap and lossy: a fixed-size ring, short string
 * codes, no allocation beyond the entry itself. Oldest entries are dropped so
 * a long call cannot grow the buffer without bound; the tail is what matters
 * because failures show up at teardown.
 *
 * Thread-safe: AudioRouter writes from the main handler (re-apply ticks,
 * watchdog), from device-callback threads and from the plugin's caller thread.
 */
class CallAudioTimeline(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Entry(
        /** Wall clock at record time. Rendered relative to the first entry. */
        val atMs: Long,
        /** Short stable code, e.g. "start", "mode", "route". */
        val event: String,
        /** Free-form context, e.g. "MODE_IN_COMMUNICATION" or "+1500ms". */
        val detail: String,
    )

    private val entries = ArrayDeque<Entry>(capacity)
    private val lock = Any()

    fun record(event: String, detail: String = "") {
        synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(Entry(clock(), event, detail))
        }
    }

    /** Entries oldest-first. Safe to iterate — never the live buffer. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /**
     * Drops everything recorded so far. Called when a call starts so the
     * timeline covers one call and a report is not polluted by the previous
     * one — the exception being an orphaned router, whose stale entries are
     * exactly what we want to see, so nothing clears on stop().
     */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        /**
         * A call produces roughly 20-40 entries: start, the mode re-apply
         * schedule, route changes, vendor tweaks, teardown. 80 keeps a full
         * normal call plus the tail of a pathological one.
         */
        const val DEFAULT_CAPACITY = 80
    }
}
