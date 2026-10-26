package com.coco.dense

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val failure = runCatching { VaultCodec.decrypt("wrong", fixture("v2")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && failure.message?.contains("主密码不正确") == true)
    }

    @Test fun v3DistinguishesMasterPasswordAndDataKey() {
        val vault = fixture("v3")
        val wrongMaster = runCatching { VaultCodec.decrypt("wrong", vault, "sample-data-key") }.exceptionOrNull()
        val wrongKey = runCatching { VaultCodec.decrypt("sample-master-password", vault, "wrong") }.exceptionOrNull()
        assertTrue(wrongMaster?.message?.contains("主密码不正确") == true)
        assertTrue(wrongKey?.message?.contains("数据钥匙不正确") == true)
    }

    @Test fun damagedContentHasReadableMessage() {
        val vault = fixture("v2")
        vault.getJSONObject("meta").put("tag", "AAAAAAAAAAAAAAAAAAAAAA==")
        val failure = runCatching { VaultCodec.decrypt("sample-master-password", vault) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("保险箱内容校验失败") == true)
    }
}
