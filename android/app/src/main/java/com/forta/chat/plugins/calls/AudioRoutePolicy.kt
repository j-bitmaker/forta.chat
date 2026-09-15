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

    /**
     * [target] null = leave the route alone; [keepPin] false = the pin is gone;
     * [keepHop] true = a headset pick is still on its way through the earpiece.
     */
    data class Decision(val target: Device?, val keepPin: Boolean, val keepHop: Boolean = false)

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
            // A pinned headset gone after Telecom already left it (see onTelecomRouteChanged).
            pinned != null && pin == null -> fallback(available, callType).takeIf { it != active }
            else -> null
        }
        return Decision(target, keepPin = pin != null)
    }

    private val HEADSETS = setOf(Device.BLUETOOTH, Device.WIRED_HEADSET)

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
     *
     * A headset pin outlives that fallback. Telecom reports the earpiece about
     * 200 ms before the device list loses the headset (route7v), so ending the pin
     * here left [onDevicesChanged] nothing to act on; the pin ends there instead.
     *
     * [headsetHop] marks a headset pick that went to the earpiece first (see
     * [firstStep]): Telecom reaching the earpiece is the cue to ask for the headset.
     * A loudspeaker report in between is noise — before API 34 every audio state
     * change repeats the route — so the hop and the pin wait for the earpiece.
     */
    fun onTelecomRouteChanged(
        route: Device,
        pinned: Device?,
        callType: String = "voice",
        headsetHop: Boolean = false,
    ): Decision = when {
        headsetHop && pinned == Device.BLUETOOTH && route == Device.EARPIECE -> Decision(Device.BLUETOOTH, keepPin = true)
        headsetHop && pinned == Device.BLUETOOTH && route == Device.SPEAKER ->
            Decision(target = null, keepPin = true, keepHop = true)
        pinned == Device.SPEAKER && route != Device.SPEAKER -> Decision(Device.SPEAKER, keepPin = true)
        pinned == null && callType == "video" && route == Device.EARPIECE -> Decision(Device.SPEAKER, keepPin = false)
        else -> Decision(
            target = null,
            keepPin = pinned != null && (pinned == route || pinned in HEADSETS && route == Device.EARPIECE),
        )
    }

    /**
     * The first route to ask Telecom for when the user picks [device] while
     * Telecom last reported [telecomRoute].
     *
     * On a Samsung with Android 14 a Bluetooth request made while Telecom is on
     * the loudspeaker is acknowledged and dropped — Telecom never processes
     * USER_SWITCH_BLUETOOTH — while the same request from the earpiece connects
     * the headset (route4, 2026-09-15). Such a pick goes to the earpiece first,
     * and [onTelecomRouteChanged] asks for the headset once Telecom reports it.
     */
    fun firstStep(device: Device, telecomRoute: Device?): Device =
        if (device == Device.BLUETOOTH && telecomRoute == Device.SPEAKER) Device.EARPIECE else device

    /** The route for a call whose device just vanished. */
    fun fallback(available: Set<Device>, callType: String): Device = when {
        Device.WIRED_HEADSET in available -> Device.WIRED_HEADSET
        callType == "video" -> Device.SPEAKER
        else -> Device.EARPIECE
    }
}
