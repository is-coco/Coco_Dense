package com.coco.dense

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A device-only convenience unlock. The vault remains encrypted with its master password. */
class PatternStore(context: Context) {
    private val prefs = context.getSharedPreferences("pattern-unlock", Context.MODE_PRIVATE)
    private val secrets = DeviceSecrets(context)
    val enabled: Boolean get() = secrets.get("pattern-credential") != null

    fun set(pattern: List<Int>, master: String, dataKey: String) {
        require(valid(pattern)) { "图案至少连接 4 个点，且不能重复" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(pattern, salt))
        val credential = JSONObject().put("master", master).put("dataKey", dataKey).toString().toByteArray(Charsets.UTF_8)
        val saved = JSONObject()
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("blob", Base64.encodeToString(cipher.iv + cipher.doFinal(credential), Base64.NO_WRAP))
        secrets.put("pattern-credential", saved.toString())
        prefs.edit().clear().apply()
    }

    fun unlock(pattern: List<Int>): Pair<String, String> {
        require(valid(pattern)) { "请至少连接 4 个点" }
        val now = System.currentTimeMillis()
        val waitUntil = prefs.getLong("waitUntil", 0)
        require(now >= waitUntil) { "尝试次数过多，请稍后再试或使用主密码" }
        try {
            val saved = JSONObject(secrets.get("pattern-credential") ?: error("尚未设置图案解锁"))
            val salt = Base64.decode(saved.getString("salt"), Base64.DEFAULT)
            val blob = Base64.decode(saved.getString("blob"), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(pattern, salt), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            val credential = JSONObject(String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8))
            prefs.edit().clear().apply()
            return credential.getString("master") to credential.optString("dataKey")
        } catch (_: Exception) {
            val attempts = prefs.getInt("attempts", 0) + 1
            prefs.edit().putInt("attempts", if (attempts >= 5) 0 else attempts)
                .putLong("waitUntil", if (attempts >= 5) now + 30_000 else 0).apply()
            throw IllegalArgumentException(if (attempts >= 5) "尝试次数过多，请 30 秒后重试或使用主密码" else "图案不正确，请重试")
        }
    }

    fun clear() {
        secrets.put("pattern-credential", null)
        prefs.edit().clear().apply()
    }

    companion object {
        fun valid(pattern: List<Int>) = pattern.size >= 4 && pattern.size <= 9 &&
            pattern.all { it in 0..8 } && pattern.distinct().size == pattern.size

        private fun derive(pattern: List<Int>, salt: ByteArray): SecretKeySpec {
            val chars = pattern.joinToString("").toCharArray()
            val spec = PBEKeySpec(chars, salt, 150_000, 256)
            return try {
                SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
            } finally {
                chars.fill('\u0000')
                spec.clearPassword()
            }
        }
    }
}
