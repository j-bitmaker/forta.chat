package com.forta.chat.plugins.calls

import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import com.forta.chat.plugins.calls.AudioRouter.Device

/**
 * Translates the router's devices into Telecom's route vocabularies and back.
 *
 * Telecom owns the audio route of a self-managed call, so a pick has to reach it
 * as a `CallAudioState.ROUTE_*` constant (API 23-33, and a Bluetooth headset on
 * any API — see [viaCallEndpoint]) or as a `CallEndpoint` of the matching type (API 34+),
 * and Telecom's reports have to come back as a [Device]. Pure so the table is
 * testable without Telecom; the constants are compile-time values, so nothing
 * here loads an API 34 class on older Android.
 */
object TelecomAudioRoute {

    fun routeFor(device: Device): Int = when (device) {
        Device.EARPIECE -> CallAudioState.ROUTE_EARPIECE
        Device.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
        Device.WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
        Device.SPEAKER -> CallAudioState.ROUTE_SPEAKER
    }

    /** Null for a route no in-call control can show, such as streaming. */
    fun deviceForRoute(route: Int): Device? = when (route) {
        CallAudioState.ROUTE_EARPIECE -> Device.EARPIECE
        CallAudioState.ROUTE_BLUETOOTH -> Device.BLUETOOTH
        CallAudioState.ROUTE_WIRED_HEADSET -> Device.WIRED_HEADSET
        CallAudioState.ROUTE_SPEAKER -> Device.SPEAKER
        else -> null
    }

    fun endpointTypeFor(device: Device): Int = when (device) {
        Device.EARPIECE -> CallEndpoint.TYPE_EARPIECE
        Device.BLUETOOTH -> CallEndpoint.TYPE_BLUETOOTH
        Device.WIRED_HEADSET -> CallEndpoint.TYPE_WIRED_HEADSET
        Device.SPEAKER -> CallEndpoint.TYPE_SPEAKER
    }

    /**
     * Whether an API 34+ request for [device] names a `CallEndpoint` rather than
     * the route constant.
     *
     * A Bluetooth headset does not. On a Samsung with Android 14, once a pick had
     * taken the audio off the AirPods, Telecom acknowledged
     * `requestCallEndpointChange` for the headset and never switched to it until a
     * switch between two other routes had settled (airpods1, airpods2, 2026-09-15).
     * `setAudioRoute(ROUTE_BLUETOOTH)` goes straight to Telecom's route state
     * machine and reached the headset 5 of 5 in the same situation (airpods-exp1).
     */
    fun viaCallEndpoint(device: Device): Boolean = device != Device.BLUETOOTH

    /** Null for an endpoint no in-call control can show: streaming or unknown. */
    fun deviceForEndpointType(type: Int): Device? = when (type) {
        CallEndpoint.TYPE_EARPIECE -> Device.EARPIECE
        CallEndpoint.TYPE_BLUETOOTH -> Device.BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> Device.WIRED_HEADSET
        CallEndpoint.TYPE_SPEAKER -> Device.SPEAKER
        else -> null
    }
}
