package io.embodit.seedpits.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.seedpits.R
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.game.AlertKind
import io.embodit.seedpits.domain.game.Confirm
import io.embodit.seedpits.domain.game.GameFlow
import io.embodit.seedpits.domain.game.GameState
import io.embodit.seedpits.domain.game.Page
import io.embodit.seedpits.domain.game.Phase
import io.embodit.seedpits.domain.game.Settings
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Side
import io.embodit.seedpits.platform.SeedGraph
import io.embodit.seedpits.ui.theme.SeedColors
import io.embodit.seedpits.ui.window.WindowCtl
import kotlin.system.exitProcess

/**
 * The control window (Planar 560 × 440 dp, Placement.right of the board): four pages switched by the system
 * TabBar (Play / New game / Rules / Settings, D8). Confirmations open in place (S13). No drag code: the system
 * window controls under the window move and close it (D21).
 */
@Composable
fun ControlScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { SeedGraph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val flow = graph.flow
    Box(modifier = modifier.fillMaxSize()) {
        when (state.page) {
            Page.PLAY -> PlayPage(state, settings, flow, onMute = { graph.settings.update { s -> s.copy(sound = !s.sound) } })
            Page.NEW -> NewPage(state, settings, flow)
            Page.RULES -> RulesPage(state, flow)
            Page.SETTINGS -> SettingsPage(state, settings, graph)
        }
    }
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_grid, R.string.tab_play),
        Triple(Page.NEW, R.drawable.ic_add, R.string.tab_new),
        Triple(Page.RULES, R.drawable.ic_library, R.string.tab_rules),
        Triple(Page.SETTINGS, R.drawable.ic_settings, R.string.tab_settings),
    )
    val labels = tabs.map { stringResource(it.third) }
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEachIndexed { i, (page, icon, _) ->
            item(
                selected = state.page == page,
                onClick = { flow.setPage(page) },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = labels[i]) },
                supportContent = { T(labels[i], "tab/$page", PicoTheme.typography.labelMedium, PicoTheme.colorScheme.labelPrimary) },
            )
        }
    }
}

@Composable
internal fun levelName(level: Level): String = stringResource(
    when (level) {
        Level.EASY -> R.string.level_1
        Level.CASUAL -> R.string.level_2
        Level.SMART -> R.string.level_3
        Level.STRONG -> R.string.level_4
        Level.MASTER -> R.string.level_5
    },
)

@Composable
private fun cellName(cell: Int): String = if (Kalah.isStore(cell)) stringResource(R.string.your_store) else Kalah.letter(cell).toString()

// ---------------------------------------------------------------------------------------------- play

