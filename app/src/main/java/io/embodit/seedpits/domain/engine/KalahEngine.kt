package io.embodit.seedpits.domain.engine

import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Position
import io.embodit.seedpits.domain.rules.Side
import kotlin.random.Random

/** The five AI levels of the design package (gameSpec.ai). */
enum class Level(val number: Int, val depth: Int, val timeMs: Long, val randomTop: Int) {
    EASY(1, 1, 0, 3),
    CASUAL(2, 2, 0, 2),
    SMART(3, 4, 300, 1),
    STRONG(4, 7, 800, 1),
    MASTER(5, 12, 1500, 1);

    companion object {
        fun of(n: Int): Level = entries.firstOrNull { it.number == n } ?: SMART
    }
}

/**
 * Negamax alpha-beta with iterative deepening and a time limit. An extra turn is a node where the same side
 * moves again (no sign flip). Works on a 14-int array; [Kalah] stays the reference implementation.
 * Deterministic for a given [Random].
 */
class KalahEngine(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile var cancelled = false
    var lastDepth = 0
        private set
    var lastNodes = 0L
        private set

    data class Scored(val pit: Int, val score: Double)

    /** Chooses a pit for the side to move at [level]. */
    fun choose(p: Position, level: Level, rng: Random, timeLimitMs: Long = level.timeMs): Int {
        val legal = Kalah.legalMoves(p)
        require(legal.isNotEmpty())
        if (legal.size == 1) return legal[0]
        return when (level) {
            Level.EASY -> {
                if (rng.nextDouble() < EASY_RANDOM) legal[rng.nextInt(legal.size)]
                else pickTop(scoreMoves(p, EASY_DEPTH, 0), EASY_TOP, rng)
            }
            Level.CASUAL -> pickTop(scoreMoves(p, 2, 0), 2, rng)
            else -> best(p, level.depth, timeLimitMs).pit
        }
    }

    private fun pickTop(scored: List<Scored>, n: Int, rng: Random): Int {
        val sorted = scored.sortedByDescending { it.score }
        val top = sorted.take(n)
        return top[rng.nextInt(top.size)].pit
    }

    /** Every legal move scored by a fixed-depth search (no time limit). */
    fun scoreMoves(p: Position, depth: Int, timeLimitMs: Long): List<Scored> {
        val b = p.cells.toIntArray()
        val side = if (p.toMove == Side.SOUTH) 0 else 1
        deadline = if (timeLimitMs > 0) clock() + timeLimitMs else Long.MAX_VALUE
        aborted = false
        return orderedMoves(b, side).map { pit ->
            val c = b.copyOf()
            val nextSide = play(c, side, pit)
            val v = if (nextSide == side) search(c, nextSide, depth - 1, NEG, POS) else -search(c, nextSide, depth - 1, NEG, POS)
            Scored(pit, v)
        }
    }

    /** Iterative deepening to [maxDepth] within [timeLimitMs] (0 = no limit). */
    fun best(p: Position, maxDepth: Int, timeLimitMs: Long): Scored {
        val b = p.cells.toIntArray()
        val side = if (p.toMove == Side.SOUTH) 0 else 1
        deadline = if (timeLimitMs > 0) clock() + timeLimitMs else Long.MAX_VALUE
        aborted = false
        nodes = 0
        var bestSoFar: Scored? = null
        var order = orderedMoves(b, side)
        for (d in 1..maxDepth) {
            var alpha = NEG
            var bestPit = order[0]
            var bestScore = NEG
            val scores = HashMap<Int, Double>()
            for (pit in order) {
                val c = b.copyOf()
                val ns = play(c, side, pit)
                val v = if (ns == side) search(c, ns, d - 1, alpha, POS) else -search(c, ns, d - 1, -POS, -alpha)
                if (aborted) break
                scores[pit] = v
                if (v > bestScore) { bestScore = v; bestPit = pit }
                if (v > alpha) alpha = v
            }
            if (aborted) break
            bestSoFar = Scored(bestPit, bestScore)
            lastDepth = d
            order = order.sortedByDescending { scores[it] ?: NEG }
            if (bestScore >= WIN - 50) break
        }
        lastNodes = nodes
        return bestSoFar ?: Scored(order[0], 0.0)
    }

    private var deadline = Long.MAX_VALUE
    private var aborted = false
    private var nodes = 0L

    private fun search(b: IntArray, side: Int, depth: Int, alphaIn: Double, beta: Double): Double {
        nodes++
        if ((nodes and 1023L) == 0L && (cancelled || clock() > deadline)) aborted = true
        if (aborted) return 0.0
        if (isOver(b)) return terminal(b, side)
        if (depth <= 0) return evaluate(b, side)
        var alpha = alphaIn
        var best = NEG
        for (pit in orderedMoves(b, side)) {
            val c = b.copyOf()
            val ns = play(c, side, pit)
            val v = if (ns == side) search(c, ns, depth - 1, alpha, beta) else -search(c, ns, depth - 1, -beta, -alpha)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    companion object {
        const val WIN = 100.0

        /** Easy = depth 1, 30 % random, else a random pick of the best [EASY_TOP] (design said 3; 2 keeps the baseline bot at 40–60 %). */
        @JvmStatic var EASY_RANDOM = 0.3
        @JvmStatic var EASY_DEPTH = 1
        @JvmStatic var EASY_TOP = 2
        private const val NEG = -1e9
        private const val POS = 1e9

        private fun store(side: Int) = if (side == 0) 6 else 13
        private fun first(side: Int) = if (side == 0) 0 else 7

        fun isOver(b: IntArray): Boolean {
            var s = 0; var n = 0
            for (i in 0..5) s += b[i]
            for (i in 7..12) n += b[i]
            return s == 0 || n == 0
        }

        /** Plays [pit] in place (with capture and sweep) and returns the side to move next. */
        fun play(b: IntArray, side: Int, pit: Int): Int {
            val own = store(side)
            val skip = store(1 - side)
            var stones = b[pit]
            b[pit] = 0
            var at = pit
            while (stones > 0) {
                at = (at + 1) % 14
                if (at == skip) continue
                b[at]++
                stones--
            }
            val f = first(side)
            if (at != own && at in f..f + 5 && b[at] == 1) {
                val across = 12 - at
                if (b[across] > 0) { b[own] += b[across] + 1; b[across] = 0; b[at] = 0 }
            }
            if (isOver(b)) {
                for (i in 0..5) { b[6] += b[i]; b[i] = 0 }
                for (i in 7..12) { b[13] += b[i]; b[i] = 0 }
                return 1 - side
            }
            return if (at == own) side else 1 - side
        }

        private fun landing(side: Int, pit: Int, n: Int): Int {
            val skip = store(1 - side)
            var at = pit
            var s = n
            while (s > 0) { at = (at + 1) % 14; if (at == skip) continue; s-- }
            return at
        }

        /** Capture size if [side] plays [pit] (one lap only), else 0. */
        private fun captureOf(b: IntArray, side: Int, pit: Int): Int {
            val n = b[pit]
            if (n == 0 || n >= 13) return 0
            val land = landing(side, pit, n)
            val f = first(side)
            if (land !in f..f + 5 || b[land] != 0) return 0
            val across = 12 - land
            val acrossCount = b[across] + (if (land < pit) 1 else 0)
            return if (acrossCount > 0) acrossCount + 1 else 0
        }

        /** Extra-turn moves first, then captures, then the rest (closest to the store first). */
        fun orderedMoves(b: IntArray, side: Int): List<Int> {
            val f = first(side)
            val own = store(side)
            val extra = ArrayList<Int>(6); val cap = ArrayList<Int>(6); val rest = ArrayList<Int>(6)
            for (pit in f + 5 downTo f) {
                val n = b[pit]
                if (n == 0) continue
                when {
                    n % 13 == own - pit -> extra += pit
                    captureOf(b, side, pit) > 0 -> cap += pit
                    else -> rest += pit
                }
            }
            return extra + cap + rest
        }

        fun terminal(b: IntArray, side: Int): Double {
            val mine = b[store(side)] + (first(side)..first(side) + 5).sumOf { b[it] }
            val theirs = b[store(1 - side)] + (first(1 - side)..first(1 - side) + 5).sumOf { b[it] }
            val diff = (mine - theirs).toDouble()
            return when {
                mine > theirs -> WIN + diff
                mine < theirs -> -WIN + diff
                else -> 0.0
            }
        }

        /** gameSpec.ai.eval, from [side]'s point of view. */
        fun evaluate(b: IntArray, side: Int): Double {
            val o = 1 - side
            val storeDiff = b[store(side)] - b[store(o)]
            var mine = 0; var theirs = 0
            for (i in first(side)..first(side) + 5) mine += b[i]
            for (i in first(o)..first(o) + 5) theirs += b[i]
            var extras = 0
            for (pit in first(side)..first(side) + 5) if (b[pit] > 0 && b[pit] % 13 == store(side) - pit) extras++
            return storeDiff + 0.3 * (mine - theirs) + 0.5 * extras - 0.4 * maxCapture(b, o)
        }

        /** The largest capture [side] could make with one move right now. */
        fun maxCapture(b: IntArray, side: Int): Int {
            var best = 0
            val f = first(side)
            for (pit in f..f + 5) best = maxOf(best, captureOf(b, side, pit))
            return best
        }
    }
}

/** Baseline bot for the acceptance test (gameSpec.ai.baselineBot): extra turn > biggest capture > leftmost. */
object BaselineBot {
    fun choose(p: Position): Int {
        val legal = Kalah.legalMoves(p)
        legal.firstOrNull { Kalah.sow(p, it).extraTurn }?.let { return it }
        val caps = legal.mapNotNull { m -> Kalah.sow(p, m).capture?.let { m to it.total } }
        if (caps.isNotEmpty()) return caps.maxBy { it.second }.first
        return legal.first()
    }
}
