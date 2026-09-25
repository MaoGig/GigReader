package com.maogig.gigreader.core.common.io

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentFileStoreTest {
    private val root: File = Files.createTempDirectory("store").toFile()
    private val store = DocumentFileStore(File(root, "library"))

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun writeThenCommitProducesTheFinalFile() {
        val incoming = store.newIncomingFile()
        store.openForWrite(incoming).use { it.write("hello".toByteArray()) }
        val relative = store.commit(incoming, "doc-1")
        assertEquals("doc-1.pdf", relative)
        assertEquals("hello", store.fileFor(relative).readText())
        assertFalse(incoming.exists())
    }

    @Test
    fun orphansAndStaleTempFilesAreRemoved() {
        val incoming = store.newIncomingFile()
        store.openForWrite(incoming).use { it.write(1) }
        val kept = store.commit(incoming, "kept")
        val orphan = store.newIncomingFile().also { store.openForWrite(it).use { o -> o.write(2) } }
        store.commit(orphan, "orphan")
        val stale = store.newIncomingFile()
        stale.setLastModified(0)

        assertEquals(1, store.cleanupOrphans(setOf(kept)))
        assertTrue(store.fileFor(kept).exists())
        assertEquals(1, store.cleanupIncoming(nowMillis = System.currentTimeMillis()))
    }

    @Test
    fun pathsCannotEscapeTheRoot() {
        assertFailsWith<IllegalArgumentException> { store.fileFor("../secret") }
    }
}
