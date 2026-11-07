package io.embodit.seedpits.ui.board

import com.pico.spatial.core.ecs.Entity
import io.embodit.seedpits.domain.game.GameFlow
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.ui.board.input.TargetResolver

/** What a pinch / trigger / gaze-pinch can land on in the volume (board-input template, profile D50). */
sealed interface SeedTarget {
    /** One of the 12 pits: its collider parent `pit:<cell>` or the visible rim marker `pitMark:<cell>` under it. */
    data class Pit(val cell: Int) : SeedTarget
    /** The thin collider on the board top (a tap there cancels a preview). */
    data object Board : SeedTarget
    /** The seat plate (a tap reopens the control window when it is closed). */
    data object Plate : SeedTarget
}

/**
 * Entity name → target. The board-input layer applies it up the parent chain, so a hit on the rim marker (a child of
 * the collider) or on any other child mesh still resolves to its pit. Stones have no colliders (D17: a stone in a pit
 * is reached through the pit's collider, which covers the pile).
 */
object SeedTargets : TargetResolver<SeedTarget> {
    override fun resolve(entity: Entity): SeedTarget? = fromName(runCatching { entity.getName() }.getOrNull())

    fun fromName(name: String?): SeedTarget? {
        if (name == null) return null
        return when {
            name.startsWith("pit:") -> name.removePrefix("pit:").toIntOrNull()?.takeIf { it in (0..5) + (7..12) }?.let { SeedTarget.Pit(it) }
            name.startsWith("pitMark:") -> name.removePrefix("pitMark:").toIntOrNull()?.takeIf { it in (0..5) + (7..12) }?.let { SeedTarget.Pit(it) }
            name == BoardScene.BOARD_NAME -> SeedTarget.Board
            name == BoardScene.PLATE_NAME -> SeedTarget.Plate
            else -> null
        }
    }

    /** The same walk as `resolveUp`, on names (child first, then its parents) — for the hit-replay unit test. */
    fun resolveChain(namesChildFirst: List<String>): SeedTarget? = namesChildFirst.firstNotNullOfOrNull { fromName(it) }
}

/**
 * The one entry from a resolved tap into the game (real input and replay tests go through here): a pit → the same
 * `tapPit` the debug channel uses; the board or nothing → leave the preview; the plate → reopen the panel when closed.
 */
object SeedTapDispatcher {
    fun dispatch(target: SeedTarget?, flow: GameFlow, panelOpen: Boolean, openPanel: () -> Unit) {
        when (target) {
            is SeedTarget.Pit -> if (target.cell in 0..12 && !Kalah.isStore(target.cell)) flow.tapPit(target.cell)
            SeedTarget.Plate -> if (!panelOpen) openPanel() else flow.cancelPreview()
            SeedTarget.Board, null -> flow.cancelPreview()
        }
    }
}
