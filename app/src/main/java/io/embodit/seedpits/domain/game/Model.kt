package io.embodit.seedpits.domain.game

import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Position
import io.embodit.seedpits.domain.rules.Side
import io.embodit.seedpits.domain.rules.SowResult

enum class Language { SYSTEM, ZH, EN }

/** Volume steps of the three-stop slider. */
enum class Volume(val gain: Float) { LOW(0.45f), MID(0.75f), HIGH(1.0f) }

data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Volume = Volume.HIGH,
    val reducedMotion: Boolean = false,
    val showDigits: Boolean = true,
    val tiltDeg: Int = 15,
    val firstRunDone: Boolean = false,
    val coachDone: Boolean = false,
    val streak: Int = 0,
    val lastLevel: Int = 1,
)

enum class FirstMover { ME, THEM, RANDOM }

/** You always sit at the near row (SOUTH); the AI plays the far row (NORTH). */
data class GameConfig(val level: Level = Level.EASY, val first: FirstMover = FirstMover.ME)

/** Phases of the play page and the board (design-package dataModel.game.phase). */
enum class Phase { LOADING, WELCOME, SETUP, IDLE, PREVIEW, SOWING, AGAIN, THINKING, ENDED }

/** The four control-window pages (native TabBar). */
enum class Page { PLAY, NEW, RULES, SETTINGS }

enum class AlertKind { THEIRS, EMPTY, NOT_YOUR_TURN }

data class Alert(val kind: AlertKind, val pit: Int, val id: Int)

enum class Confirm { RESTART, RESIGN, EXIT, NEW_GAME }

/** A move being animated. The position is already committed; the board counts up through [SowResult.steps]. */
data class SowAnim(val id: Int, val result: SowResult, val byAi: Boolean)

data class GameResult(
    val winner: Side?,
    val mine: Int,
    val theirs: Int,
    val resigned: Boolean,
    val moves: Int,
    val durationMs: Long,
    val maxCapture: Int,
    val extraTurns: Int,
    val hints: Int,
    val undone: Boolean,
    val streak: Int,
    val lockedAtMove: Int?,
    val level: Level,
    val humanFirst: Boolean,
)

/** A saved, unfinished game (what the new-game page offers to continue). */
data class SavedGame(val config: GameConfig, val start: Position, val moves: List<Int>, val elapsedMs: Long, val hints: Int, val undone: Boolean, val seed: Long) {
    val position: Position get() = Kalah.replay(start, moves)
}

data class GameState(
    val phase: Phase = Phase.LOADING,
    val page: Page = Page.PLAY,
    val config: GameConfig = GameConfig(),
    val start: Position = Kalah.initial(),
    val moves: List<Int> = emptyList(),
    val position: Position = Kalah.initial(),
    val redo: List<List<Int>> = emptyList(),
    val previewPit: Int? = null,
    val hintOn: Boolean = false,
    val hintPit: Int? = null,
    val sow: SowAnim? = null,
    val animStep: Int = 0,
    val alert: Alert? = null,
    val result: GameResult? = null,
    val confirm: Confirm? = null,
    val moreOpen: Boolean = false,
    val saved: SavedGame? = null,
    val hints: Int = 0,
    val undone: Boolean = false,
    val extraTurns: Int = 0,
    val maxCapture: Int = 0,
    val lastFrom: Int? = null,
    val coachStep: Int = 0,
    val gameId: Int = 0,
    val thinkingSince: Long = 0L,
    val frozen: Boolean = false,
    val againSide: Side? = null,
    val startedAt: Long = 0L,
    /** The configuration waiting on the new-game confirmation (S02 → S13). */
    val pendingConfig: GameConfig? = null,
    val seed: Long = 1L,
) {
    /** A game with progress that a new game would throw away: moves made and not over (design S02 → S13). */
    val inProgress: Boolean get() = phase in setOf(Phase.IDLE, Phase.PREVIEW, Phase.SOWING, Phase.AGAIN, Phase.THINKING) && moves.isNotEmpty() && !position.isOver

    val humanTurn: Boolean get() = position.toMove == Side.SOUTH && !position.isOver

    /** The counts the board and the panel show: the animation cursor while sowing, else the committed position. */
    val shownCells: List<Int> get() = sow?.result?.countsAt(animStep) ?: position.cells

    /** Pits the player may tap right now (lit). */
    val litPits: List<Int> get() = if ((phase == Phase.IDLE || phase == Phase.PREVIEW) && humanTurn) Kalah.legalMoves(position) else emptyList()

    val canUndo: Boolean get() = (phase == Phase.IDLE || phase == Phase.PREVIEW || phase == Phase.THINKING) && UndoPlan.target(start, moves) != null
    val canRedo: Boolean get() = (phase == Phase.IDLE || phase == Phase.PREVIEW) && redo.isNotEmpty()
}

/** Undo goes back to the start of the player's previous turn (their whole extra-turn chain and the AI's reply). */
object UndoPlan {
    /** Number of moves to keep, or null when there is nothing of the player's to take back. */
    fun target(start: Position, moves: List<Int>): Int? {
        var p = start
        val turnStarts = ArrayList<Int>()
        var prevMover: Side? = null
        for ((i, m) in moves.withIndex()) {
            val mover = p.toMove
            if (mover == Side.SOUTH && prevMover != Side.SOUTH) turnStarts += i
            prevMover = mover
            p = Kalah.sow(p, m).after
        }
        // If it is the player's turn now and that turn started at the end, the previous start is what we undo to.
        return turnStarts.lastOrNull()
    }
}
