package dev.underthehood

import android.content.Context
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HoodApplication
        val dao = app.db.dao()
        val job = dao.job(inputData.getString("job") ?: return Result.failure()) ?: return Result.failure()
        if (job.state in listOf("DONE", "CANCELLED", "PAUSED")) return Result.success()
        var rcJob: Int? = null
        val temp = File(applicationContext.cacheDir, "transfer-${job.id}")
        return try {
            dao.jobState(job.id, "RUNNING")
            rcJob = when (job.kind) {
                "UPLOAD" -> {
                    applicationContext.contentResolver.openInputStream(Uri.parse(job.source))!!.use { input -> temp.outputStream().use { input.copyTo(it, 256*1024) } }
                    app.engine.copy(temp.parentFile!!.absolutePath, temp.name, job.destination.substringBefore(":" )+":", job.destination.substringAfter(":"))
                }
                "DOWNLOAD" -> app.engine.copy(job.source.substringBefore(":")+":", job.source.substringAfter(":"), temp.parentFile!!.absolutePath, temp.name)
                else -> error("Unknown transfer type")
            }
            while (true) {
                if (isStopped) throw kotlinx.coroutines.CancellationException()
                val status = app.engine.call("job/status", JSONObject().put("jobid", rcJob))
                val stats = app.engine.call("core/stats", JSONObject().put("group", "job/$rcJob"))
                dao.progress(job.id, stats.optLong("bytes"), stats.optLong("totalBytes"))
                if (status.optBoolean("finished")) { check(status.optBoolean("success")) { status.optString("error", "Transfer failed") }; break }
                delay(1000)
            }
            if (job.kind == "UPLOAD") {
                val info = app.engine.call("operations/stat", JSONObject().put("fs", job.destination.substringBefore(":")+":").put("remote", job.destination.substringAfter(":"))).getJSONObject("item")
                if (job.nodeId == null) dao.insert(Node(id=job.id, parent=job.parent, name=job.name, remote=job.destination.substringBefore(":"), path=job.destination.substringAfter(":"), size=info.getLong("Size")))
            } else {
                applicationContext.contentResolver.openOutputStream(Uri.parse(job.destination), "wt")!!.use { output -> temp.inputStream().use { it.copyTo(output, 256*1024) } }
            }
            dao.jobState(job.id, "DONE")
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                rcJob?.let { runCatching { app.engine.call("job/stop", JSONObject().put("jobid", it)) } }
                val state = dao.job(job.id)?.state
                if (state !in listOf("PAUSED", "CANCELLED")) dao.jobState(job.id, "QUEUED")
            }
            throw cancel
        } catch (_: Exception) {
            dao.jobState(job.id, if (runAttemptCount < 4) "QUEUED" else "FAILED", "Transfer failed. Check connectivity, permissions and account quota.")
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        } finally { temp.delete() }
    }
    companion object {
        fun enqueue(context: Context, job: Job, wifi: Boolean = false, charging: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<TransferWorker>().setInputData(workDataOf("job" to job.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresCharging(charging).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("transfer-${job.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
