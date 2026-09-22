package com.forta.chat.plugins.calls

/**
 * Whether an `ACTION_STOP` delivered to [CallForegroundService] still refers
 * to the call the service is running.
 *
 * The service is started per call but its stop intent used to carry nothing
 * that said *which* call it ended. A hangup followed by an immediate redial
 * sends the old call's stop after the new call's start — JS drops
 * `hasLiveCall` the moment the SDK call ends, before its finalize reaches the
 * native steps — and that unkeyed stop tore down the new call: its
 * notification, its wake-lock and, once the stop path also reset the audio
 * router, its audio. [CallForegroundService.start] bumps a generation
 * synchronously and every stop carries the generation it was issued against;
 * a mismatch means a newer call owns the service now.
 *
 * Kept free of Android imports so the rule is unit-testable.
 */
object CallServiceStopPolicy {

    /** Stops from callers that predate the generation extra carry -1 and are always honoured. */
    fun isStale(stopGeneration: Long, currentGeneration: Long): Boolean =
        stopGeneration >= 0L && stopGeneration != currentGeneration
}
