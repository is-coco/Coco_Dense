package com.coco.dense

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val file get() = File(context.filesDir, "vault.json")

    @Before fun prepare() { context.filesDir.mkdirs(); file.delete(); File(context.filesDir, "vault.json.bak").delete(); PatternStore(context).clear() }
    @After fun clean() { file.delete(); File(context.filesDir, "vault.json.bak").delete(); PatternStore(context).clear() }

    @Test fun vaultAndPatternRoundTrip() {
        Log.i("CocoQa", "create")
        val repo = VaultRepository(context)
        repo.create("fictional-master")
        Log.i("CocoQa", "entry")
        val entry = JSONObject().put("site", "example.test").put("account", "qa-user")
            .put("password", "fictional-password")
        repo.upsert(entry)
        val id = entry.getString("id")
        assertEquals(1, repo.entries().size)
        repo.trash(id)
        assertTrue(repo.entries().isEmpty())
        assertEquals(1, repo.entries(true).size)
        repo.trash(id, restore = true)
        assertEquals(1, repo.entries().size)
        Log.i("CocoQa", "data key")
        repo.setDataKey("fictional-key")
        Log.i("CocoQa", "pattern")
        val pattern = listOf(0, 1, 2, 4)
        PatternStore(context).set(pattern, "fictional-master", "fictional-key")
        assertEquals("fictional-master" to "fictional-key", PatternStore(context).unlock(pattern))
        assertThrows(IllegalArgumentException::class.java) { PatternStore(context).unlock(listOf(0, 3, 6, 7)) }
        Log.i("CocoQa", "reopen")
        repo.lock()
        repo.unlock("fictional-master", "fictional-key")
        assertEquals("fictional-password", repo.entries().single().getString("password"))
        repo.changeMaster("fictional-master", "fictional-next")
        Log.i("CocoQa", "new master")
        repo.lock()
        repo.unlock("fictional-next", "fictional-key")
        assertEquals(1, repo.entries().size)
        assertTrue(repo.backup()!!.exists())
        Log.i("CocoQa", "done")
        PatternStore(context).clear()
        assertFalse(PatternStore(context).enabled)
    }

    @Test fun patternSkipsMiddlePoint() {
        assertEquals(listOf(0, 1, 2), appendPatternPoint(listOf(0), 2))
        assertEquals(listOf(0, 4, 8), appendPatternPoint(listOf(0), 8))
        assertEquals(listOf(0, 1, 2), appendPatternPoint(listOf(0, 1), 2))
        assertFalse(PatternStore.valid(listOf(0, 1, 1, 2)))
    }
}
