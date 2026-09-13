package com.forta.chat.plugins.calls

import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import com.forta.chat.plugins.calls.AudioRouter.Device

/**
 * Translates the router's devices into Telecom's route vocabularies and back.
 *
 * Telecom owns the audio route of a self-managed call, so a pick has to reach it
 * as a `CallAudioState.ROUTE_*` constant (API 23-33) or as a `CallEndpoint` of
 * the matching type (API 34+), and Telecom's reports have to come back as a
 * [Device]. Pure so the table is testable without Telecom; the constants are
 * compile-time values, so nothing here loads an API 34 class on older Android.
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

    /** Null for an endpoint no in-call control can show: streaming or unknown. */
    fun deviceForEndpointType(type: Int): Device? = when (type) {
        CallEndpoint.TYPE_EARPIECE -> Device.EARPIECE
        CallEndpoint.TYPE_BLUETOOTH -> Device.BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> Device.WIRED_HEADSET
        CallEndpoint.TYPE_SPEAKER -> Device.SPEAKER
        else -> null
    }
}
