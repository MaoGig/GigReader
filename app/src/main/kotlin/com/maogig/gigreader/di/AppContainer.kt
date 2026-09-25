package com.maogig.gigreader.di

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.data.importer.ImportManager
import com.maogig.gigreader.core.data.library.CoverRepository
import com.maogig.gigreader.core.data.library.CoverStore
import com.maogig.gigreader.core.data.library.LibraryRepository
import com.maogig.gigreader.core.data.library.LocalLibraryRepository
import com.maogig.gigreader.core.data.notes.LocalNotesRepository
import com.maogig.gigreader.core.data.notes.NotesRepository
import com.maogig.gigreader.core.data.reader.LocalReaderRepository
import com.maogig.gigreader.core.data.reader.ReaderRepository
import com.maogig.gigreader.core.data.settings.DataStoreSettingsRepository
import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import com.maogig.gigreader.core.pdf.framework.FrameworkPdfEngine
import com.maogig.gigreader.core.pdf.render.RenderDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual dependency injection (§73: no DI framework). Everything is `lazy`: constructing the
 * container costs nothing, and the database, engine and caches are created on first real use —
 * never during Application.onCreate (§46).
 */
class AppContainer(private val app: Application) {
    /** Process-lifetime scope for work that must outlive a screen (imports). Idle = no threads. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val databaseDelegate = lazy { GigReaderDatabase.create(app) }
    val database: GigReaderDatabase by databaseDelegate

    val fileStore: DocumentFileStore by lazy { DocumentFileStore(File(app.filesDir, "library")) }

    val coverStore: CoverStore by lazy { CoverStore(File(app.cacheDir, "covers")) }

    val pdfEngine: PdfEngine by lazy { FrameworkPdfEngine() }

    val libraryRepository: LibraryRepository by lazy { LocalLibraryRepository(database, fileStore, coverStore) }

    val readerRepository: ReaderRepository by lazy { LocalReaderRepository(database) }

    val notesRepository: NotesRepository by lazy { LocalNotesRepository(database) }

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(
            // DataStore runs its file reads/writes in this scope, so it must be the IO dispatcher
            // (the Default-dispatcher applicationScope would do disk I/O on CPU threads).
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                app.preferencesDataStoreFile("settings")
            },
        )
    }

    val importManager: ImportManager by lazy {
        ImportManager(app.contentResolver, database, fileStore, coverStore, pdfEngine, applicationScope)
    }

    private val coverDelegate = lazy { CoverRepository(database, fileStore, coverStore, pdfEngine) }
    val coverRepository: CoverRepository by coverDelegate

    fun databaseFile(): File = app.getDatabasePath(GigReaderDatabase.NAME)

    fun isDatabaseOpen(): Boolean = databaseDelegate.isInitialized()

    /** Frees caches under memory pressure; never initializes anything that is not already alive. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (coverDelegate.isInitialized()) coverRepository.trimMemory()
            RenderDiagnostics.trimAll()
        }
    }
}
