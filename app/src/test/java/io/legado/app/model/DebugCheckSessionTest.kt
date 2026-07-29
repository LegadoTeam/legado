package io.legado.app.model

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCheckSessionTest {

    @Test
    fun completedMessagesRemainOwnedByTheirSession() {
        val firstUrl = "https://first.example"
        val secondUrl = "https://second.example"
        Debug.finishChecking()
        val firstSession = requireNotNull(Debug.tryStartCheckSession())
        Debug.prepareCheckSession(firstSession, listOf(firstUrl))
        assertFalse(Debug.isCheckServiceStarted(firstSession))
        assertTrue(Debug.markCheckServiceStarted(firstSession))
        Debug.startChecking(firstSession, BookSource(bookSourceUrl = firstUrl))
        Debug.debugMessageMap[firstUrl] = "[00:01.000] 书源自定义日志"
        Debug.recordCheckResult(
            firstSession,
            firstUrl,
            CheckSourceResult(CheckSourceStatus.PASSED),
        )

        assertTrue(Debug.finishChecking(firstSession))
        Debug.debugMessageMap.remove(firstUrl)
        assertTrue(Debug.isCheckServiceStarted(firstSession))

        val secondSession = requireNotNull(Debug.tryStartCheckSession())
        Debug.prepareCheckSession(secondSession, listOf(secondUrl))
        assertTrue(Debug.markCheckServiceStarted(secondSession))
        Debug.startChecking(secondSession, BookSource(bookSourceUrl = secondUrl))
        assertFalse(Debug.finishChecking(firstSession))
        assertTrue(Debug.isChecking(secondSession))
        val firstSnapshot = Debug.takeCheckSnapshot(firstSession, listOf(firstUrl))
        assertEquals("[00:01.000] 书源自定义日志", firstSnapshot.messages[firstUrl])
        assertEquals(CheckSourceStatus.PASSED, firstSnapshot.results[firstUrl]?.status)

        assertTrue(Debug.finishChecking(secondSession))
        Debug.takeCheckSnapshot(secondSession, listOf(secondUrl))
    }
}
