package io.embodit.seedpits.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.seedpits.platform.HeadTracker

/**
 * The control window's opening, closing and recall (native-window-move §3.5 / §7.9). Every open goes through
 * the [LevelGate]; the volume's frame system calls [stepGate] each frame. While the gate waits with the head
 * tipped, the seat plate shows "Look straight ahead" ([levelHint]).
 */
object WindowCtl {
    const val CONTROL = "SeedControl"
    const val CONTROL_TAG = "control"
    const val BOARD = "SeedBoard"

    private const val TAG = "SeedPits"
    private val gate = LevelGate()
    private val main = Handler(Looper.getMainLooper())
    private var navigator: SpatialNavigator? = null
    private var lastStepMs = 0L
    private var recallPending = false

    /** "never", then the window's lifecycle events (ON_CREATE … ON_DESTROY). */
    var panelState by mutableStateOf("never")
    var levelHint by mutableStateOf(false)
        private set
    /**
     * After a recall the window reopens with Placement.none() (profile D43) instead of Placement.right(board).
     * Emulator 6.2.0: the reopened window still lands beside the board (only a cold start follows the head), see ui-audit §11.
     */
    @Volatile var inFront = false
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY"
    val waiting: Boolean get() = gate.waiting

    fun attach(nav: SpatialNavigator) { navigator = nav }

    fun detach(nav: SpatialNavigator) { if (navigator === nav) { navigator = null; gate.cancel(); main.removeCallbacks(watchdog) } }

    /** Ask for the window; it opens once the head is level (or there is no head pose to wait for). */
    fun openWindow(why: String) {
        if (HeadTracker.supported == false) { openNow("$why/nohead-unsupported"); return }
        gate.ask(why, SystemClock.elapsedRealtime())
        Log.i(TAG, "WINDOW ask why=$why")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /** "叫回面前": close, wait for ON_DESTROY, then open through the gate (a reopened window comes up in front). */
    fun recall() {
        inFront = true
        if (!panelOpen) { openWindow("recall"); return }
        recallPending = true
        Log.i(TAG, "WINDOW recall: close first")
        runCatching { navigator?.closeWindowContainer(CONTROL, CONTROL_TAG) }
    }

    /** Called by the control window's lifecycle observer. */
    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            openWindow("recall")
        }
    }

    fun close() {
        gate.cancel()
        runCatching { navigator?.closeWindowContainer(CONTROL, CONTROL_TAG) }
    }

    /** Exit (D26 / D43): close the control window and the board volume itself (the navigator belongs to the board). */
    fun closeAll() {
        gate.cancel()
        main.removeCallbacks(watchdog)
        val nav = navigator ?: return
        runCatching { nav.closeWindowContainer(CONTROL, CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { nav.closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close board: $it") }
        Log.i(TAG, "EXIT closed all window containers")
    }

    /** Every frame from the volume's ECS system. */
    fun stepGate(now: Long, pitchDeg: Float?) {
        lastStepMs = now
        levelHint = gate.hintWanted(pitchDeg)
        gate.step(now, pitchDeg)?.let { levelHint = false; openNow(it) }
    }

    private fun openNow(why: String) {
        main.removeCallbacks(watchdog)
        val nav = navigator
        if (nav == null) { Log.w(TAG, "WINDOW open why=$why: no navigator"); return }
        Log.i(TAG, "WINDOW open why=$why inFront=$inFront")
        runCatching { nav.openWindowContainer(CONTROL, CONTROL_TAG) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) {
                val why = gate.pending ?: return
                gate.cancel(); levelHint = false
                openNow("$why/noframes")
            } else main.postDelayed(this, 3000)
        }
    }
}
