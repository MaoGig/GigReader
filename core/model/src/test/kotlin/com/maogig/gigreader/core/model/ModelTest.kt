package com.maogig.gigreader.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    @Test
    fun normalizedRectsRoundTrip() {
        val rects = listOf(NormalizedRect(0.1f, 0.2f, 0.5f, 0.25f), NormalizedRect(0f, 0.3f, 1f, 0.35f))
        assertEquals(rects, NormalizedRect.decode(NormalizedRect.encode(rects)))
        assertEquals(emptyList(), NormalizedRect.decode(""))
        assertEquals(listOf(NormalizedRect(1f, 2f, 3f, 4f)), NormalizedRect.decode("1,2,3,4;garbage;1,2"))
    }

    @Test
    fun readingProgress() {
        val pos = ReadingPosition("d", page = 10, maxPageReached = 49, updatedAt = 0)
        assertEquals(0.5f, pos.progress(100))
        assertEquals(0f, pos.progress(0))
    }

    @Test
    fun tagNormalization() {
        assertEquals("machine-learning", Tag.normalize("  #Machine-Learning "))
    }

    @Test
    fun highlightColorsFallBackToYellow() {
        assertEquals(HighlightColor.GREEN, HighlightColor.fromKey("green"))
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromKey("unknown"))
    }

    @Test
    fun syncClassification() {
        assertEquals(ChangeKind.LOCAL_NEW, classifyChange(local = 1, remote = null, lastSynced = null))
        assertEquals(ChangeKind.REMOTE_NEW, classifyChange(local = null, remote = 3, lastSynced = null))
        assertEquals(ChangeKind.UNCHANGED, classifyChange(local = 4, remote = 4, lastSynced = 4))
        assertEquals(ChangeKind.LOCAL_MODIFIED, classifyChange(local = 5, remote = 4, lastSynced = 4))
        assertEquals(ChangeKind.REMOTE_MODIFIED, classifyChange(local = 4, remote = 6, lastSynced = 4))
        // Both sides edited once since the last sync: same number, different edits.
        assertEquals(ChangeKind.CONFLICT, classifyChange(local = 5, remote = 5, lastSynced = 4))
        assertEquals(ChangeKind.PURGED, classifyChange(local = null, remote = 4, lastSynced = 4))
    }

    @Test
    fun foldersComeFirstThenRequestedOrder() {
        val items = listOf(
            doc("b", size = 10, created = 3),
            LibraryItem.FolderEntry("f2", "Zeta", null, 0, createdAt = 1, modifiedAt = 1),
            doc("a", size = 30, created = 1),
            LibraryItem.FolderEntry("f1", "alpha", null, 0, createdAt = 2, modifiedAt = 2),
            doc("c", size = 20, created = 2),
        )
        assertEquals(listOf("alpha", "Zeta", "a", "b", "c"), items.sortedForDisplay(SortOrder(SortField.NAME)).map { it.title })
        // Folders have no size, so they tie and fall back to name order.
        assertEquals(
            listOf("alpha", "Zeta", "a", "c", "b"),
            items.sortedForDisplay(SortOrder(SortField.SIZE, ascending = false)).map { it.title },
        )
        assertEquals(
            listOf("Zeta", "alpha", "a", "c", "b"),
            items.sortedForDisplay(SortOrder(SortField.CREATED)).map { it.title },
        )
    }

    @Test
    fun recentlyEditedNotesDoNotSinkUnderLastOpenedSort() {
        val note = LibraryItem.NoteEntry("n", "note", "", null, false, createdAt = 50, modifiedAt = 500)
        val opened = doc("opened", size = 1, created = 1).copy(lastOpenedAt = 100)
        val never = doc("never", size = 1, created = 1)
        assertEquals(
            listOf("note", "opened", "never"),
            listOf(never, opened, note).sortedForDisplay(SortOrder(SortField.LAST_OPENED, ascending = false)).map { it.title },
        )
    }

    private fun doc(title: String, size: Long, created: Long) = LibraryItem.DocumentEntry(
        id = title, title = title, folderId = null, fileSize = size, pageCount = 10, lastPage = null,
        maxPageReached = null, favorite = false, annotationCount = 0, lastOpenedAt = null,
        createdAt = created, modifiedAt = created,
    )
}
