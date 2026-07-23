package io.legado.app.service

import android.app.job.JobParameters
import android.app.job.JobService
import io.legado.app.constant.AppLog
import io.legado.app.model.AutoTaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class AutoTaskJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val stoppedJobs = ConcurrentHashMap.newKeySet<Int>()

    override fun onStartJob(params: JobParameters): Boolean {
        val jobId = params.jobId
        if (jobId != AutoTaskScheduler.JOB_ID_FIRST &&
            jobId != AutoTaskScheduler.JOB_ID_SECOND
        ) {
            return false
        }
        if (!AutoTaskScheduler.shouldRetry(this)) {
            AutoTaskScheduler.markStopped(jobId)
            AutoTaskScheduler.refresh(this)
            return false
        }

        stoppedJobs.remove(jobId)
        AutoTaskScheduler.markRunning(jobId)
        val nextScheduled = AutoTaskScheduler.refresh(this, afterBatch = true)
        jobs.remove(jobId)?.cancel()
        lateinit var currentJob: Job
        currentJob = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                executionLock.withLock {
                    AutoTaskRunner.runDueTasks(this@AutoTaskJobService)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                retry = true
                AutoTaskScheduler.cancelPendingAlternate(this@AutoTaskJobService, jobId)
                AppLog.put("Automatic task job failed", error)
            } finally {
                jobs.remove(jobId, currentJob)
                val wasStopped = stoppedJobs.remove(jobId)
                AutoTaskScheduler.markStopped(jobId)
                if (!wasStopped) {
                    jobFinished(params, retry)
                    if (!retry && !nextScheduled) {
                        AutoTaskScheduler.refresh(this@AutoTaskJobService, afterBatch = true)
                    }
                }
            }
        }
        jobs[jobId] = currentJob
        currentJob.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val jobId = params.jobId
        stoppedJobs.add(jobId)
        jobs.remove(jobId)?.cancel()
        AutoTaskScheduler.cancelPendingAlternate(this, jobId)
        AutoTaskScheduler.markStopped(jobId)
        return AutoTaskScheduler.shouldRetry(this)
    }

    override fun onDestroy() {
        jobs.keys.toList().forEach { jobId ->
            stoppedJobs.add(jobId)
            jobs.remove(jobId)?.cancel()
            AutoTaskScheduler.markStopped(jobId)
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val executionLock = Mutex()
    }
}
