package dev.underthehood

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Optional metadata-only sync. Provider credentials never leave the device. */
class SyncClient(private val app: HoodApplication) {
    private val client = OkHttpClient.Builder().callTimeout(45,TimeUnit.SECONDS).build()
    private val vault get()=app.vault
    private fun request(path: String, body: String? = null, authenticated: Boolean = true): String {
        val base=vault.get("sync-url")?.trimEnd('/') ?: error("Configure a sync server first")
        require(base.startsWith("https://") && !base.substringAfter("https://").contains('/')) {"Use an HTTPS project origin"}
        val builder=Request.Builder().url(base+path).header("apikey",vault.get("sync-key") ?: "")
        if(authenticated) builder.header("Authorization","Bearer ${vault.get("sync-access") ?: error("Sign in first")}")
        if(body!=null) builder.post(body.toRequestBody("application/json".toMediaType())).header("Prefer","resolution=ignore-duplicates,return=minimal")
        return client.newCall(builder.build()).execute().use { r ->
            val text=r.body?.string() ?: ""
            check(r.isSuccessful) {"Sync server returned ${r.code}. Check account, confirmation email, configuration and permissions."};text
        }
    }
    suspend fun auth(url:String,key:String,email:String,password:String,signup:Boolean): Boolean = withContext(Dispatchers.IO) {
        vault.put("sync-url",url.trim());vault.put("sync-key",key.trim())
        val data=JSONObject(request(if(signup)"/auth/v1/signup" else "/auth/v1/token?grant_type=password",JSONObject().put("email",email).put("password",password).toString(),false))
        if(!data.has("access_token")) return@withContext false
        saveSession(data);true
    }
    private fun saveSession(data:JSONObject) {
        val user=data.getJSONObject("user").getString("id")
        val previous=vault.get("sync-user")
        require(previous==null || previous==user) {"This local profile is already linked to a different sync account"}
        vault.put("sync-access",data.getString("access_token"));vault.put("sync-refresh",data.getString("refresh_token"));vault.put("sync-user",user)
    }
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        val refresh=vault.get("sync-refresh") ?: error("Sign in first")
        saveSession(JSONObject(request("/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",refresh).toString(),false)))
        val user=vault.get("sync-user")!!;val device=vault.secret("sync-device")
        val all=app.db.dao().allNodes(); val batch=JSONArray()
        all.forEach{n ->
            val payload=Snapshots.json(n)
            val event=UUID.nameUUIDFromBytes("${n.id}:$device:$n".toByteArray()).toString()
            batch.put(JSONObject().put("owner_id",user).put("event_id",event).put("device_id",device).put("payload",payload))
            if(batch.length()>=100) {request("/rest/v1/library_events",batch.toString()); while(batch.length()>0)batch.remove(0)}
        }
        if(batch.length()>0)request("/rest/v1/library_events",batch.toString())
        var cursor=vault.get("sync-cursor")?.toLongOrNull() ?: 0L; var received=0
        do {
            val events=JSONArray(request("/rest/v1/library_events?select=seq,device_id,payload&seq=gt.$cursor&order=seq.asc&limit=500"))
            for(i in 0 until events.length()) {
                val event=events.getJSONObject(i);val n=event.getJSONObject("payload")
                if(event.getString("device_id")!=device) {
                    val incoming=Snapshots.node(n)
                    HoodModel.validateName(incoming.name)
                    app.db.withTransaction {
                        val existing=app.db.dao().node(incoming.id)
                        if(existing==null) {
                            val collision=app.db.dao().allNodes().any{it.parent==incoming.parent && it.name==incoming.name}
                            app.db.dao().insert(if(collision)incoming.copy(name=incoming.name.take(180)+" (sync ${incoming.id.take(8)})") else incoming)
                        } else if(existing!=incoming) {
                            // Preserve local edits visibly. Never silently overwrite a different revision.
                            val conflictId=UUID.nameUUIDFromBytes(incoming.toString().toByteArray()).toString()
                            if(app.db.dao().node(conflictId)==null)app.db.dao().insert(incoming.copy(id=conflictId,name=incoming.name.take(180)+" (conflict ${conflictId.take(8)})"))
                        }
                    }; received++
                }
                cursor=event.getLong("seq");vault.put("sync-cursor",cursor.toString())
            }
        }while(events.length()==500)
        received
    }
}
@Composable fun SyncDialog(m:HoodModel,dismiss:()->Unit) {
    var url by remember{mutableStateOf(m.app.vault.get("sync-url") ?: "")};var key by remember{mutableStateOf(m.app.vault.get("sync-key") ?: "")}
    var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};val busy by m.busy.collectAsState()
    AlertDialog(onDismissRequest=dismiss,title={Text("Account & sync")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Optional Supabase account. Your local profile works without it. Sync shares filenames, virtual folders and physical locations; provider tokens stay on this device.")
        OutlinedTextField(url,{url=it},label={Text("Supabase project URL")},singleLine=true)
        OutlinedTextField(key,{key=it},label={Text("Publishable key")},singleLine=true)
        OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true)
        OutlinedTextField(password,{password=it},label={Text("Account password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        Row {TextButton({m.task {val active=SyncClient(m.app).auth(url,key,email,password,true);m.message.value=if(active)"Account created" else "Check your email to confirm your account"}},enabled=!busy){Text("Create account")}
            TextButton({m.task{SyncClient(m.app).auth(url,key,email,password,false);m.message.value="Signed in"}},enabled=!busy){Text("Sign in")}}
        Text("Devices must connect the same remote IDs separately. Different revisions are preserved as visible conflicts. Metadata is protected by server access controls, but is not end-to-end encrypted.",style=MaterialTheme.typography.bodySmall)
        Button({m.task{val n=SyncClient(m.app).sync();m.message.value="Metadata sync complete: $n events received"}},enabled=!busy){Text("Sync metadata now")}
    }},confirmButton={TextButton(dismiss){Text("Close")}})
}
