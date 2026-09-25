package com.maogig.gigreader.diagnostics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.core.data.importer.ImportProgress
import com.maogig.gigreader.core.pdf.render.RenderStats
import com.maogig.gigreader.core.ui.components.SectionHeader
import com.maogig.gigreader.core.ui.components.formatFileSize
import com.maogig.gigreader.core.ui.theme.TabularNumbers
import com.maogig.gigreader.di.AppContainer
import java.text.DateFormat
import java.util.Date

// Developer tool, debug builds only (plan §67). Text is intentionally hardcoded English: this is
// the single exception to the string-resources rule. The release source set has a no-op twin.

/** Debug builds ship the diagnostics screen. */
val diagnosticsAvailable: Boolean = true

@Composable
fun DiagnosticsRoute(container: AppContainer, onBack: () -> Unit) {
    val factory = remember(container) { viewModelFactory { initializer { DiagnosticsViewModel(container) } } }
    val viewModel: DiagnosticsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onTrimCaches = viewModel::trimCaches,
        onForceGc = viewModel::forceGc,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTrimCaches: () -> Unit,
    onForceGc: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Diagnostics", modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val snapshot = state.snapshot
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item(key = "actions", contentType = "actions") {
                ActionsRow(
                    busy = state.busy,
                    onRefresh = onRefresh,
                    onTrimCaches = onTrimCaches,
                    onForceGc = onForceGc,
                )
            }
            if (snapshot == null) {
                item(key = "reading", contentType = "stat") { StatRow(label = "Status", value = "reading…") }
            } else {
                snapshotItems(snapshot)
            }
        }
    }
}

private fun LazyListScope.snapshotItems(s: DiagnosticsSnapshot) {
    header(key = "h_memory", title = "Memory")
    stat(key = "java_heap", label = "Java heap (used / max)", value = "${formatFileSize(s.javaHeapUsedBytes)} / ${formatFileSize(s.javaHeapMaxBytes)}")
    stat(key = "native_heap", label = "Native heap (allocated)", value = formatFileSize(s.nativeHeapAllocatedBytes))
    stat(key = "pss", label = "Total PSS", value = s.totalPssKb?.let { formatFileSize(it * 1024L) } ?: "unavailable")

    header(key = "h_render", title = "Render pipelines (${s.pipelines.size})")
    if (s.pipelines.isEmpty()) {
        stat(key = "render_none", label = "Open documents", value = "none")
    } else {
        itemsIndexed(
            items = s.pipelines,
            key = { index, _ -> "pipeline_$index" },
            contentType = { _, _ -> "pipeline" },
        ) { index, stats ->
            PipelineCard(index = index, stats = stats)
        }
    }

    header(key = "h_storage", title = "Storage")
    stat(key = "database", label = "Database", value = databaseText(s))
    stat(key = "library", label = "Library files", value = formatFileSize(s.libraryBytes))
    stat(key = "covers", label = "Covers cache", value = formatFileSize(s.coversBytes))

    header(key = "h_work", title = "Background work")
    stat(key = "import", label = "Import queue", value = importText(s.importProgress))
    stat(key = "search", label = "Search index", value = "not built yet")

    header(key = "h_engine", title = "PDF engine")
    stat(key = "engine", label = "Engine id", value = s.pdfEngineId)

    val takenAt = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(s.takenAtMillis))
    stat(key = "taken_at", label = "Snapshot taken at", value = takenAt)
}

private fun LazyListScope.header(key: String, title: String) {
    item(key = key, contentType = "header") { SectionHeader(title = title) }
}

private fun LazyListScope.stat(key: String, label: String, value: String) {
    item(key = key, contentType = "stat") { StatRow(label = label, value = value) }
}

@Composable
private fun ActionsRow(
    busy: Boolean,
    onRefresh: () -> Unit,
    onTrimCaches: () -> Unit,
    onForceGc: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
        OutlinedButton(onClick = onTrimCaches, enabled = !busy) { Text("Trim caches") }
        OutlinedButton(onClick = onForceGc, enabled = !busy) { Text("Force GC") }
    }
}

@Composable
private fun PipelineCard(index: Int, stats: RenderStats) {
    val lookups = stats.hits + stats.misses
    val hitRate = if (lookups > 0) stats.hits * 100 / lookups else 0L
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "Document ${index + 1}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { heading() },
            )
            StatRow(label = "Cached bitmaps", value = stats.cachedBitmaps.toString())
            StatRow(
                label = "Cache (used / budget)",
                value = "${formatFileSize(stats.cacheBytes)} / ${formatFileSize(stats.cacheBudgetBytes)}",
            )
            StatRow(label = "Bitmap pool", value = formatFileSize(stats.poolBytes))
            StatRow(label = "Hits / misses", value = "${stats.hits} / ${stats.misses} ($hitRate%)")
            StatRow(label = "Renders completed", value = stats.rendersCompleted.toString())
            StatRow(label = "Skipped as stale", value = stats.rendersSkippedStale.toString())
            StatRow(label = "Last render", value = "${stats.lastRenderMillis} ms")
            StatRow(label = "Failed pages", value = stats.failedPages.toString())
            StatRow(label = "Worker", value = if (stats.busy) "busy" else "idle")
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            textAlign = TextAlign.End,
        )
    }
}

private fun databaseText(s: DiagnosticsSnapshot): String {
    val size = s.databaseBytes?.let { formatFileSize(it) } ?: "no file yet"
    val wal = s.databaseWalBytes?.let { " + WAL ${formatFileSize(it)}" } ?: ""
    val open = if (s.databaseOpen) "open" else "not opened"
    return "$size$wal · $open"
}

private fun importText(progress: ImportProgress?): String = when {
    progress == null -> "idle (not created this session)"
    !progress.active -> "idle"
    else -> buildString {
        append(progress.completed).append('/').append(progress.total)
        progress.currentName?.let { append(" · ").append(it) }
        if (progress.bytesTotal > 0) {
            append(" · ").append(formatFileSize(progress.bytesCopied))
            append(" / ").append(formatFileSize(progress.bytesTotal))
        }
    }
}
