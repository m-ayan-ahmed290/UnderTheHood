package app.underthehood

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

fun File.sha256(): String = inputStream().use { it.digest() }

fun InputStream.digest(): String {
    val d = MessageDigest.getInstance("SHA-256")
    val b = ByteArray(65536)
    while (true) {
        val n = read(b)
        if (n < 0) break
        d.update(b, 0, n)
    }
    return d.digest().joinToString("") { "%02x".format(it) }
}

object Queue {
    suspend fun enqueue(
        context: Context,
        job: Transfer,
        wifi: Boolean = false,
        charging: Boolean = false,
    ) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(charging)
                .setRequiresStorageNotLow(true)
                .build()
        val request =
            OneTimeWorkRequestBuilder<TransferWorker>()
                .setInputData(workDataOf("job" to job.id))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("transfer")
                .build()
        context.hood.db
            .store()
            .putJob(job.copy(state = "QUEUED", error = "", workId = request.id.toString()))
        WorkManager.getInstance(context)
            .enqueueUniqueWork("transfer-${job.id}", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun pause(context: Context, job: Transfer) {
        context.hood.db.store().progress(job.id, "PAUSED", job.done, job.total)
        WorkManager.getInstance(context).cancelUniqueWork("transfer-${job.id}")
    }

    suspend fun cancel(context: Context, job: Transfer) {
        context.hood.db.store().progress(job.id, "CANCELLED", job.done, job.total)
        WorkManager.getInstance(context).cancelUniqueWork("transfer-${job.id}")
    }
}

class TransferWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private val store = context.hood.db.store()
    private var activeJob: Long? = null
    private lateinit var transfer: Transfer
    private val scratch
        get() = applicationContext.cacheDir.resolve("transfers/${transfer.id}").apply { mkdirs() }

