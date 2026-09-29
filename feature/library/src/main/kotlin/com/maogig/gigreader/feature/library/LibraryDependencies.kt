package com.maogig.gigreader.feature.library

import androidx.compose.runtime.Immutable
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.data.importer.ImportManager
import com.maogig.gigreader.core.data.library.CoverRepository
import com.maogig.gigreader.core.data.library.LibraryRepository
import com.maogig.gigreader.core.data.notes.NotesRepository
import com.maogig.gigreader.core.data.settings.SettingsRepository

/**
 * Everything the library screens need, passed explicitly (manual DI, docs/ARCHITECTURE.md §2.3).
 * The app creates one instance and remembers it, so it is stable across recompositions.
 */
@Immutable
class LibraryDependencies(
    val library: LibraryRepository,
    val notes: NotesRepository,
    val importer: ImportManager,
    val covers: CoverRepository,
    val settings: SettingsRepository,
    val files: DocumentFileStore,
)
