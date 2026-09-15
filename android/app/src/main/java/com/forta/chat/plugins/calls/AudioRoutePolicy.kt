package com.forta.chat.plugins.calls

import com.forta.chat.plugins.calls.AudioRouter.Device

/**
 * Where the call's audio goes when the set of devices changes. Pure so the
 * table lives in a JVM JUnit test without an AudioManager.
 *
 * A device the user picked by hand — the in-call speaker toggle — is a pin.
 * Before this rule a Bluetooth headset that appeared mid-call (or flapped on
 * and off, as some do) yanked the route back off the loudspeaker the user
 * had just asked for, and the toggle looked broken. Only a loudspeaker pin
 * holds against Bluetooth: an earpiece pin does not, because on Android the
 * in-call UI has no other way to reach a headset that connects mid-call.
 * The pin ends when the pinned device itself goes away.
 */
object AudioRoutePolicy {

    /** [target] null = leave the route alone; [keepPin] false = the pin is gone. */
    data class Decision(val target: Device?, val keepPin: Boolean)

    fun onDevicesChanged(
        active: Device,
        available: Set<Device>,
        pinned: Device?,
        callType: String,
    ): Decision {
        val pin = pinned?.takeIf { it in available }
        val target = when {
            Device.BLUETOOTH in available && active != Device.BLUETOOTH && pin != Device.SPEAKER -> Device.BLUETOOTH
            active !in available -> fallback(available, callType)
            else -> null
        }
        return Decision(target, keepPin = pin != null)
    }

    /**
     * The route after Telecom reports one it switched to on its own.
     *
     * Telecom owns the audio route of a self-managed call and moves it to a
     * headset the moment one connects. A loudspeaker the user pinned is asked
     * back, the same way it holds against a device change; any other pin holds
     * only while Telecom is on it, and an unpinned route is simply what the call
     * now uses — except the earpiece in a video call. Telecom falls back to the
     * earpiece when a headset leaves, whatever the call type, and a video call
     * belongs on the loudspeaker unless the user put it at the ear.
     */
    fun onTelecomRouteChanged(route: Device, pinned: Device?, callType: String = "voice"): Decision = when {
        pinned == Device.SPEAKER && route != Device.SPEAKER -> Decision(Device.SPEAKER, keepPin = true)
        pinned == null && callType == "video" && route == Device.EARPIECE -> Decision(Device.SPEAKER, keepPin = false)
        else -> Decision(target = null, keepPin = pinned != null && pinned == route)
    }

    /** The route for a call whose device just vanished. */
    fun fallback(available: Set<Device>, callType: String): Device = when {
        Device.WIRED_HEADSET in available -> Device.WIRED_HEADSET
        callType == "video" -> Device.SPEAKER
        else -> Device.EARPIECE
    }
}
