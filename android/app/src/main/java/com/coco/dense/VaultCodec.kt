package com.coco.dense

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The on-disk JSON matches shared/main.js in the desktop application. */
object VaultCodec {
    private const val ROUNDS = 250_000
    private const val V2 = "coco-dense-envelope-v2"
    private const val V3 = "coco-dense-envelope-v3"
    private val random = SecureRandom()
    private fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private fun unb64(value: String) = Base64.getDecoder().decode(value)

    fun newPayload(): JSONObject = JSONObject().put("version", 1)
        .put("createdAt", now()).put("updatedAt", now())
        .put("entries", JSONArray()).put("settings", JSONObject())

    fun now(): String = java.time.Instant.now().toString()

    private fun derive(secret: String, salt: ByteArray, rounds: Int): ByteArray {
        require(rounds in 1..5_000_000) { "无效的密钥派生轮数" }
        val spec = PBEKeySpec(secret.toCharArray(), salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun seal(key: ByteArray, content: ByteArray): JSONObject {
        val iv = bytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(content)
        val split = encrypted.size - 16
        return JSONObject().put("version", 1).put("alg", "aes-256-gcm")
            .put("iv", b64(iv)).put("tag", b64(encrypted.copyOfRange(split, encrypted.size)))
            .put("data", b64(encrypted.copyOfRange(0, split)))
    }

    private fun open(key: ByteArray, wrapped: JSONObject): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(wrapped.getString("iv"))))
        return cipher.doFinal(unb64(wrapped.getString("data")) + unb64(wrapped.getString("tag")))
    }

    fun wrapWithPassword(secret: String, content: ByteArray): JSONObject {
        val salt = bytes(16)
        val encrypted = seal(derive(secret, salt, ROUNDS), content)
        return JSONObject().put("version", 1).put("kdf", "pbkdf2").put("rounds", ROUNDS)
            .put("salt", b64(salt)).put("iv", encrypted.getString("iv"))
            .put("tag", encrypted.getString("tag")).put("data", encrypted.getString("data"))
    }

    fun unwrapWithPassword(secret: String, wrapped: JSONObject): ByteArray =
        open(derive(secret, unb64(wrapped.getString("salt")), wrapped.optInt("rounds", ROUNDS)), wrapped)

    data class Unlocked(val payload: JSONObject, val vaultKey: ByteArray, val dataKeyVault: Boolean)

    fun decrypt(master: String, vault: JSONObject, dataKey: String = ""): Unlocked {
        val format = vault.optString("format")
        if (format == V2 || format == V3) {
            val isV3 = format == V3
            if (isV3) {
                unwrapWithPassword(master, vault.getJSONObject("auth").getJSONObject("wrapped"))
                require(dataKey.isNotBlank()) { "请输入数据钥匙" }
            }
            val key = unwrapWithPassword(if (isV3) dataKey.trim() else master, vault.getJSONObject("key"))
            val meta = JSONObject(String(open(key, vault.getJSONObject("meta")), Charsets.UTF_8))
            val settings = if (vault.has("settings")) JSONObject(String(open(key, vault.getJSONObject("settings")), Charsets.UTF_8)) else JSONObject()
            val entries = JSONArray()
            val raw = vault.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until raw.length()) {
                val item = raw.getJSONObject(i)
                entries.put(JSONObject(String(open(key, item.optJSONObject("sealed") ?: item), Charsets.UTF_8)))
            }
            return Unlocked(JSONObject().put("version", meta.optInt("version", 1))
                .put("createdAt", meta.optString("createdAt", now()))
                .put("updatedAt", meta.optString("updatedAt", now()))
                .put("entries", entries).put("settings", settings), key, isV3)
        }
        // Desktop legacy V1 stores a single encrypted JSON payload.
        val key = derive(master, unb64(vault.getString("salt")), vault.optInt("rounds", ROUNDS))
        return Unlocked(JSONObject(String(open(key, vault), Charsets.UTF_8)), bytes(32), false)
    }

    fun encrypt(master: String, payload: JSONObject, dataKey: String = "", existingKey: ByteArray? = null): JSONObject {
        val key = existingKey ?: bytes(32)
        val isV3 = dataKey.isNotBlank()
        val meta = JSONObject().put("version", payload.optInt("version", 1))
            .put("createdAt", payload.optString("createdAt", now())).put("updatedAt", payload.optString("updatedAt", now()))
        val wrapped = JSONObject().put("version", if (isV3) 3 else 2).put("format", if (isV3) V3 else V2)
        if (isV3) wrapped.put("auth", JSONObject().put("version", 1).put("wrapped", wrapWithPassword(master, bytes(32))))
        wrapped.put("key", wrapWithPassword(if (isV3) dataKey.trim() else master, key))
        wrapped.put("meta", seal(key, meta.toString().toByteArray(Charsets.UTF_8)))
        wrapped.put("settings", seal(key, (payload.optJSONObject("settings") ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)))
        val entries = JSONArray()
        val raw = payload.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until raw.length()) {
            val entry = raw.getJSONObject(i)
            entries.put(JSONObject().put("id", entry.optString("id"))
                .put("updatedAt", entry.optString("updatedAt"))
                .put("deletedAt", entry.optString("deletedAt"))
                .put("sealed", seal(key, entry.toString().toByteArray(Charsets.UTF_8))))
        }
        wrapped.put("entries", entries)
        return wrapped
    }
}
