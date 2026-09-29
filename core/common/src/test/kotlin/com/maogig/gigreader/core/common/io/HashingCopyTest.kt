package com.maogig.gigreader.core.common.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HashingCopyTest {
    @Test
    fun copiesAndHashesInOnePass() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val result = copyAndHash(ByteArrayInputStream(data), out, bufferSize = 4096, progressStepBytes = 300_000) { progress += it }
        assertContentEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), result.bytes)
        assertEquals(sha256(ByteArrayInputStream(data)), result.sha256)
        assertEquals(3, progress.size)
    }

    @Test
    fun knownDigest() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test
    fun cancellationStopsTheCopy() {
        assertFailsWith<CopyCancelledException> {
            copyAndHash(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), isCancelled = { true })
        }
    }
}
