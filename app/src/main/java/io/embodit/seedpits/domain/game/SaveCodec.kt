package io.embodit.seedpits.domain.game

import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Position

/** The saved game as `key=value` lines. Decoding replays the moves, so a corrupt list is rejected. */
object SaveCodec {
    fun encode(g: SavedGame): String = buildString {
        append("v=1\n")
        append("level=").append(g.config.level.number).append('\n')
        append("firstChoice=").append(g.config.first.name).append('\n')
        append("start=").append(g.start.toString()).append('\n')
        append("moves=").append(g.moves.joinToString(",")).append('\n')
        append("elapsed=").append(g.elapsedMs).append('\n')
        append("hints=").append(g.hints).append('\n')
        append("undone=").append(if (g.undone) 1 else 0).append('\n')
        append("seed=").append(g.seed).append('\n')
    }

    fun decode(text: String?): SavedGame? {
        if (text.isNullOrBlank()) return null
        val map = text.lines().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()
        if (map["v"] != "1") return null
        val level = map["level"]?.toIntOrNull()?.let { Level.of(it) } ?: return null
        val firstChoice = runCatching { FirstMover.valueOf(map["firstChoice"] ?: "ME") }.getOrDefault(FirstMover.ME)
        val start = map["start"]?.let { Position.parse(it) } ?: return null
        if (start.cells.sum() != Kalah.TOTAL) return null
        val moves = map["moves"].orEmpty().split(",").filter { it.isNotBlank() }.map { it.trim().toIntOrNull() ?: return null }
        // Validate by replaying.
        var p = start
        for (m in moves) {
            if (!Kalah.isLegal(p, m)) return null
            p = Kalah.sow(p, m).after
        }
        return SavedGame(
            config = GameConfig(level, firstChoice),
            start = start,
            moves = moves,
            elapsedMs = map["elapsed"]?.toLongOrNull() ?: 0L,
            hints = map["hints"]?.toIntOrNull() ?: 0,
            undone = map["undone"] == "1",
            seed = map["seed"]?.toLongOrNull() ?: 1L,
        )
    }
}
