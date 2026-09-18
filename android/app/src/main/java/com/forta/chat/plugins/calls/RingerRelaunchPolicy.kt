package com.forta.chat.plugins.calls

import android.content.Intent

/**
 * Whether the ringer screen is being started again from Recents rather than
 * for a call.
 *
 * A push that rings while the app is dead makes [IncomingCallActivity] the
 * root of the app's task, and the task keeps that intent — call id and all —
 * after the screen finishes. Tapping the app's card in Recents then starts the
 * ringer once more for a call that ended long ago, and it rings out its 30 s
 * (`ownerb3`, 2026-09-18: the call was answered in Bastyon at 18:23:10, the
 * card was tapped at 18:25:01). Android marks such a start with
 * `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`; no start made for a call carries it.
 */
object RingerRelaunchPolicy {
    fun isFromRecents(intentFlags: Int): Boolean =
        intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
}
