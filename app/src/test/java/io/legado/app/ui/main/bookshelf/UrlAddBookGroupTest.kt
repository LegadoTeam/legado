package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UrlAddBookGroupTest {

    @Test
    fun positiveShelfGroupIsAssignedToNewBook() {
        assertEquals(0b0100L, mergeBookGroupForUrlAdd(0L, 0b0100L))
    }

    @Test
    fun highestPositiveShelfGroupIsAssigned() {
        val highestPositiveGroupId = 1L shl 62

        assertEquals(
            highestPositiveGroupId,
            mergeBookGroupForUrlAdd(0L, highestPositiveGroupId),
        )
    }

    @Test
    fun existingGroupMembershipsArePreserved() {
        assertEquals(0b0111L, mergeBookGroupForUrlAdd(0b0101L, 0b0010L))
    }

    @Test
    fun existingTargetGroupIsIdempotent() {
        assertEquals(0b0101L, mergeBookGroupForUrlAdd(0b0101L, 0b0100L))
    }

    @Test
    fun systemAndUnavailableShelfGroupsAreIgnored() {
        val currentGroupMask = 0b0101L
        val ignoredGroupIds = listOf(
            0L,
            -1L,
            -2L,
            -3L,
            -4L,
            -5L,
            -6L,
            -11L,
            -100L,
            Long.MIN_VALUE,
        )

        ignoredGroupIds.forEach { groupId ->
            assertEquals(
                "groupId=$groupId",
                currentGroupMask,
                mergeBookGroupForUrlAdd(currentGroupMask, groupId),
            )
        }
    }

    @Test
    fun positiveGroupCanBeAddedToLegacyHighBitMask() {
        val currentGroupMask = Long.MIN_VALUE or 0b0001L

        assertEquals(
            Long.MIN_VALUE or 0b0101L,
            mergeBookGroupForUrlAdd(currentGroupMask, 0b0100L),
        )
    }

    @Test
    fun migrationRestoresExistingGroupsBeforeAddingSelectedGroup() {
        val existingBook = Book(bookUrl = "old", group = 0b0011L)
        val fetchedBook = Book(bookUrl = "new", group = 0b1000L)

        val migratedBook = migrateBookForUrlAdd(
            existingBook = existingBook,
            fetchedBook = fetchedBook,
            toc = emptyList(),
            selectedShelfGroupId = 0b0100L,
        )

        assertSame(fetchedBook, migratedBook)
        assertEquals(0b0111L, migratedBook.group)
    }
}
