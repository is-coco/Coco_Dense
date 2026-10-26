package com.coco.dense

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

data class SyncConfig(val server: String, val user: String, val password: String, val path: String = "/CocoDense/vault.json")
const val DEFAULT_WEBDAV_SERVER = "https://dav.jianguoyun.com/dav/"
data class SyncConflict(val id: String, val local: JSONObject?, val remote: JSONObject?)
sealed class SyncResult {
    data class Done(val entries: Int): SyncResult()
    data class NeedsChoice(val conflicts: List<SyncConflict>): SyncResult()
}
enum class SyncAction { DOWNLOAD, UPLOAD, QUICK }

class WebDavSync(private val context: Context, private val repo: VaultRepository,
                 private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()) {
    private val secrets = DeviceSecrets(context)
    fun config(): SyncConfig = SyncConfig(secrets.get("dav-server") ?: DEFAULT_WEBDAV_SERVER, secrets.get("dav-user") ?: "",
        secrets.get("dav-password") ?: "", secrets.get("dav-path") ?: "/CocoDense/vault.json")
    fun saveConfig(config: SyncConfig) {
        require(config.server.startsWith("https://")) { "同步地址必须使用 HTTPS" }
        val previous = config()
        if (previous.server.trimEnd('/') != config.server.trimEnd('/') || previous.user != config.user || previous.path != config.path) resetBaseline()
        secrets.put("dav-server", config.server.trimEnd('/'))
        secrets.put("dav-user", config.user)
        secrets.put("dav-password", config.password)
        secrets.put("dav-path", config.path)
    }
    fun resetBaseline() { secrets.put("sync-base", null) }

    private data class Response(val code: Int, val etag: String?, val bytes: ByteArray)
    private fun url(config: SyncConfig, path: String = config.path): String {
        val base = config.server.trimEnd('/') + "/"
        val segments = path.split('/').filter(String::isNotBlank).joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return base + segments + if (path.endsWith('/')) "/" else ""
    }
    private fun request(config: SyncConfig, method: String, path: String = config.path,
                        body: ByteArray? = null, condition: Pair<String, String>? = null): Response {
        val builder = Request.Builder().url(url(config, path))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("${config.user}:${config.password}".toByteArray()))
            .header("Accept", "application/json")
        if (condition != null) builder.header(condition.first, condition.second)
        builder.method(method, body?.toRequestBody("application/json".toMediaType()) ?: if (method == "PUT") byteArrayOf().toRequestBody() else null)
        return client.newCall(builder.build()).execute().use { Response(it.code, it.header("ETag"), it.body?.bytes() ?: byteArrayOf()) }
    }
    private fun ensureDirectory(config: SyncConfig) {
        val parts = config.path.split('/').filter(String::isNotBlank).dropLast(1)
        var current = ""
        for (part in parts) {
            current += "/$part"
            val response = request(config, "MKCOL", "$current/")
            require(response.code in listOf(200, 201, 204, 301, 405)) { "创建 WebDAV 目录失败：${response.code}" }
        }
    }

    private fun baseline(): JSONObject? {
        val saved = secrets.get("sync-base") ?: return null
        val master = repo.master ?: return null
        return try { VaultCodec.decrypt(master, JSONObject(saved), repo.dataKey).payload } catch (_: Exception) { null }
    }
    private fun remember(payload: JSONObject) {
        secrets.put("sync-base", VaultCodec.encrypt(requireNotNull(repo.master), payload, repo.dataKey).toString())
    }
    private fun decryptRemote(bytes: ByteArray, master: String, dataKey: String): VaultCodec.Unlocked {
        val wrapped = try { JSONObject(String(bytes, Charsets.UTF_8)) }
            catch (_: Exception) { throw IllegalArgumentException("云端文件不是有效的保险箱 JSON") }
        return try { VaultCodec.decrypt(master, wrapped, dataKey) }
            catch (e: Exception) {
                if (e.message == "请输入数据钥匙") throw IllegalArgumentException("云端保险箱需要数据钥匙，请在同步页面输入")
                throw IllegalArgumentException("无法解密云端保险箱：请核对云端主密码和数据钥匙；若凭据无误，请检查云端文件是否损坏")
            }
    }

    /** Read-only download first; replace the local vault only when it contains no records or folders. */
    fun importExisting(master: String, dataKey: String): Int {
        require(repo.payload != null) { "请先解锁本机保险箱" }
        require(repo.entries(true).isEmpty() && repo.folders().isEmpty()) { "本机已有记录或分组，已停止覆盖；请先备份并手动合并" }
        require(master.isNotBlank()) { "请输入云端保险箱的主密码" }
        val config = config()
        require(config.server.startsWith("https://") && config.user.isNotBlank() && config.password.isNotBlank()) { "请先保存 WebDAV 账号和应用密码" }
        val download = request(config, "GET")
        require(download.code == 200) { if (download.code == 404) "远端路径没有保险箱文件，请检查路径" else "下载失败：${download.code}" }
        val unlocked = decryptRemote(download.bytes, master, dataKey)
        repo.import(download.bytes, master, dataKey)
        remember(unlocked.payload)
        return SyncMerger.entries(unlocked.payload).size
    }

    /** Merge the cloud vault into the local vault without writing to WebDAV. */
    fun download(choices: Map<String, Boolean> = emptyMap()): SyncResult {
        val config = config()
        require(config.server.startsWith("https://") && config.user.isNotBlank() && config.password.isNotBlank()) { "请先配置 WebDAV" }
        val local = requireNotNull(repo.payload) { "保险箱未解锁" }
        val response = request(config, "GET")
        require(response.code == 200) {
            when (response.code) {
                404 -> "远端路径没有保险箱文件，请检查远端路径"
                401, 403 -> "WebDAV 账号或应用密码无效，无法下载"
                else -> "下载失败：${response.code}"
            }
        }
        val remote = decryptRemote(response.bytes, requireNotNull(repo.master), repo.dataKey).payload
        val merged = if (repo.entries(true).isEmpty() && repo.folders().isEmpty()) remote else {
            val (candidate, conflicts) = SyncMerger.merge(local, remote, baseline(), choices)
            if (conflicts.isNotEmpty()) return SyncResult.NeedsChoice(conflicts)
            candidate
        }
        repo.backup("before-download")
        repo.applySynced(merged)
        remember(remote)
        return SyncResult.Done(SyncMerger.entries(merged).size)
    }

    /** choices maps entry ID to true for local or false for remote. Never writes unresolved conflicts. */
    fun sync(choices: Map<String, Boolean> = emptyMap()): SyncResult {
        val config = config()
        require(config.server.startsWith("https://") && config.user.isNotBlank() && config.password.isNotBlank()) { "请先配置 WebDAV" }
        val local = requireNotNull(repo.payload) { "保险箱未解锁" }
        repeat(3) {
            val download = request(config, "GET")
            require(download.code == 200 || download.code == 404) {
                if (download.code == 401 || download.code == 403) "WebDAV 账号或应用密码无效，无法读取云端"
                else "下载失败：${download.code}"
            }
            val exists = download.code == 200
            if (!exists && repo.entries(true).isEmpty() && repo.folders().isEmpty())
                error("远端路径没有保险箱文件；已停止空库上传，请检查远端路径")
            if (exists) require(!download.etag.isNullOrBlank()) { "服务器没有提供 ETag，已停止上传以避免覆盖其他设备" }
            val remote = if (exists) decryptRemote(download.bytes, requireNotNull(repo.master), repo.dataKey).payload else null
            val merged: JSONObject
            if (remote == null) merged = local
            else {
                val (candidate, conflicts) = SyncMerger.merge(local, remote, baseline(), choices)
                if (conflicts.isNotEmpty()) return SyncResult.NeedsChoice(conflicts)
                merged = candidate
            }
            val ciphertext = VaultCodec.encrypt(requireNotNull(repo.master), merged, repo.dataKey).toString().toByteArray()
            repo.backup("before-sync")
            if (!exists) ensureDirectory(config)
            val upload = request(config, "PUT", body = ciphertext,
                condition = if (exists) "If-Match" to requireNotNull(download.etag) else "If-None-Match" to "*")
            if (upload.code == 412) return@repeat
            require(upload.code in listOf(200, 201, 204)) { "上传失败：${upload.code}" }
            val verify = request(config, "GET")
            require(verify.code == 200 && verify.bytes.contentEquals(ciphertext)) { "无法确认云端写入，已保留本地数据" }
            repo.applySynced(merged)
            remember(merged)
            return SyncResult.Done(SyncMerger.entries(merged).size)
        }
        error("云端同时发生修改，请稍后再同步")
    }
}
