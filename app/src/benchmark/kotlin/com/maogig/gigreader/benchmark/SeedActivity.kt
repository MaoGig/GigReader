package com.maogig.gigreader.benchmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.lifecycleScope
import com.maogig.gigreader.GigReaderApplication
import com.maogig.gigreader.MainActivity
import com.maogig.gigreader.core.data.importer.ImportOutcome
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Benchmark build type only (app/src/benchmark): creates the test documents used by :benchmark on
 * the device itself, so the repository never carries large PDFs (docs/PERFORMANCE_AND_POWER.md §9.2).
 *
 * For every requested title that is not already in the library it writes a synthetic PDF
 * ([SyntheticPdf]) to the cache directory and imports it through the real
 * [com.maogig.gigreader.core.data.importer.ImportManager], exactly like a user import.
 *
 * Extras (all optional):
 * - `pages` (Int, default 10): page count of each generated PDF.
 * - `title` (String): library title; also the generated file name (`<title>.pdf`).
 * - `count` (Int, default 1): seed `count` documents titled `<title>-001`, `<title>-002`, …
 * - `open` (Boolean, default false): afterwards reset the reading position of the (first) document
 *   and open it in [MainActivity] via [MainActivity.ACTION_OPEN_DOCUMENT].
 *
 * Status is exposed to UiAutomator through Compose test tags published as resource ids:
 * `seed_running` while working; then either the reader opens (`open`), or the (transparent) screen
 * shows `seed_done` / `seed_failed` and stays until it is tapped or Back is pressed. Waiting for an
 * explicit marker, instead of for this activity to disappear, lets a test tell "finished" from
 * "not started yet" without races, and keeps a window alive for Macrobenchmark's launch detection.
 *
 * From a shell: `adb shell am start -n com.maogig.gigreader/com.maogig.gigreader.benchmark.SeedActivity
 * --ei pages 2000 --es title bench-2000p`.
 */
class SeedActivity : ComponentActivity() {
    private var status by mutableStateOf(SeedStatus.RUNNING)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SeedStatusMarker(status = status, onDismiss = { finish() }) }

        val request = SeedRequest.from(intent)
        val seeder = DocumentSeeder(
            container = (application as GigReaderApplication).container,
            workDir = File(cacheDir, WORK_DIR),
        )
        lifecycleScope.launch {
            val ids: List<String>? = try {
                withContext(Dispatchers.IO) { seeder.seed(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Seeding '${request.title}' failed", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.e(LOG_TAG, "Seeding '${request.title}' ran out of memory", e)
                null
            }
            if (ids == null) {
                status = SeedStatus.FAILED
            } else if (request.open) {
                openInReader(ids.first())
                finish()
            } else {
                status = SeedStatus.DONE
            }
        }
    }

    private fun openInReader(documentId: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DOCUMENT)
                .putExtra(MainActivity.EXTRA_DOCUMENT_ID, documentId),
        )
    }
}

/** Seeds documents idempotently (by exact library title). Runs on the caller's (IO) dispatcher. */
private class DocumentSeeder(private val container: AppContainer, private val workDir: File) {

    suspend fun seed(request: SeedRequest): List<String> {
        val ids = request.titles().map { title -> seedOne(title, request.pages) }
        if (request.open) {
            // Every benchmark iteration starts from the first page at "fit width".
            container.readerRepository.savePosition(documentId = ids.first(), page = 0, pageOffset = 0f, zoom = 1f)
        }
        return ids
    }

    private suspend fun seedOne(title: String, pages: Int): String {
        findByTitle(title)?.let { return it }

        workDir.mkdirs()
        val file = File(workDir, "$title.pdf")
        try {
            val started = System.nanoTime()
            SyntheticPdf.write(file, title, pages)
            Log.i(LOG_TAG, "Generated '${file.name}' ($pages pages, ${file.length()} bytes) in ${(System.nanoTime() - started) / 1_000_000} ms")

            val importer = container.importManager
            // Subscribe before queuing the import: `outcomes` has no replay.
            val outcome = withTimeoutOrNull(IMPORT_TIMEOUT_MS) {
                importer.outcomes
                    .onSubscription { importer.import(listOf(Uri.fromFile(file)), folderId = null) }
                    // Imported carries the title; Duplicate/Failed carry the file name.
                    .first { it.displayName == title || it.displayName == file.name }
            } ?: error("Import of '${file.name}' timed out after $IMPORT_TIMEOUT_MS ms")

            return when (outcome) {
                is ImportOutcome.Imported -> outcome.documentId
                is ImportOutcome.Duplicate -> {
                    if (outcome.inTrash) {
                        container.libraryRepository.restoreFromTrash(listOf(ItemRef(outcome.existingId, ItemKind.DOCUMENT)))
                    }
                    outcome.existingId
                }
                is ImportOutcome.Failed -> error("Import of '${file.name}' failed: ${outcome.error}")
            }
        } finally {
            // The import copied the bytes into the library; the generated source is no longer needed.
            file.delete()
        }
    }

    private suspend fun findByTitle(title: String): String? =
        container.libraryRepository.search(title).documents.firstOrNull { it.title == title }?.id
}

private class SeedRequest(val title: String, val pages: Int, val count: Int, val open: Boolean) {
    fun titles(): List<String> =
        if (count == 1) listOf(title) else (1..count).map { "$title-${it.toString().padStart(3, '0')}" }

    companion object {
        fun from(intent: Intent): SeedRequest {
            val pages = intent.getIntExtra(EXTRA_PAGES, DEFAULT_PAGES).coerceIn(1, MAX_PAGES)
            val rawTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "benchmark-${pages}p"
            return SeedRequest(
                title = sanitizeTitle(rawTitle),
                pages = pages,
                count = intent.getIntExtra(EXTRA_COUNT, 1).coerceIn(1, MAX_COUNT),
                open = intent.getBooleanExtra(EXTRA_OPEN, false),
            )
        }

        /**
         * The title doubles as the file name, and the importer derives the library title from the
         * file name (minus ".pdf"), so keep it file-safe and without surrounding spaces or dots.
         */
        private fun sanitizeTitle(raw: String): String =
            UNSAFE_TITLE_CHARS.replace(raw.trim(), "_").take(MAX_TITLE_LENGTH).trim(' ', '.').ifEmpty { "benchmark" }

        private val UNSAFE_TITLE_CHARS = Regex("[^A-Za-z0-9 ._-]")
    }
}

private enum class SeedStatus(val tag: String) {
    RUNNING("seed_running"),
    DONE("seed_done"),
    FAILED("seed_failed"),
}

/** A transparent full-window marker whose test tag (resource id for UiAutomator) is the status. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeedStatusMarker(status: SeedStatus, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        val finished = status != SeedStatus.RUNNING
        Box(
            Modifier
                .fillMaxSize()
                .testTag(status.tag)
                .then(if (finished) Modifier.clickable(onClick = onDismiss) else Modifier),
        )
    }
}

private const val LOG_TAG = "GigReaderSeed"
private const val WORK_DIR = "benchmark-seed"
private const val EXTRA_PAGES = "pages"
private const val EXTRA_TITLE = "title"
private const val EXTRA_COUNT = "count"
private const val EXTRA_OPEN = "open"
private const val DEFAULT_PAGES = 10
private const val MAX_PAGES = 5_000
private const val MAX_COUNT = 500
private const val MAX_TITLE_LENGTH = 96
private const val IMPORT_TIMEOUT_MS = 5 * 60_000L
