package com.maogig.gigreader.core.common.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Page dimensions in PDF points for a whole document, as two parallel arrays. */
class PageSizes(val widths: FloatArray, val heights: FloatArray) {
    init {
        require(widths.size == heights.size)
    }

    val count: Int get() = widths.size

    fun copy(): PageSizes = PageSizes(widths.copyOf(), heights.copyOf())

    companion object {
        /** Every page estimated with the size of the first page (uniform documents are the norm). */
        fun uniform(count: Int, width: Float, height: Float) =
            PageSizes(FloatArray(count) { width }, FloatArray(count) { height })
    }
}

/** Compact binary encoding of [PageSizes] (8 bytes per page) for the page_metrics table. */
object PageSizeCodec {
    fun encode(sizes: PageSizes): ByteArray {
        val buffer = ByteBuffer.allocate(sizes.count * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sizes.count) {
            buffer.putFloat(sizes.widths[i])
            buffer.putFloat(sizes.heights[i])
        }
        return buffer.array()
    }

    /** Returns `null` if [bytes] is not a valid encoding of [expectedCount] pages. */
    fun decode(bytes: ByteArray, expectedCount: Int): PageSizes? {
        if (expectedCount < 0 || bytes.size != expectedCount * 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = FloatArray(expectedCount)
        val h = FloatArray(expectedCount)
        for (i in 0 until expectedCount) {
            w[i] = buffer.getFloat()
            h[i] = buffer.getFloat()
        }
        return PageSizes(w, h)
    }
}
