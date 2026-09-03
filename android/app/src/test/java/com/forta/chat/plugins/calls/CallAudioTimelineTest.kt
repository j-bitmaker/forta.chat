package com.forta.chat.plugins.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The timeline is the evidence a bug report carries about how the audio stack
 * reached its final state, so the properties that matter are: order is
 * preserved, the buffer cannot grow without bound, the *tail* survives
 * truncation (failures happen at teardown), and concurrent writers cannot
 * corrupt it — AudioRouter records from the main handler, device callbacks
 * and the plugin thread.
 */
class CallAudioTimelineTest {

    @Test
    fun recordsEventsInOrderWithTimestamps() {
        var now = 1_000L
        val timeline = CallAudioTimeline(clock = { now })

        timeline.record("start", "voice")
        now = 1_250L
        timeline.record("mode", "MODE_IN_COMMUNICATION")

        val entries = timeline.snapshot()
        assertEquals(2, entries.size)
        assertEquals("start", entries[0].event)
        assertEquals("voice", entries[0].detail)
        assertEquals(1_000L, entries[0].atMs)
        assertEquals("mode", entries[1].event)
        assertEquals(1_250L, entries[1].atMs)
    }

    @Test
    fun detailDefaultsToEmpty() {
        val timeline = CallAudioTimeline()

        timeline.record("stop")

        assertEquals("", timeline.snapshot().single().detail)
    }

    @Test
    fun dropsOldestEntriesOnceCapacityIsReached() {
        val timeline = CallAudioTimeline(capacity = 3)

        timeline.record("first")
        timeline.record("second")
        timeline.record("third")
        timeline.record("fourth")

        // The tail is what diagnoses a failure — teardown must survive.
        assertEquals(
            listOf("second", "third", "fourth"),
            timeline.snapshot().map { it.event },
        )
    }

    @Test
    fun clearDropsEverything() {
        val timeline = CallAudioTimeline()
        timeline.record("start")

        timeline.clear()

        assertTrue(timeline.snapshot().isEmpty())
    }

    @Test
    fun snapshotIsDetachedFromLaterWrites() {
        val timeline = CallAudioTimeline()
        timeline.record("start")

        val snapshot = timeline.snapshot()
        timeline.record("stop")

        assertEquals(1, snapshot.size)
        assertEquals(2, timeline.snapshot().size)
    }

    @Test
    fun concurrentWritersDoNotCorruptTheBuffer() {
        val timeline = CallAudioTimeline(capacity = 500)
        val writers = 8
        val perWriter = 50
        val pool = Executors.newFixedThreadPool(writers)
        val done = CountDownLatch(writers)

        repeat(writers) { writer ->
            pool.execute {
                repeat(perWriter) { i -> timeline.record("w$writer", "$i") }
                done.countDown()
            }
        }

        assertTrue("writers did not finish", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(writers * perWriter, timeline.snapshot().size)
    }
}
