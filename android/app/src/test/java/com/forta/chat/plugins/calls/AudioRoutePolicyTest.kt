package com.forta.chat.plugins.calls

import com.forta.chat.plugins.calls.AudioRouter.Device
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The device-change table behind [AudioRouter.handleDevicesChanged]. The
 * rule it fixes (O08): a Bluetooth headset appearing — or flapping — must not
 * pull the route off a loudspeaker the user pinned by hand.
 */
class AudioRoutePolicyTest {

    private val builtIn = setOf(Device.EARPIECE, Device.SPEAKER)

    private fun decide(
        active: Device,
        available: Set<Device>,
        pinned: Device? = null,
        callType: String = "voice",
    ) = AudioRoutePolicy.onDevicesChanged(active, available, pinned, callType)

    @Test
    fun bluetoothAppearing_takesTheRoute_whenNothingIsPinned() {
        assertEquals(
            AudioRoutePolicy.Decision(Device.BLUETOOTH, keepPin = false),
            decide(Device.EARPIECE, builtIn + Device.BLUETOOTH),
        )
    }

    @Test
    fun bluetoothAppearing_doesNotOverrideAPinnedLoudspeaker() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = true),
            decide(Device.SPEAKER, builtIn + Device.BLUETOOTH, pinned = Device.SPEAKER),
        )
    }

    @Test
    fun bluetoothFlapping_leavesAPinnedLoudspeakerAlone_everyTime() {
        // connect → disconnect → connect: the pin survives each event.
        var pin: Device? = Device.SPEAKER
        for (available in listOf(builtIn + Device.BLUETOOTH, builtIn, builtIn + Device.BLUETOOTH)) {
            val d = decide(Device.SPEAKER, available, pinned = pin)
            assertEquals("event on $available", null, d.target)
            if (!d.keepPin) pin = null
        }
        assertEquals(Device.SPEAKER, pin)
    }

    @Test
    fun bluetoothAppearing_stillWins_overAPinnedEarpiece() {
        // The in-call UI has no other way to reach a headset that connects
        // mid-call, so an earpiece pin does not hold against Bluetooth.
        assertEquals(
            AudioRoutePolicy.Decision(Device.BLUETOOTH, keepPin = true),
            decide(Device.EARPIECE, builtIn + Device.BLUETOOTH, pinned = Device.EARPIECE),
        )
    }

    @Test
    fun pinnedBluetoothGoingAway_dropsThePin_andFallsBack() {
        assertEquals(
            AudioRoutePolicy.Decision(Device.EARPIECE, keepPin = false),
            decide(Device.BLUETOOTH, builtIn, pinned = Device.BLUETOOTH),
        )
        assertEquals(
            AudioRoutePolicy.Decision(Device.SPEAKER, keepPin = false),
            decide(Device.BLUETOOTH, builtIn, pinned = Device.BLUETOOTH, callType = "video"),
        )
    }

    @Test
    fun activeDeviceGone_prefersAWiredHeadset() {
        assertEquals(
            AudioRoutePolicy.Decision(Device.WIRED_HEADSET, keepPin = false),
            decide(Device.BLUETOOTH, builtIn + Device.WIRED_HEADSET),
        )
    }

    @Test
    fun nothingRelevantChanged_leavesTheRouteAlone() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = false),
            decide(Device.EARPIECE, builtIn + Device.WIRED_HEADSET),
        )
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = true),
            decide(Device.SPEAKER, builtIn, pinned = Device.SPEAKER),
        )
    }

    @Test
    fun bluetoothAlreadyActive_isNotReselected() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = false),
            decide(Device.BLUETOOTH, builtIn + Device.BLUETOOTH),
        )
    }

    // -- Telecom switched the route itself ----------------------------------------
    //
    // Telecom owns the audio route of a self-managed call. On a Samsung with
    // Android 14 it ignored every route request made through AudioManager and
    // moved the call to AirPods the moment they connected (stage 3, 2026-09-13).

    @Test
    fun telecomMovingToBluetooth_isAskedBack_toAPinnedLoudspeaker() {
        assertEquals(
            AudioRoutePolicy.Decision(Device.SPEAKER, keepPin = true),
            AudioRoutePolicy.onTelecomRouteChanged(Device.BLUETOOTH, pinned = Device.SPEAKER),
        )
    }

    @Test
    fun telecomReportingThePinnedLoudspeaker_changesNothing() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = true),
            AudioRoutePolicy.onTelecomRouteChanged(Device.SPEAKER, pinned = Device.SPEAKER),
        )
    }

    @Test
    fun telecomConfirmingAPinnedEarpiece_keepsThePin() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = true),
            AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = Device.EARPIECE),
        )
    }

    @Test
    fun telecomMovingToBluetooth_endsAnEarpiecePin() {
        // Same rule as a device change: only a loudspeaker pin holds against a
        // headset, because the in-call UI has no other way to reach one.
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = false),
            AudioRoutePolicy.onTelecomRouteChanged(Device.BLUETOOTH, pinned = Device.EARPIECE),
        )
    }

    @Test
    fun telecomRoute_isMirrored_whenNothingIsPinned() {
        for (route in Device.values()) {
            assertEquals(
                "route $route",
                AudioRoutePolicy.Decision(target = null, keepPin = false),
                AudioRoutePolicy.onTelecomRouteChanged(route, pinned = null),
            )
        }
    }

    // -- A video call Telecom drops onto the earpiece --------------------------------
    //
    // Telecom falls back to the earpiece when a headset leaves, whatever the call
    // type. A video call starts on the loudspeaker without a pin, so nothing asked
    // for it again and the call stayed at the ear (route7v, 2026-09-15).

    @Test
    fun anUnpinnedVideoCall_telecomDropsOnTheEarpiece_isAskedBackToTheLoudspeaker() {
        assertEquals(
            AudioRoutePolicy.Decision(Device.SPEAKER, keepPin = false),
            AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = null, callType = "video"),
        )
    }

    @Test
    fun anEarpieceTheUserPicked_holdsInAVideoCall() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = true),
            AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = Device.EARPIECE, callType = "video"),
        )
    }

    @Test
    fun aHeadsetPickInProgress_isNotOverriddenByTheLoudspeaker() {
        // Telecom reports the earpiece before the device list loses a leaving headset
        // (route7v), and while the headset is still listed the pick stands; asking for
        // the loudspeaker on that report would undo it.
        val decision = AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = Device.BLUETOOTH, callType = "video")
        assertEquals(null, decision.target)
    }

    @Test
    fun aVoiceCall_staysOnTheEarpieceTelecomChose() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = false),
            AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = null, callType = "voice"),
        )
    }

    @Test
    fun anUnpinnedVideoCall_followsTelecomEverywhereElse() {
        for (route in Device.values().filter { it != Device.EARPIECE }) {
            assertEquals(
                "route $route",
                AudioRoutePolicy.Decision(target = null, keepPin = false),
                AudioRoutePolicy.onTelecomRouteChanged(route, pinned = null, callType = "video"),
            )
        }
    }

    // -- A headset the user picked, leaving ---------------------------------------------
    //
    // When a headset leaves, Telecom falls back to the earpiece and reports it about
    // 200 ms before the device list loses the headset (route7v, 2026-09-15). Ending the
    // pin on that report left the device change nothing to act on — the earpiece was
    // still available — and a video call whose headset the user had picked stayed at
    // the ear. The pin now ends where the class says: when the device itself goes away.

    @Test
    fun telecomFallingBackToTheEarpiece_keepsAHeadsetPin_forTheDeviceList() {
        for (headset in listOf(Device.BLUETOOTH, Device.WIRED_HEADSET)) {
            for (callType in listOf("voice", "video")) {
                assertEquals(
                    "$headset, $callType",
                    AudioRoutePolicy.Decision(target = null, keepPin = true),
                    AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = headset, callType = callType),
                )
            }
        }
    }

    @Test
    fun telecomMovingAHeadsetPinnedCallAnywhereElse_stillEndsThePin() {
        val moves = listOf(
            Device.BLUETOOTH to Device.SPEAKER,
            Device.BLUETOOTH to Device.WIRED_HEADSET,
            Device.WIRED_HEADSET to Device.SPEAKER,
            Device.WIRED_HEADSET to Device.BLUETOOTH,
        )
        for ((headset, route) in moves) {
            assertEquals("$headset → $route", false, AudioRoutePolicy.onTelecomRouteChanged(route, pinned = headset).keepPin)
        }
    }

    @Test
    fun aPickedHeadsetGone_afterTelecomLeftIt_returnsAVideoCallToTheLoudspeaker() {
        for (headset in listOf(Device.BLUETOOTH, Device.WIRED_HEADSET)) {
            assertEquals(
                "$headset",
                AudioRoutePolicy.Decision(Device.SPEAKER, keepPin = false),
                decide(Device.EARPIECE, builtIn, pinned = headset, callType = "video"),
            )
        }
    }

    @Test
    fun aPickedHeadsetGone_afterTelecomLeftIt_leavesAVoiceCallOnTheEarpiece() {
        // The earpiece is where a voice call falls back, and Telecom is already there.
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = false),
            decide(Device.EARPIECE, builtIn, pinned = Device.BLUETOOTH, callType = "voice"),
        )
    }

    @Test
    fun aVideoCallsPickedHeadsetLeaving_endsOnTheLoudspeaker_whenTelecomReportsFirst() {
        // route7v order. The router mirrors Telecom's earpiece before the device change.
        var pin: Device? = Device.BLUETOOTH
        val report = AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pin, callType = "video")
        assertEquals(null, report.target)
        if (!report.keepPin) pin = null
        assertEquals(Device.SPEAKER, decide(Device.EARPIECE, builtIn, pinned = pin, callType = "video").target)
    }

    @Test
    fun aVideoCallsPickedHeadsetLeaving_endsOnTheLoudspeaker_whenTheDeviceListReportsFirst() {
        var pin: Device? = Device.BLUETOOTH
        val devices = decide(Device.BLUETOOTH, builtIn, pinned = pin, callType = "video")
        assertEquals(Device.SPEAKER, devices.target)
        if (!devices.keepPin) pin = null
        // Telecom's own fallback to the earpiece, reported before it acts on the request.
        assertEquals(Device.SPEAKER, AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pin, callType = "video").target)
    }
}
