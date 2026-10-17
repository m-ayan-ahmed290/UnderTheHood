package dev.underthehood

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class Rclone(private val context: Context, private val vault: Vault) {
    private val binary get() = File(context.applicationInfo.nativeLibraryDir, "librclone.so")
    private val config get() = File(context.noBackupFilesDir, "rclone.conf")
    private val password by lazy { vault.secret("rclone-config") }
    private val rcPassword by lazy { vault.secret("rclone-rc") }
    private var server: Process? = null
    private val lock = Any()
    private var port = 0
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    fun process(arguments: List<String>, combineOutput:Boolean = true): Process {
        check(binary.isFile) { "Bundled Rclone is missing for this device ABI" }
        val builder = ProcessBuilder(listOf(binary.absolutePath) + arguments + listOf("--config", config.absolutePath))
        builder.environment()["RCLONE_CONFIG_PASS"] = password
        builder.environment()["HOME"] = context.noBackupFilesDir.absolutePath
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
        return builder.redirectErrorStream(combineOutput).start()
    }
    private fun ensureServer() = synchronized(lock) {
        if (server?.isAlive == true) return@synchronized
        port = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        // Credentials are passed through environment, never command arguments or logs.
        val builder = ProcessBuilder(binary.absolutePath, "rcd", "--rc-addr", "127.0.0.1:$port", "--config", config.absolutePath,
            "--rc-user", "hood", "--rc-allow-origin", "http://127.0.0.1:$port", "--log-level", "ERROR")
        builder.environment().putAll(mapOf("RCLONE_CONFIG_PASS" to password, "RCLONE_RC_PASS" to rcPassword,
            "HOME" to context.noBackupFilesDir.absolutePath, "TMPDIR" to context.cacheDir.absolutePath))
        server = builder.redirectOutput(ProcessBuilder.Redirect.to(File(context.cacheDir, "rclone-private.log"))).redirectErrorStream(true).start()
        repeat(100) {
            try { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 100) }; return@synchronized }
            catch (_: Exception) { Thread.sleep(50) }
        }
        error("Rclone could not start. Reopen the app or check device compatibility.")
    }
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        ensureServer()
        val request = Request.Builder().url("http://127.0.0.1:$port/$method")
            .header("Authorization", Credentials.basic("hood", rcPassword))
            .post(params.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val result = JSONObject(response.body?.string() ?: "{}")
            if (!response.isSuccessful) error(result.optString("error", "Rclone request failed (${response.code})"))
            result
        }
    }
    suspend fun remotes(): List<String> = call("config/listremotes").optJSONArray("remotes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
    suspend fun providers(): List<JSONObject> = call("config/providers").getJSONArray("providers").let { a -> List(a.length()) { a.getJSONObject(it) } }
    suspend fun list(remote: String, path: String = ""): List<JSONObject> = call("operations/list", JSONObject().put("fs", "$remote:").put("remote", path)
        .put("opt", JSONObject().put("recurse", false).put("showHash", true))).getJSONArray("list").let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun encryptConfiguration() {
        if (!config.exists()) config.writeText("")
        val encrypt = process(listOf("config", "encryption", "set"))
        encrypt.outputStream.bufferedWriter().use { it.write("$password\n$password\n") }
        encrypt.inputStream.copyTo(object : java.io.OutputStream() { override fun write(b: Int) {} override fun write(b: ByteArray, off: Int, len: Int) {} })
        check(encrypt.waitFor() == 0) { "Could not encrypt provider configuration" }
    }
    private suspend fun setupCommand(arguments:List<String>,onAuthUrl:(String)->Unit):JSONObject = withContext(Dispatchers.IO) {
        val child=process(arguments,false)
        var output=""
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val reader=Thread {
            try {
                child.inputStream.use { input ->
                    val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val count=input.read(buffer);if(count<0)break;check(bytes.size()+count<=2*1024*1024){"Provider response is too large"};bytes.write(buffer,0,count)}
                    output=bytes.toString("UTF-8")
                }
            }catch(e:Exception){failure.set(e)}
        }.apply{isDaemon=true;start()}
        val errors=Thread {
            runCatching { child.errorStream.bufferedReader().useLines {lines->lines.forEach {line->AuthorizationLinks.find(line)?.let(onAuthUrl)}} }.onFailure{failure.set(it)}
        }.apply{isDaemon=true;start()}
        try {
            withTimeout(5*60*1000L){while(child.isAlive)delay(100)}
            reader.join(2000);errors.join(2000)
            check(child.exitValue()==0){"Provider authorization failed. Check provider options, custom OAuth credentials, or whether shared credentials are still accepted."}
            check(failure.get()==null){"Could not read provider authorization response"}
            JSONObject(output)
        }finally{
            if(child.isAlive){child.destroy();if(!child.waitFor(2,TimeUnit.SECONDS))child.destroyForcibly()}
            child.inputStream.close();child.errorStream.close()
        }
    }
    suspend fun configure(rawName:String,provider:String,options:Map<String,String>,
        onQuestion:suspend(JSONObject)->String={error("Provider needs another setup choice")},
        onAuthUrl:(String)->Unit={},onStatus:(String)->Unit) = withContext(Dispatchers.IO) {
        val name=AccountNames.normalize(rawName)
        require(provider!="mega"){"MEGA requires a password-based setup unavailable in this wizard"}
        require(options.keys.all{it.matches(Regex("[A-Za-z0-9_]+"))}){"Invalid provider option"}
        var resume=false
        if(name in remotes()) {
            val entry=call("config/get",JSONObject().put("name",name))
            resume=entry.optString("type")==provider && entry.optString("token").isBlank() && provider in setOf("drive","onedrive","dropbox","box","pcloud","yandex")
            check(resume){"An account with this name already exists. Choose another name."}
        }
        synchronized(lock){server?.destroy();server=null}
        encryptConfiguration()
        val defaults=options.filterValues{it.isNotBlank()}.toMutableMap()
        defaults["config_is_local"]="true";defaults["config_auth_no_browser"]="true"
        if(defaults["client_id"].isNullOrBlank())defaults["config_shared_client_id"]="true"
        val parameters=defaults.flatMap{(key,value)->listOf(key,value)}
        var args=mutableListOf("config",if(resume)"update" else "create",name)
        if(!resume)args.add(provider)
        args.addAll(parameters);args.add("--non-interactive")
        repeat(40) {
            onStatus("Starting provider authorization…")
            val response=setupCommand(args,onAuthUrl)
            val state=response.getString("State")
            if(state.isEmpty()) {
                close()
                onStatus("Checking account authorization…")
                call("operations/fsinfo",JSONObject().put("fs","$name:"))
                onStatus("Account connected and checked")
                return@withContext
            }
            val question=response.getJSONObject("Option")
            require(!question.optBoolean("IsPassword")){"This provider requires a password-based configuration that this wizard does not support"}
            question.put("Error",response.optString("Error"))
            val answer=onQuestion(question)
            args=mutableListOf("config","update",name)
            args.addAll(parameters);args.addAll(listOf("--non-interactive","--continue","--state",state,"--result",answer))
        }
        error("Provider setup exceeded its step limit")
    }
    suspend fun copy(sourceFs: String, source: String, destinationFs: String, destination: String): Int = call("operations/copyfile",
        JSONObject().put("srcFs", sourceFs).put("srcRemote", source).put("dstFs", destinationFs).put("dstRemote", destination).put("_async", true)).getInt("jobid")
    fun close() = synchronized(lock) { server?.destroy(); server = null }
}
