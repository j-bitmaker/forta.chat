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
}
