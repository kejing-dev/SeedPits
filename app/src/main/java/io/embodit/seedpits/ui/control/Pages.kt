package io.embodit.seedpits.ui.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.SegmentSlider
import com.pico.spatial.ui.design.Switch
import io.embodit.seedpits.R
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.game.Confirm
import io.embodit.seedpits.domain.game.FirstMover
import io.embodit.seedpits.domain.game.GameConfig
import io.embodit.seedpits.domain.game.GameFlow
import io.embodit.seedpits.domain.game.GameState
import io.embodit.seedpits.domain.game.Language
import io.embodit.seedpits.domain.game.Phase
import io.embodit.seedpits.domain.game.Settings
import io.embodit.seedpits.domain.game.Volume
import io.embodit.seedpits.platform.SeedGraph
import io.embodit.seedpits.ui.theme.SeedColors
import io.embodit.seedpits.ui.window.WindowCtl

// ---------------------------------------------------------------------------------------------- new game

/**
 * A flexible gap for pages that must fill the 640 dp window (design layoutRule / mustPass M10, QA #3 q3-1): it takes a
 * share of the spare height between [min] and [max]; whatever is left after the caps centres the block.
 */
@Composable
private fun ColumnScope.FlexGap(min: androidx.compose.ui.unit.Dp, max: androidx.compose.ui.unit.Dp) =
    Spacer(Modifier.heightIn(min = min, max = max).weight(1f))

@Composable
internal fun NewPage(state: GameState, settings: Settings, flow: GameFlow) {
    var level by remember { mutableStateOf(Level.of(settings.lastLevel)) }
    var first by remember { mutableStateOf(FirstMover.ME) }
    val ink = PicoTheme.colorScheme.labelPrimary
    val ink2 = PicoTheme.colorScheme.labelSecondary
    val confirming = state.confirm == Confirm.NEW_GAME
    // With the confirmation bar open the gaps shrink so the bar stays on screen without scrolling.
    val big = if (confirming) 14.dp else 44.dp
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.Center) {
        T(stringResource(R.string.new_title), "new/title", PicoTheme.typography.headlineSmall, ink)
        FlexGap(8.dp, big)
        val saved = state.saved
        if (saved != null && state.phase == Phase.SETUP) {
            PrimaryButton(stringResource(R.string.new_continue, levelName(saved.config.level), saved.moves.size), "continue", { flow.continueSaved() }, Modifier.fillMaxWidth(), icon = R.drawable.ic_history)
            FlexGap(8.dp, big)
        }
        T(stringResource(R.string.new_who), "new/who", PicoTheme.typography.titleMedium, ink2)
        Gap(8.dp)
        Row(Modifier.fillMaxWidth()) {
            SegmentControl(modifier = Modifier.weight(1f)) {
                Level.entries.forEach { lv ->
                    SegmentItem(
                        selected = level == lv,
                        onClick = { level = lv },
                        modifier = Modifier.weight(1f),
                        title = { T(levelName(lv), "new/level/${lv.number}", PicoTheme.typography.labelLarge, PicoTheme.colorScheme.labelPrimary) },
                    )
                }
            }
        }
        Gap(6.dp)
        T(stringResource(R.string.new_level_note), "new/note", PicoTheme.typography.bodyMediumMultiline, ink2, Modifier.fillMaxWidth(), maxLines = 2)
        FlexGap(8.dp, big)
        T(stringResource(R.string.new_first), "new/first", PicoTheme.typography.titleMedium, ink2)
        Gap(8.dp)
        Row(Modifier.fillMaxWidth()) {
            SegmentControl(modifier = Modifier.weight(1f)) {
                listOf(FirstMover.ME to R.string.first_me, FirstMover.THEM to R.string.first_them, FirstMover.RANDOM to R.string.first_random).forEach { (f, label) ->
                    SegmentItem(
                        selected = first == f,
                        onClick = { first = f },
                        modifier = Modifier.weight(1f),
                        title = { T(stringResource(label), "new/firstopt/$f", PicoTheme.typography.labelLarge, PicoTheme.colorScheme.labelPrimary) },
                    )
                }
            }
        }
        FlexGap(10.dp, big)
        PrimaryButton(stringResource(R.string.new_start), "start", { flow.requestNewGame(GameConfig(level, first)) }, Modifier.fillMaxWidth().height(64.dp), icon = R.drawable.ic_play, tall = true)
        if (confirming) {
            // S02 → S13: a game in progress (or the saved one) would be lost.
            val opp = if (state.inProgress) state.config.level else state.saved?.config?.level ?: state.config.level
            val moves = if (state.inProgress) state.moves.size else state.saved?.moves?.size ?: 0
            Gap(10.dp)
            ConfirmBar(
                text = stringResource(R.string.confirm_newgame), body = stringResource(R.string.confirm_newgame_body, levelName(opp), moves),
                yes = stringResource(R.string.confirm_yes_newgame), id = "confirm/NEW_GAME",
                onYes = { flow.confirmYes() }, onNo = { flow.cancelConfirm() },
            )
        }
        FlexGap(8.dp, if (confirming) 12.dp else 28.dp)
        T(stringResource(R.string.new_streak, settings.streak), "new/streak", PicoTheme.typography.bodyMediumMultiline, ink2, Modifier.fillMaxWidth(), maxLines = 2)
    }
}

