package io.embodit.seedpits.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import io.embodit.seedpits.ui.board.SoundKind
import kotlin.math.abs
import kotlin.random.Random

/**
 * Event sounds from `assets/sounds/<kind>.wav` (+ `_2`, `_3` variants), through a SoundPool with 4 voices.
 * Drops and store thuds get ±7 % random rate on top of the caller's rate (the pitch rises with the pile) and
 * rotate through their variants without repeating; [pan] places the sound left / right of the board centre.
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
        val varied = kind == SoundKind.DROP || kind == SoundKind.STORE
        var r = rate
        var v = 0
        if (varied) {
            var jitter = 0.93f + random.nextFloat() * 0.14f
            val prev = lastRate[kind]
            if (prev != null && abs(jitter - prev) < 0.015f) jitter = if (jitter < 1f) jitter + 0.03f else jitter - 0.03f
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
        val vol = gain.coerceIn(0f, 1f) * (BASE[kind] ?: 1f)
        val left = vol * (1f - pan.coerceIn(-1f, 1f) * 0.5f).coerceAtMost(1f)
        val right = vol * (1f + pan.coerceIn(-1f, 1f) * 0.5f).coerceAtMost(1f)
        pool.play(id, left, right, 1, 0, r.coerceIn(0.5f, 2f))
    }

    fun release() { runCatching { pool.release() } }

    private companion object {
        const val TAG = "SeedPits"
        /** Same table as tools/make_sounds.py --loudness. */
        val BASE = mapOf(SoundKind.DROP to 0.72f, SoundKind.STORE to 0.8f, SoundKind.GRAB to 0.72f)
    }
}
