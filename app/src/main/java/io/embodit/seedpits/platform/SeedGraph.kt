package io.embodit.seedpits.platform

import android.content.Context
import android.util.Log
import io.embodit.seedpits.data.FileGameRepository
import io.embodit.seedpits.data.PrefsSettingsRepository
import io.embodit.seedpits.data.SettingsRepository
import io.embodit.seedpits.domain.game.Cue
import io.embodit.seedpits.domain.game.GameFlow
import io.embodit.seedpits.ui.board.SoundKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide objects shared by the volume and the control window (native-window-move §3.4). */
class SeedGraph(context: Context) {
    val settings: SettingsRepository = PrefsSettingsRepository(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sounds = SoundPlayer(context)
    val flow = GameFlow(
        repo = FileGameRepository(context),
        settings = settings,
        scope = scope,
        clock = { android.os.SystemClock.elapsedRealtime() },
        log = { Log.i(TAG, it) },
        cue = { c ->
            sounds.play(
                when (c) {
                    Cue.ILLEGAL -> SoundKind.ILLEGAL
                    Cue.AGAIN -> SoundKind.AGAIN
                    Cue.WIN -> SoundKind.WIN
                    Cue.LOSE -> SoundKind.LOSE
                    Cue.DRAW -> SoundKind.DRAW
                },
            )
        },
    )

    init {
        flow.onExit = { io.embodit.seedpits.ui.control.exitApp(context.applicationContext, this) }
    }

    companion object {
        const val TAG = "SeedPits"
        fun from(context: Context): SeedGraph = (context.applicationContext as SpatialApplication).graph
    }
}
