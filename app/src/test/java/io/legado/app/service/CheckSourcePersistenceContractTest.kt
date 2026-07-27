package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckSourcePersistenceContractTest {

    @Test
    fun checkServiceOnlyWritesOwnedSourceFields() {
        val service = projectFile("app/src/main/java/io/legado/app/service/CheckSourceService.kt")
        val dao = projectFile("app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt")

        assertTrue(service.contains("bookSourceDao.updateCheckResult("))
        assertFalse(service.contains("bookSourceDao.update(it)"))
        listOf(
            "bookSourceGroup = :bookSourceGroup",
            "bookSourceComment = :bookSourceComment",
            "respondTime = :respondTime",
            "lastUpdateTime = :expectedLastUpdateTime",
            "bookSourceGroup = :expectedBookSourceGroup",
            "bookSourceComment = :expectedBookSourceComment",
            "respondTime = :expectedRespondTime",
        ).forEach { assertTrue(dao.contains(it)) }
        assertTrue(service.contains("source.lastUpdateTime != selected.lastUpdateTime"))
        assertTrue(service.contains("Debug.recordCheckResult("))
    }

    @Test
    fun sessionStopCancelsBeforeStoppingAndDoneFollowsRelease() {
        val model = projectFile("app/src/main/java/io/legado/app/model/CheckSource.kt")
        val service = projectFile("app/src/main/java/io/legado/app/service/CheckSourceService.kt")
        assertTrue(model.contains("val selectedSourcesKey = IntentData.put(sources.map { it.copy() })"))
        assertTrue(model.contains("putExtra(EXTRA_SESSION_ID, sessionId)"))
        assertTrue(model.contains("fun stop(context: Context, sessionId: Long)"))
        assertFalse(model.contains("IntentData.put(\"checkSourceSelectedIds\""))

        val stopBranch = service.substringAfter("IntentAction.stop ->")
            .substringBefore("return super.onStartCommand")
        val cancelIndex = stopBranch.indexOf("checkJob?.cancel()")
        val stopIndex = stopBranch.indexOf("stopSelf(startId)")
        assertTrue(cancelIndex >= 0 && cancelIndex < stopIndex)

        val finishSession = service.substringAfter("private fun finishCheckSession(sessionId: Long)")
            .substringBefore("private suspend fun checkSource")
        val releaseIndex = finishSession.indexOf("Debug.finishChecking(sessionId)")
        val doneIndex = finishSession.indexOf("postEvent(EventBus.CHECK_SOURCE_DONE, sessionId)")
        assertTrue(releaseIndex >= 0 && releaseIndex < doneIndex)
        val onDestroy = service.substringAfter("override fun onDestroy()")
            .substringBefore("private fun check(")
        assertFalse(onDestroy.contains("CHECK_SOURCE_DONE"))
    }

    private fun projectFile(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(root, path)
            if (candidate.isFile) return candidate.readText()
            root = root.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }
}
