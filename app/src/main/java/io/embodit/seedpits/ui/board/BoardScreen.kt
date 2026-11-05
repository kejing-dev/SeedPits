package io.embodit.seedpits.ui.board

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.seedpits.R
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.game.GameState
import io.embodit.seedpits.domain.game.Phase
import io.embodit.seedpits.domain.game.Settings
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Side
import io.embodit.seedpits.platform.SeedGraph
import io.embodit.seedpits.ui.window.WindowCtl
import kotlin.math.abs

/**
 * The volume (default container): the board. It asks for the control window at launch (through the level gate)
 * and closes it when it goes. Compose only hands over state; entities live in [BoardScene], motion runs in
 * [BoardFrameSystem].
 */
@Composable
fun BoardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { SeedGraph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val owner = LocalLifecycleOwner.current

    DisposableEffect(navigator) {
        // The tracking natives load with the spatial runtime, so the head provider starts here, not in Application.
        io.embodit.seedpits.platform.HeadTracker.start()
        WindowCtl.attach(navigator)
        WindowCtl.openWindow("launch")
        onDispose {
            WindowCtl.close()
            WindowCtl.detach(navigator)
        }
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> graph.flow.pause()
                Lifecycle.Event.ON_START -> graph.flow.resume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    graph.sounds.enabled = settings.sound
    graph.sounds.gain = settings.volume.gain
    val input = boardInput(state, settings, plateText(state), plateDot(state), DebugFlags.testGlyph)
    BoardView(input, graph, modifier.fillMaxSize())
}

/** Debug-only switches the receiver flips (D10 test glyph). */
object DebugFlags {
    var testGlyph by mutableStateOf(false)
}

@Composable
private fun levelLabel(level: Level): String = stringResource(
    when (level) {
        Level.EASY -> R.string.level_1
        Level.CASUAL -> R.string.level_2
        Level.SMART -> R.string.level_3
        Level.STRONG -> R.string.level_4
        Level.MASTER -> R.string.level_5
    },
)

@Composable
private fun plateText(s: GameState): String {
    val ai = levelLabel(s.config.level)
    return when {
        WindowCtl.levelHint -> stringResource(R.string.plate_level)
        WindowCtl.panelState == "ON_DESTROY" && !WindowCtl.waiting -> stringResource(R.string.plate_open)
        s.phase == Phase.LOADING -> ""
        s.phase == Phase.WELCOME -> stringResource(R.string.plate_welcome)
        s.phase == Phase.SETUP -> stringResource(R.string.plate_setup)
        s.phase == Phase.ENDED -> when (s.result?.winner) {
            Side.SOUTH -> stringResource(R.string.plate_win)
            Side.NORTH -> stringResource(R.string.plate_lose, ai)
            null -> stringResource(R.string.plate_draw)
        }
        s.phase == Phase.AGAIN -> if (s.againSide == Side.NORTH) stringResource(R.string.plate_ai_again, ai) else stringResource(R.string.plate_you_again)
        s.phase == Phase.THINKING -> stringResource(R.string.plate_ai_turn, ai)
        s.phase == Phase.SOWING -> if (s.sow?.result?.side == Side.NORTH) stringResource(R.string.plate_ai_turn, ai) else stringResource(R.string.plate_you)
        else -> stringResource(R.string.plate_you)
    }
}

private fun plateDot(s: GameState): Int {
    val ai = s.phase == Phase.THINKING || s.againSide == Side.NORTH || (s.phase == Phase.SOWING && s.sow?.result?.side == Side.NORTH) || (s.phase == Phase.ENDED && s.result?.winner == Side.NORTH)
    return if (ai) BoardTextures.SLATE else BoardTextures.OCHRE
}

internal fun boardInput(s: GameState, settings: Settings, plate: String, dot: Int, testGlyph: Boolean): BoardInput {
    val preview = s.previewPit?.takeIf { s.phase == Phase.PREVIEW && Kalah.isLegal(s.position, it) }?.let { Kalah.sow(s.position, it) }
    val hint = s.hintPit?.takeIf { s.phase == Phase.IDLE && Kalah.isLegal(s.position, it) }?.let { Kalah.sow(s.position, it) }
    return BoardInput(
        gameId = s.gameId,
        phase = s.phase,
        cells = s.position.cells,
        shown = s.shownCells,
        toMove = s.position.toMove,
        lit = s.litPits,
        previewPit = s.previewPit,
        preview = preview,
        hintPit = s.hintPit,
        hint = hint,
        sowId = s.sow?.id,
        sow = s.sow?.result,
        sowByAi = s.sow?.byAi == true,
        alertId = s.alert?.id,
        alertPit = s.alert?.pit,
        lastFrom = s.lastFrom,
        againSide = if (s.phase == Phase.AGAIN) s.againSide else null,
        winner = s.result?.winner,
        ended = s.phase == Phase.ENDED,
        coach = s.coachStep < 3,
        reducedMotion = settings.reducedMotion,
        showDigits = settings.showDigits,
        tiltDeg = settings.tiltDeg,
        plateText = plate,
        plateDot = dot,
        plateTappable = WindowCtl.panelState == "ON_DESTROY" && !WindowCtl.waiting,
        testGlyph = testGlyph,
        frozen = s.frozen,
    )
}

@Composable
private fun BoardView(input: BoardInput, graph: SeedGraph, modifier: Modifier) {
    val context = LocalContext.current
    val currentInput by rememberUpdatedState(input)
    val scene = remember {
        BoardScene(context) { e ->
            when (e) {
                is SceneEvent.Step -> graph.flow.onSowStep(e.id, e.step)
                is SceneEvent.Done -> graph.flow.onSowDone(e.id)
                is SceneEvent.Sound -> graph.sounds.play(e.kind, e.rate, e.pan)
            }
        }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(scene) {
        BoardSceneHolder.scene = scene
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (BoardSceneHolder.scene === scene) BoardSceneHolder.scene = null
            scene.dispose()
        }
    }
    LaunchedEffect(scene) { scene.createResources() }

    SpatialView(
        modifier = modifier
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectSpatialTapGesture(context = context, targetedToEntity = null) { tap ->
                    val name = tap.targetEntity?.getName()
                    Log.i(BoardScene.TAG, "TAP target=$name")
                    val pit = BoardScene.pitOf(name)
                    when {
                        pit != null -> graph.flow.tapPit(pit)
                        name == BoardScene.PLATE_NAME -> if (!WindowCtl.panelOpen) WindowCtl.openWindow("plate")
                        else -> graph.flow.cancelPreview()
                    }
                }
            },
        initial = { content, _ ->
            content.addEntity(scene.root)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val board = runCatching { Entity.loadSuspend("asset://models/board.glb") }.onFailure { Log.e(BoardScene.TAG, "board model failed", it) }.getOrNull()
            val stones = (0..3).mapNotNull { i ->
                runCatching { Entity.loadSuspend("asset://models/stone_$i.glb") }.onFailure { Log.e(BoardScene.TAG, "stone $i failed", it) }.getOrNull()
            }
            stones.forEach { it.enabled = false; content.addEntity(it) }
            Log.i(BoardScene.TAG, "MODELS loaded board=${board != null} stones=${stones.size} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            scene.install(board, stones)
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(P3(abs(corner.x), abs(corner.y), abs(corner.z)))
            }
            scene.apply(currentInput)
        },
    )
}
