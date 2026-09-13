package com.forta.chat.plugins.calls

import com.forta.chat.plugins.calls.AudioRouter.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The translation between the router's devices and Telecom's two route
 * vocabularies: `CallAudioState.ROUTE_*` (API 23-33) and `CallEndpoint.TYPE_*`
 * (API 34+). The numbers are Telecom's public constants; pinning them as
 * literals catches a swapped branch that would send "speaker" to the earpiece.
 */
class TelecomAudioRouteTest {

    @Test
    fun devices_mapToTelecomRouteConstants() {
        assertEquals(1, TelecomAudioRoute.routeFor(Device.EARPIECE))
        assertEquals(2, TelecomAudioRoute.routeFor(Device.BLUETOOTH))
        assertEquals(4, TelecomAudioRoute.routeFor(Device.WIRED_HEADSET))
        assertEquals(8, TelecomAudioRoute.routeFor(Device.SPEAKER))
    }

    @Test
    fun telecomRoutes_mapBackToDevices() {
        assertEquals(Device.EARPIECE, TelecomAudioRoute.deviceForRoute(1))
        assertEquals(Device.BLUETOOTH, TelecomAudioRoute.deviceForRoute(2))
        assertEquals(Device.WIRED_HEADSET, TelecomAudioRoute.deviceForRoute(4))
        assertEquals(Device.SPEAKER, TelecomAudioRoute.deviceForRoute(8))
    }

    @Test
    fun aRouteTheRouterHasNoDeviceFor_isIgnored() {
        // ROUTE_STREAMING (16): the call is streamed to another device, which
        // no in-call control here can represent.
        assertNull(TelecomAudioRoute.deviceForRoute(16))
        assertNull(TelecomAudioRoute.deviceForRoute(0))
    }

    @Test
    fun devices_mapToCallEndpointTypes() {
        assertEquals(1, TelecomAudioRoute.endpointTypeFor(Device.EARPIECE))
        assertEquals(2, TelecomAudioRoute.endpointTypeFor(Device.BLUETOOTH))
        assertEquals(3, TelecomAudioRoute.endpointTypeFor(Device.WIRED_HEADSET))
        assertEquals(4, TelecomAudioRoute.endpointTypeFor(Device.SPEAKER))
    }

    @Test
    fun callEndpointTypes_mapBackToDevices() {
        assertEquals(Device.EARPIECE, TelecomAudioRoute.deviceForEndpointType(1))
        assertEquals(Device.BLUETOOTH, TelecomAudioRoute.deviceForEndpointType(2))
        assertEquals(Device.WIRED_HEADSET, TelecomAudioRoute.deviceForEndpointType(3))
        assertEquals(Device.SPEAKER, TelecomAudioRoute.deviceForEndpointType(4))
        // TYPE_STREAMING (5) and TYPE_UNKNOWN (-1).
        assertNull(TelecomAudioRoute.deviceForEndpointType(5))
        assertNull(TelecomAudioRoute.deviceForEndpointType(-1))
    }

    @Test
    fun everyDevice_survivesBothRoundTrips() {
        for (device in Device.values()) {
            assertEquals(device, TelecomAudioRoute.deviceForRoute(TelecomAudioRoute.routeFor(device)))
            assertEquals(device, TelecomAudioRoute.deviceForEndpointType(TelecomAudioRoute.endpointTypeFor(device)))
        }
    }
}
