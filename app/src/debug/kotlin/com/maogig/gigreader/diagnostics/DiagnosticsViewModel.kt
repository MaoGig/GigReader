package com.maogig.gigreader.diagnostics

import android.content.ComponentCallbacks2
import android.os.Debug
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maogig.gigreader.core.data.importer.ImportProgress
import com.maogig.gigreader.core.pdf.render.RenderDiagnostics
import com.maogig.gigreader.core.pdf.render.RenderStats
import com.maogig.gigreader.di.AppContainer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One point-in-time reading. Nothing here is live: it changes only when the user asks. */
@Immutable
internal data class DiagnosticsSnapshot(
    val takenAtMillis: Long,
    val javaHeapUsedBytes: Long,
    val javaHeapMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    /** Total PSS in KiB, or `null` if the platform refused to report it. */
    val totalPssKb: Int?,
    val pipelines: List<RenderStats>,
    val databaseOpen: Boolean,
    /** `null` when the file does not exist (yet). */
    val databaseBytes: Long?,
    val databaseWalBytes: Long?,
    val libraryBytes: Long,
    val coversBytes: Long,
    /** `null` when the import manager was never created (the database is not open either). */
    val importProgress: ImportProgress?,
    val pdfEngineId: String,
)

@Immutable
internal data class DiagnosticsUiState(
    val busy: Boolean = false,
    val snapshot: DiagnosticsSnapshot? = null,
)

/**
 * Debug-only. Reads memory, render caches and storage on demand (open, Refresh, after an action);
 * there is deliberately no timer or polling so the tool itself does not disturb what it measures.
 */
internal class DiagnosticsViewModel(
    private val container: AppContainer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() = perform(action = null)

    /** Same path as a real `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` (called on the main thread). */
    fun trimCaches() = perform(action = { container.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) })

    fun forceGc() = perform(action = { Runtime.getRuntime().gc() }, actionOnIo = true)

    private fun perform(action: (() -> Unit)?, actionOnIo: Boolean = false) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            if (action != null && !actionOnIo) action()
            val snapshot = withContext(io) {
                if (action != null && actionOnIo) action()
                takeSnapshot()
            }
            _state.value = DiagnosticsUiState(busy = false, snapshot = snapshot)
        }
    }

    private fun takeSnapshot(): DiagnosticsSnapshot {
        val runtime = Runtime.getRuntime()
        val pssKb = try {
            Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        } catch (e: RuntimeException) {
            null
        }
        // Read before touching importManager: creating it initializes the database lazily.
        val databaseOpen = container.isDatabaseOpen()
        val databaseFile = container.databaseFile()
        val walFile = File(databaseFile.path + "-wal")
        return DiagnosticsSnapshot(
            takenAtMillis = System.currentTimeMillis(),
            javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaHeapMaxBytes = runtime.maxMemory(),
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            totalPssKb = pssKb,
            pipelines = RenderDiagnostics.snapshot(),
            databaseOpen = databaseOpen,
            databaseBytes = databaseFile.takeIf { it.isFile }?.length(),
            databaseWalBytes = walFile.takeIf { it.isFile }?.length(),
            libraryBytes = container.fileStore.totalSize(),
            coversBytes = container.coverStore.totalSize(),
            importProgress = if (databaseOpen) container.importManager.progress.value else null,
            pdfEngineId = container.pdfEngine.id,
        )
    }
}
