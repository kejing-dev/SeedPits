package io.embodit.seedpits.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.game.AlertKind
import io.embodit.seedpits.domain.game.Confirm
import io.embodit.seedpits.domain.game.FirstMover
import io.embodit.seedpits.domain.game.GameConfig
import io.embodit.seedpits.domain.game.Language
import io.embodit.seedpits.domain.game.Page
import io.embodit.seedpits.domain.game.Phase
import io.embodit.seedpits.domain.game.Volume
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Position
import io.embodit.seedpits.ui.board.BoardSceneHolder
import io.embodit.seedpits.ui.board.DebugFlags
import io.embodit.seedpits.ui.window.WindowCtl

/**
 * Debug builds only. Every command calls the same GameFlow functions as real input (profile D29: no shortcuts).
 *
 *     adb shell am broadcast -a io.embodit.seedpits.DEBUG -p io.embodit.seedpits --es cmd tap --es a d
 *
 * cmd (+ `--es a <arg>`):
 *   tap a–f|A–F · sow a–f (tap twice) · play (= [播]) · cancel · pos 4,4,…(14),S|N · new <level 1–5>[,me|them|random]
 *   undo · redo · hint on|off · more · resign · restart · yes · no · again · menu · continue · welcome
 *   page play|new|rules|settings · lang system|zh|en · sound on|off · volume 0|1|2 · reduced on|off
 *   digits on|off · tilt 0|15 · freeze on|off · finish (end the animation now) · recall · open
 *   goto S00…S16 (see AGENTS.md) · tex F|off (D10 test glyph) · light <intensity> · state · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = SeedGraph.from(context)
        val flow = graph.flow
        val a = intent.getStringExtra("a").orEmpty().trim()
        val on = a == "on" || a == "true" || a == "1"
        val cmd = intent.getStringExtra("cmd").orEmpty()
        fun pit(s: String) = s.firstOrNull()?.let { Kalah.pitOf(it) }
        when (cmd) {
            "tap" -> pit(a)?.let { flow.tapPit(it) }
            "sow" -> pit(a)?.let { flow.tapPit(it); flow.tapPit(it) }
            "play" -> flow.confirmSow()
            "cancel" -> flow.cancelPreview()
            "pos" -> Position.parse(a.replace(";", "/"))?.let { flow.startFrom(it) } ?: Log.w(TAG, "bad pos $a")
            "new" -> {
                val parts = a.split(",")
                val level = Level.of(parts.getOrNull(0)?.toIntOrNull() ?: 1)
                val first = when (parts.getOrNull(1)?.trim()) { "them" -> FirstMover.THEM; "random" -> FirstMover.RANDOM; else -> FirstMover.ME }
                flow.startGame(GameConfig(level, first))
            }
            "undo" -> flow.undo()
            "redo" -> flow.redo()
            "hint" -> flow.setHint(on)
            "more" -> flow.toggleMore()
            "resign" -> flow.requestConfirm(Confirm.RESIGN)
            "restart" -> flow.requestConfirm(Confirm.RESTART)
            "exit" -> { flow.setPage(Page.SETTINGS); flow.requestConfirm(Confirm.EXIT) }
            "yes" -> flow.confirmYes()
            "no" -> flow.cancelConfirm()
            "again" -> flow.playAgain()
            "menu" -> flow.toMenu()
            "continue" -> flow.continueSaved()
            "welcome" -> flow.welcomeDone()
            "page" -> flow.setPage(when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY })
            "lang" -> graph.settings.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> graph.settings.update { it.copy(sound = on) }
            "volume" -> graph.settings.update { it.copy(volume = Volume.entries[(a.toIntOrNull() ?: 2).coerceIn(0, 2)]) }
            "reduced" -> graph.settings.update { it.copy(reducedMotion = on) }
            "digits" -> graph.settings.update { it.copy(showDigits = on) }
            "tilt" -> graph.settings.update { it.copy(tiltDeg = if (a == "0") 0 else 15) }
            "freeze" -> flow.freeze(on)
            "finish" -> BoardSceneHolder.scene?.finishNow()
            "recall" -> WindowCtl.recall()
            "open" -> WindowCtl.openWindow("debug")
            "tex" -> DebugFlags.testGlyph = a.equals("F", ignoreCase = true)
            "light" -> a.toFloatOrNull()?.let { BoardSceneHolder.scene?.lightIntensity = it }
            "reset" -> graph.settings.update { it.copy(firstRunDone = false, coachDone = false, streak = 0) }
            "goto" -> goto(a.uppercase(), graph)
            "state", "dump" -> Log.i(TAG, "DUMP ${flow.state.value.copy(saved = null)} lastThinkMs=${flow.lastThinkMs} panel=${WindowCtl.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${BoardSceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
        val s = flow.state.value
        Log.i(TAG, "CMD $cmd a=$a phase=${s.phase} page=${s.page} pos=${s.position} preview=${s.previewPit} alert=${s.alert?.kind}")
    }

    /** Reaches a named state (design-package states[]) through the normal flow functions. */
    private fun goto(id: String, graph: SeedGraph) {
        val flow = graph.flow
        flow.freeze(false)
        when (id) {
            "S01" -> { graph.settings.update { it.copy(firstRunDone = false) }; flow.toMenu(); flow.setPage(Page.RULES) }
            "S02" -> flow.toMenu()
            "S03" -> { if (flow.state.value.phase !in listOf(Phase.IDLE, Phase.PREVIEW)) flow.startGame(flow.state.value.config.copy(first = FirstMover.ME)) else flow.cancelPreview(); flow.setPage(Page.PLAY) }
            "S04" -> { goto("S03", graph); flow.tapPit(3) }
            "S06" -> { flow.startGame(GameConfig(Level.MASTER, FirstMover.THEM)); flow.freeze(true) }
            "S07" -> { flow.startFrom(Position.parse("4,4,4,4,4,1,0,4,4,4,4,4,4,3/S")!!); flow.setPage(Page.PLAY); flow.showAgainNotice() }
            "S08" -> { goto("S03", graph); flow.freeze(true); flow.alert(AlertKind.THEIRS, 12) }
            "S09" -> { flow.startFrom(Position.parse("0,0,0,0,0,1,26,2,1,1,1,1,1,14/S")!!); flow.tapPit(5); flow.tapPit(5) }
            "S11" -> flow.setPage(Page.SETTINGS)
            "S12" -> flow.setPage(Page.RULES)
            "S13" -> { goto("S03", graph); flow.requestConfirm(Confirm.RESIGN) }
            else -> Log.w(TAG, "goto $id: not a v1 state (S00 is cold start, S05 = sow, S16 = force-stop + relaunch)")
        }
    }

    private companion object { const val TAG = "SeedPits" }
}
