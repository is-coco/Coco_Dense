package com.coco.dense

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ApkUpdater(private val context: Context) {
    private val client = OkHttpClient()
    data class Release(val name: String, val url: String, val sha256: String?)
    private fun newer(remote: String): Boolean {
        val a = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = BuildConfig.VERSION_NAME.split('.').map { it.toIntOrNull() ?: 0 }
        return (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { index ->
            val difference = (a.getOrNull(index) ?: 0) - (b.getOrNull(index) ?: 0)
            if (difference == 0) null else difference > 0
        } ?: false
    }
    fun latest(): Release? {
        val request = Request.Builder().url("https://api.github.com/repos/is-coco/Coco_Dense/releases?per_page=30")
            .header("Accept", "application/vnd.github+json").build()
        val releases = client.newCall(request).execute().use { require(it.isSuccessful); JSONArray(it.body!!.string()) }
        for (index in 0 until releases.length()) {
            val raw = releases.getJSONObject(index)
            val tag = raw.optString("tag_name")
            if (!tag.startsWith("android-v") || !newer(tag.removePrefix("android-"))) continue
            val assets = raw.optJSONArray("assets") ?: continue
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) return Release(name, asset.getString("browser_download_url"),
                    asset.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) })
            }
        }
        return null
    }
    fun download(release: Release): File {
        require(release.url.startsWith("https://github.com/is-coco/Coco_Dense/releases/download/"))
        val target = File(context.cacheDir, "updates/${release.name.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
        target.parentFile?.mkdirs()
        client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
            require(response.isSuccessful) { "下载失败：${response.code}" }
            target.outputStream().use { output -> response.body!!.byteStream().copyTo(output) }
        }
        if (release.sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
            require(digest.equals(release.sha256, true)) { target.delete(); "APK 校验失败" }
        }
        return target
    }
}
