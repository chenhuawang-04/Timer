package com.timer.app.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.text.Charsets.UTF_8
import org.json.JSONArray
import org.json.JSONObject

data class CloudSnapshotChunkDescriptor(
    val index: Int,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Int
)

data class CloudSnapshotManifest(
    val schemaVersion: Int,
    val snapshotId: String,
    val exportedAtEpochMillis: Long,
    val payloadSha256: String,
    val logicalDataSha256: String,
    val payloadSizeBytes: Int,
    val compressedSizeBytes: Int,
    val chunkSizeBytes: Int,
    val chunkCount: Int,
    val compression: String,
    val chunks: List<CloudSnapshotChunkDescriptor>
)

data class CloudSnapshotPointer(
    val schemaVersion: Int,
    val snapshotId: String,
    val manifestRelativePath: String,
    val logicalDataSha256: String,
    val exportedAtEpochMillis: Long
)

data class PreparedCloudSnapshotChunk(
    val descriptor: CloudSnapshotChunkDescriptor,
    val bytes: ByteArray
)

data class PreparedCloudSnapshot(
    val snapshotRootRelativePath: String,
    val manifestRelativePath: String,
    val manifest: CloudSnapshotManifest,
    val pointer: CloudSnapshotPointer,
    val manifestJson: String,
    val pointerJson: String,
    val payloadJson: String,
    val payloadBytes: ByteArray,
    val chunks: List<PreparedCloudSnapshotChunk>
)

object CloudSnapshotCodec {
    const val SCHEMA_VERSION = 1
    const val DEFAULT_CHUNK_SIZE_BYTES = 256 * 1024
    const val LATEST_POINTER_RELATIVE_PATH = "latest.json"
    private const val COMPRESSION_GZIP = "gzip"

    fun prepareSnapshot(
        payloadJson: String,
        logicalDataSha256: String,
        exportedAtEpochMillis: Long,
        chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
        snapshotId: String = defaultSnapshotId(logicalDataSha256, exportedAtEpochMillis)
    ): PreparedCloudSnapshot {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be greater than 0" }
        val snapshotRootRelativePath = "snapshots/$snapshotId"
        val manifestRelativePath = "$snapshotRootRelativePath/manifest.json"
        val payloadBytes = payloadJson.toByteArray(UTF_8)
        val compressedBytes = gzip(payloadBytes)
        val chunks = chunkBytes(compressedBytes, chunkSizeBytes).mapIndexed { index, bytes ->
            PreparedCloudSnapshotChunk(
                descriptor = CloudSnapshotChunkDescriptor(
                    index = index,
                    relativePath = "$snapshotRootRelativePath/chunks/chunk-${index.toString().padStart(3, '0')}.bin",
                    sha256 = sha256(bytes),
                    sizeBytes = bytes.size
                ),
                bytes = bytes
            )
        }
        val manifest = CloudSnapshotManifest(
            schemaVersion = SCHEMA_VERSION,
            snapshotId = snapshotId,
            exportedAtEpochMillis = exportedAtEpochMillis,
            payloadSha256 = sha256(payloadBytes),
            logicalDataSha256 = logicalDataSha256,
            payloadSizeBytes = payloadBytes.size,
            compressedSizeBytes = compressedBytes.size,
            chunkSizeBytes = chunkSizeBytes,
            chunkCount = chunks.size,
            compression = COMPRESSION_GZIP,
            chunks = chunks.map { it.descriptor }
        )
        val pointer = CloudSnapshotPointer(
            schemaVersion = SCHEMA_VERSION,
            snapshotId = snapshotId,
            manifestRelativePath = manifestRelativePath,
            logicalDataSha256 = logicalDataSha256,
            exportedAtEpochMillis = exportedAtEpochMillis
        )
        return PreparedCloudSnapshot(
            snapshotRootRelativePath = snapshotRootRelativePath,
            manifestRelativePath = manifestRelativePath,
            manifest = manifest,
            pointer = pointer,
            manifestJson = encodeManifest(manifest),
            pointerJson = encodePointer(pointer),
            payloadJson = payloadJson,
            payloadBytes = payloadBytes,
            chunks = chunks
        )
    }

