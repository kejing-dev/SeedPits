package io.embodit.seedpits.ui.board

import android.os.SystemClock
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import io.embodit.seedpits.platform.HeadTracker
import io.embodit.seedpits.ui.window.WindowCtl

/**
 * Once per rendered frame inside the ECS: the board's animation, and the window level gate's head-pitch feed.
 * The SDK instantiates the system itself, so it finds the scene through [BoardSceneHolder].
 */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        BoardSceneHolder.scene?.onFrame(context.deltaTime)
        WindowCtl.stepGate(SystemClock.elapsedRealtime(), HeadTracker.pitchDeg())
    }
}

/** The board currently on screen (at most one). */
object BoardSceneHolder {
    @Volatile var scene: BoardScene? = null
}
