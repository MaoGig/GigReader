package com.maogig.gigreader

import com.maogig.gigreader.core.data.importer.ImportError
import com.maogig.gigreader.core.data.importer.ImportOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportFeedbackTest {
    @Test
    fun singleOutcomesKeepTheirDetails() {
        val messages = summarizeImportOutcomes(
            listOf(
                ImportOutcome.Imported("d1", "Paper"),
                ImportOutcome.Duplicate("d2", "old.pdf", inTrash = false),
                ImportOutcome.Failed("x.pdf", ImportError.NOT_A_PDF),
            ),
        )

        assertEquals(
            listOf(
                ImportMessage.Imported("Paper", "d1"),
                ImportMessage.Duplicate("old.pdf", "d2", inTrash = false),
                ImportMessage.Failed("x.pdf", ImportError.NOT_A_PDF),
            ),
            messages,
        )
    }

    @Test
    fun burstsAreCountedAndCancellationsAreNotReported() {
        val messages = summarizeImportOutcomes(
            listOf(
                ImportOutcome.Imported("d1", "A"),
                ImportOutcome.Imported("d2", "B"),
                ImportOutcome.Duplicate("d3", "c.pdf", inTrash = false),
                ImportOutcome.Duplicate("d4", "d.pdf", inTrash = true),
                ImportOutcome.Failed("e.pdf", ImportError.CORRUPTED),
                ImportOutcome.Failed("f.pdf", ImportError.CANCELLED),
            ),
        )

        assertEquals(
            listOf(
                ImportMessage.ImportedMany(2),
                ImportMessage.DuplicateMany(2),
                ImportMessage.Failed("e.pdf", ImportError.CORRUPTED),
            ),
            messages,
        )
    }

    @Test
    fun onlyLiveDocumentsCanBeOpened() {
        assertEquals("d1", ImportMessage.Imported("A", "d1").openableDocumentId())
        assertEquals("d2", ImportMessage.Duplicate("b.pdf", "d2", inTrash = false).openableDocumentId())
        assertNull(ImportMessage.Duplicate("b.pdf", "d2", inTrash = true).openableDocumentId())
        assertNull(ImportMessage.ImportedMany(3).openableDocumentId())
        assertNull(ImportMessage.Failed("c.pdf", ImportError.NO_SPACE).openableDocumentId())
    }
}
