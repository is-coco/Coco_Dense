package com.coco.dense

import org.json.JSONArray
import org.json.JSONObject

/** Pure three-way merge shared by online sync and JVM tests. */
object SyncMerger {
    fun entries(payload: JSONObject): Map<String, JSONObject> {
        val raw = payload.optJSONArray("entries") ?: JSONArray()
        return (0 until raw.length()).map { raw.getJSONObject(it) }
            .filter { it.optString("id").isNotEmpty() }.associateBy { it.getString("id") }
    }
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.opt(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun same(a: JSONObject?, b: JSONObject?) = canonical(a) == canonical(b)
    private fun folders(payload: JSONObject?): Map<String, JSONObject> {
        val raw = payload?.optJSONObject("settings")?.optJSONArray("folders") ?: JSONArray()
        return (0 until raw.length()).map { raw.getJSONObject(it) }
            .filter { it.optString("id").isNotEmpty() }.associateBy { it.getString("id") }
    }
    fun merge(local: JSONObject, remote: JSONObject, base: JSONObject?, choices: Map<String, Boolean> = emptyMap()): Pair<JSONObject, List<SyncConflict>> {
        val l = entries(local); val r = entries(remote); val b = base?.let(::entries) ?: emptyMap()
        val conflicts = mutableListOf<SyncConflict>()
        val result = JSONArray()
        for (id in (l.keys + r.keys)) {
            val left = l[id]; val right = r[id]; val prior = b[id]
            val chosen = when {
                left == null && prior == null -> right
                right == null && prior == null -> left
                left == null || right == null -> if (choices.containsKey(id)) {
                    if (choices[id] == true) left else right
                } else { conflicts += SyncConflict(id, left, right); null }
                same(left, right) -> left
                prior != null && same(left, prior) -> right
                prior != null && same(right, prior) -> left
                choices.containsKey(id) -> if (choices[id] == true) left else right
                else -> { conflicts += SyncConflict(id, left, right); null }
            }
            if (chosen != null) result.put(chosen)
        }
        val lf = folders(local); val rf = folders(remote); val bf = folders(base)
        val mergedFolders = JSONArray()
        for (id in (lf.keys + rf.keys + bf.keys)) {
            val left = lf[id]; val right = rf[id]; val prior = bf[id]
            val chosen = when {
                same(left, right) -> left
                prior != null && same(left, prior) -> right
                prior != null && same(right, prior) -> left
                prior == null && left == null -> right
                prior == null && right == null -> left
                choices.containsKey("folder:$id") -> if (choices["folder:$id"] == true) left else right
                else -> { conflicts += SyncConflict("folder:$id", left, right); null }
            }
            if (chosen != null) mergedFolders.put(chosen)
        }
        val preferredSettings = if (local.optString("updatedAt") >= remote.optString("updatedAt")) local.optJSONObject("settings") else remote.optJSONObject("settings")
        val settings = JSONObject((preferredSettings ?: JSONObject()).toString()).put("folders", mergedFolders)
        val merged = JSONObject().put("version", 1).put("createdAt", local.optString("createdAt", remote.optString("createdAt")))
            .put("updatedAt", VaultCodec.now()).put("entries", result).put("settings", settings)
        return merged to conflicts
    }
}
