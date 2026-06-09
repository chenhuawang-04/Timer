package com.timer.app.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoContentsClientsTest {
    @Test
    fun readFileReturnsNullForMissingRemoteFileWithoutOpeningInputStream() {
        val missing = FakeHttpURLConnection(
            responseCode = HttpURLConnection.HTTP_NOT_FOUND,
            responseBody = "{}",
            errorBody = """{"message":"Not Found"}""",
            failIfInputStreamIsOpened = true
        )
        val client = FakeRepoContentsClient(missing)

        val bytes = client.readFile(configuration(), accessToken = "token", relativePath = "latest.json")

        assertNull(bytes)
        assertFalse(missing.inputStreamOpened)
        assertTrue(missing.errorStreamOpened)
        assertEquals("GET", missing.capturedMethod)
    }

    @Test
    fun writeFileCreatesRemoteFileWhenPreviousFileDoesNotExist() {
        val missing = FakeHttpURLConnection(
            responseCode = HttpURLConnection.HTTP_NOT_FOUND,
            responseBody = "{}",
            errorBody = """{"message":"Not Found"}""",
            failIfInputStreamIsOpened = true
        )
        val created = FakeHttpURLConnection(
            responseCode = HttpURLConnection.HTTP_CREATED,
            responseBody = "{}"
        )
        val client = FakeRepoContentsClient(missing, created)

        client.writeFile(
            configuration = configuration(),
            accessToken = "token",
            relativePath = "snapshots/first/chunks/chunk-000.bin",
            bytes = "hello".toByteArray(Charsets.UTF_8),
            message = "timer sync: first snapshot"
        )

        assertEquals("GET", missing.capturedMethod)
        assertEquals("PUT", created.capturedMethod)
        val body = JSONObject(created.outputAsString())
        assertEquals("timer sync: first snapshot", body.getString("message"))
        assertEquals("aGVsbG8=", body.getString("content"))
        assertEquals("main", body.getString("branch"))
        assertFalse(body.has("sha"))
    }

    private fun configuration(): CloudSyncConfiguration = CloudSyncConfiguration(
        autoSyncEnabled = true,
        provider = CloudSyncProviders.GITHUB,
        repositoryOwner = "owner",
        repositoryName = "repo",
        branch = "main",
        basePath = "timer-sync",
        wifiOnly = false
    )

    private class FakeRepoContentsClient(
        vararg connections: FakeHttpURLConnection
    ) : BaseRepoContentsClient() {
        private val connections = ArrayDeque(connections.toList())

        override fun openConnection(method: String, url: String, accessToken: String): HttpURLConnection {
            val connection = connections.removeFirst()
            connection.capture(method = method, url = url, accessToken = accessToken)
            return connection
        }

        override fun buildEndpointUrl(path: String, query: Map<String, String>): String {
            val queryString = query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            return "https://example.invalid/$path?$queryString"
        }
    }

    private class FakeHttpURLConnection(
        private val responseCode: Int,
        private val responseBody: String,
        private val errorBody: String = "",
        private val failIfInputStreamIsOpened: Boolean = false
    ) : HttpURLConnection(URL("https://example.invalid")) {
        private val output = ByteArrayOutputStream()
        var capturedMethod: String? = null
            private set
        var capturedUrl: String? = null
            private set
        var capturedAccessToken: String? = null
            private set
        var inputStreamOpened: Boolean = false
            private set
        var errorStreamOpened: Boolean = false
            private set

        fun capture(method: String, url: String, accessToken: String) {
            capturedMethod = method
            capturedUrl = url
            capturedAccessToken = accessToken
            requestMethod = method
        }

        fun outputAsString(): String = output.toByteArray().toString(Charsets.UTF_8)

        override fun getResponseCode(): Int = responseCode

        override fun getInputStream(): InputStream {
            inputStreamOpened = true
            if (failIfInputStreamIsOpened) {
                throw IOException("inputStream should not be opened for this fake connection")
            }
            return ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
        }

        override fun getErrorStream(): InputStream? {
            errorStreamOpened = true
            return errorBody.takeIf(String::isNotBlank)
                ?.toByteArray(Charsets.UTF_8)
                ?.let(::ByteArrayInputStream)
        }

        override fun getOutputStream(): OutputStream = output

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
