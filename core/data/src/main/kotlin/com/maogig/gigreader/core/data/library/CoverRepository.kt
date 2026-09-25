package com.maogig.gigreader.core.data.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.maogig.gigreader.core.common.cache.WeightedLruCache
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.SOURCE_MANAGED
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import com.maogig.gigreader.core.pdf.render.CoverRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Loads library covers: memory LRU (bounded by bytes) → disk file → regeneration from the PDF.
 *
 * Regeneration only happens when the cover file is missing (the system may clear the cache dir) and
 * is limited to one PDF at a time, so scrolling a large library can never fan out into many engine
 * sessions. Scrolling itself only touches the memory cache or decodes ~20 KB files.
 */
class CoverRepository(
    private val db: GigReaderDatabase,
    private val files: DocumentFileStore,
    private val store: CoverStore,
    private val engine: PdfEngine,
    maxMemoryBytes: Long = 12L * 1024 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val memory = WeightedLruCache<String, Bitmap>(
        maxWeight = maxMemoryBytes,
        weigher = { _, b -> b.allocationByteCount.toLong() },
    )
    private val regenerate = Semaphore(1)
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Synchronous memory lookup for the first frame (no suspension, no I/O). */
    fun cached(documentId: String): Bitmap? = memory.peek(documentId)

    /** Returns the cover, decoding or regenerating it if needed; `null` if the PDF cannot render. */
    suspend fun load(documentId: String): Bitmap? {
        memory[documentId]?.let { return it }
        val decoded = withContext(io) { decode(documentId) } ?: regenerateCover(documentId)
        if (decoded != null) memory.put(documentId, decoded)
        return decoded
    }

    fun invalidate(documentId: String) {
        memory.remove(documentId)
        failed.remove(documentId)
    }

    fun trimMemory() = memory.clear()

    private fun decode(documentId: String): Bitmap? {
        val file = store.fileFor(documentId)
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private suspend fun regenerateCover(documentId: String): Bitmap? {
        if (documentId in failed) return null
        return regenerate.withPermit {
            // Another caller may have produced it while we waited for the permit.
            withContext(io) { decode(documentId) }?.let { return@withPermit it }
            val entity = db.documentDao().getById(documentId)
            if (entity == null || entity.sourceKind != SOURCE_MANAGED || entity.deletedAt != null) {
                failed.add(documentId)
                return@withPermit null
            }
            try {
                val document = engine.open(files.fileFor(entity.sourcePath))
                try {
                    CoverRenderer.renderTo(document, store.fileFor(documentId), CoverStore.WIDTH_PX)
                } finally {
                    document.close()
                }
                withContext(io) { decode(documentId) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed.add(documentId)
                null
            }
        }
    }
}
