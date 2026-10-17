package dev.underthehood

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One-way SAF backup: source deletion never triggers cloud deletion. */
class BackupWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as HoodApplication;val dao=app.db.dao()
        val rule=dao.backups().find{it.id==inputData.getString("rule")} ?: return Result.success()
        return try {
            val root=DocumentFile.fromTreeUri(app,Uri.parse(rule.tree)) ?: return Result.failure()
            require(root.canRead())
            val folderId="backup-${rule.id}"
            if(dao.node(folderId)==null)dao.insert(Node(id=folderId,name="Backup ${root.name ?: "folder"} ${rule.id.take(6)}",folder=true))
            suspend fun walk(directory:DocumentFile,parent:String) {
                for(file in directory.listFiles()) {
                    if(isStopped) return
                    val name=file.name ?: continue
                    if(file.isDirectory) {
                        val id=UUID.nameUUIDFromBytes("${rule.id}:${file.uri}".toByteArray()).toString()
                        if(dao.node(id)==null)dao.insert(Node(id=id,parent=parent,name=name,folder=true))
                        walk(file,id)
                    } else if(file.isFile) {
                        val revision="${file.lastModified()}-${file.length()}"
                        val id=UUID.nameUUIDFromBytes("${rule.id}:${file.uri}:$revision".toByteArray()).toString()
                        if(dao.job(id)==null) {
                            val versioned="$name ($revision)"
                            val job=Job(id=id,kind="UPLOAD",source=file.uri.toString(),destination="${rule.remote}:.underthehood/backups/${rule.id}/$id/$name",name=versioned,parent=parent,total=file.length())
                            dao.putJob(job);TransferWorker.enqueue(app,job,rule.wifi,rule.charging)
                        }
                    }
                }
            }
            walk(root,folderId);Result.success()
        }catch(_:Exception){Result.retry()}
    }
    companion object {
        fun schedule(context:Context,rule:BackupRule) {
            val request=PeriodicWorkRequestBuilder<BackupWorker>(12,TimeUnit.HOURS).setInputData(workDataOf("rule" to rule.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if(rule.wifi)NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresCharging(rule.charging).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("backup-${rule.id}",ExistingPeriodicWorkPolicy.KEEP,request)
        }
    }
}
