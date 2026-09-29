package com.maogig.gigreader.core.pdf.mupdf

import com.artifex.mupdf.fitz.SeekableStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChannelSeekableStreamTest {
    private fun withStream(bytes: ByteArray, block: (ChannelSeekableStream) -> Unit) {
        val file = File.createTempFile("seekable", ".bin")
        try {
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use { block(ChannelSeekableStream(it.channel)) }
        } finally {
            file.delete()
        }
    }

    private val data = ByteArray(1000) { (it % 251).toByte() }

    @Test
    fun readsSequentiallyAndReportsEnd() = withStream(data) { s ->
        val buf = ByteArray(400)
        assertEquals(400, s.read(buf))
        assertContentEquals(data.copyOfRange(0, 400), buf)
        assertEquals(400L, s.position())
        assertEquals(400, s.read(buf))
        assertEquals(200, s.read(buf))
        assertContentEquals(data.copyOfRange(800, 1000), buf.copyOf(200))
        assertEquals(-1, s.read(buf))
    }

    @Test
    fun seeksFromStartCurrentAndEnd() = withStream(data) { s ->
        assertEquals(100L, s.seek(100, SeekableStream.SEEK_SET))
        val one = ByteArray(1)
        s.read(one)
        assertEquals(data[100], one[0])
        assertEquals(151L, s.seek(50, SeekableStream.SEEK_CUR))
        assertEquals(990L, s.seek(-10, SeekableStream.SEEK_END))
        s.read(one)
        assertEquals(data[990], one[0])
        assertEquals(1000L, s.seek(0, SeekableStream.SEEK_END))
        assertEquals(-1, s.read(one))
    }

    @Test
    fun rejectsSeekBeforeStartAndBadWhence() = withStream(data) { s ->
        assertFailsWith<IOException> { s.seek(-1, SeekableStream.SEEK_SET) }
        assertFailsWith<IOException> { s.seek(-2000, SeekableStream.SEEK_END) }
        assertFailsWith<IOException> { s.seek(0, 7) }
    }

    @Test
    fun emptyBufferReadsNothing() = withStream(data) { s ->
        assertEquals(0, s.read(ByteArray(0)))
        assertEquals(0L, s.position())
    }

    @Test
    fun regularFilesAreSeekable() {
        val file = File.createTempFile("seekable", ".bin")
        try {
            RandomAccessFile(file, "r").use { assertTrue(ChannelSeekableStream.isSeekable(it.channel)) }
        } finally {
            file.delete()
        }
    }
}
