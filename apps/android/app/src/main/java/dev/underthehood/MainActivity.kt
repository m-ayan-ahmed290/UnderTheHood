package dev.underthehood

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model: HoodModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { HoodTheme { HoodApp(model) } } }
}
@Composable fun HoodTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if(dark) darkColorScheme(primary=Color(0xFF9BE2CB), background=Color(0xFF10191C), surface=Color(0xFF162226), secondaryContainer=Color(0xFF29403D))
        else lightColorScheme(primary=Color(0xFF176B57), background=Color(0xFFF6F8F7), surface=Color.White, secondaryContainer=Color(0xFFD8EEE5))
    MaterialTheme(colorScheme=colors, typography=Typography(), content=content)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HoodApp(m: HoodModel) {
    val unlocked by m.unlocked.collectAsState(); val busy by m.busy.collectAsState(); val message by m.message.collectAsState()
    val nodes by m.nodes.collectAsState(); val jobs by m.jobs.collectAsState(); val remotes by m.remotes.collectAsState()
    var tab by remember { mutableIntStateOf(0) }; var parent by remember { mutableStateOf("root") }; var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All files") }; var dialog by remember { mutableStateOf("") }; var selected by remember { mutableStateOf<Node?>(null) }
    var pendingDownload by remember { mutableStateOf<Node?>(null) }; var uploadName by remember { mutableStateOf("") }; var uploadUri by remember { mutableStateOf<Uri?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uploadUri = it
        uploadName = context.contentResolver.query(it,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else "Upload" } ?: "Upload"
        dialog="upload"
    } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if(uri != null) pendingDownload?.let { m.download(it,uri) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { m.exportMetadata(it) } }
    BackHandler(parent!="root" && tab==0) { parent=nodes.find { it.id==parent }?.parent ?: "root" }
    Surface(Modifier.fillMaxSize()) {
        if (!unlocked) {
            var password by remember { mutableStateOf("") }
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement=Arrangement.Center) {
                Icon(Icons.Outlined.CloudQueue,null,Modifier.size(60.dp),tint=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp)); Text("UnderTheHood",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                Text("One library. All your clouds.",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(32.dp)); Text(if(m.app.vault.exists) "Welcome back" else "Your cloud, on your terms",style=MaterialTheme.typography.headlineSmall)
                Text(if(m.app.vault.exists) "Unlock your local profile to continue." else "Create a local passphrase. No cloud account is needed to get started.",Modifier.padding(vertical=12.dp))
                OutlinedTextField(password,{password=it},label={Text("Passphrase · 12+ characters")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
                Button({m.unlock(password)},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(top=16.dp)) { Text(if(m.app.vault.exists) "Unlock library" else "Create local profile") }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth()); if(message.isNotEmpty()) Text(message,Modifier.padding(top=12.dp),color=MaterialTheme.colorScheme.error)
            }
        } else {
            Scaffold(topBar={ TopAppBar(title={ Column { Text("UnderTheHood",fontWeight=FontWeight.Bold); Text("YOUR CLOUD, CONNECTED",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) } },actions={ IconButton({m.task { m.refresh() }}) { Icon(Icons.Outlined.Refresh,"Refresh accounts") } }) },
                bottomBar={ NavigationBar { listOf("My Cloud","Accounts","Transfers","Settings").forEachIndexed { i, label -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(when(i){0->Icons.Outlined.FolderOpen;1->Icons.Outlined.CloudQueue;2->Icons.Outlined.SwapVert;else->Icons.Outlined.Settings},label)},label={Text(label)}) } } },
                floatingActionButton={ if(tab==0) FloatingActionButton({dialog="add"}) { Icon(Icons.Outlined.Add,"Add files or folder") } else if(tab==1) FloatingActionButton({dialog="connect"}) { Icon(Icons.Outlined.Add,"Connect account") } }
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(message.isNotBlank()) Surface(color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth().clickable { m.message.value="" }) { Text(message,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall) }
                    when(tab) {
                        0 -> {
                            OutlinedTextField(query,{query=it},placeholder={Text("Search your library")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(16.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("All files","Starred","Trash").forEach { f -> FilterChip(selected=filter==f,onClick={filter=f},label={Text(f)}) } }
                            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                if(parent!="root") IconButton({parent=nodes.find{it.id==parent}?.parent ?: "root"}) { Icon(Icons.Outlined.ArrowBack,"Parent folder") }
                                Text(if(parent=="root") "My Cloud" else nodes.find{it.id==parent}?.name ?: "My Cloud",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                            }
                            val hiddenParents = remember(nodes) { val ids=nodes.filter{it.trash}.map{it.id}.toMutableSet(); var changed=true; while(changed) { val count=ids.size; nodes.filter{it.parent in ids}.forEach{ids.add(it.id)}; changed=count!=ids.size }; ids }
                            val visible = nodes.filter { n ->
                                (if(filter=="Trash") n.trash else n.id !in hiddenParents) &&
                                (filter!="Starred" || n.starred) && (query.isNotBlank() || filter!="All files" || n.parent==parent) && n.name.contains(query,true)
                            }
                            if(visible.isEmpty()) EmptyState(Icons.Outlined.FolderOpen,"Space for everything",if(remotes.isEmpty()) "Connect a cloud account, then import existing files or upload something new." else "Create a folder, upload a file, or import from Accounts.")
                            LazyColumn { items(visible,key={it.id}) { node ->
                                ListItem(headlineContent={Text(node.name,maxLines=1)},supportingContent={Text(if(node.folder) "Virtual folder" else "${formatBytes(node.size)}  ·  ${node.remote ?: "Unavailable"}")},
                                    leadingContent={Icon(if(node.folder) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,null,tint=MaterialTheme.colorScheme.primary)},
                                    trailingContent={IconButton({selected=node;dialog="actions"}) {Icon(Icons.Outlined.MoreVert,"Actions for ${node.name}")}},
                                    modifier=Modifier.clickable { if(node.folder) {parent=node.id;filter="All files"} else m.preview(node) })
                                HorizontalDivider(Modifier.padding(start=68.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                            } }
                        }
                        1 -> Accounts(m,parent) {item -> m.importFile(item,parent)}
                        2 -> {
                            Text("Transfers",Modifier.padding(20.dp),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                            if(jobs.isEmpty()) EmptyState(Icons.Outlined.SwapVert,"All caught up","Uploads and downloads appear here with real progress.")
                            LazyColumn { items(jobs,key={it.id}) { job -> Card(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)) { Column(Modifier.padding(16.dp)) {
                                Text(job.name,fontWeight=FontWeight.SemiBold); Text("${job.kind.lowercase()} · ${job.state.lowercase()}",style=MaterialTheme.typography.bodySmall)
                                if(job.state=="RUNNING") { if(job.total>0) LinearProgressIndicator(progress={ (job.bytes.toFloat()/job.total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth().padding(top=8.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp)) }
                                Text("${formatBytes(job.bytes)} / ${formatBytes(job.total)}",style=MaterialTheme.typography.labelSmall)
                                job.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                                Row { if(job.state in listOf("RUNNING","QUEUED")) TextButton({m.queue(job,"PAUSED")}) {Text("Pause")}
                                    if(job.state in listOf("FAILED","PAUSED")) TextButton({m.queue(job,"QUEUED")}) {Text("Retry / resume")}
                                    if(job.state!="DONE") TextButton({m.queue(job,"CANCELLED")}) {Text("Cancel")} }
                            } } } }
                        }
                        3 -> Settings(m, onExport={export.launch("UnderTheHood-metadata.json")})
                    }
                }
            }
        }
    }
    when(dialog) {
        "add" -> AlertDialog(onDismissRequest={dialog=""},title={Text("Add to My Cloud")},text={Column { TextButton({dialog="folder"}){Text("Create virtual folder")}; TextButton({dialog="";picker.launch(arrayOf("*/*"))}){Text("Upload from device")}; TextButton({tab=1;dialog=""}){Text("Import existing cloud files")} }},confirmButton={TextButton({dialog=""}){Text("Close")}})
        "folder" -> NameDialog("New folder","Create", "",{dialog=""}) {m.folder(it,parent);dialog=""}
        "upload" -> {
            var destination by remember {mutableStateOf(remotes.firstOrNull() ?: "")}; var wifi by remember {mutableStateOf(false)}
            AlertDialog(onDismissRequest={dialog=""},title={Text("Upload $uploadName")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
                if(remotes.isEmpty()) Text("Connect an account first.") else Text("Destination account")
                remotes.forEach { name -> Row(Modifier.fillMaxWidth().clickable {destination=name},verticalAlignment=Alignment.CenterVertically) {RadioButton(destination==name,{destination=name});Text(name)} }
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(wifi,{wifi=it});Text("Wi-Fi only")}
            }},confirmButton={TextButton({ uploadUri?.let{m.upload(it,uploadName,parent,destination,wifi)};dialog="" },enabled=destination.isNotEmpty()){Text("Upload")}},dismissButton={TextButton({dialog=""}){Text("Cancel")}})
        }
        "connect" -> ConnectDialog(m){dialog=""}
        "rename" -> selected?.let {node -> NameDialog("Rename","Save",node.name,{dialog=""}) {m.edit(node,name=it);dialog=""} }
        "move" -> selected?.let {node -> AlertDialog(onDismissRequest={dialog=""},title={Text("Move to folder")},text={LazyColumn {
            item {TextButton({m.edit(node,parent="root");dialog=""}){Text("My Cloud")}}
            items(nodes.filter{it.folder && !it.trash && it.id!=node.id}) { folder -> TextButton({m.edit(node,parent=folder.id);dialog=""}){Text(folder.name)} }
        }},confirmButton={TextButton({dialog=""}){Text("Cancel")}}) }
        "actions" -> selected?.let {node -> AlertDialog(onDismissRequest={dialog=""},title={Text(node.name)},text={Column {
            if(!node.folder && !node.trash) {TextButton({m.preview(node);dialog=""}){Text("Open with…")};TextButton({pendingDownload=node;save.launch(node.name);dialog=""}){Text("Save to device")}}
            TextButton({dialog="rename"}){Text("Rename in library")};TextButton({dialog="move"}){Text("Move in library")}
            TextButton({m.edit(node,starred=!node.starred);dialog=""}){Text(if(node.starred)"Remove from starred" else "Add to starred")}
            TextButton({m.edit(node,trash=!node.trash);dialog=""}){Text(if(node.trash)"Restore" else "Move to trash")}
            Text(if(node.folder) "Virtual folders organize metadata only." else "Stored at ${node.remote}:${node.path}\n${formatBytes(node.size)}\nCloud originals are retained when you trash an entry.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton({dialog=""}){Text("Close")}}) }
    }
}
@Composable fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector,title:String,body:String) {
    Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally) {Spacer(Modifier.height(24.dp));Icon(icon,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(16.dp));Text(title,style=MaterialTheme.typography.titleLarge);Text(body,Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodyMedium)}
}
@Composable fun NameDialog(title:String,action:String,initial:String,dismiss:()->Unit,confirm:(String)->Unit) {
    var value by remember {mutableStateOf(initial)}
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={OutlinedTextField(value,{value=it},label={Text("Name")},singleLine=true)},confirmButton={TextButton({confirm(value)},enabled=value.isNotBlank()){Text(action)}},dismissButton={TextButton(dismiss){Text("Cancel")}})
}
@Composable fun Accounts(m:HoodModel,parent:String,onImport:(JSONObject)->Unit) {
    val remotes by m.remotes.collectAsState(); val remote by m.remote.collectAsState();val path by m.remotePath.collectAsState();val physical by m.physical.collectAsState();val quota by m.quota.collectAsState()
    var confirmRemove by remember {mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize()) {
        item {Text("Connected accounts",Modifier.padding(20.dp),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)}
        if(remotes.isEmpty()) item {EmptyState(Icons.Outlined.CloudQueue,"Bring your clouds together","Connect Google Drive, OneDrive, Dropbox or another Rclone provider. Multiple accounts are welcome.")}
        items(remotes) { name -> Card(Modifier.padding(horizontal=16.dp,vertical=6.dp).fillMaxWidth()) {ListItem(headlineContent={Text(name)},supportingContent={val q=quota[name];Text(if(q?.has("total")==true) "${formatBytes(q.optLong("used"))} of ${formatBytes(q.optLong("total"))}" else "Quota not reported")},leadingContent={Icon(Icons.Outlined.CloudQueue,null)},trailingContent={Row {IconButton({m.browse(name)}){Icon(Icons.Outlined.FolderOpen,"Browse $name")};IconButton({confirmRemove=name}){Icon(Icons.Outlined.LinkOff,"Disconnect $name")}}})} }
        if(remote.isNotEmpty()) {
            item {Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {IconButton({m.browse(remote,path.substringBeforeLast('/',""))}){Icon(Icons.Outlined.ArrowUpward,"Parent remote folder")};Text("$remote:/$path",style=MaterialTheme.typography.titleMedium);IconButton({m.browse(remote,path)}){Icon(Icons.Outlined.Refresh,"Rescan folder")}}}
            items(physical) {item -> ListItem(headlineContent={Text(item.optString("Name"))},supportingContent={Text(if(item.optBoolean("IsDir"))"Folder" else formatBytes(item.optLong("Size")))},leadingContent={Icon(if(item.optBoolean("IsDir")) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,null)},trailingContent={if(!item.optBoolean("IsDir")) IconButton({onImport(item)}){Icon(Icons.Outlined.Add,"Import into current virtual folder")}},modifier=Modifier.clickable {if(item.optBoolean("IsDir"))m.browse(remote,item.getString("Path"))})}
        }
    }
    if(confirmRemove.isNotBlank()) AlertDialog(onDismissRequest={confirmRemove=""},title={Text("Disconnect $confirmRemove?")},text={Text("Cloud data is retained. Library entries from this account will be unavailable until it is reconnected with the same ID.")},confirmButton={TextButton({m.disconnect(confirmRemove);confirmRemove=""}){Text("Disconnect")}},dismissButton={TextButton({confirmRemove=""}){Text("Cancel")}})
}
@Composable fun ConnectDialog(m:HoodModel,dismiss:()->Unit) {
    val providers by m.providers.collectAsState(); var name by remember {mutableStateOf("")}; var provider by remember {mutableStateOf("drive")}
    var client by remember {mutableStateOf("")};var secret by remember {mutableStateOf("")};var extra by remember {mutableStateOf("{}")};var error by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=dismiss,title={Text("Connect a cloud")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Use an official provider login. Empty OAuth fields use Rclone’s built-in credentials where available.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{name=it},label={Text("Unique account ID")},singleLine=true)
        Text("Provider",Modifier.padding(top=12.dp),fontWeight=FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("drive","onedrive","dropbox","s3","webdav").forEach{p -> FilterChip(provider==p,{provider=p},label={Text(p)},modifier=Modifier.padding(end=6.dp))}}
        OutlinedTextField(provider,{provider=it},label={Text("Rclone backend ID")},singleLine=true)
        Text("${providers.size} backends available. ${providers.find{it.optString("Name")==provider}?.optString("Description") ?: ""}",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(client,{client=it},label={Text("OAuth client ID (optional)")},singleLine=true)
        OutlinedTextField(secret,{secret=it},label={Text("OAuth client secret (optional)")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        OutlinedTextField(extra,{extra=it},label={Text("Advanced provider options (JSON)")},minLines=2)
        Text("Some providers require additional options. MEGA uses Rclone’s account configuration rather than browser OAuth. Shared OAuth access depends on each provider’s current policy.",style=MaterialTheme.typography.bodySmall)
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({try {
        val json=JSONObject(extra); val values=json.keys().asSequence().associateWith{json.getString(it)}.toMutableMap()
        if(client.isNotBlank())values["client_id"]=client.trim();if(secret.isNotBlank())values["client_secret"]=secret
        require(provider in providers.map{it.optString("Name")}){"Choose an available backend ID"}
        m.connect(name,provider,values);dismiss()
    }catch(e:Exception){error=e.message ?: "Invalid options"}},enabled=name.isNotBlank()){Text("Connect")}},dismissButton={TextButton(dismiss){Text("Cancel")}})
}
@Composable fun Settings(m:HoodModel,onExport:()->Unit) {
    var showSync by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Your setup",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("LOCAL FIRST",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
        Text("Your library is stored on this device. Cloud files stay in your connected accounts. Optional account sync shares library metadata when a backend is configured.")
        OutlinedButton(onExport,Modifier.fillMaxWidth()){Text("Export metadata backup")}
        OutlinedButton({showSync=true},Modifier.fillMaxWidth()){Text("Account & cross-device sync")}
        OutlinedButton({m.app.engine.close();m.unlocked.value=false},Modifier.fillMaxWidth()){Text("Lock local profile")}
        Text("Security & recovery",style=MaterialTheme.typography.titleLarge)
        Text("The passphrase verifier uses Argon2id. Session secrets use Android Keystore AES-GCM. Rclone configuration is encrypted after account setup. Local metadata uses app-private SQLite and is excluded from Android backup.")
        Text("Keep metadata exports safely: they contain filenames and physical locations. This development release does not yet provide an encrypted recovery bundle or biometric unlock.",style=MaterialTheme.typography.bodySmall)
        Text("UnderTheHood 0.1.0 · Rclone 1.75.2",style=MaterialTheme.typography.labelMedium)
    }
    if(showSync) SyncDialog(m){showSync=false}
}
