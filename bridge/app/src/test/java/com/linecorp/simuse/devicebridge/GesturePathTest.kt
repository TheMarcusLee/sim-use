// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge

import com.linecorp.simuse.devicebridge.handler.GestureHandler
import com.linecorp.simuse.devicebridge.server.ActionRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/gesture` with a `points` body: the request shape the phone farm's
 * motion model sends for a human swipe — a dozen-odd samples of an arc,
 * each with the milliseconds since touch-down.
 *
 * Parsing and duration are pure and tested here; the dispatch itself
 * needs a real `Path` and an `AccessibilityService`, so it belongs to
 * the on-device suite.
 */
class GesturePathTest {

    private fun points(json: String) = ActionRouter.parseTimedPoints(json)

    @Test
    fun parsesASampledPath() {
        val parsed = points("""[{"x":540.5,"y":1800,"t":0},{"x":530,"y":1200,"t":140},{"x":520,"y":700,"t":310}]""")
        assertEquals(3, parsed.size)
        assertEquals(540.5f, parsed[0].x, 0.001f)
        assertEquals(1800f, parsed[0].y, 0.001f)
        assertEquals(0L, parsed[0].t)
        assertEquals(310L, parsed[2].t)
    }

    @Test
    fun timestampIsOptionalSoABarePolylineStillParses() {
        val parsed = points("""[{"x":10,"y":20},{"x":30,"y":40}]""")
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.t == 0L })
    }

    @Test
    fun anythingThatIsNotAPathParsesToNothing() {
        assertTrue(points("not json").isEmpty())
        assertTrue(points("""{"x":1,"y":2}""").isEmpty())
        // A sample missing a coordinate would leave a hole in the stroke;
        // reject the request rather than play a different gesture.
        assertTrue(points("""[{"x":1,"y":2,"t":0},{"x":3,"t":50}]""").isEmpty())
    }

    @Test
    fun durationSpansTheFirstAndLastSample() {
        val path = listOf(
            GestureHandler.TimedPoint(0f, 0f, 0L),
            GestureHandler.TimedPoint(5f, 5f, 120L),
            GestureHandler.TimedPoint(10f, 10f, 260L),
        )
        assertEquals(260L, GestureHandler.pathDuration(path))
    }

    @Test
    fun durationIsBoundedLikeASwipe() {
        val runaway = listOf(
            GestureHandler.TimedPoint(0f, 0f, 0L),
            GestureHandler.TimedPoint(1f, 1f, 900_000L),
        )
        assertEquals(GestureHandler.MAX_SWIPE_DURATION, GestureHandler.pathDuration(runaway))
        val instant = listOf(
            GestureHandler.TimedPoint(0f, 0f, 0L),
            GestureHandler.TimedPoint(1f, 1f, 0L),
        )
        assertEquals(GestureHandler.MIN_SWIPE_DURATION, GestureHandler.pathDuration(instant))
    }
}
