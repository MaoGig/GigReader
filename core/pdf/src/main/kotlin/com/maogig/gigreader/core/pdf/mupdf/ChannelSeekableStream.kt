package com.maogig.gigreader.core.pdf.mupdf

import com.artifex.mupdf.fitz.SeekableInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Feeds MuPDF (which has no file-descriptor API) from a [FileChannel] over the descriptor we were
 * given. Unlike `/proc/self/fd/N` this needs no path lookup, so it also works for descriptors whose
 * file the app could not open by path (provider-owned files). It does not own the channel.
 */
internal class ChannelSeekableStream(private val channel: FileChannel) : SeekableInputStream {
    override fun read(b: ByteArray): Int {
        if (b.isEmpty()) return 0
        val n = channel.read(ByteBuffer.wrap(b))
        return n // -1 at end of file, like InputStream.read
    }

    override fun seek(offset: Long, whence: Int): Long {
        val target = when (whence) {
            SeekableInputStream.SEEK_SET -> offset
            SeekableInputStream.SEEK_CUR -> channel.position() + offset
            SeekableInputStream.SEEK_END -> channel.size() + offset
            else -> throw IOException("bad whence $whence")
        }
        if (target < 0) throw IOException("seek before start of file")
        channel.position(target)
        return target
    }

    override fun position(): Long = channel.position()

    companion object {
        /** `true` when [channel] supports random access (regular file), `false` for pipes and sockets. */
        fun isSeekable(channel: FileChannel): Boolean = try {
            channel.position(channel.position())
            true
        } catch (_: IOException) {
            false
        }
    }
}
