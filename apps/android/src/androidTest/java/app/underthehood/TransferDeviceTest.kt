package app.underthehood

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferDeviceTest {
    @Test
    fun chunkRoundTripProtectionFailoverAndCorruptionDetection() = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = c.hood.vault
        if (vault.exists) vault.unlock("instrumentation test passphrase")
        else vault.create("instrumentation test passphrase")
        val store = c.hood.db.store()
        val folder = c.cacheDir.resolve("transfer-integration-${uuid()}").apply { mkdirs() }
        val source = folder.resolve("source.bin")
        source.outputStream().use { out ->
            val block = ByteArray(65536) { (it % 251).toByte() }
            repeat(273) { out.write(block) }
        }
        val remotes =
            (1..2).map { number ->
                val path = folder.resolve("remote-$number").apply { mkdirs() }
                val r =
                    Remote(
                        uuid(),
                        "Test account $number",
                        "alias",
                        healthy = true,
                        free = 1024L * 1024 * 1024,
                    )
                Engine.rpc(
                    "config/create",
                    json(
                        "name" to r.id,
                        "type" to "alias",
                        "parameters" to json("remote" to path.path),
                        "opt" to json("nonInteractive" to true),
                    ),
                )
                store.putRemote(r)
                r
            }
        val upload =
            Transfer(
                operation = "upload",
                uri = Uri.fromFile(source).toString(),
                name = "chunked.bin",
                remote = remotes.first().id,
                total = source.length(),
                chunked = true,
                protected = true,
                distributed = false,
            )
        store.putJob(upload)
        val worker =
            TestListenableWorkerBuilder<TransferWorker>(c)
                .setInputData(workDataOf("job" to upload.id))
                .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val node = store.node(upload.id)!!
        val manifest = JSONObject(node.manifest)
        assertEquals(2, manifest.getJSONArray("chunks").length())
        manifest.getJSONArray("chunks").objects().forEach { chunk ->
            assertEquals(2, chunk.getJSONArray("copies").length())
            assertEquals(
                2,
                chunk.getJSONArray("copies").objects().map { it.getString("path") }.distinct().size,
            )
            assertEquals(
                remotes.first().id,
                chunk.getJSONArray("copies").getJSONObject(0).getString("remote"),
            )
        }
        folder.resolve("remote-1").deleteRecursively()
        val destination = folder.resolve("restored.bin")
        val download =
            Transfer(
                operation = "download",
                node = node.id,
                name = node.name,
                uri = Uri.fromFile(destination).toString(),
                total = node.size,
            )
        store.putJob(download)
        assertEquals(
            ListenableWorker.Result.success(),
            TestListenableWorkerBuilder<TransferWorker>(c)
                .setInputData(workDataOf("job" to download.id))
                .build()
                .doWork(),
        )
        assertEquals(source.sha256(), destination.sha256())
        val copy =
            manifest.getJSONArray("chunks").getJSONObject(0).getJSONArray("copies").getJSONObject(1)
        folder.resolve("remote-2/${copy.getString("path")}").writeText("corrupt bytes")
        val corrupt =
            download.copy(
                id = uuid(),
                uri = Uri.fromFile(folder.resolve("corrupt-download.bin")).toString(),
            )
        store.putJob(corrupt)
        assertEquals(
            ListenableWorker.Result.failure(),
            TestListenableWorkerBuilder<TransferWorker>(c)
                .setInputData(workDataOf("job" to corrupt.id))
                .setRunAttemptCount(3)
                .build()
                .doWork(),
        )
        assertEquals("FAILED", store.job(corrupt.id)?.state)
    }
}
