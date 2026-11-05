package io.embodit.seedpits.domain.game

import io.embodit.seedpits.data.GameRepository
import io.embodit.seedpits.data.SettingsRepository
import io.embodit.seedpits.domain.engine.KalahEngine
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Position
import io.embodit.seedpits.domain.rules.Side
import io.embodit.seedpits.domain.rules.SowResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Sounds the flow itself asks for (the board plays the per-stone ones). */
enum class Cue { ILLEGAL, AGAIN, WIN, LOSE, DRAW }

/**
 * Turn sequencing for one game against the AI: preview → sow (committed and saved before the animation) →
 * extra turn / capture / sweep → the other side. The board reports animation progress and completion;
 * a watchdog finishes a sow the board never reports. Debug commands call the same functions as real input.
 * Main-thread only, except the search, which runs on [engineDispatcher].
 */
class GameFlow(
    private val repo: GameRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
    var cue: (Cue) -> Unit = {},
) {
    /** Set by the app: release and end the process (D26). Debug `yes` and the panel both go through confirmYes. */
    var onExit: () -> Unit = {}

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state
    val engine = KalahEngine()

    private var sowCounter = 0
    private var alertCounter = 0
    private var aiJob: Job? = null
    private var alertJob: Job? = null
    private var againJob: Job? = null
    private var watchdogJob: Job? = null
    var lastThinkMs: Long = 0L
        private set

    private fun set(transform: (GameState) -> GameState) { _state.value = transform(_state.value) }
    private val s: GameState get() = _state.value

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (s.phase != Phase.LOADING) return
        val saved = SaveCodec.decode(repo.load())?.takeIf { !it.position.isOver }
        set { it.copy(saved = saved) }
        when {
            !settings.settings.value.firstRunDone -> {
                set { it.copy(phase = Phase.WELCOME, page = Page.RULES) }
                log("GAME welcome")
            }
            saved != null -> restore(saved)
            else -> {
                set { it.copy(phase = Phase.SETUP, page = Page.NEW, config = GameConfig(Level.of(settings.settings.value.lastLevel))) }
                log("GAME setup")
            }
        }
    }

    /** S01 [开始]: the rules have been seen; go to the new-game page. */
    fun welcomeDone() {
        settings.update { it.copy(firstRunDone = true) }
        set { it.copy(phase = Phase.SETUP, page = Page.NEW) }
        log("GAME setup (welcome done)")
    }

    fun startGame(config: GameConfig, startPosition: Position? = null) {
        cancelJobs()
        val seed = clock() xor 0x5EED
        val firstSide = when (config.first) {
            FirstMover.ME -> Side.SOUTH
            FirstMover.THEM -> Side.NORTH
            FirstMover.RANDOM -> if (Random(seed).nextBoolean()) Side.SOUTH else Side.NORTH
        }
        val start = startPosition ?: Kalah.initial(firstSide)
        settings.update { it.copy(lastLevel = config.level.number) }
        set {
            GameState(
                phase = Phase.IDLE, page = Page.PLAY, config = config, start = start, moves = emptyList(), position = start,
                hintOn = false, saved = null, gameId = it.gameId + 1, startedAt = clock(), seed = seed,
                coachStep = if (settings.settings.value.coachDone) 3 else 0, frozen = it.frozen,
            )
        }
        log("GAME new level=${config.level} first=${start.toMove} pos=$start")
        persist()
        enterTurn()
    }

    fun continueSaved() {
        val saved = s.saved ?: return
        restore(saved)
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        val pos = saved.position
        set {
            it.copy(
                phase = Phase.IDLE, page = Page.PLAY, config = saved.config, start = saved.start, moves = saved.moves, position = pos,
                redo = emptyList(), previewPit = null, sow = null, animStep = 0, alert = null, result = null, confirm = null,
                hints = saved.hints, undone = saved.undone, saved = null, gameId = it.gameId + 1, startedAt = clock() - saved.elapsedMs,
                seed = saved.seed, coachStep = 3, lastFrom = saved.moves.lastOrNull(),
            )
        }
        log("GAME restored moves=${saved.moves.size} pos=$pos")
        enterTurn()
    }

    /** Background: stop thinking (the state stays THINKING and restarts on [resume]). */
    fun pause() {
        aiJob?.cancel(); aiJob = null
        engine.cancelled = true
        persist()
        log("GAME paused phase=${s.phase}")
    }

    fun resume() {
        engine.cancelled = false
        if (s.phase == Phase.THINKING && aiJob == null) think()
        log("GAME resumed phase=${s.phase}")
    }

    // ---------------------------------------------------------------- input

    /** A tap on a pit collider (0..5 yours, 7..12 theirs). Same path for pinch, trigger and debug `tap`. */
    fun tapPit(pit: Int) {
        val st = s
        when (st.phase) {
            Phase.THINKING -> { alert(AlertKind.NOT_YOUR_TURN, pit); return }
            Phase.IDLE, Phase.PREVIEW -> Unit
            else -> { log("TAP ignored pit=${Kalah.letter(pit)} phase=${st.phase}"); return }
        }
        if (pit !in Kalah.pits(Side.SOUTH)) {
            if (pit in Kalah.pits(Side.NORTH)) alert(AlertKind.THEIRS, pit)
            return
        }
        if (st.position.cells[pit] == 0) { alert(AlertKind.EMPTY, pit); return }
        if (st.phase == Phase.PREVIEW && st.previewPit == pit) { sow(pit, byAi = false); return }
        set { it.copy(phase = Phase.PREVIEW, previewPit = pit, alert = null, moreOpen = false, coachStep = if (it.coachStep == 0) 1 else it.coachStep) }
        log("PREVIEW pit=${Kalah.letter(pit)} ${describe(Kalah.sow(st.position, pit))}")
    }

    /** A tap on the board but not on a pit, or [取消]: leave the preview. */
    fun cancelPreview() {
        if (s.phase != Phase.PREVIEW) return
        set { it.copy(phase = Phase.IDLE, previewPit = null) }
        log("PREVIEW cancel")
    }

    /** [播] on the panel. */
    fun confirmSow() {
        val pit = s.previewPit ?: return
        if (s.phase == Phase.PREVIEW) sow(pit, byAi = false)
    }

    // ---------------------------------------------------------------- sowing

    private fun sow(pit: Int, byAi: Boolean) {
        val st = s
        if (!Kalah.isLegal(st.position, pit)) { log("SOW rejected ${Kalah.letter(pit)}"); return }
        val result = Kalah.sow(st.position, pit)
        val id = ++sowCounter
        val human = result.side == Side.SOUTH
        set {
            it.copy(
                phase = Phase.SOWING, moves = it.moves + pit, position = result.after, redo = if (human) emptyList() else it.redo,
                previewPit = null, sow = SowAnim(id, result, byAi), animStep = 0, alert = null, moreOpen = false, lastFrom = pit,
                extraTurns = it.extraTurns + if (human && result.extraTurn) 1 else 0,
                maxCapture = if (human) maxOf(it.maxCapture, result.capture?.total ?: 0) else it.maxCapture,
                coachStep = if (human && it.coachStep < 3) 3 else it.coachStep,
                hintPit = null,
            )
        }
        if (human && !settings.settings.value.coachDone) settings.update { it.copy(coachDone = true) }
        persist()
        log("SOW id=$id side=${result.side} pit=${Kalah.letter(pit)} ${describe(result)} after=${result.after}")
        watchdogJob?.cancel()
        val budget = expectedMs(result, byAi) + 2500
        watchdogJob = scope.launch {
            delay(budget)
            if (s.sow?.id == id) { log("SOW watchdog id=$id"); onSowDone(id) }
        }
    }

    /** How long the board's animation should take (design: grab 200 ms + 110 ms a stone, AI × 0.65). */
    fun expectedMs(r: SowResult, byAi: Boolean): Long {
        if (settings.settings.value.reducedMotion) return 150
        val per = if (byAi) 70L else 110L
        return 200 + per * r.drops.size + (if (r.capture != null) 400 else 0) + (if (r.sweeps.isNotEmpty()) 600 else 0) + 150
    }

    fun onSowStep(id: Int, step: Int) {
        val st = s
        if (st.sow?.id != id || step <= st.animStep) return
        set { it.copy(animStep = step.coerceAtMost(it.sow?.result?.stepCount ?: step)) }
    }

    fun onSowDone(id: Int) {
        val st = s
        val anim = st.sow ?: return
        if (anim.id != id) return
        watchdogJob?.cancel(); watchdogJob = null
        set { it.copy(sow = null, animStep = 0) }
        log("SOW done id=$id")
        when {
            s.position.isOver -> end(resigned = false)
            anim.result.extraTurn -> {
                set { it.copy(phase = Phase.AGAIN, againSide = anim.result.side) }
                cue(Cue.AGAIN)
                log("AGAIN side=${anim.result.side}")
                againJob?.cancel()
                againJob = scope.launch {
                    delay(AGAIN_MS)
                    while (s.frozen) delay(100)
                    if (s.phase == Phase.AGAIN) enterTurn()
                }
            }
            else -> enterTurn()
        }
    }

    private fun enterTurn() {
        val pos = s.position
        when {
            pos.isOver -> end(resigned = false)
            pos.toMove == Side.NORTH -> {
                set { it.copy(phase = Phase.THINKING, previewPit = null, againSide = null, thinkingSince = clock()) }
                think()
            }
            else -> {
                set { it.copy(phase = Phase.IDLE, previewPit = null, againSide = null) }
                refreshHint()
            }
        }
    }

    private fun think() {
        aiJob?.cancel()
        val st = s
        val gameId = st.gameId
        val movesAt = st.moves.size
        val pos = st.position
        val level = st.config.level
        val rng = Random(st.seed + movesAt * 7919L)
        engine.cancelled = false
        log("AI think level=$level pos=$pos")
        aiJob = scope.launch {
            val t0 = clock()
            val pit = withContext(engineDispatcher) { engine.choose(pos, level, rng) }
            val took = clock() - t0
            lastThinkMs = took
            log("AI pick=${Kalah.letter(pit)} level=$level took=${took}ms depth=${engine.lastDepth} nodes=${engine.lastNodes}")
            val wait = MIN_THINK_MS - (clock() - s.thinkingSince)
            if (wait > 0) delay(wait)
            while (s.frozen) delay(100)
            val now = s
            if (now.phase == Phase.THINKING && now.gameId == gameId && now.moves.size == movesAt) sow(pit, byAi = true)
            aiJob = null
        }
    }

    private fun end(resigned: Boolean) {
        cancelJobs()
        val st = s
        val pos = st.position
        val winner = if (resigned) Side.NORTH else pos.winner
        val mine = pos.southStore + if (resigned) pos.rowTotal(Side.SOUTH) else 0
        val theirs = pos.northStore + if (resigned) pos.rowTotal(Side.NORTH) else 0
        val clean = !resigned && st.hints == 0 && !st.undone
        val prevStreak = settings.settings.value.streak
        val streak = when {
            winner == Side.SOUTH && clean -> prevStreak + 1
            winner == Side.NORTH -> 0
            else -> prevStreak
        }
        settings.update { it.copy(streak = streak) }
        // The move after which one store held 25 or more.
        var lockedAt: Int? = null
        var p = st.start
        for ((i, m) in st.moves.withIndex()) {
            p = Kalah.sow(p, m).after
            if (p.southStore >= 25 || p.northStore >= 25) { lockedAt = i + 1; break }
        }
        val result = GameResult(
            winner = winner, mine = mine, theirs = theirs, resigned = resigned, moves = st.moves.size,
            durationMs = clock() - st.startedAt, maxCapture = st.maxCapture, extraTurns = st.extraTurns, hints = st.hints,
            undone = st.undone, streak = streak, lockedAtMove = lockedAt, level = st.config.level, humanFirst = st.start.toMove == Side.SOUTH,
        )
        repo.clear()
        set { it.copy(phase = Phase.ENDED, result = result, previewPit = null, confirm = null, moreOpen = false, page = Page.PLAY, hintPit = null) }
        cue(when (winner) { Side.SOUTH -> Cue.WIN; Side.NORTH -> Cue.LOSE; null -> Cue.DRAW })
        log("GAME ended winner=$winner $mine:$theirs resigned=$resigned moves=${st.moves.size} streak=$streak")
    }

    // ---------------------------------------------------------------- tools

    fun undo() {
        val st = s
        if (!st.canUndo) return
        val keep = UndoPlan.target(st.start, st.moves) ?: return
        cancelJobs()
        val removed = st.moves.subList(keep, st.moves.size).toList()
        val pos = Kalah.replay(st.start, st.moves.take(keep))
        set { it.copy(moves = it.moves.take(keep), position = pos, redo = it.redo + listOf(removed), undone = true, phase = Phase.IDLE, previewPit = null, lastFrom = it.moves.take(keep).lastOrNull(), moreOpen = false) }
        persist()
        log("UNDO keep=$keep removed=${removed.map { Kalah.letter(it) }} pos=$pos")
        enterTurn()
    }

    fun redo() {
        val st = s
        if (!st.canRedo) return
        val chunk = st.redo.last()
        val moves = st.moves + chunk
        val pos = Kalah.replay(st.start, moves)
        set { it.copy(moves = moves, position = pos, redo = it.redo.dropLast(1), previewPit = null, lastFrom = moves.lastOrNull(), moreOpen = false) }
        persist()
        log("REDO ${chunk.map { Kalah.letter(it) }} pos=$pos")
        enterTurn()
    }

    fun toggleHint() {
        if (s.phase == Phase.ENDED) return
        set { it.copy(hintOn = !it.hintOn, hintPit = null) }
        log("HINT ${if (s.hintOn) "on" else "off"}")
        refreshHint()
    }

    fun setHint(on: Boolean) { if (s.hintOn != on) toggleHint() }

    private fun refreshHint() {
        val st = s
        if (!st.hintOn || st.phase != Phase.IDLE || !st.humanTurn) { if (st.hintPit != null) set { it.copy(hintPit = null) }; return }
        val pit = KalahEngine().best(st.position, Level.SMART.depth, 0).pit
        set { it.copy(hintPit = pit, hints = it.hints + 1) }
        persist()
        log("HINT pit=${Kalah.letter(pit)}")
    }

    fun requestConfirm(c: Confirm) { set { it.copy(confirm = c, moreOpen = false) }; log("CONFIRM ask $c") }
    fun cancelConfirm() { set { it.copy(confirm = null) }; log("CONFIRM cancel") }

    /** Confirms RESTART / RESIGN; EXIT is carried out by the window (it owns the navigator). */
    fun confirmYes(): Confirm? {
        val c = s.confirm ?: return null
        set { it.copy(confirm = null) }
        log("CONFIRM yes $c")
        when (c) {
            Confirm.RESIGN -> if (s.phase != Phase.ENDED && s.phase != Phase.SETUP && s.phase != Phase.WELCOME) end(resigned = true)
            Confirm.RESTART -> { cancelJobs(); repo.clear(); set { it.copy(phase = Phase.SETUP, page = Page.NEW, saved = null, result = null, sow = null, previewPit = null) } }
            Confirm.EXIT -> { persist(); onExit() }
        }
        return c
    }

    fun toggleMore() = set { it.copy(moreOpen = !it.moreOpen) }

    fun setPage(page: Page) {
        if (s.page == page) return
        set { it.copy(page = page, moreOpen = false, confirm = if (it.confirm == Confirm.EXIT && page != Page.SETTINGS) null else it.confirm) }
        log("PAGE $page")
    }

    /** S09 [再来一局]: same opponent, the other side goes first. */
    fun playAgain() {
        val r = s.result
        val first = if (r?.humanFirst == true) FirstMover.THEM else FirstMover.ME
        startGame(s.config.copy(first = first))
    }

    /** S09 [主菜单]. */
    fun toMenu() {
        cancelJobs()
        set { it.copy(phase = Phase.SETUP, page = Page.NEW, saved = SaveCodec.decode(repo.load())?.takeIf { g -> !g.position.isOver }) }
        log("GAME setup (menu)")
    }

    /** QA: keep short-lived states (S07 / S08 / thinking) on screen until the next command. */
    fun freeze(on: Boolean) { set { it.copy(frozen = on) }; log("FREEZE $on") }

    /** Debug `pos`: a game from an arbitrary position (same flow afterwards). */
    fun startFrom(position: Position) = startGame(s.config.copy(first = if (position.toMove == Side.SOUTH) FirstMover.ME else FirstMover.THEM), position)

    /** Debug `goto S07`: the extra-turn notice for the side to move, held. */
    fun showAgainNotice() {
        cancelJobs()
        set { it.copy(phase = Phase.AGAIN, againSide = it.position.toMove, frozen = true) }
        cue(Cue.AGAIN)
    }

    fun alert(kind: AlertKind, pit: Int) {
        val id = ++alertCounter
        set { it.copy(alert = Alert(kind, pit, id)) }
        cue(Cue.ILLEGAL)
        log("ALERT $kind pit=${Kalah.letter(pit)}")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            while (s.frozen) delay(100)
            if (s.alert?.id == id) set { it.copy(alert = null) }
        }
    }

    private fun persist() {
        val st = s
        if (st.phase == Phase.SETUP || st.phase == Phase.WELCOME || st.phase == Phase.LOADING || st.phase == Phase.ENDED || st.position.isOver) return
        val ok = repo.save(SaveCodec.encode(SavedGame(st.config, st.start, st.moves, clock() - st.startedAt, st.hints, st.undone, st.seed)))
        if (!ok) log("SAVE failed")
    }

    private fun cancelJobs() {
        aiJob?.cancel(); aiJob = null
        againJob?.cancel(); againJob = null
        watchdogJob?.cancel(); watchdogJob = null
        engine.cancelled = true
    }

    fun describe(r: SowResult): String = buildString {
        append("drops=").append(r.drops.joinToString("") { if (Kalah.isStore(it)) "*" else Kalah.letter(it).toString() })
        if (r.extraTurn) append(" extra")
        r.capture?.let { append(" capture=${Kalah.letter(it.across)}+${it.total}") }
        if (r.sweeps.isNotEmpty()) append(" sweep=" + r.sweeps.joinToString { "${it.side}:${it.total}" })
    }

    companion object {
        const val AGAIN_MS = 600L
        const val ALERT_MS = 1600L
        const val MIN_THINK_MS = 400L
    }
}
