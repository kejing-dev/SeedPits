package io.embodit.seedpits

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.seedpits.platform.SeedGraph
import io.embodit.seedpits.ui.board.BoardScreen
import io.embodit.seedpits.ui.control.ControlScreen
import io.embodit.seedpits.ui.theme.SeedTheme
import io.embodit.seedpits.ui.window.WindowCtl

/**
 * Two surfaces in Shared Space (design-package windowModel): the board volume (default container, 0.72 × 0.28 ×
 * 0.32 m from the manifest) and the control window (Planar 560 × 440 dp) to its right.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            AppRoot { BoardScreen() }
        }
        WindowContainer(
            WindowCtl.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 440.dp),
            placement = { containers.firstOrNull { it.id == WindowCtl.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            AppRoot {
                ControlScreen(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 400.dp, maxWidth = 900.dp, maxHeight = 640.dp))
            }
        }
    }

/** The theme of every window, following the in-app language setting. */
@Composable
fun AppRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { SeedGraph.from(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    SeedTheme(language = settings.language, content = content)
}
