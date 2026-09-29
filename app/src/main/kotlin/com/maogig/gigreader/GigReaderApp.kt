package com.maogig.gigreader

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maogig.gigreader.core.data.importer.ImportProgress
import com.maogig.gigreader.core.model.ThemeMode
import com.maogig.gigreader.core.ui.theme.GigReaderTheme
import com.maogig.gigreader.core.ui.theme.LocalUiPreferences
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GigReaderApp(
    container: AppContainer,
    externalRequests: StateFlow<ExternalRequest?>,
    onRequestConsumed: (ExternalRequest) -> Unit,
) {
    // Nothing is composed until the stored settings are known: until then the window background of
    // the XML theme shows, instead of a frame in the default theme that a Light or AMOLED user would
    // see flash. The value outlives the activity, so a recreated activity themes its first frame.
    val settings by container.settings.collectAsStateWithLifecycle()
    val loaded = settings ?: return
    GigReaderTheme(themeMode = loaded.themeMode, animationsEnabled = loaded.animationsEnabled) {
        SystemBarsFollowTheme(isDark = LocalUiPreferences.current.isDark, amoled = loaded.themeMode == ThemeMode.AMOLED)
        val navigator = rememberNavigator()
        val activity = LocalActivity.current
        val resources = LocalResources.current
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val importer = container.importManager

        // Key of the external reader that an "Open with" launch created: Back from it (when it is the
        // only screen above the library root) returns to the calling app instead of the library.
        var callerEntryKey by rememberSaveable { mutableStateOf<String?>(null) }
        val goBack: () -> Unit = {
            if (navigator.backStack.size == 2 && navigator.current.key == callerEntryKey) {
                leaveApp(activity, importer.progress.value)
            } else {
                navigator.pop()
            }
        }
        BackHandler(enabled = navigator.canGoBack) { goBack() }
        KeepTaskWhileImporting(atRoot = !navigator.canGoBack, progress = importer.progress)

        val request by externalRequests.collectAsStateWithLifecycle()
        LaunchedEffect(request) {
            val r = request ?: return@LaunchedEffect
            when (r) {
                // On top of whatever the user had open: Back returns there (or to the calling app).
                is ExternalRequest.View -> {
                    navigator.navigate(Destination.ExternalReader(r.uri.toString()))
                    if (r.startedActivity) callerEntryKey = navigator.current.key
                }
                is ExternalRequest.Import -> importer.import(r.uris, folderId = null)
                ExternalRequest.NewNote -> {
                    val id = try {
                        container.notesRepository.createNote()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // e.g. disk full: tell the user instead of crashing the composition
                    }
                    if (id != null) {
                        navigator.navigate(Destination.NoteEditor(id))
                    } else {
                        val text = resources.getString(R.string.app_note_create_failed)
                        scope.launch { snackbarHostState.showSnackbar(text) }
                    }
                }
                is ExternalRequest.OpenDocument -> navigator.resetTo(Destination.Reader(r.documentId))
            }
            onRequestConsumed(r)
        }

        // Import results for screens other than the library (which shows its own): files shared
        // from another app while reading, "Add to library" in the external reader, and so on.
        ImportFeedback(
            outcomes = importer.outcomes,
            host = snackbarHostState,
            reportHere = { navigator.current.destination !is Destination.Library },
            onOpenDocument = { navigator.navigate(Destination.Reader(it)) },
        )

        val libraryDeps = remember(container) {
            LibraryDependencies(
                library = container.libraryRepository,
                notes = container.notesRepository,
                importer = importer,
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
                importer = importer,
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
                        // Breadcrumbs and the folder panel may name a folder already in the stack.
                        onOpenFolder = { navigator.navigateOrPopTo(Destination.Library(it)) },
                        onOpenDocument = { navigator.navigate(Destination.Reader(it)) },
                        onOpenNote = { navigator.navigate(Destination.NoteEditor(it)) },
                        onOpenTrash = { navigator.navigate(Destination.Trash) },
                        onOpenSettings = { navigator.navigate(Destination.Settings) },
                        onNavigateUp = if (navigator.canGoBack) goBack else null,
                        onOpenRoot = { navigator.popToRoot() },
                    )
                    is Destination.Reader -> ReaderRoute(
                        source = ReaderSource.LibraryDocument(d.documentId),
                        deps = readerDeps,
                        onBack = goBack,
                    )
                    is Destination.ExternalReader -> ReaderRoute(
                        source = ReaderSource.ExternalUri(d.uri),
                        deps = readerDeps,
                        onBack = goBack,
                    )
                    is Destination.NoteEditor -> NoteEditorRoute(
                        noteId = d.noteId,
                        notes = container.notesRepository,
                        onBack = goBack,
                    )
                    Destination.Trash -> TrashRoute(deps = libraryDeps, onBack = goBack)
                    Destination.Settings -> SettingsRoute(
                        settings = container.settingsRepository,
                        onBack = goBack,
                        onOpenDiagnostics = if (diagnosticsAvailable) ({ navigator.navigate(Destination.Diagnostics) }) else null,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                    Destination.Diagnostics -> DiagnosticsRoute(container = container, onBack = goBack)
                }
            }
            // Overlay above the screens (no pointer input of its own: touches reach the screen).
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val onReader = navigator.current.destination.let { it is Destination.Reader || it is Destination.ExternalReader }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        // Clears the reader's bottom bar (same offset as the reader's own snackbars).
                        .padding(bottom = if (onReader) 72.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * Leaves the app from its last screen. While imports are pending the task only moves to the back:
 * on API 26–30 the read grants of shared URIs end with the activity, so finishing it would make
 * every file not copied yet fail.
 */
private fun leaveApp(activity: Activity?, progress: ImportProgress) {
    activity ?: return
    if (progress.active) activity.moveTaskToBack(true) else activity.finish()
}

/**
 * Back on the library root normally finishes the activity (API 26–30), which revokes the grants of
 * files shared with the app that are still queued. While imports are pending, Back only moves the
 * task to the back. Enabled only then, so the system's default Back (and its predictive animation)
 * applies otherwise.
 */
@Composable
private fun KeepTaskWhileImporting(atRoot: Boolean, progress: StateFlow<ImportProgress>) {
    val activity = LocalActivity.current
    // `active` flips twice per batch; the byte counters of the progress never recompose this. The
    // StateFlow's current value arrives with the first collection (no StateFlow.value read during
    // composition, which Compose lint rejects).
    val active by remember(progress) { progress.map { it.active }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    BackHandler(enabled = atRoot && active) { activity?.moveTaskToBack(true) }
}

/**
 * Keeps status/navigation bar icons readable when the in-app theme differs from the system's
 * night mode (e.g. Light theme on a dark system). Re-applied only when the theme changes. AMOLED
 * gets a true black navigation bar instead of the grey translucent scrim.
 */
@Composable
private fun SystemBarsFollowTheme(isDark: Boolean, amoled: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(activity, isDark, amoled) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { isDark },
            navigationBarStyle = if (amoled) {
                SystemBarStyle.dark(AndroidColor.BLACK)
            } else {
                SystemBarStyle.auto(LightScrim, DarkScrim) { isDark }
            },
        )
    }
}

// Same scrims enableEdgeToEdge uses by default for 3-button navigation.
private val LightScrim = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
