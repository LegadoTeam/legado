package io.legado.app.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskSchedulePolicy
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

object AutoTaskScheduler {

    const val JOB_ID_FIRST = 0x41555401
    const val JOB_ID_SECOND = 0x41555402
    private const val RETRY_BACKOFF_MS = 60_000L
    private val jobIds = intArrayOf(JOB_ID_FIRST, JOB_ID_SECOND)
    private val runningJobIds = linkedSetOf<Int>()

    @Synchronized
    fun refresh(
        context: Context = appCtx,
        afterBatch: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!context.getPrefBoolean(PreferKey.autoTaskService)) {
            cancelAll(context)
            return false
        }
        val rules = AutoTask.enabled()
        val runAt = if (afterBatch) {
            AutoTaskSchedulePolicy.nextAfterBatchAt(rules, now)
        } else {
            AutoTaskSchedulePolicy.nextDueAt(rules, now)
        }
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        if (runAt == null) {
            cancelPendingJobs(scheduler)
            return false
        }
        val jobId = jobIds.firstOrNull { it !in runningJobIds } ?: return false
        cancelPendingJobs(scheduler)
        val info = JobInfo.Builder(jobId, ComponentName(context, AutoTaskJobService::class.java))
            .setMinimumLatency((runAt - now).coerceAtLeast(0L))
            .setBackoffCriteria(RETRY_BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)
            .setPersisted(true)
            .build()
        return try {
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (error: RuntimeException) {
            AppLog.put("Unable to schedule automatic tasks", error)
            false
        }
    }

    @Synchronized
    fun markRunning(jobId: Int) {
        runningJobIds.add(jobId)
    }

    @Synchronized
    fun markStopped(jobId: Int) {
        runningJobIds.remove(jobId)
    }

    @Synchronized
    fun cancelPendingAlternate(context: Context, currentJobId: Int) {
        val alternate = AutoTaskSchedulePolicy.alternateSlot(
            currentJobId,
            JOB_ID_FIRST,
            JOB_ID_SECOND
        )
        if (alternate !in runningJobIds) {
            context.getSystemService(JobScheduler::class.java)?.cancel(alternate)
        }
    }

    @Synchronized
    fun cancelAll(context: Context = appCtx) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        jobIds.forEach(scheduler::cancel)
    }

    fun shouldRetry(context: Context): Boolean {
        if (!context.getPrefBoolean(PreferKey.autoTaskService)) return false
        return AutoTaskSchedulePolicy.nextAfterBatchAt(
            AutoTask.enabled(),
            System.currentTimeMillis()
        ) != null
    }

    private fun cancelPendingJobs(scheduler: JobScheduler) {
        jobIds.filter { it !in runningJobIds }.forEach(scheduler::cancel)
    }
}
