package dev.underthehood

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import java.io.File

class HoodModel(application: Application) : AndroidViewModel(application) {
    val app = application as HoodApplication
    val nodes = app.db.dao().observeNodes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs = app.db.dao().jobs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var unlocked = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val remotes = MutableStateFlow<List<String>>(emptyList())
    val providers = MutableStateFlow<List<JSONObject>>(emptyList())
    val physical = MutableStateFlow<List<JSONObject>>(emptyList())
    val quota = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    var remote = MutableStateFlow("")
    var remotePath = MutableStateFlow("")
    private var failedUnlocks = 0
    fun task(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } catch (_: CancellationException) { throw CancellationException() }
        catch (e: Exception) { message.value = when(e) {
            is android.database.sqlite.SQLiteConstraintException -> "A file or folder with that name already exists here."
            else -> e.message?.take(240) ?: "Operation failed"
        } } finally { busy.value = false }
    }
    fun unlock(password: String) = task {
        if (failedUnlocks > 0) delay(minOf(30000L, 1000L * failedUnlocks * failedUnlocks))
        val success = withContext(Dispatchers.Default) { if (!app.vault.exists) { app.vault.create(password); true } else app.vault.unlock(password) }
        if (!success) { failedUnlocks++; error("Incorrect passphrase") }
        failedUnlocks = 0; unlocked.value = true; refresh()
    }
    suspend fun refresh() {
        remotes.value = app.engine.remotes()
        providers.value = app.engine.providers()
        quota.value = remotes.value.associateWith { name -> runCatching { app.engine.call("operations/about", JSONObject().put("fs", "$name:")) }.getOrDefault(JSONObject()) }
    }
    fun browse(name: String, path: String = "") = task {
        remote.value = name; remotePath.value = path; physical.value = app.engine.list(name, path)
    }
    fun connect(name: String, provider: String, options: Map<String,String>) = task {
        app.engine.configure(name, provider, options) { message.value = it }; refresh()
    }
    fun disconnect(name: String) = task {
        app.engine.call("config/delete", JSONObject().put("name", name)); refresh()
        message.value = "Account removed. Existing cloud files were kept."
    }
    fun folder(name: String, parent: String) = task {
        validateName(name); app.db.dao().insert(Node(parent=parent, name=name, folder=true))
    }
    fun edit(node: Node, name: String = node.name, starred: Boolean = node.starred, trash: Boolean = node.trash, parent: String = node.parent) = task {
        validateName(name)
        var current = parent
        val all = nodes.value.associateBy { it.id }
        val visited = mutableSetOf<String>()
        while (current != "root") {
            require(current != node.id && visited.add(current)) { "A folder cannot contain itself" }
            current = all[current]?.parent ?: error("Destination no longer exists")
        }
        app.db.dao().update(node.copy(name=name, parent=parent, starred=starred, trash=trash, modified=System.currentTimeMillis()))
    }
    fun importFile(item: JSONObject, parent: String) = task {
        require(!item.optBoolean("IsDir")) { "Open a folder to import its files" }
        val path = item.getString("Path")
        val existing = nodes.value.find { it.remote == remote.value && it.path == path && !it.trash }
        require(existing == null) { "This file is already in your library" }
        app.db.dao().insert(Node(parent=parent, name=item.getString("Name"), remote=remote.value, path=path,
            size=item.optLong("Size"), mime=item.optString("MimeType", "application/octet-stream")))
        message.value = "Added to My Cloud without copying the original"
    }
    fun upload(uri: Uri, name: String, parent: String, destination: String, wifi: Boolean = false) = task {
        validateName(name)
        val job = Job(kind="UPLOAD", source=uri.toString(), destination="$destination:.underthehood/objects/${java.util.UUID.randomUUID()}/$name", name=name, parent=parent)
        app.db.dao().putJob(job); TransferWorker.enqueue(app, job, wifi)
        message.value = "Upload queued"
    }
    fun download(node: Node, uri: Uri) = task {
        require(node.remote != null && node.path != null)
        val job = Job(kind="DOWNLOAD", source="${node.remote}:${node.path}", destination=uri.toString(), nodeId=node.id, name=node.name)
        app.db.dao().putJob(job); TransferWorker.enqueue(app, job)
    }
    fun preview(node: Node) = task {
        require(node.remote != null && node.path != null)
        require(node.size <= 100L*1024*1024) { "For files over 100 MB, use Save to device and open with a media app." }
        val dir = File(app.cacheDir, "previews").apply { mkdirs() }
        // Cache filenames are derived from IDs, never an untrusted remote path.
        val file = File(dir, node.id + "." + node.name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(12))
        val id = app.engine.copy("${node.remote}:", node.path, dir.absolutePath, file.name)
        while (true) {
            val status = app.engine.call("job/status", JSONObject().put("jobid", id))
            if (status.optBoolean("finished")) { check(status.optBoolean("success")) { "Preview download failed" }; break }
            delay(500)
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        app.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, node.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open ${node.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun queue(job: Job, state: String) = task {
        app.db.dao().jobState(job.id, state)
        WorkManager.getInstance(app).cancelUniqueWork("transfer-${job.id}").result.get()
        if (state == "QUEUED") TransferWorker.enqueue(app, job.copy(state=state))
    }
    fun exportMetadata(uri: Uri) = task {
        val snapshot=Snapshots.encode(app.db.dao().allNodes())
        withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(snapshot.toString(2)) } }
        message.value = "Metadata exported. Provider credentials are excluded."
    }
    fun restoreMetadata(uri:Uri) = task {
        val bytes=withContext(Dispatchers.IO){app.contentResolver.openInputStream(uri)!!.use{input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024){val count=input.read(buffer);if(count<0)break;out.write(buffer,0,count)}
            out.toByteArray()
        }}
        require(bytes.size<=16*1024*1024){"Snapshot exceeds 16 MB"}
        val recovered=withContext(Dispatchers.Default){Snapshots.decode(JSONObject(String(bytes,Charsets.UTF_8)))}
        app.db.withTransaction {
            require(app.db.dao().allNodes().isEmpty()){"Restore requires an empty library to preserve existing data"}
            recovered.forEach{app.db.dao().insert(it)}
        }
        message.value="Restored ${recovered.size} library entries. Reconnect matching remote IDs to access the cloud files."
    }
    fun backupMetadata(destination:String) = task {
        val folder=File(app.noBackupFilesDir,"metadata-snapshots").apply{mkdirs()}
        val file=File(folder,"${System.currentTimeMillis()}.json")
        withContext(Dispatchers.IO){file.writeText(Snapshots.encode(app.db.dao().allNodes()).toString())}
        val job=Job(kind="UPLOAD",source=Uri.fromFile(file).toString(),destination="$destination:.underthehood/metadata/${file.name}",name="Metadata snapshot ${file.name}",nodeId="metadata-snapshot")
        app.db.dao().putJob(job);TransferWorker.enqueue(app,job)
        message.value="Metadata backup queued. Snapshot contains filenames and locations; cloud credentials are excluded."
    }
    companion object {
        fun validateName(name: String) { require(name.isNotBlank() && name.length <= 255 && name !in listOf(".","..") && name.none { it == '/' || it == '\\' || it.code < 32 }) { "Choose a valid name without path separators" } }
    }
}
fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KB","MB","GB","TB"); var n = value.toDouble()/1024; var i=0
    while(n >= 1024 && i < units.lastIndex) { n/=1024; i++ }
    return String.format(java.util.Locale.ROOT,"%.1f %s",n,units[i])
}
