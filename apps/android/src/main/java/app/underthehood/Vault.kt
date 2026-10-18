package app.underthehood

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object Crypto {
    fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun seal(data: ByteArray, key: SecretKey): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return encode(c.iv + c.doFinal(data))
    }

    fun open(data: String, key: SecretKey): ByteArray {
        val b = decode(data)
        require(b.size >= 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return c.doFinal(b.copyOfRange(12, b.size))
    }

    fun derive(password: String, salt: ByteArray): SecretKey =
        SecretKeySpec(
            decode(
                Engine.raw("uth/derive", json("password" to password, "salt" to encode(salt)))
                    .getString("key")
            ),
            "AES",
        )
}

class Vault(private val context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (ks.getKey("uth.device", null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                    "uth.device",
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build()
                        )
                    }
                    .generateKey()
        }

    val exists
        get() = prefs.contains("profile")

    val deviceId: String
        get() =
            prefs.getString("device", null)
                ?: uuid().also { prefs.edit().putString("device", it).commit() }

    fun create(password: String) {
        require(!exists)
        require(password.length >= 12) { "Use at least 12 characters" }
        val salt = Crypto.random(16)
        val secret = Crypto.encode(Crypto.random(32))
        val wrapped = Crypto.seal(secret.toByteArray(), Crypto.derive(password, salt))
        prefs.edit().putString("profile", wrapped).putString("salt", Crypto.encode(salt)).commit()
        save("engine", secret)
        Engine.start(context, secret)
    }

    fun unlock(password: String) {
        val secret =
            String(
                Crypto.open(
                    prefs.getString("profile", null) ?: error("Create a profile first"),
                    Crypto.derive(password, Crypto.decode(prefs.getString("salt", null)!!)),
                )
            )
        save("engine", secret)
        Engine.start(context, secret)
    }

    fun background() {
        Engine.start(context, load("engine") ?: error("Unlock your profile first"))
    }

    fun save(name: String, value: String) {
        prefs.edit().putString("secret.$name", Crypto.seal(value.toByteArray(), key)).commit()
    }

    fun load(name: String): String? =
        prefs.getString("secret.$name", null)?.let { String(Crypto.open(it, key)) }

    fun remove(name: String) {
        prefs.edit().remove("secret.$name").commit()
    }

    fun recovery(): String =
        json(
                "version" to 1,
                "salt" to prefs.getString("salt", null),
                "profile" to prefs.getString("profile", null),
            )
            .toString()

    fun restoreRecovery(data: String, password: String) {
        require(!exists) { "Recovery can only be imported on a fresh installation" }
        val o = JSONObject(data)
        require(o.getInt("version") == 1)
        val salt = o.getString("salt")
        val wrapped = o.getString("profile")
        val secret = String(Crypto.open(wrapped, Crypto.derive(password, Crypto.decode(salt))))
        prefs.edit().putString("salt", salt).putString("profile", wrapped).commit()
        save("engine", secret)
        Engine.start(context, secret)
    }
}
