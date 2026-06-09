package com.timer.app.sync

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.text.Charsets.UTF_8
import org.json.JSONObject

private data class RepoFileSnapshot(
    val sha: String?,
    val content: ByteArray?
)

private class RepoApiException(
    val statusCode: Int,
    override val message: String
) : IllegalStateException(message)

class RepoContentsClientFactory {
    internal fun create(provider: String): RepoContentsClient = when (provider) {
        CloudSyncProviders.GITHUB -> GitHubRepoContentsClient()
        else -> GiteeRepoContentsClient()
    }
}

internal interface RepoContentsClient {
    fun readFile(
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String
    ): ByteArray?

    fun writeFile(
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String,
        bytes: ByteArray,
        message: String
    )

    fun deleteFile(
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String,
        message: String
    )
}

private abstract class BaseRepoContentsClient : RepoContentsClient {
    override fun readFile(
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String
    ): ByteArray? = getFile(configuration, accessToken, relativePath, includeContent = true)?.content

    override fun writeFile(
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String,
        bytes: ByteArray,
        message: String
    ) {
        val existing = getFile(configuration, accessToken, relativePath, includeContent = false)
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(bytes))
            .put("branch", configuration.normalized().branch)
        existing?.sha?.let { body.put("sha", it) }
        requestJson(
            method = "PUT",
            configuration = configuration,
            accessToken = accessToken,
            relativePath = relativePath,
            body = body
        )
    }

    override fun deleteFile(
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String,
        message: String
    ) {
        val existing = getFile(configuration, accessToken, relativePath, includeContent = false) ?: return
        val body = JSONObject()
            .put("message", message)
            .put("sha", existing.sha)
            .put("branch", configuration.normalized().branch)
        requestJson(
            method = "DELETE",
            configuration = configuration,
            accessToken = accessToken,
            relativePath = relativePath,
            body = body
        )
    }

    private fun getFile(
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String,
        includeContent: Boolean
    ): RepoFileSnapshot? {
        val response = requestJson(
            method = "GET",
            configuration = configuration,
            accessToken = accessToken,
            relativePath = relativePath,
            body = null,
            returnNullOnNotFound = true
        ) ?: return null
        val sha = response.optString("sha").ifBlank { null }
        val content = if (!includeContent) {
            null
        } else {
            val encodedContent = response.optString("content").replace("\n", "").trim()
            when {
                encodedContent.isNotBlank() && response.optString("encoding").equals("base64", ignoreCase = true) -> {
                    Base64.getDecoder().decode(encodedContent)
                }
                response.optString("download_url").isNotBlank() -> {
                    requestBytes(response.getString("download_url"), accessToken)
                }
                else -> null
            }
        }
        return RepoFileSnapshot(sha = sha, content = content)
    }

    protected fun requestJson(
        method: String,
        configuration: CloudSyncConfiguration,
        accessToken: String,
        relativePath: String,
        body: JSONObject?,
        returnNullOnNotFound: Boolean = false
    ): JSONObject? {
        val connection = openConnection(
            method = method,
            url = buildContentsUrl(configuration.normalized(), relativePath),
            accessToken = accessToken
        )
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(UTF_8)) }
        }
        return connection.useJsonObject(returnNullOnNotFound)
    }

    protected fun requestBytes(url: String, accessToken: String): ByteArray {
        val connection = openConnection(method = "GET", url = url, accessToken = accessToken)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw RepoApiException(code, connection.readErrorMessage())
            }
            connection.inputStream.use { stream -> stream.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.useJsonObject(returnNullOnNotFound: Boolean): JSONObject? {
        return try {
            val code = responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND && returnNullOnNotFound) {
                inputStream?.close()
                errorStream?.close()
                return null
            }
            if (code !in 200..299) {
                throw RepoApiException(code, readErrorMessage())
            }
            inputStream.use { stream ->
                JSONObject(stream.reader(UTF_8).readText())
            }
        } finally {
            disconnect()
        }
    }

    private fun HttpURLConnection.readErrorMessage(): String {
        val text = (errorStream ?: inputStream)?.use { stream ->
            stream.reader(UTF_8).readText()
        }.orEmpty()
        return text.ifBlank { "HTTP $responseCode" }
    }

    private fun buildContentsUrl(configuration: CloudSyncConfiguration, relativePath: String): String {
        val normalized = configuration.normalized()
        val joinedPath = listOf(normalized.basePath, relativePath.trim('/'))
            .filter(String::isNotBlank)
            .joinToString("/")
        return buildEndpointUrl(
            path = "repos/${encode(normalized.repositoryOwner)}/${encode(normalized.repositoryName)}/contents/${encodePath(joinedPath)}",
            query = mapOf("ref" to normalized.branch)
        )
    }

    protected abstract fun openConnection(method: String, url: String, accessToken: String): HttpURLConnection

    protected abstract fun buildEndpointUrl(path: String, query: Map<String, String>): String

    protected fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    protected fun encodePath(path: String): String = path
        .split('/')
        .filter(String::isNotBlank)
        .joinToString("/") { encode(it) }
}

private class GitHubRepoContentsClient : BaseRepoContentsClient() {
    override fun openConnection(method: String, url: String, accessToken: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "Timer-Android/1.0")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doInput = true
        return connection
    }

    override fun buildEndpointUrl(path: String, query: Map<String, String>): String {
        val queryString = query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "https://api.github.com/$path?$queryString"
    }
}

private class GiteeRepoContentsClient : BaseRepoContentsClient() {
    override fun openConnection(method: String, url: String, accessToken: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Timer-Android/1.0")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doInput = true
        return connection
    }

    override fun buildEndpointUrl(path: String, query: Map<String, String>): String {
        val queryString = query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "https://gitee.com/api/v5/$path?$queryString"
    }
}
