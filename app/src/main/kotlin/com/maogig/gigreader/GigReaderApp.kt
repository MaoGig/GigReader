package com.maogig.gigreader

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.ui.theme.GigReaderTheme
import com.maogig.gigreader.di.AppContainer
import com.maogig.gigreader.diagnostics.DiagnosticsRoute
import com.maogig.gigreader.diagnostics.diagnosticsAvailable
import com.maogig.gigreader.feature.library.LibraryDependencies
import com.maogig.gigreader.feature.library.LibraryRoute
import com.maogig.gigreader.feature.library.TrashRoute
import com.maogig.gigreader.feature.notes.NoteEditorRoute
import com.maogig.gigreader.feature.reader.ReaderDependencies
import com.maogig.gigreader.feature.reader.ReaderRoute
import com.maogig.gigreader.feature.reader.ReaderSource
import com.maogig.gigreader.feature.settings.SettingsRoute
import com.maogig.gigreader.navigation.Destination
import com.maogig.gigreader.navigation.NavHost
import com.maogig.gigreader.navigation.rememberNavigator
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GigReaderApp(
    container: AppContainer,
    externalRequests: StateFlow<ExternalRequest?>,
    onRequestConsumed: () -> Unit,
) {
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    GigReaderTheme(themeMode = settings.themeMode, animationsEnabled = settings.animationsEnabled) {
        val navigator = rememberNavigator()
        BackHandler(enabled = navigator.canGoBack) { navigator.pop() }

        val request by externalRequests.collectAsStateWithLifecycle()
        LaunchedEffect(request) {
            when (val r = request) {
                null -> return@LaunchedEffect
                is ExternalRequest.View -> navigator.resetTo(Destination.ExternalReader(r.uri.toString()))
                is ExternalRequest.Import -> container.importManager.import(r.uris, folderId = null)
                ExternalRequest.NewNote -> {
                    val id = container.notesRepository.createNote()
                    navigator.navigate(Destination.NoteEditor(id))
                }
                is ExternalRequest.OpenDocument -> navigator.resetTo(Destination.Reader(r.documentId))
            }
            onRequestConsumed()
        }

        val libraryDeps = remember(container) {
            LibraryDependencies(
                library = container.libraryRepository,
                notes = container.notesRepository,
                importer = container.importManager,
                covers = container.coverRepository,
                settings = container.settingsRepository,
                files = container.fileStore,
            )
        }
        val readerDeps = remember(container) {
            ReaderDependencies(
                library = container.libraryRepository,
                reader = container.readerRepository,
                engine = container.pdfEngine,
                files = container.fileStore,
                settings = container.settingsRepository,
                importer = container.importManager,
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            // Exposes Compose test tags as resource ids for UiAutomator (Macrobenchmark).
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        ) {
            NavHost(navigator) { entry ->
                when (val d = entry.destination) {
                    is Destination.Library -> LibraryRoute(
                        folderId = d.folderId,
                        deps = libraryDeps,
                        onOpenFolder = { navigator.navigate(Destination.Library(it)) },
                        onOpenDocument = { navigator.navigate(Destination.Reader(it)) },
                        onOpenNote = { navigator.navigate(Destination.NoteEditor(it)) },
                        onOpenTrash = { navigator.navigate(Destination.Trash) },
                        onOpenSettings = { navigator.navigate(Destination.Settings) },
                        onNavigateUp = if (navigator.canGoBack) ({ navigator.pop() }) else null,
                    )
                    is Destination.Reader -> ReaderRoute(
                        source = ReaderSource.LibraryDocument(d.documentId),
                        deps = readerDeps,
                        onBack = { navigator.pop() },
                    )
                    is Destination.ExternalReader -> ReaderRoute(
                        source = ReaderSource.ExternalUri(d.uri),
                        deps = readerDeps,
                        onBack = { if (!navigator.pop()) navigator.resetTo(Destination.Library()) },
                    )
                    is Destination.NoteEditor -> NoteEditorRoute(
                        noteId = d.noteId,
                        notes = container.notesRepository,
                        onBack = { navigator.pop() },
                    )
                    Destination.Trash -> TrashRoute(deps = libraryDeps, onBack = { navigator.pop() })
                    Destination.Settings -> SettingsRoute(
                        settings = container.settingsRepository,
                        onBack = { navigator.pop() },
                        onOpenDiagnostics = if (diagnosticsAvailable) ({ navigator.navigate(Destination.Diagnostics) }) else null,
                    )
                    Destination.Diagnostics -> DiagnosticsRoute(container = container, onBack = { navigator.pop() })
                }
            }
        }
    }
}