@Composable
private fun PlayPage(state: GameState, settings: Settings, flow: GameFlow, onMute: () -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val ink2 = PicoTheme.colorScheme.labelSecondary
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
        if (state.phase == Phase.ENDED && state.result != null) {
            ResultCard(state, flow)
            return@Column
        }
        // Title row
        val title = when (state.phase) {
            Phase.PREVIEW -> stringResource(R.string.title_preview)
            Phase.THINKING -> stringResource(R.string.title_thinking)
            Phase.AGAIN -> if (state.againSide == Side.NORTH) stringResource(R.string.title_again_them) else stringResource(R.string.title_again)
            Phase.SOWING -> stringResource(R.string.title_sowing)
            Phase.IDLE -> stringResource(R.string.title_your_turn)
            Phase.LOADING -> stringResource(R.string.loading)
            else -> stringResource(R.string.title_setup)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (state.phase == Phase.THINKING || state.againSide == Side.NORTH) SeedColors.Opponent else SeedColors.Primary))
            HGap(8.dp)
            T(title, "play/title", PicoTheme.typography.titleLarge, ink)
            HGap(8.dp)
            T(stringResource(R.string.title_vs, levelName(state.config.level)), "play/vs", PicoTheme.typography.bodyMedium, ink2, Modifier.weight(1f))
        }
        Gap(10.dp)
        // Scores: their store : your store (40 dp), with the pits line between.
        val cells = state.shownCells
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                T(stringResource(R.string.label_them), "play/them", PicoTheme.typography.labelMedium, ink2)
                T(cells[Kalah.NORTH_STORE].toString(), "play/themStore", PicoTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), SeedColors.StoreThem)
            }
            Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
                MirrorRow((12 downTo 7).toList(), cells, emptyList())
                Gap(4.dp)
                T(stringResource(R.string.pits_line, (7..12).sumOf { cells[it] }, (0..5).sumOf { cells[it] }), "play/pits", PicoTheme.typography.bodySmall, ink2)
                Gap(4.dp)
                MirrorRow((0..5).toList(), cells, state.litPits)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                T(stringResource(R.string.label_you), "play/you", PicoTheme.typography.labelMedium, ink2)
                T(cells[Kalah.SOUTH_STORE].toString(), "play/youStore", PicoTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), SeedColors.StoreYou)
            }
        }
        Gap(10.dp)
        // Main action slot: [播] + [取消] in a preview, [重做] right after an undo, otherwise empty (kept tall).
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                state.phase == Phase.PREVIEW -> {
                    PrimaryButton(stringResource(R.string.btn_sow), "sow", { flow.confirmSow() }, Modifier.weight(1f), icon = R.drawable.ic_play)
                    SecondaryButton(stringResource(R.string.btn_cancel), "cancel", { flow.cancelPreview() }, icon = R.drawable.ic_close, minWidth = 120.dp)
                }
                state.canRedo && state.phase == Phase.IDLE -> PrimaryButton(stringResource(R.string.btn_redo), "redo", { flow.redo() }, Modifier.weight(1f), icon = R.drawable.ic_redo)
                else -> Unit
            }
        }
        Gap(10.dp)
        // Secondary actions.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "undo", { flow.undo() }, Modifier.weight(1f), icon = R.drawable.ic_undo, enabled = state.canUndo, minWidth = 0.dp)
            SecondaryButton(
                stringResource(if (state.hintOn) R.string.btn_hint_on else R.string.btn_hint), "hint", { flow.toggleHint() }, Modifier.weight(1.25f),
                icon = R.drawable.ic_bulb, active = state.hintOn, minWidth = 0.dp,
            )
            SecondaryButton(
                stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_muted), "mute", onMute, Modifier.weight(1f),
                icon = if (settings.sound) R.drawable.ic_volume_up else R.drawable.ic_volume_off, minWidth = 0.dp,
            )
            SecondaryButton(stringResource(if (state.moreOpen) R.string.btn_less else R.string.btn_more), "more", { flow.toggleMore() }, Modifier.weight(1f), icon = R.drawable.ic_more, minWidth = 0.dp)
        }
        if (state.moreOpen) {
            Gap(8.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_redo), "more/redo", { flow.redo() }, Modifier.weight(1f), icon = R.drawable.ic_redo, enabled = state.canRedo, minWidth = 0.dp)
                SecondaryButton(stringResource(R.string.btn_resign), "more/resign", { flow.requestConfirm(Confirm.RESIGN) }, Modifier.weight(1f), icon = R.drawable.ic_flag, minWidth = 0.dp)
                SecondaryButton(stringResource(R.string.btn_restart), "more/restart", { flow.requestConfirm(Confirm.RESTART) }, Modifier.weight(1f), icon = R.drawable.ic_replay, minWidth = 0.dp)
            }
        }
        Gap(10.dp)
        val confirm = state.confirm
        if (confirm == Confirm.RESIGN || confirm == Confirm.RESTART) {
            ConfirmBar(
                text = stringResource(if (confirm == Confirm.RESIGN) R.string.confirm_resign else R.string.confirm_restart),
                yes = stringResource(if (confirm == Confirm.RESIGN) R.string.confirm_yes_resign else R.string.confirm_yes_restart),
                id = "confirm/$confirm",
                onYes = { flow.confirmYes() }, onNo = { flow.cancelConfirm() },
            )
        } else {
            T(statusLine(state), "play/status", PicoTheme.typography.bodyLargeMultiline, if (state.alert != null) PicoTheme.colorScheme.error else ink, Modifier.fillMaxWidth(), maxLines = 2)
        }
    }
}

