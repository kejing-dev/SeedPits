package io.embodit.seedpits.ui.board

import io.embodit.seedpits.domain.rules.Kalah
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class P3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: P3) = P3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: P3) = P3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = P3(x * k, y * k, z * k)
    fun lerp(o: P3, t: Float) = P3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun length(): Float = sqrt(x * x + y * y + z * z)
}

/**
 * The board in metres (design-package gameSpec.board / slots): origin on the board's top centre, +X right,
 * +Z toward the player, +Y up. Everything derives from [PITCH]. `tools/build_board.py` uses the same numbers
 * for board.glb (pits are spherical bowls, stores elliptical bowls).
 */
object PitLayout {
    const val PITCH = 0.072f
    const val BOARD_W = 0.66f
    const val BOARD_D = 0.24f
    const val BOARD_T = 0.03f
    const val PIT_R = 0.030f
    const val PIT_DEPTH = 0.015f
    const val ROW_Z = 0.045f
    const val STORE_X = 0.28f
    const val STORE_A = 0.040f   // half-width along X
    const val STORE_B = 0.070f   // half-length along Z
    const val STORE_DEPTH = 0.015f
    const val STONE_D = 0.016f
    const val STONE_H = 0.011f
    const val LAYER = 0.010f
    const val STORE_LAYER = 0.0125f

    /** Pit collider: Ø 6.4 cm (as a box), 5.3 cm tall from the bowl bottom to 3.8 cm over the rim (covers the 24-stone pile). */
    const val COLLIDER_W = 0.064f
    const val COLLIDER_H = 0.053f
    val COLLIDER_CENTER_Y = -PIT_DEPTH + COLLIDER_H / 2

    /** Sphere radius of a pit bowl: (r² + d²) / 2d. */
    private val BOWL_R = (PIT_R * PIT_R + PIT_DEPTH * PIT_DEPTH) / (2 * PIT_DEPTH)

    fun center(cell: Int): P3 = when (cell) {
        Kalah.SOUTH_STORE -> P3(STORE_X, 0f, 0f)
        Kalah.NORTH_STORE -> P3(-STORE_X, 0f, 0f)
        in 0..5 -> P3((cell - 2.5f) * PITCH, 0f, ROW_Z)
        in 7..12 -> P3((2.5f - (cell - 7)) * PITCH, 0f, -ROW_Z)
        else -> error("cell $cell")
    }

    /** Height of the pit bowl surface at horizontal distance [r] from the pit centre (≤ 0). */
    fun pitBowl(r: Float): Float {
        if (r >= PIT_R) return 0f
        return -PIT_DEPTH + (BOWL_R - sqrt(max(0f, BOWL_R * BOWL_R - r * r)))
    }

    /** Height of the store bowl at (dx, dz) from the store centre (≤ 0). */
    fun storeBowl(dx: Float, dz: Float): Float {
        val u2 = (dx / STORE_A) * (dx / STORE_A) + (dz / STORE_B) * (dz / STORE_B)
        if (u2 >= 1f) return 0f
        return -STORE_DEPTH * sqrt(1f - u2)
    }

    /** Slot offsets of a pit (relative to its centre). 7 / 6 / 7 / 3 / 1 = 24, then a column up the middle. */
    val pitSlots: List<P3> by lazy {
        val list = ArrayList<P3>()
        val half = STONE_H / 2
        fun ring(n: Int, r: Float, phase: Float, layer: Int) {
            for (k in 0 until n) {
                val a = phase + k * (2 * Math.PI / n).toFloat()
                val x = r * cos(a); val z = r * sin(a)
                list += P3(x, pitBowl(r) + half + layer * LAYER, z)
            }
        }
        list += P3(0f, pitBowl(0f) + half, 0f)
        ring(6, 0.017f, 0f, 0)
        ring(6, 0.0175f, (Math.PI / 6).toFloat(), 1)
        list += P3(0f, pitBowl(0f) + half + 2 * LAYER, 0f)
        ring(6, 0.017f, 0f, 2)
        ring(3, 0.0095f, (Math.PI / 6).toFloat(), 3)
        list += P3(0f, pitBowl(0f) + half + 4 * LAYER, 0f)
        list
    }

    /** Slot offsets of a store: hex rows clipped to shrinking ellipses, five layers (a mound), 59 slots. */
    val storeSlots: List<P3> by lazy {
        val list = ArrayList<P3>()
        val sp = 0.0166f
        val scales = floatArrayOf(0.84f, 0.70f, 0.56f, 0.42f, 0.30f)
        for ((layer, s) in scales.withIndex()) {
            val a = STORE_A * 100f * s
            val b = STORE_B * 100f * s
            val offset = if (layer % 2 == 1) sp * 50f else 0f
            val spCm = sp * 100f
            val pts = ArrayList<P3>()
            for (r in -12..12) {
                val z = r * spCm * 0.866f
                val xo = (if (r % 2 != 0) spCm / 2 else 0f) + offset
                for (c in -6..6) {
                    val x = c * spCm + xo
                    if ((x / a) * (x / a) + (z / b) * (z / b) <= 1f) {
                        val mx = x / 100f; val mz = z / 100f
                        pts += P3(mx, storeBowl(mx, mz) + STONE_H / 2 + layer * STORE_LAYER, mz)
                    }
                }
            }
            // Fill from the middle outwards so a few stones gather in the middle of the store.
            list += pts.sortedBy { (it.x / STORE_A) * (it.x / STORE_A) + (it.z / STORE_B) * (it.z / STORE_B) }
        }
        list
    }

    /** Where the [index]-th stone of [cell] rests (board space). Beyond the table the stones stack up the middle. */
    fun slot(cell: Int, index: Int): P3 {
        val table = if (Kalah.isStore(cell)) storeSlots else pitSlots
        val c = center(cell)
        if (index < table.size) return c + table[index]
        val top = table.maxOf { it.y }
        return c + P3(0f, top + (index - table.size + 1) * STONE_H * 0.9f, 0f)
    }

    /** Where a digit tile sits: near side of your pits and the stores, far side of theirs. */
    fun digitTile(cell: Int): P3 {
        val c = center(cell)
        return when {
            Kalah.isStore(cell) -> P3(c.x, 0.0005f, 0.092f)
            cell in 0..5 -> P3(c.x, 0.0005f, 0.089f)
            else -> P3(c.x, 0.0005f, -0.089f)
        }
    }

    /** Far edge, where the seat plate stands (board space): plate bottom 7 cm over the board, centre 8.75 cm. */
    val SEAT_PLATE = P3(0f, 0.0875f, -0.13f)
    const val SEAT_W = 0.10f
    const val SEAT_H = 0.035f

    /** Handful: 8 cm above the rim, spread within 2.5 cm. */
    const val HANDFUL_LIFT = 0.08f
    const val HANDFUL_R = 0.025f
    const val ARC_LIFT = 0.10f
}
