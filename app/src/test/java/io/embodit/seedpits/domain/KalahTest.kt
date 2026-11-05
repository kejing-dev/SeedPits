package io.embodit.seedpits.domain

import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Position
import io.embodit.seedpits.domain.rules.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KalahTest {
    private fun pos(vararg c: Int, side: Side = Side.SOUTH) = Position(c.toList(), side)

    @Test fun counterclockwiseSequenceFromEveryPit() {
        for (side in Side.entries) for (pit in Kalah.pits(side)) {
            val p = Kalah.initial(side)
            val r = Kalah.sow(p, pit)
            assertEquals(4, r.drops.size)
            var at = pit
            for (d in r.drops) {
                at = (at + 1) % 14
                if (at == Kalah.store(side.other)) at = (at + 1) % 14
                assertEquals(at, d)
            }
            assertEquals(48, r.after.cells.sum())
        }
    }

    @Test fun skipsOpponentStore() {
        // 13 stones in f: goes all the way round, never into the north store.
        val p = pos(0, 0, 0, 0, 0, 13, 0, 1, 1, 1, 1, 1, 1, 0)
        val r = Kalah.sow(p, 5)
        assertFalse(r.drops.contains(Kalah.NORTH_STORE))
        assertEquals(13, r.drops.size)
        assertEquals(5, r.last) // full lap lands back in f
    }

    @Test fun lastInStoreGivesExtraTurnAndChains() {
        val p = pos(1, 0, 0, 0, 2, 1, 0, 4, 4, 4, 4, 4, 4, 0)
        val r1 = Kalah.sow(p, 5)
        assertTrue(r1.extraTurn)
        assertEquals(Side.SOUTH, r1.after.toMove)
        val r2 = Kalah.sow(r1.after, 4)
        assertTrue(r2.extraTurn)
        assertEquals(Side.SOUTH, r2.after.toMove)
        // a third link: e(4) had 2 → after r2 f has 1
        val r3 = Kalah.sow(r2.after, 5)
        assertTrue(r3.extraTurn)
    }

    @Test fun captureWhenAcrossNotEmpty() {
        // a has 1, b empty, across b is E (11) with 5.
        val p = pos(1, 0, 3, 3, 3, 3, 0, 4, 4, 4, 4, 5, 4, 0)
        val r = Kalah.sow(p, 0)
        val c = assertNotNull(r.capture).let { r.capture!! }
        assertEquals(1, c.landing)
        assertEquals(11, c.across)
        assertEquals(6, c.total)
        assertEquals(6, r.after.southStore)
        assertEquals(0, r.after.cells[1])
        assertEquals(0, r.after.cells[11])
    }

    @Test fun noCaptureWhenAcrossEmpty() {
        val p = pos(1, 0, 3, 3, 3, 3, 0, 4, 4, 4, 4, 0, 4, 9)
        val r = Kalah.sow(p, 0)
        assertNull(r.capture)
        assertEquals(1, r.after.cells[1])
        assertEquals(Side.NORTH, r.after.toMove)
    }

    @Test fun sweepWhenSouthRowEmpties() {
        val p = pos(0, 0, 0, 0, 0, 1, 20, 2, 3, 4, 5, 6, 7, 0)
        val r = Kalah.sow(p, 5)
        assertTrue(r.after.isOver)
        assertFalse(r.extraTurn) // game over: no extra turn
        assertEquals(1, r.sweeps.size)
        assertEquals(Side.NORTH, r.sweeps[0].side)
        assertEquals(27, r.after.northStore)
        assertEquals(21, r.after.southStore)
        assertEquals(Side.NORTH, r.after.winner)
    }

    @Test fun sweepWhenNorthRowEmptiesAfterSouthMove() {
        // South captures the last north stones: north row empties, south sweeps its own row.
        val p = pos(1, 0, 2, 0, 0, 0, 20, 0, 0, 0, 0, 3, 0, 22)
        val r = Kalah.sow(p, 0)
        assertNotNull(r.capture)
        assertTrue(r.after.isOver)
        assertEquals(Side.SOUTH, r.sweeps.single().side)
        assertEquals(26, r.after.southStore)
    }

    @Test fun drawAt24() {
        val p = pos(0, 0, 0, 0, 0, 1, 23, 1, 0, 0, 0, 0, 0, 23)
        val r = Kalah.sow(p, 5)
        assertTrue(r.after.isOver)
        assertEquals(24, r.after.southStore)
        assertEquals(24, r.after.northStore)
        assertNull(r.after.winner)
    }

    @Test fun animationCountsEndAtCommittedPosition() {
        val rnd = kotlin.random.Random(7)
        repeat(300) {
            var p = Kalah.initial(if (rnd.nextBoolean()) Side.SOUTH else Side.NORTH)
            while (!p.isOver) {
                val moves = Kalah.legalMoves(p)
                val r = Kalah.sow(p, moves[rnd.nextInt(moves.size)])
                assertEquals(r.after.cells, r.countsAt(r.stepCount))
                assertEquals(p.cells, r.countsAt(0))
                for (k in 0..r.stepCount) assertEquals(48, r.countsAt(k).sum() + (if (k in 1..r.drops.size) r.drops.size - (k - 1) else 0))
                p = r.after
            }
        }
    }

    @Test fun perftDepth6() {
        fun perft(p: Position, d: Int): Long {
            if (d == 0 || p.isOver) return 1
            return Kalah.legalMoves(p).sumOf { perft(Kalah.sow(p, it).after, d - 1) }
        }
        // Reference: an independent array implementation (engine play) must agree node for node.
        fun perftArr(b: IntArray, side: Int, d: Int): Long {
            if (d == 0 || io.embodit.seedpits.domain.engine.KalahEngine.isOver(b)) return 1
            var n = 0L
            val f = if (side == 0) 0 else 7
            for (pit in f..f + 5) if (b[pit] > 0) { val c = b.copyOf(); val ns = io.embodit.seedpits.domain.engine.KalahEngine.play(c, side, pit); n += perftArr(c, ns, d - 1) }
            return n
        }
        val p = Kalah.initial()
        for (d in 1..6) assertEquals(perftArr(p.cells.toIntArray(), 0, d), perft(p, d))
        assertEquals(6, perft(p, 1))
    }

    @Test fun lettersRoundTrip() {
        for (i in (0..5) + (7..12)) assertEquals(i, Kalah.pitOf(Kalah.letter(i)))
        assertEquals(12, Kalah.opposite(0)) // a ↔ F
        assertEquals('F', Kalah.letter(12))
        assertEquals('A', Kalah.letter(7))
    }
}
