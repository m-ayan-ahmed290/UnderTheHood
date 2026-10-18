package app.underthehood

import android.content.Context
import androidx.room.withTransaction
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SyncClient(private val context: Context) {
    private val vault = context.hood.vault
    private val store = context.hood.db.store()

    companion object {
        const val URL_BASE = "https://vqkbjzumonqudkeapgxv.supabase.co"
        const val PUBLIC_KEY = "sb_publishable_rozyNWY5EK0tvZt1JZsFEw_2vDgSyRX"
    }

    private fun http(path: String, body: String? = null, token: String? = null): String {
        val c = URL(URL_BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.requestMethod = if (body == null) "GET" else "POST"
        c.setRequestProperty("apikey", PUBLIC_KEY)
        c.setRequestProperty("Content-Type", "application/json")
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val result =
                (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use {
                    it.readText()
                } ?: ""
            if (code !in 200..299) {
                val message =
                    runCatching {
                            JSONObject(result).let {
                                it.optString(
                                    "msg",
                                    it.optString(
                                        "message",
                                        it.optString("error_description", "Request failed"),
                                    ),
                                )
                            }
                        }
                        .getOrDefault("Request failed")
                error("$message (HTTP $code)")
            }
            result
        } finally {
            c.disconnect()
        }
    }

    val email
        get() =
            vault.load("session")?.let { JSONObject(it).optJSONObject("user")?.optString("email") }

    suspend fun login(email: String, password: String, signup: Boolean): String =
        withContext(Dispatchers.IO) {
            val result =
                JSONObject(
                    http(
                        if (signup) "/auth/v1/signup" else "/auth/v1/token?grant_type=password",
                        json("email" to email.trim(), "password" to password).toString(),
                    )
                )
            if (result.has("access_token")) {
                vault.save("session", result.toString())
                "Signed in"
            } else
                "Check your email to confirm your account, then sign in. Email delivery depends on the project's mail configuration."
        }

    fun logout() {
        vault.remove("session")
    }

    private fun session(): JSONObject {
        val old = JSONObject(vault.load("session") ?: error("Sign in to sync first"))
        val current =
            JSONObject(
                http(
                    "/auth/v1/token?grant_type=refresh_token",
                    json("refresh_token" to old.getString("refresh_token")).toString(),
                )
            )
        vault.save("session", current.toString())
        return current
    }

    suspend fun push(passphrase: String): String =
        withContext(Dispatchers.IO) {
            require(passphrase.length >= 12) {
                "Use a sync passphrase of at least 12 characters. Keep it for other devices."
            }
            val s = session()
            val user = s.getJSONObject("user").getString("id")
            val key =
                Crypto.derive(
                    passphrase,
                    MessageDigest.getInstance("SHA-256").digest(user.toByteArray()).copyOf(16),
                )
            val payload =
                json(
                    "version" to 1,
                    "nodes" to JSONArray(store.nodes().map { it.toJson() }),
                    "remotes" to
                        JSONArray(
                            store.remotes().map {
                                json("id" to it.id, "name" to it.name, "provider" to it.provider)
                            }
                        ),
                )
            val encrypted = Crypto.seal(payload.toString().toByteArray(), key)
            require(encrypted.length <= 20971520) { "Snapshot exceeds the sync size limit" }
            http(
                "/rest/v1/uth_snapshots",
                json(
                        "id" to uuid(),
                        "user_id" to user,
                        "device_id" to vault.deviceId,
                        "payload" to encrypted,
                    )
                    .toString(),
                s.getString("access_token"),
            )
            "Encrypted library snapshot uploaded. Provider tokens stay on this device."
        }

    suspend fun snapshots(): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val s = session()
            JSONArray(
                    http(
                        "/rest/v1/uth_snapshots?select=id,device_id,created_at&order=created_at.desc&limit=50",
                        token = s.getString("access_token"),
                    )
                )
                .objects()
        }

    suspend fun pull(id: String, passphrase: String): String =
        withContext(Dispatchers.IO) {
            require(java.util.UUID.fromString(id).toString() == id)
            val s = session()
            val user = s.getJSONObject("user").getString("id")
            val rows =
                JSONArray(
                    http(
                        "/rest/v1/uth_snapshots?id=eq.$id&select=payload",
                        token = s.getString("access_token"),
                    )
                )
            require(rows.length() == 1) { "Snapshot not found" }
            val key =
                Crypto.derive(
                    passphrase,
                    MessageDigest.getInstance("SHA-256").digest(user.toByteArray()).copyOf(16),
                )
            val payload =
                JSONObject(String(Crypto.open(rows.getJSONObject(0).getString("payload"), key)))
            merge(payload, id)
        }

    suspend fun merge(payload: JSONObject, source: String): String {
        require(payload.getInt("version") == 1) { "Unsupported snapshot format" }
        val incoming = payload.getJSONArray("nodes").objects().map { Node.fromJson(it) }
        require(incoming.size <= 100000) { "Snapshot is too large" }
        val ids = incoming.map { it.id }.toSet()
        require(ids.size == incoming.size)
        for (n in incoming) {
            LibraryRules.name(n.name)
            require(n.parent.isEmpty() || n.parent in ids) { "Snapshot has missing folders" }
            require(n.size >= 0)
            require(LibraryRules.canMove(n.id, n.parent, incoming)) {
                "Snapshot has a folder cycle"
            }
        }
        var added = 0
        var conflicts = 0
        context.hood.db.withTransaction {
            for (n in incoming) {
                val local = store.node(n.id)
                if (local == null) {
                    store.put(n)
                    added++
                } else if (local.toJson().toString() != n.toJson().toString()) {
                    val conflictId = UUIDName(source + n.id)
                    if (store.node(conflictId) == null) {
                        store.put(
                            n.copy(
                                id = conflictId,
                                name = n.name.take(180) + " (conflict)",
                                trashed = false,
                            )
                        )
                        conflicts++
                    }
                }
            }
            val current = store.remotes().map { it.id }.toSet()
            payload.optJSONArray("remotes")?.objects()?.forEach {
                if (it.getString("id") !in current)
                    store.putRemote(
                        Remote(it.getString("id"), it.getString("name"), it.getString("provider"))
                    )
            }
        }
        return "Imported $added items; preserved $conflicts conflicts as separate items. Reconnect accounts on this device before transferring files."
    }

    suspend fun export(): String =
        json(
                "version" to 1,
                "nodes" to JSONArray(store.nodes().map { it.toJson() }),
                "remotes" to
                    JSONArray(
                        store.remotes().map {
                            json("id" to it.id, "name" to it.name, "provider" to it.provider)
                        }
                    ),
            )
            .toString()
}
