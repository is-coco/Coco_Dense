package com.coco.dense

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class VaultRepository(private val context: Context) {
    private val file = File(context.filesDir, "vault.json")
    private val atomic = AtomicFile(file)
    var payload: JSONObject? = null; private set
    var master: String? = null; private set
    var dataKey: String = ""; private set
    private var vaultKey: ByteArray? = null
    val exists: Boolean get() = file.exists()

    fun create(password: String) {
        require(!exists) { "本机已有保险箱，请先解锁" }
        require(password.isNotBlank()) { "主密码不能为空" }
        master = password
        payload = VaultCodec.newPayload()
        save()
    }

    fun unlock(password: String, key: String = "") {
        val unlocked = VaultCodec.decrypt(password, JSONObject(file.readText()), key)
        master = password
        dataKey = key
        vaultKey = unlocked.vaultKey
        payload = unlocked.payload
    }

    fun lock() { payload = null; master = null; dataKey = ""; vaultKey?.fill(0); vaultKey = null }

    fun save() {
        val p = requireNotNull(payload) { "保险箱未解锁" }
        val pwd = requireNotNull(master)
        p.put("updatedAt", VaultCodec.now())
        val wrapped = VaultCodec.encrypt(pwd, p, dataKey, vaultKey)
        // Check ciphertext before replacing the last usable local copy.
        VaultCodec.decrypt(pwd, wrapped, dataKey)
        val stream = atomic.startWrite()
        try {
            stream.write(wrapped.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            vaultKey = VaultCodec.decrypt(pwd, wrapped, dataKey).vaultKey
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun backup(reason: String = "manual"): File? {
        if (!exists) return null
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val backup = File(dir, "vault-${System.currentTimeMillis()}-${reason.replace(Regex("[^a-zA-Z0-9-]"), "-")}.json")
        file.copyTo(backup)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        return backup
    }

    fun export(): ByteArray { requireNotNull(payload); return file.readBytes() }

    fun import(bytes: ByteArray, password: String, key: String = "") {
        val raw = JSONObject(String(bytes, Charsets.UTF_8))
        val unlocked = VaultCodec.decrypt(password, raw, key)
        backup("before-import")
        master = password; dataKey = key; vaultKey = unlocked.vaultKey; payload = unlocked.payload
        save()
    }

    fun changeMaster(old: String, next: String) {
        require(old == master && next.isNotBlank()) { "主密码不正确或新密码为空" }
        backup("before-password-change")
        master = next
        save()
    }

    fun setDataKey(next: String) {
        requireNotNull(payload)
        backup("before-data-key-change")
        dataKey = next.trim()
        save()
    }

    fun entries(includeDeleted: Boolean = false): List<JSONObject> {
        val raw = payload?.optJSONArray("entries") ?: return emptyList()
        return (0 until raw.length()).map { raw.getJSONObject(it) }
            .filter { includeDeleted || it.optString("deletedAt").isEmpty() }
    }

    fun upsert(entry: JSONObject) {
        val p = requireNotNull(payload)
        val raw = p.optJSONArray("entries") ?: JSONArray()
        if (entry.optString("id").isEmpty()) entry.put("id", UUID.randomUUID().toString())
        if (entry.optString("createdAt").isEmpty()) entry.put("createdAt", VaultCodec.now())
        entry.put("updatedAt", VaultCodec.now())
        val next = JSONArray()
        for (i in 0 until raw.length()) if (raw.getJSONObject(i).optString("id") != entry.getString("id")) next.put(raw.getJSONObject(i))
        next.put(entry)
        p.put("entries", next)
        save()
    }

    fun trash(id: String, restore: Boolean = false) {
        val entry = entries(true).firstOrNull { it.optString("id") == id } ?: return
        entry.put("deletedAt", if (restore) "" else VaultCodec.now())
        entry.put("updatedAt", VaultCodec.now())
        save()
    }

    fun folders(): List<JSONObject> {
        val raw = payload?.optJSONObject("settings")?.optJSONArray("folders") ?: return emptyList()
        return (0 until raw.length()).map { raw.getJSONObject(it) }
    }

    fun putFolder(folder: JSONObject) {
        val p = requireNotNull(payload)
        val settings = p.optJSONObject("settings") ?: JSONObject()
        val raw = settings.optJSONArray("folders") ?: JSONArray()
        if (folder.optString("id").isEmpty()) folder.put("id", UUID.randomUUID().toString())
        folder.put("updatedAt", VaultCodec.now())
        val next = JSONArray()
        for (i in 0 until raw.length()) if (raw.getJSONObject(i).optString("id") != folder.getString("id")) next.put(raw.getJSONObject(i))
        next.put(folder)
        settings.put("folders", next); p.put("settings", settings); save()
    }

    fun removeFolder(id: String) {
        val p = requireNotNull(payload)
        val settings = p.optJSONObject("settings") ?: JSONObject()
        val next = JSONArray()
        folders().filter { it.optString("id") != id }.forEach(next::put)
        settings.put("folders", next); p.put("settings", settings)
        entries(true).filter { it.optString("folderId") == id }.forEach { it.put("folderId", "").put("updatedAt", VaultCodec.now()) }
        save()
    }

    fun applySynced(merged: JSONObject) {
        payload = merged
        vaultKey = null
        save()
    }
}
