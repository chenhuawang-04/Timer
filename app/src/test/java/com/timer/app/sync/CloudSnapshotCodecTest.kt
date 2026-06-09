package com.timer.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSnapshotCodecTest {
    @Test
    fun prepareAndRestoreRoundTripPreservesPayloadAndManifest() {
        val payload = buildString {
            append("{\"tasks\":[")
            repeat(4_000) { index ->
                if (index > 0) append(',')
                append("{\"id\":").append(index).append(",\"name\":\"Task ").append(index).append("\"}")
            }
            append("]}")
        }
        val logicalDigest = CloudSnapshotCodec.sha256("stable-data".toByteArray())

        val prepared = CloudSnapshotCodec.prepareSnapshot(
            payloadJson = payload,
            logicalDataSha256 = logicalDigest,
            exportedAtEpochMillis = 123456789L,
            chunkSizeBytes = 512
        )

        assertTrue(prepared.manifest.chunkCount > 1)
        assertEquals(logicalDigest, prepared.manifest.logicalDataSha256)
        assertEquals(prepared.manifest.snapshotId, prepared.pointer.snapshotId)
        assertEquals(prepared.manifestRelativePath, prepared.pointer.manifestRelativePath)
        assertTrue(prepared.snapshotRootRelativePath.startsWith("snapshots/"))
        assertTrue(prepared.manifestRelativePath.startsWith("${prepared.snapshotRootRelativePath}/"))
        assertTrue(
            prepared.chunks.all { chunk ->
                chunk.descriptor.relativePath.startsWith("${prepared.snapshotRootRelativePath}/chunks/")
            }
        )

        val restored = CloudSnapshotCodec.restorePayloadJson(
            manifest = prepared.manifest,
            chunkBytes = prepared.chunks.map { it.bytes }
        )

        assertEquals(payload, restored)
        val manifestJson = CloudSnapshotCodec.encodeManifest(prepared.manifest)
        val decoded = CloudSnapshotCodec.decodeManifest(manifestJson)
        assertEquals(prepared.manifest.chunkCount, decoded.chunkCount)
        assertEquals(prepared.manifest.payloadSha256, decoded.payloadSha256)

        val pointerJson = CloudSnapshotCodec.encodePointer(prepared.pointer)
        val decodedPointer = CloudSnapshotCodec.decodePointer(pointerJson)
        assertEquals(prepared.pointer.snapshotId, decodedPointer.snapshotId)
        assertEquals(prepared.pointer.manifestRelativePath, decodedPointer.manifestRelativePath)
        assertEquals(prepared.pointer.logicalDataSha256, decodedPointer.logicalDataSha256)
    }
}
