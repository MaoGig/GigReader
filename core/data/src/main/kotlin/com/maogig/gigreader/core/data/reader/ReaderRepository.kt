package com.maogig.gigreader.core.data.reader

import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.io.PageSizeCodec
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.entity.PageMetricsEntity
import com.maogig.gigreader.core.database.entity.ReadingPositionEntity
import com.maogig.gigreader.core.database.toModel
import com.maogig.gigreader.core.model.ReadingPosition

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

    suspend fun savePosition(documentId: String, page: Int, pageOffset: Float, zoom: Float)

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

    override suspend fun savePosition(documentId: String, page: Int, pageOffset: Float, zoom: Float) {
        val previous = positions.get(documentId)
        positions.upsert(
            ReadingPositionEntity(
                documentId = documentId,
                page = page,
                pageOffset = pageOffset,
                zoom = zoom,
                maxPageReached = maxOf(page, previous?.maxPageReached ?: 0),
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
