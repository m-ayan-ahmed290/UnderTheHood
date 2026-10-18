@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package app.underthehood

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme =
                    if (dark)
                        darkColorScheme(
                            primary = Color(0xFF66DEC6),
                            background = Color(0xFF101918),
                            surface = Color(0xFF152321),
                        )
                    else
                        lightColorScheme(
                            primary = Color(0xFF006D5B),
                            secondary = Color(0xFF52645F),
                            background = Color(0xFFF5F8F5),
                            surface = Color(0xFFFCFDF9),
                        )
            ) {
                HoodScreen()
            }
        }
    }
}

fun bytes(n: Long): String {
    if (n < 0) return "Unknown"
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = n.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < 3) {
        value /= 1024
        i++
    }
    return "%.1f %s".format(value, units[i])
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
    )
}

@Composable
fun HoodScreen(model: Model = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unlocked by model.unlocked.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val nodes by model.nodes.collectAsStateWithLifecycle()
    val remotes by model.remotes.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var parent by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var focused by remember { mutableStateOf<Node?>(null) }
    var dialog by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var passConfirm by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(model.sync.email ?: "") }
    var syncPassword by remember { mutableStateOf("") }
    var syncPassphrase by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var addAccount by remember { mutableStateOf(false) }
    var backupRemote by remember { mutableStateOf("") }
    var pendingDownload by remember { mutableStateOf<Node?>(null) }
    var pendingOpen by remember { mutableStateOf<Node?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            snack.showSnackbar(message)
            model.message.value = ""
        }
    }
    val pickFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            if (it.isNotEmpty()) model.upload(it, parent)
        }
    val saveFile =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            pendingDownload?.let { n -> if (uri != null) model.download(n, uri) }
            pendingDownload = null
        }
    val pickTree =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) model.backup(uri, backupRemote)
        }
    val notifyPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val exportMetadata =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                model.action {
                    require(syncPassphrase.length >= 12) {
                        "Enter an archive passphrase of at least 12 characters"
                    }
                    val salt = Crypto.random(16)
                    val encrypted =
                        withContext(Dispatchers.IO) {
                            Crypto.seal(
                                model.sync.export().toByteArray(),
                                Crypto.derive(syncPassphrase, salt),
                            )
                        }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(
                                json(
                                        "version" to 1,
                                        "salt" to Crypto.encode(salt),
                                        "payload" to encrypted,
                                    )
                                    .toString()
                                    .toByteArray()
                            )
                        } ?: error("Cannot write archive")
                    }
                    model.message.value =
                        "Encrypted metadata archive saved. Keep your passphrase and reconnect accounts after restoring."
                }
        }
    val importMetadata =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                model.action {
                    val payload =
                        withContext(Dispatchers.IO) {
                            val raw =
                                context.contentResolver
                                    .openInputStream(uri)
                                    ?.bufferedReader()
                                    ?.use { it.readText() } ?: error("Cannot read archive")
                            require(raw.length <= 30 * 1024 * 1024) { "Archive is too large" }
                            val archive = JSONObject(raw)
                            require(archive.getInt("version") == 1)
                            JSONObject(
                                String(
                                    Crypto.open(
                                        archive.getString("payload"),
                                        Crypto.derive(
                                            syncPassphrase,
                                            Crypto.decode(archive.getString("salt")),
                                        ),
                                    )
                                )
                            )
                        }
                    model.message.value = model.sync.merge(payload, "archive")
                }
        }
    fun openFile(node: Node) {
        try {
            val file = context.cacheDir.resolve("previews/${node.id}")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, node.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            model.message.value =
                "No installed app can open this file type. Use Save to device instead."
        }
    }
    LaunchedEffect(jobs, pendingOpen) {
        val n = pendingOpen
        if (
            n != null &&
                jobs.any { it.node == n.id && it.operation == "preview" && it.state == "DONE" }
        ) {
            openFile(n)
            pendingOpen = null
        }
    }
    val authUrl by model.authUrl.collectAsStateWithLifecycle()
    LaunchedEffect(authUrl) {
        if (authUrl.isNotEmpty())
            try {
                val uri = Uri.parse(authUrl)
                require(uri.scheme in listOf("http", "https"))
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                model.message.value =
                    "Browser could not open. Use the authorization button to retry."
            }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (unlocked)
                TopAppBar(
                    title = {
                        Column {
                            Text("UnderTheHood", fontWeight = FontWeight.Bold)
                            Text(
                                listOf(
                                    "Your cloud, together",
                                    "Connected accounts",
                                    "Transfer activity",
                                    "Your preferences",
                                )[tab],
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    actions = {
                        if (busy)
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { model.lock() }) {
                            Icon(Icons.Outlined.Lock, "Lock profile")
                        }
                    },
                )
        },
        bottomBar = {
            if (unlocked)
                NavigationBar {
                    listOf(
                            "My Cloud" to Icons.Outlined.Cloud,
                            "Accounts" to Icons.Outlined.Storage,
                            "Transfers" to Icons.Outlined.SwapVert,
                            "Settings" to Icons.Outlined.Settings,
                        )
                        .forEachIndexed { i, p ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = {
                                    tab = i
                                    selection = emptySet()
                                },
                                icon = { Icon(p.second, p.first) },
                                label = { Text(p.first) },
                            )
                        }
                }
        },
        floatingActionButton = {
            if (unlocked && tab == 0)
                ExtendedFloatingActionButton(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Outlined.Upload, "Upload") },
                    text = { Text("Upload files") },
                )
        },
    ) { padding ->
        if (!unlocked) {
            Column(
                Modifier.padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.CloudQueue,
                    null,
                    Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Everything.\nUnder one hood.",
                    fontSize = 36.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your files, across your accounts. A private library that starts on this device.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    if (model.context.vault.exists) "Unlock your local profile"
                    else "Create your local profile",
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Field(pass, { pass = it }, "Passphrase · at least 12 characters", true)
                if (!model.context.vault.exists) {
                    Spacer(Modifier.height(8.dp))
                    Field(passConfirm, { passConfirm = it }, "Confirm passphrase", true)
                    Text(
                        "Keep your passphrase safe. Cloud account sign-in is optional and separate.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Button(
                    onClick = {
                        if (!model.context.vault.exists && pass != passConfirm)
                            model.message.value = "Passphrases do not match"
                        else {
                            model.unlock(pass)
                            pass = ""
                            passConfirm = ""
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) {
                    Text(
                        if (busy) "Unlocking…"
                        else if (model.context.vault.exists) "Unlock" else "Create profile"
                    )
                }
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        Field(search, { search = it }, "Search your library")
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    parent = ""
                                    search = ""
                                }
                            ) {
                                Text("My Cloud")
                            }
                            if (parent.isNotEmpty()) {
                                Icon(Icons.Outlined.ChevronRight, null)
                                Text(
                                    nodes.find { it.id == parent }?.name ?: "Folder",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                IconButton(
                                    onClick = {
                                        parent = nodes.find { it.id == parent }?.parent ?: ""
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Parent folder")
                                }
                            } else Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    text = ""
                                    dialog = "folder"
                                }
                            ) {
                                Icon(Icons.Outlined.CreateNewFolder, "New folder")
                            }
                            IconButton(onClick = { grid = !grid }) {
                                Icon(
                                    if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                                    "Toggle grid",
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("All", "Starred", "Trash").forEach { label ->
                                FilterChip(
                                    selected = filter == label,
                                    onClick = {
                                        filter = label
                                        selection = emptySet()
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (selection.isNotEmpty())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${selection.size} selected", Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        nodes
                                            .filter { it.id in selection }
                                            .forEach { model.edit(it, starred = true) }
                                        selection = emptySet()
                                    }
                                ) {
                                    Text("Star")
                                }
                                TextButton(
                                    onClick = {
                                        nodes
                                            .filter { it.id in selection }
                                            .forEach { model.edit(it, trashed = filter != "Trash") }
                                        selection = emptySet()
                                    }
                                ) {
                                    Text(if (filter == "Trash") "Restore" else "Trash")
                                }
                                IconButton(onClick = { selection = emptySet() }) {
                                    Icon(Icons.Outlined.Close, "Clear selection")
                                }
                            }
                    }
                    val visible =
                        nodes.filter { n ->
                            when (filter) {
                                "Trash" -> n.trashed
                                "Starred" -> n.starred && LibraryRules.visible(n, nodes)
                                else ->
                                    LibraryRules.visible(n, nodes) &&
                                        (search.isNotEmpty() || n.parent == parent)
                            } &&
                                (search.isEmpty() ||
                                    n.name.contains(search, true) ||
                                    n.tags.contains(search, true) ||
                                    n.notes.contains(search, true))
                        }
                    if (visible.isEmpty())
                        EmptyState(
                            if (filter == "Trash") "Your trash is empty" else "Room for everything",
                            if (remotes.isEmpty())
                                "Connect an account to bring your cloud files together."
                            else "Upload a file, create a folder, or import existing cloud files.",
                        )
                    else if (grid)
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(155.dp),
                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visible, key = { it.id }) { node ->
                                ElevatedCard(
                                    onClick = {
                                        if (node.folder) parent = node.id else focused = node
                                    }
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Icon(
                                            if (node.folder) Icons.Outlined.Folder
                                            else Icons.Outlined.Description,
                                            null,
                                            Modifier.size(40.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            node.name,
                                            maxLines = 2,
                                            modifier = Modifier.padding(top = 12.dp),
                                        )
                                        Text(
                                            if (node.folder) "Folder" else bytes(node.size),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        IconButton(onClick = { focused = node }) {
                                            Icon(Icons.Outlined.MoreHoriz, "File actions")
                                        }
                                    }
                                }
                            }
                        }
                    else
                        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
                            items(visible, key = { it.id }) { node ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (selection.isNotEmpty())
                                                    selection =
                                                        if (node.id in selection)
                                                            selection - node.id
                                                        else selection + node.id
                                                else if (node.folder) {
                                                    parent = node.id
                                                    search = ""
                                                } else focused = node
                                            },
                                            onLongClick = { selection = selection + node.id },
                                        )
                                        .padding(horizontal = 18.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selection.isNotEmpty())
                                        Checkbox(
                                            node.id in selection,
                                            {
                                                selection =
                                                    if (node.id in selection) selection - node.id
                                                    else selection + node.id
                                            },
                                        )
                                    else
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Icon(
                                                if (node.folder) Icons.Outlined.Folder
                                                else Icons.Outlined.Description,
                                                null,
                                                Modifier.padding(12.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                        Text(
                                            node.name,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                        )
                                        Text(
                                            if (node.folder) "Virtual folder"
                                            else
                                                "${bytes(node.size)} · ${if(node.manifest.isNotEmpty())"Managed file"else remotes.find{it.id==node.remote}?.name?:"Account unavailable"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (node.starred)
                                        Icon(Icons.Outlined.Star, "Starred", Modifier.size(18.dp))
                                    IconButton(onClick = { focused = node }) {
                                        Icon(Icons.Outlined.MoreVert, "Actions for ${node.name}")
                                    }
                                }
                            }
                        }
                }
                1 ->
                    LazyColumn(
                        contentPadding = PaddingValues(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item {
                            val known = remotes.filter { it.total >= 0 }
                            Card {
                                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Text(
                                        "A little more space.\nA lot less switching.",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "${bytes(known.sumOf{it.total})} reported capacity · ${remotes.size} accounts",
                                        Modifier.padding(top = 12.dp),
                                    )
                                    Text(
                                        "${remotes.count{it.total<0}} accounts have unknown quota. Usable space depends on your storage policy.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = { addAccount = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.Add, null)
                                Text("Connect an account", Modifier.padding(start = 8.dp))
                            }
                        }
                        items(remotes, key = { it.id }) { r ->
                            OutlinedCard {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${r.provider} · ${if(r.healthy)"Last test succeeded"else"Needs connection test"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "${bytes(r.used)} used · ${bytes(r.free)} available",
                                        Modifier.padding(vertical = 8.dp),
                                    )
                                    Row {
                                        TextButton(onClick = { model.test(r) }) { Text("Test") }
                                        TextButton(onClick = { model.explore(r) }) {
                                            Text("Browse")
                                        }
                                        TextButton(onClick = { model.index(r) }) {
                                            Text("Index existing")
                                        }
                                    }
                                    Row {
                                        TextButton(
                                            onClick = {
                                                model.startRemote(r.provider, r.name, "", "", r)
                                            }
                                        ) {
                                            Text("Reconnect")
                                        }
                                        TextButton(
                                            onClick = {
                                                focused = null
                                                text = r.id
                                                dialog = "disconnect"
                                            }
                                        ) {
                                            Text("Disconnect")
                                        }
                                    }
                                }
                            }
                        }
                    }
                2 ->
                    LazyColumn(
                        contentPadding = PaddingValues(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (jobs.isEmpty())
                            item {
                                EmptyState(
                                    "Nothing in transit",
                                    "Uploads, downloads and previews appear here, with their real progress and errors.",
                                )
                            }
                        items(jobs, key = { it.id }) { job ->
                            OutlinedCard {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(job.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${job.operation} · ${job.state}",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    if (job.state == "RUNNING")
                                        LinearProgressIndicator(
                                            progress = {
                                                if (job.total > 0)
                                                    (job.done.toFloat() / job.total).coerceIn(
                                                        0f,
                                                        1f,
                                                    )
                                                else 0f
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                        )
                                    Text(
                                        "${bytes(job.done)} / ${bytes(job.total)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (job.error.isNotEmpty())
                                        Text(job.error, color = MaterialTheme.colorScheme.error)
                                    Row {
                                        if (job.state in listOf("RUNNING", "QUEUED", "RETRYING"))
                                            TextButton(
                                                onClick = {
                                                    model.action { Queue.pause(context, job) }
                                                }
                                            ) {
                                                Text("Pause")
                                            }
                                        if (job.state in listOf("PAUSED", "FAILED", "CANCELLED"))
                                            TextButton(
                                                onClick = {
                                                    model.action {
                                                        Queue.enqueue(
                                                            context,
                                                            job,
                                                            model.wifi,
                                                            model.charging,
                                                        )
                                                    }
                                                }
                                            ) {
                                                Text("Retry / resume")
                                            }
                                        if (job.state !in listOf("DONE", "CANCELLED"))
                                            TextButton(
                                                onClick = {
                                                    model.action { Queue.cancel(context, job) }
                                                }
                                            ) {
                                                Text("Cancel")
                                            }
                                        if (job.state == "DONE" && job.operation == "preview")
                                            TextButton(
                                                onClick = {
                                                    nodes
                                                        .find { it.id == job.node }
                                                        ?.let { openFile(it) }
                                                }
                                            ) {
                                                Text("Open")
                                            }
                                    }
                                }
                            }
                        }
                    }
                3 ->
                    LazyColumn(
                        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text("Storage & transfers", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Uploads use private managed objects. Existing files are imported by reference.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        item {
                            var policy by remember { mutableStateOf(model.policy) }
                            Row {
                                FilterChip(
                                    policy == "most-free",
                                    {
                                        policy = "most-free"
                                        model.policy = policy
                                    },
                                    { Text("Most free") },
                                )
                                Spacer(Modifier.width(8.dp))
                                FilterChip(
                                    policy == "manual",
                                    {
                                        policy = "manual"
                                        model.policy = policy
                                    },
                                    { Text("Choose account") },
                                )
                            }
                            if (policy == "manual")
                                AccountPicker(remotes, model.preferred) { model.preferred = it }
                        }
                        item {
                            Toggle("Split uploads into 16 MiB chunks", model.chunks) {
                                model.chunks = it
                            }
                            Toggle("Protection: two verified copies", model.protection) {
                                model.protection = it
                            }
                            Text(
                                "Protection doubles physical storage. Capacity mode can lose a file if any required chunk becomes unavailable.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Toggle("Wi-Fi / unmetered network only", model.wifi) { model.wifi = it }
                            Toggle("Only while charging", model.charging) { model.charging = it }
                            TextButton(
                                onClick = {
                                    if (android.os.Build.VERSION.SDK_INT >= 33)
                                        notifyPermission.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                }
                            ) {
                                Text("Enable transfer notifications")
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text(
                                "One-way folder backup",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            AccountPicker(remotes, backupRemote) { backupRemote = it }
                            Button(
                                onClick = {
                                    if (backupRemote.isEmpty())
                                        model.message.value = "Choose a backup account first"
                                    else pickTree.launch(null)
                                }
                            ) {
                                Text("Choose a folder to back up")
                            }
                            Text(
                                "Every 6 hours when Android permits. Source deletions do not delete cloud backups. Each changed file creates a new version.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        item {
                            val rules by model.backups.collectAsStateWithLifecycle()
                            rules.forEach { r ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.label, Modifier.weight(1f))
                                    TextButton(
                                        onClick = {
                                            model.action {
                                                val req =
                                                    androidx.work
                                                        .OneTimeWorkRequestBuilder<BackupWorker>()
                                                        .setInputData(
                                                            androidx.work.workDataOf("rule" to r.id)
                                                        )
                                                        .build()
                                                androidx.work.WorkManager.getInstance(context)
                                                    .enqueue(req)
                                            }
                                        }
                                    ) {
                                        Text("Run now")
                                    }
                                    IconButton(
                                        onClick = {
                                            model.action {
                                                androidx.work.WorkManager.getInstance(context)
                                                    .cancelUniqueWork("backup-${r.id}")
                                                model.store.deleteBackup(r.id)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Outlined.Delete, "Remove backup rule")
                                    }
                                }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text(
                                "Account & encrypted snapshots",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "Optional Supabase account. Sync currently uses manual, encrypted library snapshots. File bytes and provider credentials are not sent to Supabase.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        item {
                            Field(email, { email = it }, "Email")
                            Spacer(Modifier.height(8.dp))
                            Field(syncPassword, { syncPassword = it }, "Account password", true)
                            Row {
                                Button(
                                    onClick = {
                                        model.action {
                                            model.message.value =
                                                model.sync.login(email, syncPassword, false)
                                            syncPassword = ""
                                        }
                                    }
                                ) {
                                    Text("Sign in")
                                }
                                TextButton(
                                    onClick = {
                                        model.action {
                                            model.message.value =
                                                model.sync.login(email, syncPassword, true)
                                            syncPassword = ""
                                        }
                                    }
                                ) {
                                    Text("Create account")
                                }
                                TextButton(
                                    onClick = {
                                        model.sync.logout()
                                        model.message.value = "Signed out"
                                    }
                                ) {
                                    Text("Sign out")
                                }
                            }
                        }
                        item {
                            Field(
                                syncPassphrase,
                                { syncPassphrase = it },
                                "Sync / archive passphrase",
                                true,
                            )
                            Text(
                                "Use the same passphrase on each device. Losing it makes encrypted snapshots unrecoverable.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                Button(
                                    onClick = {
                                        model.action {
                                            model.message.value = model.sync.push(syncPassphrase)
                                        }
                                    }
                                ) {
                                    Text("Upload snapshot")
                                }
                                TextButton(
                                    onClick = {
                                        model.action { snapshots = model.sync.snapshots() }
                                    }
                                ) {
                                    Text("Find snapshots")
                                }
                            }
                        }
                        items(snapshots) { s ->
                            OutlinedCard {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(s.optString("created_at"))
                                    Text(
                                        "Device ${s.optString("device_id").take(8)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(
                                        onClick = {
                                            model.action {
                                                model.message.value =
                                                    model.sync.pull(
                                                        s.getString("id"),
                                                        syncPassphrase,
                                                    )
                                            }
                                        }
                                    ) {
                                        Text("Merge snapshot · preserve conflicts")
                                    }
                                }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text("Metadata recovery", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "An encrypted archive preserves your virtual tree and chunk manifests. Store it somewhere safe; it contains no cloud login tokens.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                TextButton(
                                    onClick = {
                                        if (syncPassphrase.length < 12)
                                            model.message.value =
                                                "Enter an archive passphrase above first"
                                        else exportMetadata.launch("UnderTheHood-library.json")
                                    }
                                ) {
                                    Text("Export archive")
                                }
                                TextButton(
                                    onClick = {
                                        importMetadata.launch(
                                            arrayOf("application/json", "application/octet-stream")
                                        )
                                    }
                                ) {
                                    Text("Restore archive")
                                }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text("UnderTheHood 0.1.0", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Native Android · Rclone 1.75.2\nOffline library. No root. No separate Rclone installation.\nPreviews use compatible apps installed on your device.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
            }
        }
    }
    if (dialog == "folder" || dialog == "rename" || dialog == "notes" || dialog == "tags")
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = {
                Text(
                    mapOf(
                        "folder" to "New virtual folder",
                        "rename" to "Rename",
                        "notes" to "Notes",
                        "tags" to "Tags",
                    )[dialog] ?: ""
                )
            },
            text = { Field(text, { text = it }, "Value") },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (dialog) {
                            "folder" -> model.createFolder(parent, text)
                            "rename" -> focused?.let { model.edit(it, name = text) }
                            "notes" -> focused?.let { model.edit(it, notes = text) }
                            "tags" -> focused?.let { model.edit(it, tags = text) }
                        }
                        dialog = ""
                        focused = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
    if (dialog == "disconnect")
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Disconnect account?") },
            text = {
                Text(
                    "This removes its login from this device. Cloud files remain, but library files on this account will be unavailable until you reconnect it."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        remotes.find { it.id == text }?.let { model.disconnect(it) }
                        dialog = ""
                    }
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
    if (dialog == "move")
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Move to virtual folder") },
            text = {
                LazyColumn {
                    item {
                        TextButton(
                            onClick = {
                                focused?.let { model.edit(it, parent = "") }
                                focused = null
                                dialog = ""
                            }
                        ) {
                            Text("My Cloud")
                        }
                    }
                    items(
                        nodes.filter {
                            it.folder &&
                                !it.trashed &&
                                LibraryRules.canMove(focused?.id ?: "", it.id, nodes)
                        }
                    ) { folder ->
                        TextButton(
                            onClick = {
                                focused?.let { model.edit(it, parent = folder.id) }
                                focused = null
                                dialog = ""
                            }
                        ) {
                            Text(folder.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
    if (focused != null && dialog.isEmpty()) {
        val node = focused!!
        ModalBottomSheet(onDismissRequest = { focused = null }) {
            Column(Modifier.padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
                Text(node.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (node.folder) "Virtual folder" else "${bytes(node.size)} · ${node.mime}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!node.folder) {
                    TextButton(
                        onClick = {
                            if (context.cacheDir.resolve("previews/${node.id}").exists())
                                openFile(node)
                            else {
                                pendingOpen = node
                                model.download(node, null, true)
                            }
                            focused = null
                        }
                    ) {
                        Text("Open with compatible app")
                    }
                    TextButton(
                        onClick = {
                            pendingDownload = node
                            saveFile.launch(node.name)
                            focused = null
                        }
                    ) {
                        Text("Save to device")
                    }
                }
                TextButton(
                    onClick = {
                        text = node.name
                        dialog = "rename"
                    }
                ) {
                    Text("Rename")
                }
                TextButton(onClick = { dialog = "move" }) { Text("Move") }
                if (!node.folder)
                    TextButton(
                        onClick = {
                            model.duplicate(node)
                            focused = null
                        }
                    ) {
                        Text("Duplicate library reference")
                    }
                TextButton(
                    onClick = {
                        model.edit(node, starred = !node.starred)
                        focused = null
                    }
                ) {
                    Text(if (node.starred) "Remove star" else "Add star")
                }
                TextButton(
                    onClick = {
                        text = node.notes
                        dialog = "notes"
                    }
                ) {
                    Text("Edit notes")
                }
                TextButton(
                    onClick = {
                        text = node.tags
                        dialog = "tags"
                    }
                ) {
                    Text("Edit tags")
                }
                TextButton(
                    onClick = {
                        model.edit(node, trashed = !node.trashed)
                        focused = null
                    }
                ) {
                    Text(if (node.trashed) "Restore from trash" else "Move to trash")
                }
                if (node.trashed)
                    TextButton(onClick = { dialog = "forget" }) {
                        Text("Remove library reference permanently")
                    }
                if (node.manifest.isNotEmpty())
                    Text(
                        "Storage manifest: ${JSONObject(node.manifest).getJSONArray("chunks").length()} pieces; ${if(JSONObject(node.manifest).optBoolean("protected"))"two verified copies at upload"else"capacity mode"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                else if (node.remote.isNotEmpty())
                    Text(
                        "Physical location: ${remotes.find{it.id==node.remote}?.name?:node.remote}/${node.path}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
    if (dialog == "forget")
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Remove library references?") },
            text = {
                Text(
                    "This removes this item and its descendants from the library. Physical cloud objects remain. Export a metadata archive first to keep a recovery path."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        focused?.let { model.forget(it) }
                        focused = null
                        dialog = ""
                    }
                ) {
                    Text("Remove references")
                }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
    if (addAccount) AccountDialog(model, onDismiss = { addAccount = false })
    val wizard by model.wizard.collectAsStateWithLifecycle()
    val configuring by model.wizardRemote.collectAsStateWithLifecycle()
    if (configuring != null) {
        val question = wizard?.optJSONObject("Option")
        val passwordBackend =
            question?.optBoolean("IsPassword") == true &&
                question.optString("Name") in listOf("pass", "password") &&
                configuring?.provider != "crypt"
        var answer by
            remember(wizard) {
                mutableStateOf(
                    question?.opt("Default")?.takeUnless { it == JSONObject.NULL }?.toString() ?: ""
                )
            }
        AlertDialog(
            onDismissRequest = { model.cancelWizard() },
            title = { Text("Connect ${configuring!!.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(wizard?.optString("Error") ?: "")
                    if (passwordBackend) {
                        Text(
                            "This backend requires a provider password. UnderTheHood does not collect provider passwords. Close setup and choose an OAuth or key-based provider. MEGA account login is unavailable in this build for this reason."
                        )
                    } else if (question != null) {
                        Text(question.optString("Help"))
                        Spacer(Modifier.height(8.dp))
                        Field(
                            answer,
                            { answer = it },
                            question.optString("Name", "Answer"),
                            question.optBoolean("IsPassword"),
                        )
                        question.optJSONArray("Examples")?.objects()?.forEach { e ->
                            TextButton(onClick = { answer = e.optString("Value") }) {
                                Text("${e.optString("Value")} · ${e.optString("Help")}")
                            }
                        }
                    } else
                        Text(
                            if (busy)
                                "Waiting for provider authorization… Complete sign-in in your browser."
                            else "Preparing provider setup…"
                        )
                    if (authUrl.isNotEmpty())
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                )
                            }
                        ) {
                            Text("Open authorization page")
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { model.continueRemote(answer) },
                    enabled = !busy && question != null && !passwordBackend,
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { model.cancelWizard() }) { Text("Close setup") }
            },
        )
    }
    val explorer by model.physicalRemote.collectAsStateWithLifecycle()
    val listing by model.physical.collectAsStateWithLifecycle()
    val path by model.physicalPath.collectAsStateWithLifecycle()
    if (explorer != null)
        ModalBottomSheet(onDismissRequest = { model.physicalRemote.value = null }) {
            Column(Modifier.fillMaxHeight(0.9f).padding(18.dp)) {
                Text(explorer!!.name, style = MaterialTheme.typography.titleLarge)
                Text("Original cloud folders · $path", style = MaterialTheme.typography.bodySmall)
                if (path.isNotEmpty())
                    TextButton(
                        onClick = { model.explore(explorer!!, path.substringBeforeLast('/', "")) }
                    ) {
                        Text("Parent folder")
                    }
                LazyColumn {
                    items(listing) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    if (f.optBoolean("IsDir"))
                                        model.explore(explorer!!, f.getString("Path"))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    if (f.optBoolean("IsDir")) Icons.Outlined.Folder
                                    else Icons.Outlined.Description,
                                    null,
                                )
                                Text(f.getString("Name"), Modifier.padding(start = 8.dp))
                            }
                            if (!f.optBoolean("IsDir"))
                                TextButton(onClick = { model.importFile(explorer!!, f, parent) }) {
                                    Text("Import")
                                }
                        }
                    }
                }
            }
        }
}

@Composable
fun Toggle(label: String, initial: Boolean, change: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(
            checked,
            {
                checked = it
                change(it)
            },
        )
    }
}

@Composable
fun AccountPicker(remotes: List<Remote>, selected: String, onSelect: (String) -> Unit) {
    var chosen by remember(selected) { mutableStateOf(selected) }
    Column {
        remotes.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    chosen = r.id
                    onSelect(r.id)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    chosen == r.id,
                    {
                        chosen = r.id
                        onSelect(r.id)
                    },
                )
                Text(r.name)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.CloudQueue,
            null,
            Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun AccountDialog(model: Model, onDismiss: () -> Unit) {
    val providers by model.providers.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var name by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect cloud storage") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                if (selected == null) {
                    Field(search, { search = it }, "Find a provider")
                    providers
                        .filter { it.toString().contains(search, true) }
                        .forEach { p ->
                            TextButton(
                                onClick = {
                                    selected = p
                                    name = p.optString("Description", p.optString("Name"))
                                }
                            ) {
                                Text(p.optString("Description", p.optString("Name")))
                            }
                        }
                } else {
                    Text(selected!!.optString("Description"))
                    Field(name, { name = it }, "Account display name")
                    Spacer(Modifier.height(12.dp))
                    Text("Custom OAuth application (optional)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Leave these empty to use Rclone's defaults. Some providers restrict shared credentials; their authorization errors will be shown.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Field(clientId, { clientId = it }, "Client ID · optional")
                    Spacer(Modifier.height(8.dp))
                    Field(clientSecret, { clientSecret = it }, "Client secret · optional", true)
                    Text(
                        "Provider options and official sign-in follow. No developer account is required when the provider accepts Rclone's default application.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let { p ->
                        model.startRemote(
                            p.optString("Prefix", p.optString("Name")),
                            name,
                            clientId,
                            clientSecret,
                        )
                        onDismiss()
                    }
                },
                enabled = selected != null && name.isNotBlank(),
            ) {
                Text("Continue")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