    fun encodeManifest(manifest: CloudSnapshotManifest): String {
        val root = JSONObject()
            .put("schemaVersion", manifest.schemaVersion)
            .put("snapshotId", manifest.snapshotId)
            .put("exportedAtEpochMillis", manifest.exportedAtEpochMillis)
            .put("payloadSha256", manifest.payloadSha256)
            .put("logicalDataSha256", manifest.logicalDataSha256)
            .put("payloadSizeBytes", manifest.payloadSizeBytes)
            .put("compressedSizeBytes", manifest.compressedSizeBytes)
            .put("chunkSizeBytes", manifest.chunkSizeBytes)
            .put("chunkCount", manifest.chunkCount)
            .put("compression", manifest.compression)
            .put(
                "chunks",
                JSONArray().apply {
                    manifest.chunks.forEach { chunk ->
                        put(
                            JSONObject()
                                .put("index", chunk.index)
                                .put("relativePath", chunk.relativePath)
                                .put("sha256", chunk.sha256)
                                .put("sizeBytes", chunk.sizeBytes)
                        )
                    }
                }
            )
        return root.toString(2)
    }

    fun decodeManifest(json: String): CloudSnapshotManifest {
        val root = JSONObject(json)
        val chunks = buildList {
            val array = root.optJSONArray("chunks") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    CloudSnapshotChunkDescriptor(
                        index = item.optInt("index", index),
                        relativePath = item.getString("relativePath"),
                        sha256 = item.getString("sha256"),
                        sizeBytes = item.optInt("sizeBytes", 0)
                    )
                )
            }
        }
        return CloudSnapshotManifest(
            schemaVersion = root.optInt("schemaVersion", 0),
            snapshotId = root.optString("snapshotId", "legacy"),
            exportedAtEpochMillis = root.optLong("exportedAtEpochMillis", 0L),
            payloadSha256 = root.optString("payloadSha256"),
            logicalDataSha256 = root.optString("logicalDataSha256"),
            payloadSizeBytes = root.optInt("payloadSizeBytes", 0),
            compressedSizeBytes = root.optInt("compressedSizeBytes", 0),
            chunkSizeBytes = root.optInt("chunkSizeBytes", DEFAULT_CHUNK_SIZE_BYTES),
            chunkCount = root.optInt("chunkCount", chunks.size),
            compression = root.optString("compression", COMPRESSION_GZIP),
            chunks = chunks.sortedBy { it.index }
        )
    }

    fun encodePointer(pointer: CloudSnapshotPointer): String {
        return JSONObject()
            .put("schemaVersion", pointer.schemaVersion)
            .put("snapshotId", pointer.snapshotId)
            .put("manifestRelativePath", pointer.manifestRelativePath)
            .put("logicalDataSha256", pointer.logicalDataSha256)
            .put("exportedAtEpochMillis", pointer.exportedAtEpochMillis)
            .toString(2)
    }

    fun decodePointer(json: String): CloudSnapshotPointer {
        val root = JSONObject(json)
        return CloudSnapshotPointer(
            schemaVersion = root.optInt("schemaVersion", 0),
            snapshotId = root.getString("snapshotId"),
            manifestRelativePath = root.getString("manifestRelativePath"),
            logicalDataSha256 = root.optString("logicalDataSha256"),
            exportedAtEpochMillis = root.optLong("exportedAtEpochMillis", 0L)
        )
    }

    fun restorePayloadJson(
        manifest: CloudSnapshotManifest,
        chunkBytes: List<ByteArray>
    ): String {
        require(manifest.compression == COMPRESSION_GZIP) {
            "Unsupported compression: ${manifest.compression}"
        }
        require(chunkBytes.size == manifest.chunkCount) {
            "Expected ${manifest.chunkCount} chunks but received ${chunkBytes.size}"
        }
        manifest.chunks.zip(chunkBytes).forEach { (descriptor, bytes) ->
            require(descriptor.sha256 == sha256(bytes)) {
                "Chunk ${descriptor.relativePath} failed integrity verification"
            }
        }
        val compressed = ByteArrayOutputStream().use { output ->
            chunkBytes.forEach(output::write)
            output.toByteArray()
        }
        require(compressed.size == manifest.compressedSizeBytes) {
            "Compressed payload length mismatch"
        }
        val payloadBytes = gunzip(compressed)
        require(payloadBytes.size == manifest.payloadSizeBytes) {
            "Payload length mismatch"
        }
        require(sha256(payloadBytes) == manifest.payloadSha256) {
            "Payload integrity verification failed"
        }
        return payloadBytes.toString(UTF_8)
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    private fun chunkBytes(bytes: ByteArray, chunkSizeBytes: Int): List<ByteArray> {
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        return buildList {
            var index = 0
            while (index < bytes.size) {
                val end = minOf(index + chunkSizeBytes, bytes.size)
                add(bytes.copyOfRange(index, end))
                index = end
            }
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
            stream.readBytes()
        }
    }

    private fun defaultSnapshotId(logicalDataSha256: String, exportedAtEpochMillis: Long): String {
        return "snapshot-${exportedAtEpochMillis}-${logicalDataSha256.take(12)}"
    }
}
