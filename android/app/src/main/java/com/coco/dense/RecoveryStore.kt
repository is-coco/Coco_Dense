package com.coco.dense

import android.content.Context
import org.json.JSONObject
import java.io.File

class RecoveryStore(context: Context) {
    private val file = File(context.filesDir, "recovery.json")
    fun question(): String? = try { JSONObject(file.readText()).getJSONArray("questions").getString(0) } catch (_: Exception) { null }
    fun save(master: String, question: String, answer: String) {
        require(question.isNotBlank() && answer.trim().length >= 2)
        val secret = answer.trim().lowercase().replace(Regex("\\s+"), " ")
        val wrapped = VaultCodec.wrapWithPassword(secret, master.toByteArray())
        file.writeText(JSONObject().put("version", 1).put("questions", org.json.JSONArray().put(question.trim()))
            .put("wrapped", wrapped).put("updatedAt", VaultCodec.now()).toString())
    }
    fun recover(answer: String): String {
        val raw = JSONObject(file.readText())
        val secret = answer.trim().lowercase().replace(Regex("\\s+"), " ")
        return String(VaultCodec.unwrapWithPassword(secret, raw.getJSONObject("wrapped")))
    }
    fun clear() { file.delete() }
}
