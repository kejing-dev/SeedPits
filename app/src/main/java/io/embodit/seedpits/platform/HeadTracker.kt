package io.embodit.seedpits.platform

import android.util.Log
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlin.math.abs
import kotlin.math.asin

/**
 * Head pitch for the window level gate (native-window-move §7.9) and nothing else: the head pose is never used
 * to aim, place or select (profile D1). A pose of all zeros with an identity rotation is treated as invalid.
 */
object HeadTracker {
    private var provider: HMDTrackingProvider? = null
    @Volatile var supported: Boolean? = null
        private set

    fun start() {
        if (provider != null) return
        runCatching {
            val p = HMDTrackingProvider()
            val result = p.start()
            Log.i(TAG, "HEAD provider start=$result state=${p.state} support=${p.supportState}")
            // Shared Space (this app's container) reports NOT_IN_FULL_SPACE: no head pose to gate on, so the
            // window opens at once (the system places Shared Space windows upright in front of the user).
            if (p.supportState.name.startsWith("NOT") || p.supportState.name.contains("UNSUPPORTED")) {
                runCatching { p.stop() }
                supported = false
            } else {
                provider = p
                supported = true
            }
        }.onFailure {
            supported = false
            Log.w(TAG, "HEAD provider unavailable: $it")
        }
    }

    /** Pitch in degrees (+ up), or null without a valid head pose. */
    fun pitchDeg(): Float? {
        val data = runCatching { provider?.latestData }.getOrNull() ?: return null
        val pose = data.hmdPose
        val q = pose.rotation
        val pos = pose.position
        val identity = abs(q.x) < 1e-6f && abs(q.y) < 1e-6f && abs(q.z) < 1e-6f
        val zeroPos = abs(pos.x) < 1e-6f && abs(pos.y) < 1e-6f && abs(pos.z) < 1e-6f
        if (identity && zeroPos) return null
        val f = q.rotateVector(Vector3(0f, 0f, -1f))
        val pitch = Math.toDegrees(asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        return if (pitch.isFinite()) pitch else null
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
    }

    private const val TAG = "SeedPits"
}
