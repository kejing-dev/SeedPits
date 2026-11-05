package io.embodit.seedpits.domain.rules

/**
 * Kalah (6 pits, 4 stones). Cells are indexed counterclockwise as seen from the player:
 *
 * ```
 *        12  11  10   9   8   7        far row, the opponent's pits F E D C B A (right to left)
 *   13                            6    stores: 13 = opponent (left), 6 = yours (right)
 *         0   1   2   3   4   5        near row, your pits a b c d e f (left to right)
 * ```
 * Sowing goes index + 1, skipping the opponent's store. Pure Kotlin, no Android.
 */
enum class Side {
    SOUTH, NORTH;

    val other: Side get() = if (this == SOUTH) NORTH else SOUTH
}

object Kalah {
    const val PITS = 6
    const val CELLS = 14
    const val SOUTH_STORE = 6
    const val NORTH_STORE = 13
    const val START = 4
    const val TOTAL = 48

    fun store(side: Side): Int = if (side == Side.SOUTH) SOUTH_STORE else NORTH_STORE
    fun pits(side: Side): IntRange = if (side == Side.SOUTH) 0..5 else 7..12
    fun isStore(cell: Int): Boolean = cell == SOUTH_STORE || cell == NORTH_STORE
    fun owner(cell: Int): Side = if (cell <= SOUTH_STORE) Side.SOUTH else Side.NORTH
    fun opposite(pit: Int): Int = 12 - pit

    /** a–f for your pits (0..5), A–F for theirs (7..12, A next to your store). */
    fun letter(pit: Int): Char = when (pit) {
        in 0..5 -> 'a' + pit
        in 7..12 -> 'A' + (pit - 7)
        else -> '?'
    }

    fun pitOf(letter: Char): Int? = when (letter) {
        in 'a'..'f' -> letter - 'a'
        in 'A'..'F' -> 7 + (letter - 'A')
        else -> null
    }

    fun initial(toMove: Side = Side.SOUTH): Position =
        Position(List(CELLS) { if (isStore(it)) 0 else START }, toMove)

    fun legalMoves(p: Position): List<Int> = if (p.isOver) emptyList() else pits(p.toMove).filter { p.cells[it] > 0 }

    fun isLegal(p: Position, pit: Int): Boolean = !p.isOver && pit in pits(p.toMove) && p.cells[pit] > 0

    /** Plays [pit] for the side to move. The result carries every stone's landing cell, in order, for the animation. */
    fun sow(p: Position, pit: Int): SowResult {
        require(isLegal(p, pit)) { "illegal move ${letter(pit)} in $p" }
        val side = p.toMove
        val cells = p.cells.toIntArray()
        val skip = store(side.other)
        var stones = cells[pit]
        cells[pit] = 0
        val drops = ArrayList<Int>(stones)
        var at = pit
        while (stones > 0) {
            at = (at + 1) % CELLS
            if (at == skip) continue
            cells[at]++
            drops += at
            stones--
        }
        val last = at
        val extra = last == store(side)
        var capture: Capture? = null
        if (!extra && last in pits(side) && cells[last] == 1) {
            val across = opposite(last)
            if (cells[across] > 0) {
                val taken = cells[across]
                cells[store(side)] += taken + 1
                cells[across] = 0
                cells[last] = 0
                capture = Capture(landing = last, across = across, fromAcross = taken, store = store(side))
            }
        }
        // Either row empty after the move: the other side sweeps its own row into its own store.
        var sweeps: List<Sweep> = emptyList()
        val southEmpty = pits(Side.SOUTH).all { cells[it] == 0 }
        val northEmpty = pits(Side.NORTH).all { cells[it] == 0 }
        if (southEmpty || northEmpty) {
            val list = ArrayList<Sweep>()
            for (s in Side.entries) {
                val taken = pits(s).filter { cells[it] > 0 }.map { it to cells[it] }
                if (taken.isNotEmpty()) {
                    for ((c, _) in taken) cells[c] = 0
                    cells[store(s)] += taken.sumOf { it.second }
                    list += Sweep(s, taken, store(s))
                }
            }
            sweeps = list
        }
        val over = southEmpty || northEmpty
        val next = if (extra && !over) side else side.other
        val after = Position(cells.toList(), next)
        return SowResult(side = side, from = pit, drops = drops, extraTurn = extra && !over, capture = capture, sweeps = sweeps, before = p, after = after)
    }

