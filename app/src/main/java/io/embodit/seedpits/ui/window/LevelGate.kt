package io.embodit.seedpits.ui.window

import kotlin.math.abs

/**
 * Opens a window only while the head is level (native-window-move §7.9, profile D9): a Planar window opened
 * while looking down or up comes out pitched and cannot be straightened afterwards. Pure Kotlin; the caller
 * feeds time and head pitch (degrees, + up; null without a valid head pose) every frame.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L
    val waiting get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why; levelSinceMs = -1L; noHeadSinceMs = -1L
    }

    fun cancel() { pending = null; levelSinceMs = -1L; noHeadSinceMs = -1L }

    fun waitedMs(now: Long) = if (pending == null) 0L else now - askedMs

    /** Returns the reason to open now, or null to keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel(); return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel(); return why
    }

    fun hintWanted(pitchDeg: Float?) = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
}