// ---------------------------------------------------------------------------------------------- rules

@Composable
internal fun RulesPage(state: GameState, flow: GameFlow) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val ink2 = PicoTheme.colorScheme.labelSecondary
    val welcome = state.phase == Phase.WELCOME
    val big = if (welcome) 14.dp else 30.dp
    // Everything on one screen, centred; the spare height goes into capped gaps between the groups (QA #3 q3-1).
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.Center) {
        T(stringResource(R.string.rules_title), "rules/title", PicoTheme.typography.headlineSmall, ink)
        FlexGap(6.dp, big)
        RulesDiagram(Modifier.fillMaxWidth().height(96.dp))
        Gap(4.dp)
        T(stringResource(R.string.rules_diagram), "rules/diagram", PicoTheme.typography.bodySmall, ink2, Modifier.fillMaxWidth(), maxLines = 2)
        FlexGap(6.dp, big)
        listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3).forEachIndexed { i, id ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                T("${i + 1}", "rules/n$i", PicoTheme.typography.titleMedium, ink, Modifier.width(24.dp))
                T(stringResource(id), "rules/$i", PicoTheme.typography.bodyMediumMultiline, ink, Modifier.weight(1f), maxLines = 4)
            }
        }
        FlexGap(2.dp, big / 2)
        T(stringResource(R.string.rules_ex1), "rules/ex1", PicoTheme.typography.bodyMediumMultiline, ink, Modifier.fillMaxWidth().padding(vertical = 3.dp), maxLines = 3)
        T(stringResource(R.string.rules_ex2), "rules/ex2", PicoTheme.typography.bodyMediumMultiline, ink, Modifier.fillMaxWidth().padding(vertical = 3.dp), maxLines = 3)
        FlexGap(6.dp, big)
        T(stringResource(R.string.mistakes_title), "rules/mistakes", PicoTheme.typography.labelLarge, ink2)
        listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).forEachIndexed { i, id ->
            T("· " + stringResource(id), "rules/m$i", PicoTheme.typography.bodyMediumMultiline, ink, Modifier.fillMaxWidth().padding(vertical = 2.dp), maxLines = 3)
        }
        if (welcome) {
            FlexGap(8.dp, 16.dp)
            PrimaryButton(stringResource(R.string.rules_start), "welcome/start", { flow.welcomeDone() }, Modifier.fillMaxWidth(), icon = R.drawable.ic_play)
        }
    }
}

/** The rules picture: the long board, the counterclockwise arrow, a handful going over three pits. */
@Composable
private fun RulesDiagram(modifier: Modifier) {
    val wood = SeedColors.Wood
    val bowl = SeedColors.Secondary
    val ochre = SeedColors.Primary
    val blue = SeedColors.PathBlue
    val stone = SeedColors.Tertiary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val boardH = h * 0.78f
        val top = (h - boardH) / 2
        drawRoundRect(wood, Offset(0f, top), Size(w, boardH), CornerRadius(boardH * 0.18f))
        val storeW = w * 0.09f
        drawRoundRect(bowl, Offset(w * 0.02f, top + boardH * 0.15f), Size(storeW, boardH * 0.7f), CornerRadius(storeW / 2))
        drawRoundRect(ochre, Offset(w - w * 0.02f - storeW, top + boardH * 0.15f), Size(storeW, boardH * 0.7f), CornerRadius(storeW / 2))
        val pitR = boardH * 0.15f
        val x0 = w * 0.17f
        val dx = (w * 0.83f - x0) / 5
        for (i in 0..5) {
            drawCircle(bowl, pitR, Offset(x0 + i * dx, top + boardH * 0.3f))
            drawCircle(bowl, pitR, Offset(x0 + i * dx, top + boardH * 0.72f))
        }
        // a handful from pit c going right: dots over d, e, f
        for (k in 1..3) drawCircle(blue, pitR * 0.32f, Offset(x0 + (2 + k) * dx, top + boardH * 0.72f - pitR * 1.4f))
        for (k in 0..2) drawCircle(stone, pitR * 0.28f, Offset(x0 + 2 * dx + (k - 1) * pitR * 0.55f, top + boardH * 0.72f + (k % 2) * pitR * 0.3f))
        // counterclockwise arrow: along the near row to the right, up past your store, back left along the far row
        val p = Path().apply {
            moveTo(x0, top + boardH * 0.98f)
            lineTo(w * 0.93f, top + boardH * 0.98f)
            lineTo(w * 0.93f, top + boardH * 0.02f)
            lineTo(x0, top + boardH * 0.02f)
        }
        drawPath(p, ochre, style = Stroke(width = 3f))
        val ax = x0
        val ay = top + boardH * 0.02f
        drawLine(ochre, Offset(ax, ay), Offset(ax + 10f, ay - 6f), strokeWidth = 3f)
        drawLine(ochre, Offset(ax, ay), Offset(ax + 10f, ay + 6f), strokeWidth = 3f)
    }
}

