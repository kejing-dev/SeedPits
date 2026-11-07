package io.embodit.seedpits.ui

import io.embodit.seedpits.data.FakeGameRepository
import io.embodit.seedpits.data.FakeSettingsRepository
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.game.AlertKind
import io.embodit.seedpits.domain.game.FirstMover
import io.embodit.seedpits.domain.game.GameConfig
import io.embodit.seedpits.domain.game.GameFlow
import io.embodit.seedpits.domain.game.Phase
import io.embodit.seedpits.domain.game.Settings
import io.embodit.seedpits.ui.board.SeedTapDispatcher
import io.embodit.seedpits.ui.board.SeedTarget
import io.embodit.seedpits.ui.board.SeedTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hit replay (build.md 2026-11-06: 「命中目标 → 格子必须有回放单测」): every way a real pinch / trigger can land — the
 * pit collider, the visible rim marker under it, a GLB child below either, the board top, the seat plate, nothing —
 * goes through the same resolver + dispatcher as BoardView, and must end at the right pit / action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private fun flow(): GameFlow {
        val d = UnconfinedTestDispatcher()
        return GameFlow(FakeGameRepository(), FakeSettingsRepository(Settings(firstRunDone = true)), CoroutineScope(d), d, clock = { 0L })
            .also { it.start(); it.startGame(GameConfig(Level.EASY, FirstMover.ME)) }
    }

    @Test fun everyPitResolvesFromColliderMarkerAndChildMesh() {
        for (cell in (0..5) + (7..12)) {
            assertEquals(SeedTarget.Pit(cell), SeedTargets.resolveChain(listOf("pit:$cell", "boardRoot", "tiltRoot", "seedRoot")))
            assertEquals(SeedTarget.Pit(cell), SeedTargets.resolveChain(listOf("pitMark:$cell", "pit:$cell", "boardRoot")))
            assertEquals(SeedTarget.Pit(cell), SeedTargets.resolveChain(listOf("Torus", "pitMark:$cell", "pit:$cell", "boardRoot")))
        }
        // Stores and stones are not targets; the board top and the plate are.
        assertNull(SeedTargets.fromName("pit:6"))
        assertNull(SeedTargets.fromName("pit:13"))
        assertNull(SeedTargets.resolveChain(listOf("Stone", "stone:12", "boardRoot", "tiltRoot", "seedRoot")))
        assertEquals(SeedTarget.Board, SeedTargets.resolveChain(listOf("board", "boardRoot")))
        assertEquals(SeedTarget.Plate, SeedTargets.resolveChain(listOf("seatPlate", "seedRoot")))
    }

    @Test fun replayedPitTapsPreviewThenSow() {
        val f = flow()
        val hit = SeedTargets.resolveChain(listOf("pitMark:3", "pit:3", "boardRoot"))
        SeedTapDispatcher.dispatch(hit, f, panelOpen = true) {}
        assertEquals(Phase.PREVIEW, f.state.value.phase)
        assertEquals(3, f.state.value.previewPit)
        SeedTapDispatcher.dispatch(SeedTargets.resolveChain(listOf("pit:3")), f, panelOpen = true) {}
        assertEquals(Phase.SOWING, f.state.value.phase)
        assertEquals(listOf(3), f.state.value.moves)
    }

    @Test fun replayedOtherHitsCancelOrWarn() {
        val f = flow()
        SeedTapDispatcher.dispatch(SeedTarget.Pit(2), f, true) {}
        SeedTapDispatcher.dispatch(SeedTargets.resolveChain(listOf("board", "boardRoot")), f, true) {}
        assertEquals(Phase.IDLE, f.state.value.phase)
        SeedTapDispatcher.dispatch(SeedTarget.Pit(2), f, true) {}
        SeedTapDispatcher.dispatch(null, f, true) {}
        assertEquals(Phase.IDLE, f.state.value.phase)
        SeedTapDispatcher.dispatch(SeedTargets.resolveChain(listOf("pitMark:9", "pit:9")), f, true) {}
        assertEquals(AlertKind.THEIRS, f.state.value.alert?.kind)
        var opened = 0
        SeedTapDispatcher.dispatch(SeedTarget.Plate, f, panelOpen = false) { opened++ }
        assertEquals(1, opened)
        assertTrue(f.state.value.moves.isEmpty())
    }
}
