package io.legado.app.config

import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoTaskPersistenceContractTest {

    private val root = repositoryRoot()

    @Test
    fun `database and dao keep ordered non-destructive task storage`() {
        val database = file("app/src/main/java/io/legado/app/data/AppDatabase.kt").readText()
        val dao = file("app/src/main/java/io/legado/app/data/dao/AutoTaskRuleDao.kt").readText()
        val autoTask = file("app/src/main/java/io/legado/app/model/AutoTask.kt").readText()
        val activity = file(
            "app/src/main/java/io/legado/app/ui/autoTask/AutoTaskActivity.kt"
        ).readText()
        assertTrue(database.contains("version = 95"))
        assertTrue(database.contains("AutoMigration(from = 94, to = 95)"))
        assertTrue(database.contains("AutoTaskRule::class"))
        assertTrue(dao.contains("ORDER BY customOrder"))
        assertTrue(dao.contains("@Upsert"))
        assertFalse(dao.contains("deleteAll"))
        assertMutationChecksLegacyFirst(
            autoTask.substringAfter("fun upsert(").substringBefore("fun delete("),
            "appDb.autoTaskRuleDao.upsert"
        )
        assertMutationChecksLegacyFirst(
            autoTask.substringAfter("fun delete(").substringBefore("fun move("),
            "appDb.autoTaskRuleDao.deleteByIds"
        )
        assertMutationChecksLegacyFirst(
            autoTask.substringAfter("fun move(").substringBefore("fun updateRunState("),
            "appDb.autoTaskRuleDao.update"
        )
        assertMutationChecksLegacyFirst(
            autoTask.substringAfter("fun get(").substringBefore("fun upsert("),
            "appDb.autoTaskRuleDao.getById"
        )
        assertTrue(activity.indexOf("AutoTask.all()") < activity.indexOf("flowAll()"))
    }

    @Test
    fun `exported room schema contains automatic task table`() {
        val schema = file("app/schemas/io.legado.app.data.AppDatabase/95.json")
        assertTrue("Room schema 95 must be committed", schema.isFile)
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 95"))
        assertTrue(text.contains("\"tableName\": \"auto_task_rules\""))
        assertTrue(text.contains("\"columnName\": \"customOrder\""))
    }

    @Test
    fun `persisted jobs and settings keep lifecycle contract`() {
        val manifest = file("app/src/main/AndroidManifest.xml").readText()
        val scheduler = file("app/src/main/java/io/legado/app/service/AutoTaskScheduler.kt").readText()
        val service = file("app/src/main/java/io/legado/app/service/AutoTaskJobService.kt").readText()
        val preferences = file("app/src/main/res/xml/pref_main.xml").readText()
        val myFragment = file("app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt").readText()
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.permission.BIND_JOB_SERVICE"))
        assertTrue(manifest.contains(".service.AutoTaskJobService"))
        assertTrue(scheduler.contains("setPersisted(true)"))
        assertTrue(scheduler.contains("JOB_ID_FIRST"))
        assertTrue(scheduler.contains("JOB_ID_SECOND"))
        assertTrue(scheduler.contains("cancelPendingJobs(scheduler)"))
        assertTrue(scheduler.contains("AutoTaskSchedulePolicy.nextAfterBatchAt"))
        assertTrue(service.contains("cancelPendingAlternate"))
        assertTrue(service.contains("return AutoTaskScheduler.shouldRetry(this)"))
        assertTrue(service.contains("start = CoroutineStart.LAZY"))
        assertTrue(service.contains("jobs.remove(jobId, currentJob)"))
        val saveJob = service.indexOf("jobs[jobId] = currentJob")
        val startJob = service.indexOf("currentJob.start()")
        assertTrue(saveJob >= 0)
        assertTrue(startJob > saveJob)
        val completion = service.substringAfter("} finally {")
            .substringBefore("override fun onStopJob")
        val stoppedFlag = completion.indexOf("stoppedJobs.remove(jobId)")
        val completionRelease = completion.indexOf("AutoTaskScheduler.markStopped(jobId)")
        val jobFinished = completion.indexOf("jobFinished(params, retry)")
        assertTrue(stoppedFlag >= 0)
        assertTrue(completionRelease > stoppedFlag)
        assertTrue(jobFinished > completionRelease)
        val destroy = service.substringAfter("override fun onDestroy()")
        val stopMarker = destroy.indexOf("stoppedJobs.add(jobId)")
        val cancelJob = destroy.indexOf("jobs.remove(jobId)?.cancel()")
        val releaseSlot = destroy.indexOf("AutoTaskScheduler.markStopped(jobId)")
        val cancelScope = destroy.indexOf("scope.cancel()")
        assertTrue(stopMarker >= 0)
        assertTrue(cancelJob > stopMarker)
        assertTrue(releaseSlot > cancelJob)
        assertTrue(cancelScope > releaseSlot)
        assertTrue(preferences.contains("android:key=\"autoTaskService\""))
        assertTrue(myFragment.contains("val appContext = requireContext().applicationContext"))
        assertTrue(myFragment.contains("Coroutine.async { AutoTaskScheduler.refresh(appContext) }"))
        assertTrue(myFragment.contains("AutoTaskScheduler.cancelAll(appContext)"))
    }

    @Test
    fun `startup refresh stays in background and notification denial is non-fatal`() {
        val app = file("app/src/main/java/io/legado/app/App.kt").readText()
        val protocol = file("app/src/main/java/io/legado/app/model/AutoTaskProtocol.kt").readText()
        val runner = file("app/src/main/java/io/legado/app/model/AutoTaskRunner.kt").readText()
        val asyncIndex = app.indexOf("Coroutine.async {")
        val refreshIndex = app.indexOf("AutoTaskScheduler.refresh(this@App)")
        assertTrue(asyncIndex >= 0 && refreshIndex > asyncIndex)
        assertTrue(protocol.contains("areNotificationsEnabled()"))
        assertTrue(protocol.contains("catch (error: RuntimeException)"))
        assertTrue(protocol.contains("Notification skipped"))
        assertFalse(protocol.contains("BookController.refreshToc"))
        assertFalse(protocol.contains("runBlocking"))
        assertTrue(protocol.contains("WebBook.getBookInfoAwait"))
        assertTrue(protocol.contains("WebBook.getChapterListAwait"))
        assertTrue(protocol.contains("appDb.runInTransaction"))
        assertTrue(runner.contains("runScriptWithContext {"))
        assertTrue(runner.contains("error.autoTaskCancellation()?.let { throw it }"))
        assertTrue(runner.contains("is RhinoInterruptError -> cause as? CancellationException"))
    }

    @Test
    fun `backup and restore include persisted automatic tasks`() {
        val backup = file("app/src/main/java/io/legado/app/help/storage/Backup.kt").readText()
        val restore = file("app/src/main/java/io/legado/app/help/storage/Restore.kt").readText()

        assertTrue(backup.contains("\"autoTask.json\""))
        assertTrue(backup.contains("appDb.autoTaskRuleDao.all()"))
        assertTrue(restore.contains("fileToListT<AutoTaskRule>(path, \"autoTask.json\")"))
        assertTrue(restore.contains("appDb.autoTaskRuleDao.upsert"))
        val preferenceRestore = restore.indexOf("appCtx.getSharedPreferences(path, \"config\")")
        val taskUpsert = restore.indexOf("appDb.autoTaskRuleDao.upsert")
        val scheduleRefresh = restore.indexOf("AutoTaskScheduler.refresh(appCtx)")
        assertTrue(preferenceRestore >= 0)
        assertTrue(taskUpsert > preferenceRestore)
        assertTrue(scheduleRefresh > taskUpsert)
        assertFalse(restore.contains("autoTaskRuleDao.delete"))
    }

    @Test
    fun `automatic task backup json preserves every persisted field`() {
        val rule = AutoTaskRule(
            id = "task-id",
            name = "task-name",
            enable = false,
            cron = "1 2 3 4 5",
            loginUrl = "https://example.com/login",
            loginUi = "login-ui",
            loginCheckJs = "login-check",
            comment = "comment",
            script = "script",
            header = "header",
            jsLib = "library",
            concurrentRate = "2/1000",
            enabledCookieJar = false,
            customOrder = 7,
            lastRunAt = 8L,
            lastResult = "result",
            lastError = "error",
            lastLog = "log",
        )

        val restored = GSON.fromJsonArray<AutoTaskRule>(GSON.toJson(listOf(rule)))
            .getOrThrow()
            .single()

        assertEquals(rule, restored)
    }

    private fun file(path: String) = File(root, path)

    private fun assertMutationChecksLegacyFirst(function: String, mutation: String) {
        val legacyCheck = function.indexOf("all()")
        val mutationCall = function.indexOf(mutation)
        assertTrue(legacyCheck >= 0)
        assertTrue(mutationCall > legacyCheck)
    }

    private fun repositoryRoot(): File {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(userDirectory) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
