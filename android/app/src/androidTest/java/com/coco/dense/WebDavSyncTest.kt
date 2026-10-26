package com.coco.dense

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebDavSyncTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repo: VaultRepository
    private lateinit var sync: WebDavSync

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.filesDir.mkdirs()
        context.filesDir.resolve("vault.json").delete()
        context.getSharedPreferences("device-secrets", Context.MODE_PRIVATE).edit().clear().commit()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(10, TimeUnit.SECONDS).build()
        repo = VaultRepository(context).apply { create("qa-master") }
        sync = WebDavSync(context, repo, client)
        sync.saveConfig(SyncConfig(server.url("/dav/").toString(), "qa-user", "qa-password", "/qa/vault.json"))
    }

    @After fun tearDown() { server.shutdown() }

    private fun entry(id: String) = JSONObject().put("id", id).put("site", id)
        .put("account", "qa").put("password", "fictional-only")

    private fun remoteVault(password: String = "qa-master") = VaultCodec.encrypt(password,
        VaultCodec.newPayload().put("entries", JSONArray().put(entry("remote")))).toString()

    @Test fun downloadMergesLocallyWithoutUploading() {
        repo.upsert(entry("local"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(remoteVault()))

        val result = sync.download()

        assertTrue(result is SyncResult.Done)
        assertEquals(setOf("local", "remote"), repo.entries().map { it.getString("id") }.toSet())
        assertEquals("GET", server.takeRequest(3, TimeUnit.SECONDS)?.method)
        assertEquals(null, server.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test fun wrongCloudPasswordKeepsLocalVault() {
        repo.upsert(entry("local"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(remoteVault("other-master")))

        val failure = runCatching { sync.download() }.exceptionOrNull()

        assertTrue(failure?.message?.contains("无法解密云端保险箱") == true)
        assertEquals(listOf("local"), repo.entries().map { it.getString("id") })
        assertEquals("GET", server.takeRequest(3, TimeUnit.SECONDS)?.method)
        assertEquals(null, server.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test fun uploadReadsRemoteAndUsesEtagCondition() {
        repo.upsert(entry("local"))
        var remote = remoteVault()
        val methods = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                methods += request.method ?: ""
                return when (request.method) {
                    "GET" -> MockResponse().setResponseCode(200).setHeader("ETag", "\"revision-1\"").setBody(remote)
                    "PUT" -> {
                        assertEquals("\"revision-1\"", request.getHeader("If-Match"))
                        remote = request.body.readUtf8()
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val result = sync.sync()

        assertTrue(result is SyncResult.Done)
        assertEquals(listOf("GET", "PUT", "GET"), methods)
        val cloud = VaultCodec.decrypt("qa-master", JSONObject(remote)).payload
        assertEquals(setOf("local", "remote"), SyncMerger.entries(cloud).keys)
    }
}