    /** Replays a move list from [start]. */
    fun replay(start: Position, moves: List<Int>): Position {
        var p = start
        for (m in moves) p = sow(p, m).after
        return p
    }
}

data class Position(val cells: List<Int>, val toMove: Side) {
    init {
        require(cells.size == Kalah.CELLS)
    }

    val southStore: Int get() = cells[Kalah.SOUTH_STORE]
    val northStore: Int get() = cells[Kalah.NORTH_STORE]
    fun store(side: Side): Int = cells[Kalah.store(side)]
    fun rowTotal(side: Side): Int = Kalah.pits(side).sumOf { cells[it] }

    /** The game is over once either row is empty (the sweep has then already happened). */
    val isOver: Boolean get() = rowTotal(Side.SOUTH) == 0 || rowTotal(Side.NORTH) == 0

    /** Winner when over; null for a draw or while the game runs. */
    val winner: Side? get() = when {
        !isOver -> null
        southStore > northStore -> Side.SOUTH
        northStore > southStore -> Side.NORTH
        else -> null
    }

    override fun toString(): String = cells.joinToString(",") + "/" + (if (toMove == Side.SOUTH) "S" else "N")

    companion object {
        /** "4,4,4,4,4,4,0,4,4,4,4,4,4,0/S" or 14 numbers with an optional side. */
        fun parse(text: String): Position? {
            val parts = text.trim().split("/", ";", " ").filter { it.isNotEmpty() }
            val nums = parts.firstOrNull()?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (nums.size != Kalah.CELLS || nums.any { it < 0 }) return null
            val side = if (parts.getOrNull(1)?.uppercase()?.startsWith("N") == true) Side.NORTH else Side.SOUTH
            return Position(nums, side)
        }
    }
}

data class Capture(val landing: Int, val across: Int, val fromAcross: Int, val store: Int) {
    /** Stones that go into the store: the ones across plus the landing stone. */
    val total: Int get() = fromAcross + 1
}

data class Sweep(val side: Side, val taken: List<Pair<Int, Int>>, val store: Int) {
    val total: Int get() = taken.sumOf { it.second }
}

/**
 * One played move. [drops] lists the landing cell of each stone in order. The panel and the digit tiles count
 * up through [steps]; after the last step the counts equal [after] (the committed position).
 */
data class SowResult(
    val side: Side,
    val from: Int,
    val drops: List<Int>,
    val extraTurn: Boolean,
    val capture: Capture?,
    val sweeps: List<Sweep>,
    val before: Position,
    val after: Position,
) {
    val last: Int get() = drops.last()

    /** Animation steps: 0 = nothing yet, 1 = grabbed, 1 + k = k stones dropped, then capture, then each sweep. */
    val stepCount: Int get() = 1 + drops.size + (if (capture != null) 1 else 0) + sweeps.size

    fun countsAt(step: Int): List<Int> {
        val c = before.cells.toIntArray()
        if (step <= 0) return c.toList()
        c[from] = 0
        val dropsDone = (step - 1).coerceAtMost(drops.size)
        for (i in 0 until dropsDone) c[drops[i]]++
        var s = step - 1 - drops.size
        if (s > 0 && capture != null) {
            c[capture.store] += c[capture.across] + c[capture.landing]
            c[capture.across] = 0
            c[capture.landing] = 0
            s--
        }
        for (sw in sweeps) {
            if (s <= 0) break
            for ((cell, _) in sw.taken) { c[sw.store] += c[cell]; c[cell] = 0 }
            s--
        }
        return c.toList()
    }
}
