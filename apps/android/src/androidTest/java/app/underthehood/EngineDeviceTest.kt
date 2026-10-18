package app.underthehood

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
    @Test
    fun jniUnicodeCopyAndEncryptedConfig() = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        c.hood.vault.let {
            if (!it.exists) it.create("instrumentation test passphrase")
            else it.unlock("instrumentation test passphrase")
        }
        val src = c.cacheDir.resolve("integration-src").apply { mkdirs() }
        val dst = c.cacheDir.resolve("integration-dst").apply { mkdirs() }
        val name = "文件 café.txt"
        val value = "UnderTheHood real JNI transfer"
        src.resolve(name).writeText(value)
        Engine.rpc(
            "operations/copyfile",
            json("srcFs" to src.path, "srcRemote" to name, "dstFs" to dst.path, "dstRemote" to name),
        )
        assertEquals(value, dst.resolve(name).readText())
        val list =
            Engine.rpc("operations/list", json("fs" to dst.path, "remote" to ""))
                .getJSONArray("list")
        assertEquals(name, list.getJSONObject(0).getString("Name"))
        val providers = Engine.rpc("config/providers").getJSONArray("providers")
        assertTrue(providers.length() > 20)
        Engine.rpc(
            "config/create",
            json(
                "name" to "test-local",
                "type" to "alias",
                "parameters" to json("remote" to dst.path),
                "opt" to json("nonInteractive" to true),
            ),
        )
        assertTrue(c.filesDir.resolve("rclone.conf").readText().contains("RCLONE_ENCRYPT_V0"))
    }

    @Test
    fun aesGcmRejectsTamperAndWrongPassphrase() {
        val salt = Crypto.random(16)
        val key = Crypto.derive("test encryption password", salt)
        val ciphertext = Crypto.seal("metadata".toByteArray(), key)
        assertEquals("metadata", String(Crypto.open(ciphertext, key)))
        val tampered =
            Crypto.decode(ciphertext).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try {
            Crypto.open(Crypto.encode(tampered), key)
            fail()
        } catch (_: Exception) {}
        try {
            Crypto.open(ciphertext, Crypto.derive("wrong encryption password", salt))
            fail()
        } catch (_: Exception) {}
    }

    @Test
    fun roomPersistenceAndSnapshotConflictPreservation() = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val store = c.hood.db.store()
        val id = uuid()
        val n = Node(id = id, name = "Original.txt")
        store.put(n)
        assertEquals(n, store.node(id))
        val changed = n.copy(name = "Other device.txt", revision = 2)
        val payload =
            json(
                "version" to 1,
                "nodes" to JSONArray().put(changed.toJson()),
                "remotes" to JSONArray(),
            )
        val result = SyncClient(c).merge(payload, uuid())
        assertTrue(result.contains("1 conflicts"))
        assertEquals("Original.txt", store.node(id)?.name)
        assertTrue(store.nodes().any { it.name == "Other device.txt (conflict)" })
    }
}
