package com.maogig.gigreader.diagnostics

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.R
import com.maogig.gigreader.core.data.importer.ImportProgress
import com.maogig.gigreader.core.pdf.render.RenderStats
import com.maogig.gigreader.core.ui.components.SectionHeader
import com.maogig.gigreader.core.ui.components.formatFileSize
import com.maogig.gigreader.core.ui.theme.TabularNumbers
import com.maogig.gigreader.di.AppContainer
import java.text.DateFormat
import java.util.Date

// Developer tool, debug builds only (docs/ARCHITECTURE.md §12, item 7). Its text lives in
// app/src/debug/res (en + pt-BR). The release source set has a no-op twin with the same signatures.

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
                title = {
                    Text(
                        text = stringResource(R.string.app_diagnostics_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.app_diagnostics_back),
                        )
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
                stat(key = "reading", label = R.string.app_diagnostics_status) {
                    stringResource(R.string.app_diagnostics_reading)
                }
            } else {
                snapshotItems(snapshot)
            }
        }
    }
}

private fun LazyListScope.snapshotItems(s: DiagnosticsSnapshot) {
    header(key = "h_memory") { stringResource(R.string.app_diagnostics_section_memory) }
    stat(key = "java_heap", label = R.string.app_diagnostics_java_heap) {
        "${formatFileSize(s.javaHeapUsedBytes)} / ${formatFileSize(s.javaHeapMaxBytes)}"
    }
    stat(key = "native_heap", label = R.string.app_diagnostics_native_heap) {
        formatFileSize(s.nativeHeapAllocatedBytes)
    }
    stat(key = "pss", label = R.string.app_diagnostics_total_pss) {
        val pssKb = s.totalPssKb
        if (pssKb != null) formatFileSize(pssKb * 1024L) else stringResource(R.string.app_diagnostics_unavailable)
    }

    header(key = "h_render") { stringResource(R.string.app_diagnostics_section_render, s.pipelines.size) }
    if (s.pipelines.isEmpty()) {
        stat(key = "render_none", label = R.string.app_diagnostics_open_documents) {
            stringResource(R.string.app_diagnostics_none)
        }
    } else {
        itemsIndexed(
            items = s.pipelines,
            key = { index, _ -> "pipeline_$index" },
            contentType = { _, _ -> "pipeline" },
        ) { index, stats ->
            PipelineCard(index = index, stats = stats)
        }
    }

    header(key = "h_storage") { stringResource(R.string.app_diagnostics_section_storage) }
    stat(key = "database", label = R.string.app_diagnostics_database) { databaseText(s) }
    stat(key = "library", label = R.string.app_diagnostics_library_files) { formatFileSize(s.libraryBytes) }
    stat(key = "covers", label = R.string.app_diagnostics_covers_cache) { formatFileSize(s.coversBytes) }

    header(key = "h_work") { stringResource(R.string.app_diagnostics_section_work) }
    stat(key = "import", label = R.string.app_diagnostics_import_queue) { importText(s.importProgress) }
    stat(key = "search", label = R.string.app_diagnostics_search_index) {
        stringResource(R.string.app_diagnostics_not_built)
    }

    header(key = "h_engine") { stringResource(R.string.app_diagnostics_section_engine) }
    stat(key = "engine", label = R.string.app_diagnostics_engine_id) { s.pdfEngineId }

    val takenAt = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(s.takenAtMillis))
    stat(key = "taken_at", label = R.string.app_diagnostics_taken_at) { takenAt }
}

private fun LazyListScope.header(key: String, title: @Composable () -> String) {
    item(key = key, contentType = "header") { SectionHeader(title = title()) }
}

private fun LazyListScope.stat(key: String, @StringRes label: Int, value: @Composable () -> String) {
    item(key = key, contentType = "stat") { StatRow(label = stringResource(label), value = value()) }
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
        FilledTonalButton(onClick = onRefresh, enabled = !busy) {
            Text(stringResource(R.string.app_diagnostics_refresh))
        }
        OutlinedButton(onClick = onTrimCaches, enabled = !busy) {
            Text(stringResource(R.string.app_diagnostics_trim_caches))
        }
        OutlinedButton(onClick = onForceGc, enabled = !busy) {
            Text(stringResource(R.string.app_diagnostics_force_gc))
        }
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
                text = stringResource(R.string.app_diagnostics_document, index + 1),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { heading() },
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_cached_bitmaps),
                value = stats.cachedBitmaps.toString(),
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_cache_used_budget),
                value = "${formatFileSize(stats.cacheBytes)} / ${formatFileSize(stats.cacheBudgetBytes)}",
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_bitmap_pool),
                value = formatFileSize(stats.poolBytes),
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_hits_misses),
                value = "${stats.hits} / ${stats.misses} ($hitRate%)",
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_renders_completed),
                value = stats.rendersCompleted.toString(),
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_skipped_stale),
                value = stats.rendersSkippedStale.toString(),
            )
            // "ms" is an international unit (like the sizes from formatFileSize): no translation.
            StatRow(
                label = stringResource(R.string.app_diagnostics_last_render),
                value = "${stats.lastRenderMillis} ms",
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_failed_pages),
                value = stats.failedPages.toString(),
            )
            StatRow(
                label = stringResource(R.string.app_diagnostics_worker),
                value = stringResource(if (stats.busy) R.string.app_diagnostics_busy else R.string.app_diagnostics_idle),
            )
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

@Composable
private fun databaseText(s: DiagnosticsSnapshot): String {
    val size = s.databaseBytes?.let { formatFileSize(it) } ?: stringResource(R.string.app_diagnostics_no_file)
    val walBytes = s.databaseWalBytes
    val files = if (walBytes != null) {
        stringResource(R.string.app_diagnostics_with_wal, size, formatFileSize(walBytes))
    } else {
        size
    }
    val open = stringResource(if (s.databaseOpen) R.string.app_diagnostics_db_open else R.string.app_diagnostics_db_not_opened)
    return "$files · $open"
}

@Composable
private fun importText(progress: ImportProgress?): String = when {
    progress == null -> stringResource(R.string.app_diagnostics_import_not_created)
    !progress.active -> stringResource(R.string.app_diagnostics_idle)
    // Counts, the file name and sizes only: nothing to translate.
    else -> buildString {
        append(progress.completed).append('/').append(progress.total)
        progress.currentName?.let { append(" · ").append(it) }
        if (progress.bytesTotal > 0) {
            append(" · ").append(formatFileSize(progress.bytesCopied))
            append(" / ").append(formatFileSize(progress.bytesTotal))
        }
    }
}