@Composable
private fun MirrorRow(cells: List<Int>, counts: List<Int>, lit: List<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in cells) {
            val isLit = c in lit
            Box(
                Modifier.size(width = 30.dp, height = 26.dp).clip(RoundedCornerShape(6.dp)).background(if (isLit) SeedColors.PrimaryContainer else SeedColors.Wood.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                T(counts[c].toString(), "mirror/$c", PicoTheme.typography.labelLarge.copy(fontWeight = if (isLit) FontWeight.Bold else FontWeight.Medium), if (isLit) SeedColors.OnPrimaryContainer else SeedColors.Ink, align = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun statusLine(state: GameState): String {
    state.alert?.let { a ->
        return stringResource(
            when (a.kind) {
                AlertKind.THEIRS -> R.string.alert_theirs
                AlertKind.EMPTY -> R.string.alert_empty
                AlertKind.NOT_YOUR_TURN -> R.string.alert_wait
            },
        )
    }
    return when (state.phase) {
        Phase.PREVIEW -> {
            val pit = state.previewPit ?: return ""
            val r = Kalah.sow(state.position, pit)
            val sep = stringResource(R.string.path_sep)
            val names = r.drops.distinct().map { cellName(it) }
            val path = names.joinToString(sep)
            val letter = Kalah.letter(pit).toString()
            when {
                r.extraTurn -> stringResource(R.string.preview_store, letter, r.drops.size, path)
                r.capture != null -> stringResource(R.string.preview_capture, letter, r.drops.size, path, r.capture.fromAcross)
                else -> stringResource(R.string.preview_plain, letter, r.drops.size, path) + if (state.coachStep < 3) " " + stringResource(R.string.coach_2) else ""
            }
        }
        Phase.THINKING -> stringResource(R.string.status_thinking)
        Phase.AGAIN -> stringResource(if (state.againSide == Side.NORTH) R.string.status_again_them else R.string.status_again)
        Phase.SOWING -> stringResource(R.string.status_sowing)
        Phase.IDLE -> when {
            state.coachStep < 3 -> stringResource(R.string.coach_1)
            state.hintPit != null -> stringResource(R.string.status_hint, Kalah.letter(state.hintPit).toString())
            state.canRedo -> stringResource(R.string.status_redo)
            else -> stringResource(R.string.status_idle)
        }
        else -> stringResource(R.string.status_setup)
    }
}

@Composable
internal fun ConfirmBar(text: String, yes: String, id: String, onYes: () -> Unit, onNo: () -> Unit, body: String? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SeedColors.PrimaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        T(text, "$id/text", PicoTheme.typography.titleMedium, SeedColors.OnPrimaryContainer, Modifier.fillMaxWidth(), maxLines = 2)
        if (body != null) T(body, "$id/body", PicoTheme.typography.bodyMedium, SeedColors.OnPrimaryContainer, Modifier.fillMaxWidth(), maxLines = 2)
        Gap(8.dp)
        // Equal buttons in one row (Cloud Air round 5).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.cancel), "$id/no", onNo, Modifier.weight(1f), minWidth = 0.dp)
            PrimaryButton(yes, "$id/yes", onYes, Modifier.weight(1f).height(48.dp))
        }
    }
}

@Composable
private fun ResultCard(state: GameState, flow: GameFlow) {
    val r = state.result ?: return
    val ink = PicoTheme.colorScheme.labelPrimary
    val ink2 = PicoTheme.colorScheme.labelSecondary
    val title = when {
        r.resigned -> stringResource(R.string.result_resigned)
        r.winner == Side.SOUTH -> stringResource(R.string.result_win) + if (r.level == Level.MASTER && r.humanFirst) stringResource(R.string.result_master_first) else ""
        r.winner == Side.NORTH -> stringResource(R.string.result_lose)
        else -> stringResource(R.string.result_draw)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        T(title, "result/title", PicoTheme.typography.headlineSmall, ink, maxLines = 2)
        Gap(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            T(stringResource(R.string.label_them), "result/them", PicoTheme.typography.labelLarge, ink2)
            HGap(10.dp)
            T(r.theirs.toString(), "result/theirs", PicoTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), SeedColors.StoreThem)
            HGap(10.dp)
            T(":", "result/colon", PicoTheme.typography.displaySmall, ink2)
            HGap(10.dp)
            T(r.mine.toString(), "result/mine", PicoTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), SeedColors.StoreYou)
            HGap(10.dp)
            T(stringResource(R.string.label_you), "result/you", PicoTheme.typography.labelLarge, ink2)
        }
        Gap(8.dp)
        val mm = r.durationMs / 60000
        val ss = (r.durationMs / 1000) % 60
        T(stringResource(R.string.stats_main, r.moves, "%d:%02d".format(mm, ss), r.maxCapture, r.extraTurns), "result/stats", PicoTheme.typography.bodyMediumMultiline, ink, Modifier.fillMaxWidth(), maxLines = 2)
        val parts = ArrayList<String>()
        parts += if (r.hints == 0) stringResource(R.string.stats_no_hints) else stringResource(R.string.stats_hints, r.hints)
        if (r.undone) parts += stringResource(R.string.stats_undo)
        parts += stringResource(R.string.stats_streak, r.streak)
        r.lockedAtMove?.let { if (r.winner == Side.SOUTH) parts += stringResource(R.string.stats_locked, it) }
        T(parts.joinToString(" · "), "result/stats2", PicoTheme.typography.bodyMediumMultiline, ink, Modifier.fillMaxWidth(), maxLines = 2)
        Gap(14.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.btn_play_again), "again", { flow.playAgain() }, Modifier.weight(1f), icon = R.drawable.ic_replay)
            SecondaryButton(stringResource(R.string.btn_menu), "menu", { flow.toMenu() }, Modifier.weight(1f).height(56.dp), icon = R.drawable.ic_home, minWidth = 0.dp)
        }
        Gap(12.dp)
        T(stringResource(R.string.source_line), "result/source", PicoTheme.typography.bodySmall, ink2, Modifier.fillMaxWidth(), maxLines = 3)
    }
}

/** D26 exit: release, close the windows, remove the tasks, end the process (theremin v0.1.8 order). */
fun exitApp(context: Context, graph: SeedGraph) {
    Log.i("SeedPits", "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.pause() }
    runCatching { graph.sounds.release() }
    runCatching { io.embodit.seedpits.platform.HeadTracker.stop() }
    runCatching { WindowCtl.close() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}
