package com.coco.dense

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMergerTest {
    private fun payload(password: String, folder: String, deletedAt: String = "") = JSONObject("""
        {"version":1,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z",
        "entries":[{"id":"one","site":"示例","password":"$password","deletedAt":"$deletedAt"}],
        "settings":{"folders":[{"id":"f","name":"$folder"}]}}
    """.trimIndent())

    @Test fun simultaneousChangesRequireChoice() {
        val base = payload("a", "原名")
        val local = payload("b", "本机")
        val remote = payload("c", "云端")
        val (_, conflicts) = SyncMerger.merge(local, remote, base)
        assertEquals(setOf("one", "folder:f"), conflicts.map { it.id }.toSet())
        val (merged, resolved) = SyncMerger.merge(local, remote, base, mapOf("one" to false, "folder:f" to true))
        assertEquals(0, resolved.size)
        assertEquals("c", merged.getJSONArray("entries").getJSONObject(0).getString("password"))
        assertEquals("本机", merged.getJSONObject("settings").getJSONArray("folders").getJSONObject(0).getString("name"))
    }

    @Test fun tombstoneBeatsUnchangedEntry() {
        val base = payload("a", "原名")
        val remote = payload("a", "原名", "2026-01-03T00:00:00Z")
        val (merged, conflicts) = SyncMerger.merge(base, remote, base)
        assertEquals(0, conflicts.size)
        assertEquals("2026-01-03T00:00:00Z", merged.getJSONArray("entries").getJSONObject(0).getString("deletedAt"))
    }

    @Test fun missingPreviouslySyncedEntryIsNotSilentlyRestored() {
        val base = payload("a", "原名")
        val local = JSONObject(base.toString()).put("entries", org.json.JSONArray())
        val (_, conflicts) = SyncMerger.merge(local, base, base)
        assertEquals(listOf("one"), conflicts.map { it.id })
    }
}
