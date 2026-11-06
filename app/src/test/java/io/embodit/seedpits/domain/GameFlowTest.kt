package io.embodit.seedpits.domain

import io.embodit.seedpits.data.FakeGameRepository
import io.embodit.seedpits.data.FakeSettingsRepository
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.game.Confirm
import io.embodit.seedpits.domain.game.FirstMover
import io.embodit.seedpits.domain.game.GameConfig
import io.embodit.seedpits.domain.game.GameFlow
import io.embodit.seedpits.domain.game.Phase
import io.embodit.seedpits.domain.game.Settings
import io.embodit.seedpits.domain.rules.Position
import io.embodit.seedpits.domain.rules.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameFlowTest {
    private fun flow(settings: FakeSettingsRepository, exits: IntArray = IntArray(1)): GameFlow {
        val d = UnconfinedTestDispatcher()
        return GameFlow(FakeGameRepository(), settings, CoroutineScope(d), d, clock = { 0L }).also { it.onExit = { exits[0]++ } }
    }

    @Test fun resignKeepsTheStreak() {
        val settings = FakeSettingsRepository(Settings(firstRunDone = true, streak = 5))
        val f = flow(settings)
        f.start()
        f.startGame(GameConfig(Level.EASY, FirstMover.ME))
        f.requestConfirm(Confirm.RESIGN)
        f.confirmYes()
        assertEquals(Phase.ENDED, f.state.value.phase)
        assertTrue(f.state.value.result!!.resigned)
        assertEquals(5, settings.settings.value.streak)
        assertEquals(5, f.state.value.result!!.streak)
    }

    @Test fun captureCountMatchesThePreviewWording() {
        // a has 1, b empty, E (across b) has 5: the preview says "their 5", the result card's biggest grab is 5 too.
        val settings = FakeSettingsRepository(Settings(firstRunDone = true))
        val f = flow(settings)
        f.start()
        f.startFrom(Position.parse("1,0,3,3,3,3,0,4,4,4,4,5,4,6/S")!!)
        f.tapPit(0); f.tapPit(0)
        f.onSowDone(f.state.value.sow!!.id)
        assertEquals(5, f.state.value.maxCapture)
    }

    @Test fun exitConfirmationReleasesTheApp() {
        val exits = IntArray(1)
        val f = flow(FakeSettingsRepository(Settings(firstRunDone = true)), exits)
        f.start()
        f.requestConfirm(Confirm.EXIT)
        f.confirmYes()
        assertEquals(1, exits[0])
    }

    @Test fun firstRunShowsTheRulesThenSetup() {
        val settings = FakeSettingsRepository(Settings())
        val f = flow(settings)
        f.start()
        assertEquals(Phase.WELCOME, f.state.value.phase)
        f.welcomeDone()
        assertEquals(Phase.SETUP, f.state.value.phase)
        assertTrue(settings.settings.value.firstRunDone)
        assertEquals(Side.SOUTH, f.state.value.position.toMove)
    }

    @Test fun newGameAsksFirstWhenAGameIsInProgress() {
        val f = flow(FakeSettingsRepository(Settings(firstRunDone = true)))
        f.start()
        f.startGame(GameConfig(Level.EASY, FirstMover.ME))
        f.tapPit(2); f.tapPit(2)                       // c: extra turn, still your move
        f.onSowDone(f.state.value.sow!!.id)
        val moves = f.state.value.moves
        f.setPage(io.embodit.seedpits.domain.game.Page.NEW)
        f.requestNewGame(GameConfig(Level.MASTER, FirstMover.ME))
        assertEquals(Confirm.NEW_GAME, f.state.value.confirm)
        assertEquals(moves, f.state.value.moves)        // nothing lost yet
        f.cancelConfirm()
        assertEquals(moves, f.state.value.moves)
        f.requestNewGame(GameConfig(Level.MASTER, FirstMover.ME))
        f.confirmYes()
        assertTrue(f.state.value.moves.isEmpty())
        assertEquals(Level.MASTER, f.state.value.config.level)
    }

    @Test fun newGameStartsAtOnceWithNothingToLose() {
        val f = flow(FakeSettingsRepository(Settings(firstRunDone = true)))
        f.start()
        f.requestNewGame(GameConfig(Level.SMART, FirstMover.ME))
        assertEquals(null, f.state.value.confirm)
        assertEquals(Phase.IDLE, f.state.value.phase)
    }

    private fun savedGameRepo(): FakeGameRepository {
        // A saved, unfinished game vs Master after two moves (what a cold start would restore into S02's [继续上一盘]).
        var p = io.embodit.seedpits.domain.rules.Kalah.initial(Side.SOUTH)
        val moves = listOf(2, 5)
        for (m in moves) p = io.embodit.seedpits.domain.rules.Kalah.sow(p, m).after
        val saved = io.embodit.seedpits.domain.game.SavedGame(GameConfig(Level.MASTER, FirstMover.ME), io.embodit.seedpits.domain.rules.Kalah.initial(Side.SOUTH), moves, 5000L, 0, false, 7L)
        return FakeGameRepository(io.embodit.seedpits.domain.game.SaveCodec.encode(saved))
    }

    private fun flowWith(repo: FakeGameRepository): GameFlow {
        val d = UnconfinedTestDispatcher()
        val settings = FakeSettingsRepository(Settings(firstRunDone = true))
        return GameFlow(repo, settings, CoroutineScope(d), d, clock = { 0L })
    }

    @Test fun savedGameNewGameCancelKeepsTheSave() {
        val repo = savedGameRepo()
        val before = repo.text
        val f = flowWith(repo)
        f.start()
        f.toMenu()                                       // S02 with [继续上一盘]
        assertTrue(f.state.value.saved != null)
        f.requestNewGame(GameConfig(Level.EASY, FirstMover.ME))
        assertEquals(Confirm.NEW_GAME, f.state.value.confirm)
        f.cancelConfirm()
        assertEquals(null, f.state.value.confirm)
        assertEquals(null, f.state.value.pendingConfig)
        assertEquals(before, repo.text)                  // the save is untouched
        assertEquals(listOf(2, 5), f.state.value.saved!!.moves)
    }

    @Test fun savedGameNewGameConfirmReplacesTheSave() {
        val repo = savedGameRepo()
        val f = flowWith(repo)
        f.start()
        f.toMenu()
        f.requestNewGame(GameConfig(Level.EASY, FirstMover.ME))
        f.confirmYes()
        assertEquals(Phase.IDLE, f.state.value.phase)
        assertEquals(Level.EASY, f.state.value.config.level)
        assertTrue(f.state.value.moves.isEmpty())
        val now = io.embodit.seedpits.domain.game.SaveCodec.decode(repo.text)
        assertTrue(now == null || now.moves.isEmpty())   // the old two-move save is gone
        assertEquals(null, f.state.value.pendingConfig)
    }
}
