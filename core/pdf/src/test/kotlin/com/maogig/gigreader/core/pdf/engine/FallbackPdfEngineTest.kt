package com.maogig.gigreader.core.pdf.engine

import android.os.ParcelFileDescriptor
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.pdf.FallbackPdfEngine
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FallbackPdfEngineTest {
    private class FakeDocument(val owner: String) : PdfDocument {
        override val pageCount = 1
        override val capabilities = EngineCapabilities()
        override suspend fun pageSize(page: Int) = PageSize(1f, 1f)
        override suspend fun measurePages(range: IntRange, out: PageSizes, isCancelled: () -> Boolean) = Unit
        override suspend fun render(page: Int, jobs: List<RenderJob>) = BooleanArray(jobs.size)
        override suspend fun close() = Unit
    }

    private class FakeEngine(
        override val id: String,
        override val supportsPasswords: Boolean,
        private val failure: () -> Throwable? = { null },
    ) : PdfEngine {
        var opens = 0
        var lastPassword: String? = null
        val documents = mutableListOf<FakeDocument>()

        override suspend fun open(file: File, password: String?): PdfDocument {
            opens++
            lastPassword = password
            failure()?.let { throw it }
            return FakeDocument(id).also { documents += it }
        }

        override suspend fun open(descriptor: ParcelFileDescriptor, password: String?): PdfDocument =
            throw UnsupportedOperationException("descriptors are not exercised here")
    }

    private val file = File("x.pdf")

    @Test
    fun usesThePrimaryWhenItWorks() = runTest {
        val primary = FakeEngine("mupdf", true)
        val fallback = FakeEngine("framework", false)
        val engine = FallbackPdfEngine(primary, fallback)
        assertEquals("mupdf", engine.id)
        assertTrue(engine.supportsPasswords)
        val doc = engine.open(file, "pw") as FakeDocument
        assertEquals("mupdf", doc.owner)
        assertEquals("pw", primary.lastPassword)
        assertEquals(0, fallback.opens)
        assertFalse(engine.isUsingFallback)
    }

    @Test
    fun switchesOnceWhenThePrimaryIsUnavailable() = runTest {
        val primary = FakeEngine("mupdf", true) { EngineUnavailableException("no lib") }
        val fallback = FakeEngine("framework", false)
        val engine = FallbackPdfEngine(primary, fallback)

        val first = engine.open(file, null) as FakeDocument
        assertEquals("framework", first.owner)
        assertEquals("framework", engine.id)
        assertFalse(engine.supportsPasswords)
        assertTrue(engine.isUsingFallback)

        engine.open(file, null)
        engine.open(file, null)
        assertEquals(1, primary.opens) // remembered: MuPDF is not tried again
        assertEquals(3, fallback.opens)
    }

    @Test
    fun linkageErrorsAlsoSwitch() = runTest {
        for (error in listOf<Throwable>(
            UnsatisfiedLinkError("libmupdf_java.so"),
            ExceptionInInitializerError("static init"),
            NoClassDefFoundError("com/artifex/mupdf/fitz/Context"),
        )) {
            val primary = FakeEngine("mupdf", true) { error }
            val fallback = FakeEngine("framework", false)
            val engine = FallbackPdfEngine(primary, fallback)
            assertEquals("framework", (engine.open(file, null) as FakeDocument).owner, error.toString())
            assertEquals("framework", engine.id)
        }
    }

    @Test
    fun corruptedDocumentsAreNotRetriedOnTheFallback() = runTest {
        val primary = FakeEngine("mupdf", true) { PdfOpenException.Corrupted() }
        val fallback = FakeEngine("framework", false)
        val engine = FallbackPdfEngine(primary, fallback)
        assertFailsWith<PdfOpenException.Corrupted> { engine.open(file, null) }
        assertEquals(0, fallback.opens)
        assertEquals("mupdf", engine.id)
        assertFalse(engine.isUsingFallback)
    }

    @Test
    fun passwordRequiredPropagates() = runTest {
        val primary = FakeEngine("mupdf", true) { PdfOpenException.PasswordRequired() }
        val engine = FallbackPdfEngine(primary, FakeEngine("framework", false))
        assertFailsWith<PdfOpenException.PasswordRequired> { engine.open(file, "wrong") }
        assertEquals("mupdf", engine.id)
    }

    @Test
    fun aFailureOfTheFallbackItselfPropagates() = runTest {
        val primary = FakeEngine("mupdf", true) { EngineUnavailableException("no lib") }
        val fallback = FakeEngine("framework", false) { PdfOpenException.Unreadable() }
        val engine = FallbackPdfEngine(primary, fallback)
        assertFailsWith<PdfOpenException.Unreadable> { engine.open(file, null) }
        assertTrue(engine.isUsingFallback)
    }

    @Test
    fun unrelatedExceptionsAreNotSwallowed() = runTest {
        val boom = IllegalStateException("boom")
        val engine = FallbackPdfEngine(FakeEngine("mupdf", true) { boom }, FakeEngine("framework", false))
        assertSame(boom, assertFailsWith<IllegalStateException> { engine.open(file, null) })
        assertFalse(engine.isUsingFallback)
    }
}
