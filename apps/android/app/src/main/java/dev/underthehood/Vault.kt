package dev.underthehood

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hood-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("hood-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.toByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }
    fun get(name: String): String? = prefs.getString(name, null)?.let {
        val data = Base64.decode(it, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0,12))) }
        String(cipher.doFinal(data.copyOfRange(12, data.size)))
    }
    fun secret(name: String): String = get(name) ?: ByteArray(32).also(random::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP).also { value -> put(name, value) }
    }
    val exists get() = prefs.contains("verifier")
    fun derive(password: String, salt: ByteArray): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withSalt(salt)
            .withMemoryAsKB(65536).withIterations(3).withParallelism(1).build())
        return ByteArray(32).also { generator.generateBytes(password.toCharArray(), it) }
    }
    fun create(password: String) {
        require(!exists) { "A profile already exists" }
        require(password.length >= 12) { "Use a passphrase of at least 12 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        put("verifier", Base64.encodeToString(derive(password, salt), Base64.NO_WRAP))
    }
    fun unlock(password: String): Boolean {
        val salt = Base64.decode(get("salt") ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(derive(password, salt), Base64.decode(get("verifier"), Base64.NO_WRAP))
    }
}
