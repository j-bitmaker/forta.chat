package com.forta.chat.plugins.calls

/**
 * Which start generation each call was issued — the other half of
 * [CallServiceStopPolicy].
 *
 * [CallForegroundService.stop] used to read the *current* generation, so the
 * guard only caught a stop that was sent before the next start and delivered
 * after it. The redial race it was written for runs the other way round: JS
 * finalizes the ended call step by step (markers, audio routing, Telecom, then
 * the service) while the user is already dialling again, so the old call's stop
 * is *issued* after the new call's start, matched the current generation and
 * took the new call down. A stop now names its call and is issued against the
 * generation that call's start recorded. A stop for a call nobody started — no
 * id, or a push-keyed `$event_id` that never equals a Matrix call id — is issued
 * against the current generation, as before.
 *
 * A start is never forgotten on stop: the same call is stopped twice as a rule
 * (Telecom's onDisconnect through [CallTeardown], then the JS finalize), and the
 * second stop must stay as stale as the first. Old starts are dropped once
 * [KEEP_GENERATIONS] newer ones have been issued.
 *
 * Thread-safe; free of Android imports so the rule is unit-testable.
 */
class CallStartLedger {
    private val generationByCallId = HashMap<String, Long>()

    @Synchronized
    fun record(callId: String?, generation: Long) {
        generationByCallId.values.removeAll { it + KEEP_GENERATIONS < generation }
        if (callId.isNullOrEmpty()) return
        generationByCallId[callId] = generation
    }

    /**
     * Record [alias] as another name of [callId]'s start: the Telecom slot of a
     * push-delivered call is keyed by the push's `$event_id`, while JS launched
     * the service under the Matrix call id, and the slot's own onDisconnect
     * stops the service under its id. Without the alias that stop would fall
     * back to the current generation.
     */
    @Synchronized
    fun alias(alias: String?, callId: String?) {
        if (alias.isNullOrEmpty() || callId.isNullOrEmpty() || alias == callId) return
        generationByCallId[callId]?.let { generationByCallId[alias] = it }
    }

    /** The generation a stop for [callId] is issued against. */
    @Synchronized
    fun generationFor(callId: String?, current: Long): Long =
        callId?.takeIf { it.isNotEmpty() }?.let { generationByCallId[it] } ?: current

    companion object {
        /** A start this many generations behind the newest one is dropped on the next record. */
        const val KEEP_GENERATIONS = 16L
    }
}
