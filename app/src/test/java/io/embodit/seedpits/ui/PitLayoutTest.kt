package io.embodit.seedpits.ui

import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Side
import io.embodit.seedpits.ui.board.P3
import io.embodit.seedpits.ui.board.PitLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PitLayoutTest {
    /** Ellipsoid overlap metric for two flat stones (1.6 × 1.1 × 1.6 cm): ≥ 1 means they just touch. */
    private fun sep(a: P3, b: P3): Float {
        val dx = (a.x - b.x) / PitLayout.STONE_D
        val dz = (a.z - b.z) / PitLayout.STONE_D
        val dy = (a.y - b.y) / PitLayout.STONE_H
        return dx * dx + dy * dy + dz * dz
    }

    private fun minSep(cell: Int, n: Int): Float {
        var m = Float.MAX_VALUE
        for (i in 0 until n) for (j in i + 1 until n) m = minOf(m, sep(PitLayout.slot(cell, i), PitLayout.slot(cell, j)))
        return m
    }

    @Test fun tablesAreBigEnough() {
        assertEquals(24, PitLayout.pitSlots.size)
        assertTrue("store slots ${PitLayout.storeSlots.size}", PitLayout.storeSlots.size >= 56)
    }

    @Test fun slotsDoNotInterpenetrate() {
        val pit = minSep(0, 24)
        val store = minSep(Kalah.SOUTH_STORE, 48)
        println("min separation pit=$pit store=$store")
        assertTrue("pit stones overlap: $pit", pit >= 0.8f)
        assertTrue("store stones overlap: $store", store >= 0.8f)
    }

    @Test fun stonesStayInsideTheirBowlFootprint() {
        for ((i, s) in PitLayout.pitSlots.withIndex()) assertTrue("pit slot $i", kotlin.math.hypot(s.x, s.z) + PitLayout.STONE_D / 2 <= PitLayout.PIT_R)
        for ((i, s) in PitLayout.storeSlots.withIndex()) {
            val u = (s.x / PitLayout.STORE_A) * (s.x / PitLayout.STORE_A) + (s.z / PitLayout.STORE_B) * (s.z / PitLayout.STORE_B)
            assertTrue("store slot $i u=$u", u < 1f)
        }
        // The pile stays under the top of the pit collider (D17).
        assertTrue(PitLayout.pitSlots.maxOf { it.y } + PitLayout.STONE_H / 2 <= PitLayout.COLLIDER_CENTER_Y + PitLayout.COLLIDER_H / 2)
    }

    @Test fun every48StoneLayoutHasDistinctSlots() {
        val rnd = Random(9)
        repeat(200) {
            var p = Kalah.initial(if (rnd.nextBoolean()) Side.SOUTH else Side.NORTH)
            while (!p.isOver) {
                val legal = Kalah.legalMoves(p)
                p = Kalah.sow(p, legal[rnd.nextInt(legal.size)]).after
                val all = (0 until Kalah.CELLS).flatMap { c -> (0 until p.cells[c]).map { PitLayout.slot(c, it) } }
                assertEquals(48, all.size)
                assertEquals(48, all.toSet().size)
            }
        }
    }

    // D35: seat plate vs eye lines, 3 eye positions × 2 tilts × 0 / 12 / 24 stones.
    private fun world(p: P3, tiltDeg: Float): P3 {
        val a = Math.toRadians(tiltDeg.toDouble()).toFloat()
        val bz = p.z - PitLayout.BOARD_D / 2
        return P3(p.x, p.y * cos(a) - bz * sin(a), p.y * sin(a) + bz * cos(a))
    }

    @Test fun seatPlateNeverCrossesTheLineFromTheEyeToAPit() {
        // Eyes relative to the board's near top edge: sitting, standing, reclining (design panelGeometryCheck).
        val eyes = listOf(P3(0f, 0.45f, 0.48f), P3(0f, 0.90f, 0.38f), P3(0f, 0.30f, 0.68f), P3(-0.25f, 0.45f, 0.48f), P3(0.25f, 0.45f, 0.48f))
        for (tilt in listOf(0f, 15f)) {
            // The plate is upright: its centre follows the tilt, the card does not.
            val c = world(PitLayout.SEAT_PLATE, tilt)
            val x0 = c.x - PitLayout.SEAT_W / 2; val x1 = c.x + PitLayout.SEAT_W / 2
            val y0 = c.y - PitLayout.SEAT_H / 2; val y1 = c.y + PitLayout.SEAT_H / 2
            // Its corners are outside the board box: back in board space they sit well above the board top.
            val a = Math.toRadians(tilt.toDouble()).toFloat()
            for (dy in listOf(-PitLayout.SEAT_H / 2, PitLayout.SEAT_H / 2)) {
                val wy = c.y + dy
                val yBoard = wy * cos(a) + c.z * sin(a) // inverse rotation about X (pivot frame)
                assertTrue("plate corner above the board at tilt $tilt: $yBoard", yBoard > 0.06f)
            }
            for (eye in eyes) for (pit in (0..5) + (7..12)) for (n in listOf(0, 12, 24)) {
                val top = if (n == 0) PitLayout.center(pit) else PitLayout.slot(pit, n - 1)
                val t = world(top, tilt)
                if (t.z <= c.z) continue // the plate is in front of nothing it could hide
                val u = (c.z - eye.z) / (t.z - eye.z)
                if (u !in 0f..1f) continue
                val x = eye.x + (t.x - eye.x) * u
                val y = eye.y + (t.y - eye.y) * u
                assertFalse("tilt=$tilt eye=$eye pit=$pit n=$n crosses the plate at ($x, $y)", x in x0..x1 && y in y0..y1)
            }
        }
    }
}
