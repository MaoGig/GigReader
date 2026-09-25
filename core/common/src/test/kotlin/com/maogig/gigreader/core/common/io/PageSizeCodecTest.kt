package com.maogig.gigreader.core.common.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class PageSizeCodecTest {
    @Test
    fun roundTrip() {
        val sizes = PageSizes(floatArrayOf(595f, 842f, 612.5f), floatArrayOf(842f, 595f, 792f))
        val decoded = PageSizeCodec.decode(PageSizeCodec.encode(sizes), 3)!!
        assertContentEquals(sizes.widths, decoded.widths)
        assertContentEquals(sizes.heights, decoded.heights)
    }

    @Test
    fun rejectsMismatchedLength() {
        assertNull(PageSizeCodec.decode(ByteArray(12), 2))
    }
}
