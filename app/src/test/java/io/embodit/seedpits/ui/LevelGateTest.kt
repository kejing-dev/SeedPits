package io.embodit.seedpits.ui

import io.embodit.seedpits.ui.window.LevelGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelGateTest {
    @Test fun opensAfterDwellWhenLevel() {
        val g = LevelGate()
        g.ask("launch", 0)
        assertNull(g.step(0, 2f))
        assertNull(g.step(200, 2f))
        assertEquals("launch", g.step(320, 1f))
    }

    @Test fun waitsWhileTippedAndHints() {
        val g = LevelGate()
        g.ask("launch", 0)
        for (t in 0..5000 step 100) assertNull(g.step(t.toLong(), -30f))
        assertTrue(g.hintWanted(-30f))
        assertNull(g.step(5100, 0f))
        assertEquals("launch", g.step(5450, 0f))
    }

    @Test fun noHeadFallsBackAfterFiveSeconds() {
        val g = LevelGate()
        g.ask("recall", 0)
        assertNull(g.step(100, null))
        assertNull(g.step(5000, null))
        assertEquals("recall/nohead", g.step(5101, null))
    }

    @Test fun tippingResetsDwell() {
        val g = LevelGate()
        g.ask("x", 0)
        assertNull(g.step(0, 0f))
        assertNull(g.step(250, 20f))
        assertNull(g.step(300, 0f))
        assertNull(g.step(550, 0f))
        assertEquals("x", g.step(610, 0f))
    }
}
