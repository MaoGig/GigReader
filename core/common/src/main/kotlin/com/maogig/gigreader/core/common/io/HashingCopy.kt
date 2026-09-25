package com.maogig.gigreader.core.common.io

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class CopyResult(val bytes: Long, val sha256: String)

/**
 * Copies [input] to [output] while computing the SHA-256 of the content, in a single pass: importing
 * a 1 GB PDF reads it exactly once. [onProgress] receives the number of bytes copied so far and is
 * throttled to one call per [progressStepBytes]; [isCancelled] is polled between buffers so a
 * cancelled import stops promptly.
 */
fun copyAndHash(
    input: InputStream,
    output: OutputStream,
    bufferSize: Int = 256 * 1024,
    progressStepBytes: Long = 4L * 1024 * 1024,
    isCancelled: () -> Boolean = { false },
    onProgress: (Long) -> Unit = {},
): CopyResult {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    var total = 0L
    var nextProgress = progressStepBytes
    while (true) {
        if (isCancelled()) throw CopyCancelledException()
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        total += read
        if (total >= nextProgress) {
            onProgress(total)
            nextProgress = total + progressStepBytes
        }
    }
    output.flush()
    return CopyResult(total, digest.digest().toHex())
}

/** Hash of a stream without copying it (used to check duplicates of files already on disk). */
fun sha256(input: InputStream, bufferSize: Int = 256 * 1024): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().toHex()
}

class CopyCancelledException : Exception("copy cancelled")

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}
