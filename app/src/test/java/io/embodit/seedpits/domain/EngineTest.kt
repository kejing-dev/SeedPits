package io.embodit.seedpits.domain

import io.embodit.seedpits.domain.engine.BaselineBot
import io.embodit.seedpits.domain.engine.KalahEngine
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Position
import io.embodit.seedpits.domain.rules.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EngineTest {
    /** Plays one game; returns +1 if [south] wins, -1 if [north] wins, 0 draw. Opening: [randomPlies] random moves. */
    private fun game(south: (Position, Random) -> Int, north: (Position, Random) -> Int, seed: Int, firstSouth: Boolean, randomPlies: Int = 2): Int {
        val rnd = Random(seed)
        var p = Kalah.initial(if (firstSouth) Side.SOUTH else Side.NORTH)
        var ply = 0
        while (!p.isOver) {
            val legal = Kalah.legalMoves(p)
            val m = if (ply < randomPlies) legal[rnd.nextInt(legal.size)] else if (p.toMove == Side.SOUTH) south(p, rnd) else north(p, rnd)
            p = Kalah.sow(p, m).after
            ply++
        }
        return when (p.winner) { Side.SOUTH -> 1; Side.NORTH -> -1; else -> 0 }
    }

    private fun ai(level: Level, fixedDepth: Int? = null): (Position, Random) -> Int = { p, r ->
        if (fixedDepth != null) KalahEngine().best(p, fixedDepth, 0).pit else KalahEngine().choose(p, level, r, timeLimitMs = 0)
    }

    private fun rate(a: (Position, Random) -> Int, b: (Position, Random) -> Int, games: Int): Double {
        var score = 0.0
        for (g in 0 until games) {
            val first = g % 2 == 0
            val r = game(a, b, 1000 + g, first)
            score += when (r) { 1 -> 1.0; 0 -> 0.5; else -> 0.0 }
        }
        return score / games
    }

    @Test fun easyIsBeatableByTheBaselineBot() {
        val bot: (Position, Random) -> Int = { p, _ -> BaselineBot.choose(p) }
        val botVsEasy = rate(bot, ai(Level.EASY), 400)
        println("baseline vs easy: $botVsEasy")
        assertTrue("baseline bot should win 40–60 % against Easy, got $botVsEasy", botVsEasy in 0.40..0.60)
    }

    @Test fun levelsAreMonotonic() {
        val casualVsEasy = rate(ai(Level.CASUAL), ai(Level.EASY), 60)
        val smartVsCasual = rate(ai(Level.SMART, 4), ai(Level.CASUAL), 60)
        val strongVsSmart = rate(ai(Level.STRONG, 7), ai(Level.SMART, 4), 40)
        println("casual>easy $casualVsEasy smart>casual $smartVsCasual strong>smart $strongVsSmart")
        assertTrue(casualVsEasy >= 0.70)
        assertTrue(smartVsCasual >= 0.70)
        assertTrue(strongVsSmart >= 0.70)
    }

    @Test fun masterBeatsSmart() {
        val r = rate(ai(Level.MASTER, 10), ai(Level.SMART, 4), 40)
        println("master(d10) vs smart: $r")
        assertTrue(r >= 0.85)
    }

    @Test fun masterTakesTheObviousExtraTurnAndCapture() {
        // f has 1: free extra turn.
        val p = Position(listOf(4, 4, 4, 4, 4, 1, 0, 4, 4, 4, 4, 4, 4, 3), Side.SOUTH)
        assertEquals(5, KalahEngine().best(p, 6, 0).pit)
    }

    @Test fun masterStaysWithinTimeLimit() {
        val e = KalahEngine()
        val t0 = System.currentTimeMillis()
        e.choose(Kalah.initial(), Level.MASTER, Random(1))
        val took = System.currentTimeMillis() - t0
        println("master opening: ${took}ms depth=${e.lastDepth} nodes=${e.lastNodes}")
        assertTrue(took < 1700)
    }
}
