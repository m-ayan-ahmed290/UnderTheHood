package app.underthehood

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class Model(app: Application) : AndroidViewModel(app) {
    val context = app as HoodApp
    val store = context.db.store()
    val nodes =
        store
            .observeNodes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val remotes =
        store
            .observeRemotes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs =
        store
            .observeJobs()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val backups =
        store
            .observeBackups()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val events =
        store
            .observeEvents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val unlocked = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val providers = MutableStateFlow<List<JSONObject>>(emptyList())
    val wizard = MutableStateFlow<JSONObject?>(null)
    val wizardRemote = MutableStateFlow<Remote?>(null)
    val authUrl = MutableStateFlow("")
    val physical = MutableStateFlow<List<JSONObject>>(emptyList())
    val physicalPath = MutableStateFlow("")
    val physicalRemote = MutableStateFlow<Remote?>(null)
    val sync = SyncClient(context)
    private val preferences =
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var policy: String
        get() = preferences.getString("policy", "most-free")!!
        set(value) {
            preferences.edit().putString("policy", value).apply()
        }

    var preferred: String
        get() = preferences.getString("preferred", "")!!
        set(value) {
            preferences.edit().putString("preferred", value).apply()
        }

    var chunks: Boolean
        get() = preferences.getBoolean("chunks", true)
        set(value) {
            preferences.edit().putBoolean("chunks", value).apply()
        }

    var protection: Boolean
        get() = preferences.getBoolean("protection", false)
        set(value) {
            preferences.edit().putBoolean("protection", value).apply()
        }

    var wifi: Boolean
        get() = preferences.getBoolean("wifi", false)
        set(value) {
            preferences.edit().putBoolean("wifi", value).apply()
        }

    var charging: Boolean
        get() = preferences.getBoolean("charging", false)
        set(value) {
            preferences.edit().putBoolean("charging", value).apply()
        }

    fun action(task: suspend () -> Unit) =
        viewModelScope.launch {
            busy.value = true
            try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Operation failed"
            } finally {
                busy.value = false
            }
        }

    fun unlock(password: String) = action {
        withContext(Dispatchers.IO) {
            if (context.vault.exists) context.vault.unlock(password)
            else context.vault.create(password)
        }
        unlocked.value = true
        loadProviders()
    }

    fun lock() {
        unlocked.value = false
        wizard.value = null
    }

    suspend fun loadProviders() {
        providers.value = Engine.rpc("config/providers").getJSONArray("providers").objects()
    }

    fun createFolder(parent: String, name: String) = action {
        val normalized = LibraryRules.name(name)
        require(
            store.nodes().none { it.parent == parent && it.name == normalized && !it.trashed }
        ) {
            "A file or folder already has that name"
        }
        store.put(Node(parent = parent, name = normalized, folder = true))
    }

    fun edit(
        node: Node,
        name: String = node.name,
        parent: String = node.parent,
        starred: Boolean = node.starred,
        trashed: Boolean = node.trashed,
        notes: String = node.notes,
        tags: String = node.tags,
    ) = action {
        val all = store.nodes()
        require(LibraryRules.canMove(node.id, parent, all)) { "Cannot move a folder inside itself" }
        val n = LibraryRules.name(name)
        require(
            all.none { it.id != node.id && it.parent == parent && it.name == n && !it.trashed }
        ) {
            "A file or folder already has that name"
        }
        store.put(
            node.copy(
                name = n,
                parent = parent,
                starred = starred,
                trashed = trashed,
                notes = notes,
                tags = tags,
                modified = System.currentTimeMillis(),
                revision = node.revision + 1,
            )
        )
    }

    fun forget(node: Node) = action {
        require(node.trashed) { "Move this item to trash first" }
        val all = store.nodes()
        val ids = mutableSetOf(node.id)
        var changed = true
        while (changed) {
            changed = false
            for (n in all) if (n.parent in ids && ids.add(n.id)) changed = true
        }
        context.db.withTransaction { ids.forEach { store.deleteNode(it) } }
        message.value = "Library references removed. Cloud objects remain for recovery."
    }

    fun duplicate(node: Node) = action {
        require(!node.folder) { "Duplicate individual files" }
        store.put(
            node.copy(
                id = uuid(),
                name = node.name.take(180) + " (copy)",
                modified = System.currentTimeMillis(),
            )
        )
    }

    private var configurationJob: Long? = null

    private suspend fun configure(method: String, parameters: JSONObject): JSONObject {
        parameters.put("_async", true)
        val id = Engine.rpc(method, parameters).getLong("jobid")
        configurationJob = id
        try {
            while (true) {
                val status = Engine.rpc("job/status", json("jobid" to id))
                if (status.optBoolean("finished")) {
                    check(status.optBoolean("success")) {
                        status.optString("error", "Connection setup failed")
                    }
                    return status.optJSONObject("output") ?: JSONObject()
                }
                delay(300)
            }
        } finally {
            configurationJob = null
        }
    }

    fun startRemote(
        provider: String,
        name: String,
        clientId: String,
        clientSecret: String,
        reconnect: Remote? = null,
    ) = action {
        val r = reconnect ?: Remote(uuid(), LibraryRules.name(name), provider)
        wizardRemote.value = r
        store.putRemote(r)
        val params = JSONObject()
        if (clientId.isNotBlank()) params.put("client_id", clientId.trim())
        if (clientSecret.isNotBlank()) params.put("client_secret", clientSecret.trim())
        wizard.value =
            configure(
                "config/create",
                json(
                    "name" to r.id,
                    "type" to provider,
                    "parameters" to params,
                    "opt" to json("nonInteractive" to true, "all" to true),
                ),
            )
        finishWizard()
    }

    fun continueRemote(answer: String) = action {
        val r = wizardRemote.value ?: return@action
        val state = wizard.value?.optString("State") ?: ""
        wizard.value =
            configure(
                "config/update",
                json(
                    "name" to r.id,
                    "parameters" to JSONObject(),
                    "opt" to
                        json(
                            "nonInteractive" to true,
                            "continue" to true,
                            "all" to true,
                            "state" to state,
                            "result" to answer,
                        ),
                ),
            )
        finishWizard()
    }

    private suspend fun finishWizard() {
        if (wizard.value?.optString("State").isNullOrEmpty()) {
            val r = wizardRemote.value
            wizard.value = null
            wizardRemote.value = null
            if (r != null) testRemote(r)
        }
    }

    fun cancelWizard() = action {
        configurationJob?.let { Engine.rpc("job/stop", json("jobid" to it)) }
        wizard.value = null
        wizardRemote.value = null
        authUrl.value = ""
        message.value = "Connection setup closed. You can reconnect the account to restart setup."
    }

    suspend fun testRemote(r: Remote) {
        Engine.rpc(
            "operations/list",
            json("fs" to "${r.id}:", "remote" to "", "opt" to json("maxDepth" to 1)),
        )
        val quota =
            runCatching { Engine.rpc("operations/about", json("fs" to "${r.id}:")) }.getOrNull()
        store.putRemote(
            r.copy(
                healthy = true,
                total = quota?.optLong("total", -1) ?: -1,
                used = quota?.optLong("used", -1) ?: -1,
                free = quota?.optLong("free", -1) ?: -1,
                checked = System.currentTimeMillis(),
            )
        )
        message.value = "Connected to ${r.name}"
    }

    fun test(r: Remote) = action {
        try {
            testRemote(r)
        } catch (e: Exception) {
            store.putRemote(r.copy(healthy = false, checked = System.currentTimeMillis()))
            throw e
        }
    }

    fun disconnect(r: Remote) = action {
        Engine.rpc("config/delete", json("name" to r.id))
        store.deleteRemote(r.id)
        message.value = "Account disconnected. Files and library references were kept."
    }

    fun explore(r: Remote, path: String = "") = action {
        physicalRemote.value = r
        physicalPath.value = path
        physical.value =
            Engine.rpc("operations/list", json("fs" to "${r.id}:", "remote" to path))
                .getJSONArray("list")
                .objects()
    }

    fun importFile(r: Remote, file: JSONObject, parent: String) = action {
        val path = file.getString("Path")
        require(!file.optBoolean("IsDir")) {
            "Open folders to import individual files, or use Index existing files"
        }
        val id = UUIDName(r.id + ":" + path)
        val existing = store.node(id)
        store.put(
            (existing ?: Node(id = id, name = file.getString("Name"))).copy(
                parent = parent,
                remote = r.id,
                path = path,
                size = file.optLong("Size"),
                mime =
                    java.net.URLConnection.guessContentTypeFromName(path)
                        ?: "application/octet-stream",
                modified = System.currentTimeMillis(),
            )
        )
        message.value = "Added to your library without copying cloud data"
    }

    fun index(r: Remote) = action {
        val rows =
            Engine.rpc(
                    "operations/list",
                    json("fs" to "${r.id}:", "remote" to "", "opt" to json("recurse" to true)),
                )
                .getJSONArray("list")
                .objects()
        val root = UUIDName(r.id + ":index")
        context.db.withTransaction {
            if (store.node(root) == null)
                store.put(Node(id = root, name = "${r.name} · imported", folder = true))
            for (file in rows.sortedBy { it.getString("Path").count { c -> c == '/' } }) {
                val path = file.getString("Path")
                val id = UUIDName(r.id + ":" + path)
                val old = store.node(id)
                val parent = path.substringBeforeLast('/', "")
                val parentId = if (parent.isEmpty()) root else UUIDName(r.id + ":" + parent)
                store.put(
                    (old ?: Node(id = id, parent = parentId, name = file.getString("Name"))).copy(
                        folder = file.optBoolean("IsDir"),
                        remote = r.id,
                        path = path,
                        size = maxOf(0, file.optLong("Size")),
                        mime =
                            java.net.URLConnection.guessContentTypeFromName(path)
                                ?: "application/octet-stream",
                    )
                )
            }
        }
        message.value = "Indexed ${rows.size} items. Existing library organization was preserved."
    }

    fun upload(uris: List<Uri>, parent: String) = action {
        for (uri in uris) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            var name = "Upload"
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n)
                    if (s >= 0) size = c.getLong(s)
                }
            }
            val remote =
                LibraryRules.choose(store.remotes(), if (chunks) 0 else size, policy, preferred)
            Queue.enqueue(
                context,
                Transfer(
                    operation = "upload",
                    uri = uri.toString(),
                    name = name,
                    parent = parent,
                    remote = remote.id,
                    total = size,
                    chunked = chunks,
                    protected = protection,
                    distributed = chunks && policy != "manual",
                ),
                wifi,
                charging,
            )
        }
    }

    fun download(node: Node, uri: Uri?, preview: Boolean = false) = action {
        Queue.enqueue(
            context,
            Transfer(
                operation = if (preview) "preview" else "download",
                node = node.id,
                uri = uri?.toString() ?: "",
                name = node.name,
                total = node.size,
            ),
            wifi,
            charging,
        )
    }

    fun backup(uri: Uri, remote: String) = action {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val rule =
            Backup(
                uri = uri.toString(),
                remote = remote,
                label =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
                        ?: "Backup",
                wifi = wifi,
                charging = charging,
            )
        store.putBackup(rule)
        scheduleBackup(context, rule)
        message.value =
            "One-way backup scheduled every 6 hours; Android chooses the exact execution time."
    }

    init {
        viewModelScope.launch {
            while (isActive) {
                if (unlocked.value)
                    runCatching {
                        Engine.rpc("uth/authURL")
                            .optString("url")
                            .takeIf { it.isNotEmpty() }
                            ?.let { authUrl.value = it }
                    }
                delay(500)
            }
        }
    }
}
