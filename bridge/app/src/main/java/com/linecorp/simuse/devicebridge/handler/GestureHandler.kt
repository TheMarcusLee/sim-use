// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge.handler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

/**
 * Dispatches touch gestures via `AccessibilityService.dispatchGesture()`.
 *
 * Fire-and-forget: we return immediately after `dispatchGesture` is
 * accepted by the framework, without waiting for the callback. Callers
 * verify state changes via the a11y tree after the gesture, which is
 * how csat operates and what the spike validated.
 *
 * Direct lift from csat's `GestureHandler` shape; same constants.
 */
class GestureHandler {

    fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(service, gesture, "tap($x,$y)")
    }

    fun swipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ): Boolean {
        val clamped = clampSwipeDuration(duration)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, clamped)
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build(),
            "swipe($startX,$startY -> $endX,$endY)")
    }

    /**
     * Play a sampled path: one stroke along the polyline the caller
     * sent, lasting from its first timestamp to its last.
     *
     * This is what the farm's motion model dispatches — a thumb's arc
     * with jittered start, slight bow and a dozen-odd samples. The
     * curve reaches the screen exactly as drawn; the *timing within*
     * the stroke does not, because `StrokeDescription` walks a path at
     * a constant speed. Total duration and shape are honoured, the
     * fast-middle velocity profile is flattened. Splitting the path
     * into one continued stroke per segment would preserve it, but
     * `dispatchGesture` caps a gesture at
     * `GestureDescription.getMaxStrokeCount()` strokes and continued
     * strokes have to be dispatched one gesture at a time against a
     * callback, which this fire-and-forget handler deliberately is not.
     */
    fun path(service: AccessibilityService, points: List<TimedPoint>): Boolean {
        if (points.size < 2) return false
        val stroke = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        val duration = pathDuration(points)
        val description = GestureDescription.StrokeDescription(stroke, 0, duration)
        return dispatch(service, GestureDescription.Builder().addStroke(description).build(),
            "path(${points.size} points, ${duration}ms)")
    }

    fun gesture(service: AccessibilityService, strokes: List<StrokeParams>): Boolean {
        val builder = GestureDescription.Builder()
        for (s in strokes) {
            val path = Path()
            val waypoints = s.path
            if (waypoints != null && waypoints.size >= 2) {
                // Polyline shape — used by sim-use rotate presets, which
                // sample an arc Swift-side into a sequence of waypoints
                // and pass them through here. Dispatching the curved
                // path natively via Path.lineTo() avoids the parasitic
                // pinch that a single linear chord would introduce
                // (mid-trajectory finger distance shrinks below the
                // starting diameter for ≤90° rotations and drops to 0
                // at 180°).
                val first = waypoints.first()
                path.moveTo(first.x, first.y)
                for (i in 1 until waypoints.size) {
                    val p = waypoints[i]
                    path.lineTo(p.x, p.y)
                }
            } else {
                path.moveTo(s.startX, s.startY)
                path.lineTo(s.endX, s.endY)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, s.startTime, s.duration))
        }
        return dispatch(service, builder.build(), "gesture(${strokes.size})")
    }

    private fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription,
        label: String,
    ): Boolean =
        try {
            val ok = service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "$label dispatched=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "$label failed", e)
            false
        }

    data class StrokeParams(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val startTime: Long,
        val duration: Long,
        val path: List<Point>? = null,
    )

    data class Point(val x: Float, val y: Float)

    /** A path sample: where the finger is, and how long after touch-down. */
    data class TimedPoint(val x: Float, val y: Float, val t: Long)

    companion object {
        private const val TAG = "SimuseGesture"
        private const val TAP_DURATION = 50L
        // The sim-use client validates `--duration` up to 10 s and
        // delivers long-press holds as /swipe with start == end, so the
        // ceiling must cover the full client range — a silently
        // shortened hold breaks e.g. 10 s long-press flows. The bound
        // exists only against runaway requests; GestureDescription
        // itself accepts far longer strokes.
        internal const val MIN_SWIPE_DURATION = 10L
        internal const val MAX_SWIPE_DURATION = 10_000L

        internal fun clampSwipeDuration(duration: Long): Long =
            duration.coerceIn(MIN_SWIPE_DURATION, MAX_SWIPE_DURATION)

        /**
         * A sampled path lasts from its first timestamp to its last.
         * Clamped like a swipe: the same runaway-request bound applies,
         * and a path whose timestamps arrived scrambled still gets a
         * dispatchable duration rather than a rejected gesture.
         */
        internal fun pathDuration(points: List<TimedPoint>): Long =
            clampSwipeDuration(points.last().t - points.first().t)
    }
}