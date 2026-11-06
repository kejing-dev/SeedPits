package io.embodit.seedpits.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import io.embodit.seedpits.ui.board.SoundKind
import kotlin.math.abs
import kotlin.random.Random

/**
 * Event sounds from `assets/sounds/<kind>.wav` (+ `_2`, `_3` variants): Kenney CC0 recordings (chips on felt and
 * wood for the stones, see build/sound-sources.md), through a SoundPool with 4 voices. Recorded sounds get a random
 * variant and ±5 % pitch on top of the caller's rate (drops rise a little with the pile); [pan] places the sound
 * left / right of the board centre.
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled: Boolean = true
    @Volatile var gain: Float = 1f

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<SoundKind, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(System.nanoTime())
    private val lastVariant = HashMap<SoundKind, Int>()
    private val lastRate = HashMap<SoundKind, Float>()
    private var busyUntilMs = 0L

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) synchronized(loaded) { loaded += sampleId } }
        for (kind in SoundKind.entries) {
            val base = kind.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
                    .onFailure { if (name == "$base.wav") Log.i(TAG, "no sound asset sounds/$name") }
            }
            if (found.isNotEmpty()) ids[kind] = found
        }
    }

    fun play(kind: SoundKind, rate: Float = 1f, pan: Float = 0f) {
        if (!enabled) return
        val variants = ids[kind] ?: return
        // Every real recording gets a random variant (never the same twice in a row) and ±5 % pitch (profile D51);
        // the interface cues (again / lose / draw) play as they are.
        val varied = kind in VARIED
        var r = rate
        var v = 0
        if (varied) {
            var jitter = 0.95f + random.nextFloat() * 0.10f
            val prev = lastRate[kind]
            if (prev != null && abs(jitter - prev) < 0.012f) jitter = if (jitter < 1f) jitter + 0.025f else jitter - 0.025f
            lastRate[kind] = jitter
            r *= jitter
            if (variants.size > 1) {
                v = random.nextInt(variants.size)
                if (v == lastVariant[kind]) v = (v + 1) % variants.size
                lastVariant[kind] = v
            }
        }
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        // Drops, store thuds and the grab overlap (4 voices): a little headroom so the mix stays under -1 dBFS.
        // A sound that starts while another is still ringing plays at DUCK, so overlapping tails (a stone every
        // 70–110 ms, a drop under the grab rattle) do not add up past full scale. Same rule and table as
        // tools/make_sounds.py --loudness, which renders qa/loudness-<version>.wav.
        val now = android.os.SystemClock.uptimeMillis()
        val overlap = if (now < busyUntilMs) DUCK else 1f
        busyUntilMs = maxOf(busyUntilMs, now + (RING[kind] ?: 130L))
        val vol = gain.coerceIn(0f, 1f) * (BASE[kind] ?: 1f) * overlap
        val left = vol * (1f - pan.coerceIn(-1f, 1f) * 0.5f).coerceAtMost(1f)
        val right = vol * (1f + pan.coerceIn(-1f, 1f) * 0.5f).coerceAtMost(1f)
        pool.play(id, left, right, 1, 0, r.coerceIn(0.5f, 2f))
    }

    fun release() { runCatching { pool.release() } }

    private companion object {
        const val TAG = "SeedPits"
        /** Same table as tools/make_sounds.py --loudness. */
        const val DUCK = 0.5f
        val RING = mapOf(SoundKind.GRAB to 600L, SoundKind.CAPTURE to 250L, SoundKind.SWEEP to 350L, SoundKind.WIN to 200L, SoundKind.AGAIN to 250L)
        val VARIED = setOf(SoundKind.DROP, SoundKind.STORE, SoundKind.GRAB, SoundKind.CAPTURE, SoundKind.SWEEP, SoundKind.ILLEGAL, SoundKind.WIN)
        val BASE = mapOf(SoundKind.DROP to 1f, SoundKind.STORE to 1f, SoundKind.GRAB to 1f, SoundKind.CAPTURE to 1f, SoundKind.SWEEP to 1f)
    }
}
