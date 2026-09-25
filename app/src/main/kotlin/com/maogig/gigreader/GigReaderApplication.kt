package com.maogig.gigreader

import android.app.Application
import com.maogig.gigreader.di.AppContainer

class GigReaderApplication : Application() {
    /** Created eagerly but cheap: every dependency inside is lazy. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Intentionally nothing else: no database, no PDF engine, no cleanup, no sync at startup.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.onTrimMemory(level)
    }
}