// ---------------------------------------------------------------------------------------------- settings

@Composable
internal fun SettingsPage(state: GameState, settings: Settings, graph: SeedGraph) {
    val context = LocalContext.current
    val ink = PicoTheme.colorScheme.labelPrimary
    val ink2 = PicoTheme.colorScheme.labelSecondary
    val update = graph.settings::update
    val scroll = rememberScrollState()
    // The exit confirmation opens at the bottom of the page: bring it into view.
    androidx.compose.runtime.LaunchedEffect(state.confirm) { if (state.confirm == Confirm.EXIT) scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(stringResource(R.string.set_title), "set/title", PicoTheme.typography.titleLarge, ink)
        SettingRow(stringResource(R.string.set_language), "language") {
            SegmentControl(modifier = Modifier.width(300.dp)) {
                listOf(Language.SYSTEM to R.string.lang_system, Language.ZH to R.string.lang_zh, Language.EN to R.string.lang_en).forEach { (lang, label) ->
                    SegmentItem(
                        selected = settings.language == lang,
                        onClick = { update { it.copy(language = lang) } },
                        modifier = Modifier.weight(1f),
                        title = { T(stringResource(label), "set/lang/$lang", PicoTheme.typography.labelLarge, PicoTheme.colorScheme.labelPrimary) },
                    )
                }
            }
        }
        // Sound on / off and the three-stop volume share one row (the page fits the 640 dp window without scrolling).
        SettingRow(stringResource(R.string.set_sound_volume), "sound") {
            SegmentSlider(settings.volume.ordinal, Volume.entries.size - 1, { v -> update { it.copy(volume = Volume.entries[v.coerceIn(0, Volume.entries.size - 1)]) } }, Modifier.width(200.dp), enabled = settings.sound)
            HGap(12.dp)
            Switch(checked = settings.sound, onCheckedChange = { on -> update { it.copy(sound = on) } })
        }
        SettingRow(stringResource(R.string.set_reduced), "reduced") {
            Switch(checked = settings.reducedMotion, onCheckedChange = { on -> update { it.copy(reducedMotion = on) } })
        }
        SettingRow(stringResource(R.string.set_digits), "digits") {
            Switch(checked = settings.showDigits, onCheckedChange = { on -> update { it.copy(showDigits = on) } })
        }
        SettingRow(stringResource(R.string.set_tilt), "tilt") {
            SegmentControl(modifier = Modifier.width(180.dp)) {
                listOf(0, 15).forEach { deg ->
                    SegmentItem(
                        selected = settings.tiltDeg == deg,
                        onClick = { update { it.copy(tiltDeg = deg) } },
                        modifier = Modifier.weight(1f),
                        title = { T("$deg°", "set/tilt/$deg", PicoTheme.typography.labelLarge, PicoTheme.colorScheme.labelPrimary) },
                    )
                }
            }
        }
        SettingRow(stringResource(R.string.set_recall), "recall", hint = stringResource(R.string.set_recall_hint)) {
            SecondaryButton(stringResource(R.string.set_recall), "recall", { WindowCtl.recall() }, minWidth = 120.dp)
        }
        SettingRow(stringResource(R.string.set_about, VERSION) + " · " + stringResource(R.string.about_body), "about") {}
        Gap(6.dp)
        if (state.confirm == Confirm.EXIT) {
            ConfirmBar(
                text = stringResource(R.string.exit_title), body = stringResource(if (state.inProgress || state.phase == Phase.SETUP && state.saved != null) R.string.exit_body else R.string.exit_body_nogame), yes = stringResource(R.string.exit_yes), id = "confirm/EXIT",
                onYes = { graph.flow.confirmYes() }, onNo = { graph.flow.cancelConfirm() },
            )
        } else {
            SecondaryButton(stringResource(R.string.set_exit), "exit", { graph.flow.requestConfirm(Confirm.EXIT) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_exit)
        }
        Gap(8.dp)
    }
}

@Composable
private fun SettingRow(title: String, id: String, hint: String? = null, control: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, "set/$id", PicoTheme.typography.labelLarge, PicoTheme.colorScheme.labelPrimary, Modifier.fillMaxWidth(), maxLines = 2)
            if (hint != null) T(hint, "set/$id/hint", PicoTheme.typography.bodySmall, PicoTheme.colorScheme.labelSecondary, Modifier.fillMaxWidth(), maxLines = 2)
        }
        HGap(12.dp)
        control()
    }
}

internal const val VERSION = "0.1.4"
