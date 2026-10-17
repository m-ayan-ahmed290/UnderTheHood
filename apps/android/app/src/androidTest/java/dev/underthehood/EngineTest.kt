package dev.underthehood

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineTest {
    @Test fun bundledEngineEncryptsConfigurationAndCopiesRealFiles() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HoodApplication
        assertTrue(app.engine.providers().isNotEmpty())
        val name="fixture_${System.currentTimeMillis()}"
        app.engine.configure(name,"local",emptyMap()){}
        assertTrue(name in app.engine.remotes())
        val config=File(app.noBackupFilesDir,"rclone.conf").readText()
        assertTrue(config.contains("RCLONE_ENCRYPT_V0"))
        assertFalse(config.contains("[$name]"))
        val source=File(app.cacheDir,"engine-source.txt").apply{writeText("UnderTheHood native Android Rclone round trip ✓")}
        val destination=File(app.cacheDir,"engine-destination.txt")
        val job=app.engine.copy(source.parentFile!!.absolutePath,source.name,destination.parentFile!!.absolutePath,destination.name)
        repeat(100) {
            val status=app.engine.call("job/status",JSONObject().put("jobid",job))
            if(status.optBoolean("finished")) {assertTrue(status.optString("error"),status.optBoolean("success"));assertEquals(source.readText(),destination.readText());app.engine.close();return@runBlocking}
            delay(100)
        }
        fail("Native copy timed out")
    }
}