    override suspend fun doWork(): Result {
        transfer =
            store.job(inputData.getString("job") ?: return Result.failure())
                ?: return Result.failure()
        if (transfer.state in listOf("PAUSED", "CANCELLED", "DONE")) return Result.success()
        return try {
            applicationContext.hood.vault.background()
            setForeground(notification("Preparing transfer"))
            store.workerProgress(transfer.id, "RUNNING", 0, transfer.total)
            when (transfer.operation) {
                "upload" -> upload()
                "download",
                "preview" -> download()
                else -> error("Unknown transfer operation")
            }
            store.workerProgress(transfer.id, "DONE", transfer.total, transfer.total)
            store.event(Event(message = "${transfer.operation}: ${transfer.name}"))
            scratch.deleteRecursively()
            Result.success()
        } catch (e: CancellationException) {
            activeJob?.let {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { Engine.raw("job/stop", json("jobid" to it)) }
                }
            }
            throw e
        } catch (e: Exception) {
            val state = if (runAttemptCount < 3) "RETRYING" else "FAILED"
            store.workerProgress(
                transfer.id,
                state,
                0,
                transfer.total,
                e.message ?: "Transfer failed",
            )
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun notification(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("transfers", "File transfers", NotificationManager.IMPORTANCE_LOW)
        )
        val n =
            NotificationCompat.Builder(applicationContext, "transfers")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("UnderTheHood · ${transfer.name}")
                .setContentText(message)
                .setOngoing(true)
                .build()
        return ForegroundInfo(
            transfer.id.hashCode(),
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private suspend fun copy(
        srcFs: String,
        src: String,
        dstFs: String,
        dst: String,
        base: Long = 0,
    ) {
        val response =
            Engine.rpc(
                "operations/copyfile",
                json(
                    "srcFs" to srcFs,
                    "srcRemote" to src,
                    "dstFs" to dstFs,
                    "dstRemote" to dst,
                    "_async" to true,
                    "_group" to transfer.id,
                ),
            )
        val id = response.getLong("jobid")
        activeJob = id
        while (true) {
            currentCoroutineContext().ensureActive()
            val status = Engine.rpc("job/status", json("jobid" to id))
            val bytes = Engine.rpc("core/stats", json("group" to transfer.id)).optLong("bytes")
            store.workerProgress(transfer.id, "RUNNING", base + bytes, transfer.total)
            if (status.optBoolean("finished")) {
                activeJob = null
                check(status.optBoolean("success")) {
                    status.optString("error", "Rclone transfer failed")
                }
                break
            }
            delay(700)
        }
        Engine.rpc("core/stats-reset", json("group" to transfer.id))
    }

    private suspend fun upload() {
        if (store.node(transfer.id) != null) return
        val checkpoint = scratch.resolve("checkpoint.json")
        val previous =
            runCatching { JSONArray(checkpoint.readText()).objects() }.getOrDefault(emptyList())
        val uri = Uri.parse(transfer.uri)
        val available = store.remotes()
        val selected =
            LibraryRules.choose(
                available,
                if (transfer.chunked) 0 else transfer.total,
                "manual",
                transfer.remote,
            )
        val chunkSize = if (transfer.chunked) 16 * 1024 * 1024 else Int.MAX_VALUE
        val chunks = JSONArray()
        var position = 0L
        var index = 0
        val source =
            applicationContext.contentResolver.openInputStream(uri)
                ?: error("Source permission is no longer available. Select the file again.")
        source.use { input ->
            val buffer = ByteArray(65536)
            var end = false
            do {
                currentCoroutineContext().ensureActive()
                val part = scratch.resolve("part")
                var length = 0L
                part.outputStream().use { output ->
                    while (length < chunkSize) {
                        val n =
                            input.read(
                                buffer,
                                0,
                                minOf(buffer.size.toLong(), chunkSize - length).toInt(),
                            )
                        if (n < 0) {
                            end = true
                            break
                        }
                        output.write(buffer, 0, n)
                        length += n
                    }
                }
                if (length == 0L && index > 0) {
                    part.delete()
                    break
                }
                val remote =
                    if (transfer.chunked)
                        LibraryRules.choose(available, length, "round-robin", round = index)
                    else selected
                val objectPath = "UnderTheHood/objects/${transfer.id}/$index"
                val hash = withContext(Dispatchers.IO) { part.sha256() }
                val saved = previous.getOrNull(index)
                if (
                    saved != null &&
                        saved.optString("sha256") == hash &&
                        saved.optLong("size") == length
                ) {
                    chunks.put(saved)
                    position += length
                    index++
                    part.delete()
                    continue
                }
                val copies = JSONArray()
                val targets =
                    if (transfer.protected) {
                        val second =
                            available.firstOrNull {
                                it.id != remote.id &&
                                    it.healthy &&
                                    !it.readOnly &&
                                    (it.free < 0 || it.free >= length)
                            } ?: error("Protection mode requires two healthy writable accounts")
                        listOf(remote, second)
                    } else listOf(remote)
                for (target in targets) {
                    copy(scratch.path, "part", "${target.id}:", objectPath, position)
                    // Verify actual stored bytes, even on providers without a common hash
                    // algorithm.
                    copy("${target.id}:", objectPath, scratch.path, "verify", position)
                    check(
                        withContext(Dispatchers.IO) { scratch.resolve("verify").sha256() } == hash
                    ) {
                        "Integrity verification failed; the file was not committed"
                    }
                    scratch.resolve("verify").delete()
                    copies.put(json("remote" to target.id, "path" to objectPath))
                }
                chunks.put(
                    json("index" to index, "size" to length, "sha256" to hash, "copies" to copies)
                )
                val temporary = scratch.resolve("checkpoint.tmp")
                temporary.outputStream().use {
                    it.write(chunks.toString().toByteArray())
                    it.fd.sync()
                }
                check(temporary.renameTo(checkpoint)) { "Cannot persist transfer checkpoint" }
                position += length
                index++
                part.delete()
            } while (!end)
        }
        val node =
            Node(
                id = transfer.id,
                parent = transfer.parent,
                name = LibraryRules.name(transfer.name),
                remote = selected.id,
                size = position,
                manifest =
                    json("version" to 1, "chunks" to chunks, "protected" to transfer.protected)
                        .toString(),
                mime = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream",
            )
        applicationContext.hood.db.withTransaction {
            store.put(node)
            store.workerProgress(transfer.id, "RUNNING", position, position)
        }
        transfer = transfer.copy(total = position)
    }

    private suspend fun download() {
        val node = store.node(transfer.node) ?: error("File is no longer in your library")
        val destination =
            if (transfer.operation == "preview") {
                val folder = applicationContext.cacheDir.resolve("previews").apply { mkdirs() }
                folder.resolve(node.id).outputStream()
            } else
                applicationContext.contentResolver.openOutputStream(Uri.parse(transfer.uri), "wt")
                    ?: error("Destination is not writable")
        destination.use { output ->
            if (node.manifest.isEmpty()) {
                require(node.remote.isNotEmpty()) { "Reconnect this file's storage account" }
                copy("${node.remote}:", node.path, scratch.path, "part")
                scratch.resolve("part").inputStream().use { it.copyTo(output, 65536) }
            } else {
                val manifest = JSONObject(node.manifest)
                require(manifest.getInt("version") == 1) { "Unsupported manifest version" }
                var done = 0L
                for (chunk in manifest.getJSONArray("chunks").objects()) {
                    var verified = false
                    for (replica in chunk.getJSONArray("copies").objects()) {
                        currentCoroutineContext().ensureActive()
                        try {
                            copy(
                                "${replica.getString("remote")}:",
                                replica.getString("path"),
                                scratch.path,
                                "part",
                                done,
                            )
                            check(scratch.resolve("part").length() == chunk.getLong("size"))
                            check(
                                withContext(Dispatchers.IO) { scratch.resolve("part").sha256() } ==
                                    chunk.getString("sha256")
                            )
                            verified = true
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            scratch.resolve("part").delete()
                        }
                    }
                    check(verified) {
                        "Chunk ${chunk.getInt("index")} is unavailable or corrupt on every replica"
                    }
                    scratch.resolve("part").inputStream().use { it.copyTo(output, 65536) }
                    done += chunk.getLong("size")
                    scratch.resolve("part").delete()
                }
            }
        }
        transfer = transfer.copy(total = node.size)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = applicationContext.hood.db.store()
        val rule =
            store.backup(inputData.getString("rule") ?: return Result.failure())
                ?: return Result.success()
        if (!rule.enabled) return Result.success()
        return try {
            val tree =
                DocumentFile.fromTreeUri(applicationContext, Uri.parse(rule.uri))
                    ?: error("Folder access has expired")
            val rootId = "backup-${rule.id}"
            if (store.node(rootId) == null)
                store.put(Node(id = rootId, name = rule.label, folder = true))
            suspend fun scan(dir: DocumentFile, parent: String) {
                for (file in dir.listFiles()) {
                    currentCoroutineContext().ensureActive()
                    if (file.isDirectory) {
                        val id = UUIDName(rule.id + file.uri)
                        if (store.node(id) == null)
                            store.put(
                                Node(
                                    id = id,
                                    parent = parent,
                                    name = file.name ?: "Folder",
                                    folder = true,
                                )
                            )
                        scan(file, id)
                    } else if (file.isFile) {
                        val previous = store.backupFile(rule.id, file.uri.toString())
                        if (
                            previous?.stamp != file.lastModified() || previous.size != file.length()
                        ) {
                            val jobId =
                                UUIDName(rule.id + file.uri + file.lastModified() + file.length())
                            val existing = store.job(jobId)
                            if (existing == null || existing.state in listOf("FAILED", "CANCELLED"))
                                Queue.enqueue(
                                    applicationContext,
                                    Transfer(
                                        id = jobId,
                                        operation = "upload",
                                        uri = file.uri.toString(),
                                        name = file.name ?: "File",
                                        parent = parent,
                                        remote = rule.remote,
                                        total = file.length(),
                                        chunked = true,
                                    ),
                                    rule.wifi,
                                    rule.charging,
                                )
                            if (existing?.state == "DONE")
                                store.putBackupFile(
                                    BackupFile(
                                        rule.id,
                                        file.uri.toString(),
                                        file.lastModified(),
                                        file.length(),
                                    )
                                )
                        }
                    }
                }
            }
            scan(tree, rootId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.event(Event(message = "Backup ${rule.label}: ${e.message}"))
            Result.retry()
        }
    }
}

fun UUIDName(value: String) = java.util.UUID.nameUUIDFromBytes(value.toByteArray()).toString()

fun scheduleBackup(context: Context, rule: Backup) {
    val constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (rule.wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(rule.charging)
            .setRequiresStorageNotLow(true)
            .build()
    val work =
        PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf("rule" to rule.id))
            .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork("backup-${rule.id}", ExistingPeriodicWorkPolicy.UPDATE, work)
}
