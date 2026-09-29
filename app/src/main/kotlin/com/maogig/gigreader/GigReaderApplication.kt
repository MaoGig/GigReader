package com.maogig.gigreader

import android.app.Application
import com.maogig.gigreader.di.AppContainer

class GigReaderApplication : Application() {
    /** Created eagerly but cheap: every dependency inside is lazy. */
    lateinit var container: AppContainer
        private set

    /**
     * [container], or null before [onCreate] ran: content providers are created, and may be
     * called on binder threads, before the application's onCreate.
     */
    val containerOrNull: AppContainer?
        get() = if (::container.isInitialized) container else null

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
