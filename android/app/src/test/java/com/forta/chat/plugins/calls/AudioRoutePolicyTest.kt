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
        // A Bluetooth pick may pass through the earpiece before Telecom reaches
        // the headset; asking for the loudspeaker there would undo the pick.
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

    // -- A headset pick while Telecom is on the loudspeaker ------------------------------
    //
    // On a Samsung with Android 14 requestCallEndpointChange(BLUETOOTH) made on the
    // loudspeaker is acknowledged and dropped: Telecom never processes
    // USER_SWITCH_BLUETOOTH (5 of 5, route4). The same request from the earpiece
    // connects the headset, and loudspeaker → earpiece goes through, so such a pick
    // takes the earpiece as its first step.

    @Test
    fun aHeadsetPickFromTheLoudspeaker_goesThroughTheEarpiece() {
        assertEquals(Device.EARPIECE, AudioRoutePolicy.firstStep(Device.BLUETOOTH, telecomRoute = Device.SPEAKER))
    }

    @Test
    fun aHeadsetPickFromAnyOtherRoute_goesStraightThere() {
        for (route in Device.values().filter { it != Device.SPEAKER }) {
            assertEquals("from $route", Device.BLUETOOTH, AudioRoutePolicy.firstStep(Device.BLUETOOTH, telecomRoute = route))
        }
        // No Telecom report yet, or a call Telecom never registered: nothing to route around.
        assertEquals(Device.BLUETOOTH, AudioRoutePolicy.firstStep(Device.BLUETOOTH, telecomRoute = null))
    }

    @Test
    fun otherPicks_goStraightThere_evenFromTheLoudspeaker() {
        for (device in Device.values().filter { it != Device.BLUETOOTH }) {
            assertEquals("pick $device", device, AudioRoutePolicy.firstStep(device, telecomRoute = Device.SPEAKER))
        }
    }

    @Test
    fun telecomReachingTheEarpieceMidHop_isAskedForTheHeadset() {
        for (callType in listOf("voice", "video")) {
            assertEquals(
                callType,
                AudioRoutePolicy.Decision(Device.BLUETOOTH, keepPin = true),
                AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = Device.BLUETOOTH, callType = callType, headsetHop = true),
            )
        }
    }

    @Test
    fun telecomReachingTheHeadsetMidHop_keepsThePin() {
        // The direct request went through after all.
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = true),
            AudioRoutePolicy.onTelecomRouteChanged(Device.BLUETOOTH, pinned = Device.BLUETOOTH, headsetHop = true),
        )
    }

    @Test
    fun aLoudspeakerReportMidHop_keepsWaitingForTheEarpiece() {
        // Before API 34 Telecom re-reports its route on any audio state change (a
        // mute, a new route list), and API 34 has sent the same endpoint twice. A
        // loudspeaker report during the hop is that noise, not a refusal: dropping
        // the pick there would strand the call on the earpiece once Telecom gets there.
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = true, keepHop = true),
            AudioRoutePolicy.onTelecomRouteChanged(Device.SPEAKER, pinned = Device.BLUETOOTH, headsetHop = true),
        )
    }

    @Test
    fun anyOtherReportMidHop_endsTheHop() {
        for (route in listOf(Device.BLUETOOTH, Device.WIRED_HEADSET)) {
            val decision = AudioRoutePolicy.onTelecomRouteChanged(route, pinned = Device.BLUETOOTH, headsetHop = true)
            assertEquals("route $route", false, decision.keepHop)
        }
    }

    @Test
    fun anEarpieceReportWithoutAHop_stillEndsAHeadsetPin() {
        assertEquals(
            AudioRoutePolicy.Decision(target = null, keepPin = false),
            AudioRoutePolicy.onTelecomRouteChanged(Device.EARPIECE, pinned = Device.BLUETOOTH, headsetHop = false),
        )
    }
}
