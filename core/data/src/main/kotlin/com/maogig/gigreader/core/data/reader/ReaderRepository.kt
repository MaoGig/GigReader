package com.maogig.gigreader.core.data.reader

import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.io.PageSizeCodec
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.entity.PageMetricsEntity
import com.maogig.gigreader.core.database.entity.ReadingPositionEntity
import com.maogig.gigreader.core.database.toModel
import com.maogig.gigreader.core.model.ReadingPosition

/**
 * What the reader saves when the view settles. The first four fields restore the viewport; the last
 * two feed the library ("Continue reading" and "% read").
 */
data class SavedReadingPosition(
    /** Zero-based page at the top edge of the viewport. */
    val page: Int,
    /** Fraction (0..1) of [page] scrolled past the top edge. */
    val pageOffset: Float,
    /** Zoom relative to "fit width". */
    val zoom: Float,
    /** Horizontal offset divided by the document width (0 unless zoomed in). */
    val offsetXFraction: Float,
    /** Page shown by the "X / N" indicator (the page at the viewport's vertical center). */
    val currentPage: Int,
    /** Last page visible at the bottom edge; at the end of the document this is the last page. */
    val lastVisiblePage: Int,
)

/** Cached page geometry: the first [measuredCount] pages are exact, the rest are estimates. */
data class PageMetrics(val sizes: PageSizes, val measuredCount: Int) {
    val isComplete: Boolean get() = measuredCount >= sizes.count
}

/**
 * Per-document reading state. Writes are expected to be throttled by the caller (see
 * [com.maogig.gigreader.core.common.coroutines.CoalescingSaver]); nothing here runs periodically.
 */
interface ReaderRepository {
    suspend fun position(documentId: String): ReadingPosition?

    /**
     * Saves where the user is. max_page_reached only grows: it becomes the highest
     * [SavedReadingPosition.lastVisiblePage] ever saved, so reading to the end reaches 100 %.
     */
    suspend fun savePosition(documentId: String, position: SavedReadingPosition)

    suspend fun markOpened(documentId: String)

    suspend fun pageMetrics(documentId: String, pageCount: Int): PageMetrics?

    suspend fun savePageMetrics(documentId: String, metrics: PageMetrics)

    suspend fun updatePageCount(documentId: String, pageCount: Int)
}

class LocalReaderRepository(
    db: GigReaderDatabase,
    private val clock: Clock = Clock.System,
) : ReaderRepository {
    private val positions = db.readingPositionDao()
    private val metrics = db.pageMetricsDao()
    private val documents = db.documentDao()

    override suspend fun position(documentId: String): ReadingPosition? = positions.get(documentId)?.toModel()

    override suspend fun savePosition(documentId: String, position: SavedReadingPosition) {
        val previous = positions.get(documentId)
        positions.upsert(
            ReadingPositionEntity(
                documentId = documentId,
                page = position.page,
                pageOffset = position.pageOffset,
                zoom = position.zoom,
                offsetXFraction = position.offsetXFraction,
                currentPage = position.currentPage,
                maxPageReached = maxOf(position.lastVisiblePage, position.currentPage, previous?.maxPageReached ?: 0),
                updatedAt = clock.now(),
                version = (previous?.version ?: 0) + 1,
            ),
        )
    }

    override suspend fun markOpened(documentId: String) = documents.markOpened(documentId, clock.now())

    override suspend fun pageMetrics(documentId: String, pageCount: Int): PageMetrics? {
        val entity = metrics.get(documentId) ?: return null
        if (entity.pageCount != pageCount) return null
        val sizes = PageSizeCodec.decode(entity.sizes, pageCount) ?: return null
        return PageMetrics(sizes, entity.measuredCount.coerceIn(0, pageCount))
    }

    override suspend fun savePageMetrics(documentId: String, metrics: PageMetrics) {
        this.metrics.upsert(
            PageMetricsEntity(
                documentId = documentId,
                pageCount = metrics.sizes.count,
                sizes = PageSizeCodec.encode(metrics.sizes),
                measuredCount = metrics.measuredCount,
            ),
        )
    }

    override suspend fun updatePageCount(documentId: String, pageCount: Int) =
        documents.setPageCount(documentId, pageCount)
}
