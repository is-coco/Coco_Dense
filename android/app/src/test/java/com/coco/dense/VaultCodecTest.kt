package com.coco.dense

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VaultCodecTest {
    private fun fixture(name: String) = JSONObject(File("../fixtures/$name.json").readText())

    @Test fun desktopV2CanBeReadAndWritten() {
        val decoded = VaultCodec.decrypt("sample-master-password", fixture("v2"))
        assertEquals("example-only-123", decoded.payload.getJSONArray("entries").getJSONObject(0).getString("password"))
        val reencrypted = VaultCodec.encrypt("sample-master-password", decoded.payload)
        assertEquals(decoded.payload.getJSONArray("entries").getJSONObject(0).getString("site"),
            VaultCodec.decrypt("sample-master-password", reencrypted).payload.getJSONArray("entries").getJSONObject(0).getString("site"))
        File("build/android-v2-roundtrip.json").writeText(reencrypted.toString())
    }

    @Test fun desktopV3CanBeReadAndWritten() {
        val decoded = VaultCodec.decrypt("sample-master-password", fixture("v3"), "sample-data-key")
        assertEquals("demo", decoded.payload.getJSONArray("entries").getJSONObject(0).getString("account"))
        val reencrypted = VaultCodec.encrypt("sample-master-password", decoded.payload, "sample-data-key")
        assertEquals(3, reencrypted.getInt("version"))
        File("build/android-v3-roundtrip.json").writeText(reencrypted.toString())
    }

    @Test fun wrongPasswordFails() {
        try { VaultCodec.decrypt("wrong", fixture("v2")); throw AssertionError("Accepted wrong password") }
        catch (_: javax.crypto.AEADBadTagException) { }
    }
}
