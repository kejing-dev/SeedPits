package io.embodit.seedpits.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.seedpits.mainApp

class SpatialApplication : Application() {
    lateinit var graph: SeedGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = SeedGraph(this)
        graph.flow.start()
        launch(::mainApp)
    }
}
