package app.underthehood

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object Engine {
    init {
        System.loadLibrary("uth")
    }

    private external fun nativeCall(method: ByteArray, input: ByteArray): ByteArray

    fun raw(method: String, input: JSONObject = JSONObject()): JSONObject {
        val result =
            JSONObject(
                nativeCall(
                        method.toByteArray(Charsets.UTF_8),
                        input.toString().toByteArray(Charsets.UTF_8),
                    )
                    .toString(Charsets.UTF_8)
            )
        if (result.has("error")) throw IllegalStateException(result.optString("error").take(1200))
        return result
    }

    suspend fun rpc(method: String, input: JSONObject = JSONObject()) =
        withContext(Dispatchers.IO) { raw(method, input) }

    fun start(context: Context, password: String) {
        raw(
            "uth/init",
            json("path" to context.filesDir.resolve("rclone.conf").path, "password" to password),
        )
    }
}

fun json(vararg fields: Pair<String, Any?>) =
    JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

fun org.json.JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun readLimited(input: java.io.InputStream, limit: Int): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Archive is too large" }
        output.write(buffer, 0, count)
    }
    return output.toString("UTF-8")
}
