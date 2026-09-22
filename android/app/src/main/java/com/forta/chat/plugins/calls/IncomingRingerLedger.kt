package com.forta.chat.plugins.calls

/**
 * Bookkeeping behind [IncomingRinger]: which call this process is ringing
 * for, and whether a deadline posted earlier is still the current one.
 *
 * Kept free of Android so the rules are unit-testable. The ringer is
 * process-wide because the ringtone, the vibration and the 30-second
 * auto-reject used to live in an `IncomingCallActivity` *instance*, and a
 * second instance of that activity (a shade tap while the ringer was not on
 * top of its task) moved the static pointer to itself — the first instance
 * kept ringing over the answered call and hung it up at second 30.
 */
class IncomingRingerLedger {

    @Volatile
    var armedCallId: String? = null
        private set

    private var generation = 0L

    /** The armed call the user silenced, or null — see [silence]. */
    private var silencedCallId: String? = null

    /**
     * Ring for [callId], replacing whatever rang before. Returns the token a
     * deadline posted for this ring must present to [mayFire].
     */
    @Synchronized
    fun arm(callId: String): Long {
        // Raising the ringer again for the call that already rings keeps the
        // silence the user asked for; any other call is a new ring.
        if (callId != armedCallId) silencedCallId = null
        armedCallId = callId
        return ++generation
    }

    /**
     * Stop ringing for [callId] — or for whatever rings when [callId] is null
     * or blank. Returns whether the ringer was actually up for it; a stop
     * for a different call is a no-op, so an orphaned ringer for call A can
     * never silence, or be silenced by, call B.
     */
    @Synchronized
    fun stop(callId: String?): Boolean {
        val armed = armedCallId ?: return false
        if (!callId.isNullOrEmpty() && callId != armed) return false
        armedCallId = null
        silencedCallId = null
        generation++
        return true
    }

    /**
     * Mark whatever rings as silenced without retiring its deadline: the user
     * asked the device to stop ringing, not to answer or decline. Returns the
     * silenced call, or null when nothing rings.
     */
    @Synchronized
    fun silence(): String? {
        val armed = armedCallId ?: return null
        silencedCallId = armed
        return armed
    }

    @Synchronized
    fun isSilenced(callId: String): Boolean = silencedCallId == callId && armedCallId == callId

    @Synchronized
    fun isArmedFor(callId: String): Boolean = armedCallId == callId

    /** A deadline fires only if nothing re-armed or stopped the ringer after it was posted. */
    @Synchronized
    fun mayFire(token: Long): Boolean = armedCallId != null && token == generation
}
