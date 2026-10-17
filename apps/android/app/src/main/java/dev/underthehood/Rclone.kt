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
    fun process(arguments: List<String>): Process {
        check(binary.isFile) { "Bundled Rclone is missing for this device ABI" }
        val builder = ProcessBuilder(listOf(binary.absolutePath) + arguments + listOf("--config", config.absolutePath))
        builder.environment()["RCLONE_CONFIG_PASS"] = password
        builder.environment()["HOME"] = context.noBackupFilesDir.absolutePath
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
        return builder.redirectErrorStream(true).start()
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
    suspend fun configure(name: String, provider: String, options: Map<String, String>, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Use letters, digits, underscores or dashes for account ID" }
        require(provider != "mega") { "MEGA requires a provider password and is not supported by this password-free wizard" }
        require(options.keys.all { it.matches(Regex("[A-Za-z0-9_]+")) }) { "Invalid provider option" }
        check(name !in remotes()) { "This account ID already exists" }
        // Stop RC before editing its configuration; reload after the wizard completes.
        synchronized(lock) { server?.destroy(); server = null }
        encryptConfiguration()
        val args = mutableListOf("config", "create", name, provider, "config_is_local", "true", "config_auth_no_browser", "true")
        options.filterValues { it.isNotBlank() }.forEach { (key, value) -> args.add(key); args.add(value) }
        val child = process(args)
        val reader = Thread {
            runCatching {
                child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    Regex("""https?://(?:127\.0\.0\.1|localhost):[0-9]+/[^\s]+""").find(line)?.value?.let { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trimEnd('.', '"', '\''))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onStatus("Complete authorization in your browser, then return here.")
                    }
                } }
            }
        }.apply { isDaemon = true; start() }
        try {
            withTimeout(5 * 60 * 1000L) { while(child.isAlive) delay(200) }
            reader.join(2000)
            check(child.exitValue() == 0) { "Provider setup failed. Shared OAuth credentials may be unavailable; try custom credentials or another provider." }
            onStatus("Account connected")
        } finally { if (child.isAlive) child.destroy(); child.inputStream.close() }
    }
    suspend fun copy(sourceFs: String, source: String, destinationFs: String, destination: String): Int = call("operations/copyfile",
        JSONObject().put("srcFs", sourceFs).put("srcRemote", source).put("dstFs", destinationFs).put("dstRemote", destination).put("_async", true)).getInt("jobid")
    fun close() = synchronized(lock) { server?.destroy(); server = null }
}
