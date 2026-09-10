package io.legado.app.help.source

import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceNavigationPolicyTest {
    @Test fun restrictionFollowsOnlyItsOperationAndRequiresOptIn() = runBlocking {
        val normalReader = async { shouldSuppressSourceNavigation(true, currentCoroutineContext()) }
        withContext(SuppressSourceNavigation) {
            assertFalse(shouldSuppressSourceNavigation(false, currentCoroutineContext()))
            assertTrue(shouldSuppressSourceNavigation(true, currentCoroutineContext()))
            assertTrue(async { shouldSuppressSourceNavigation(true, currentCoroutineContext()) }.await())
            assertFalse(normalReader.await())
        }
        assertFalse(shouldSuppressSourceNavigation(true, currentCoroutineContext()))
    }
}
