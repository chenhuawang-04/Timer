package com.timer.app.sync

import android.content.Context
import com.timer.app.R
import com.timer.app.data.AppPreferencesRepository
import com.timer.app.data.BackupPayloadCodec
import com.timer.app.data.RoomTimerRepository
import com.timer.app.data.toPortableBackup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CloudSyncCoordinator(
    private val appContext: Context,
    private val applicationScope: CoroutineScope,
    private val repository: RoomTimerRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val secretStore: CloudSyncSecretStore,
    private val scheduler: CloudSyncScheduler,
    private val networkMonitor: CloudSyncNetworkMonitor,
    private val clientFactory: RepoContentsClientFactory = RepoContentsClientFactory()
) {
    private data class RemoteSnapshotRef(
        val manifestRelativePath: String,
        val manifest: CloudSnapshotManifest
    )

    private class RemoteSnapshotCorruptionException(
        override val message: String,
        cause: Throwable? = null
    ) : IllegalStateException(message, cause)

    private val mutex = Mutex()
    private val _syncInProgress = MutableStateFlow(false)

    val syncInProgress = _syncInProgress.asStateFlow()
    val credentialState: Flow<CloudSyncCredentialSnapshot> = secretStore.credentialState

    suspend fun refreshScheduleFromStoredPreferences() {
        val configuration = preferencesRepository.preferences.first().cloudSync.configuration
        scheduler.refresh(configuration, secretStore.hasUsableToken())
    }

    fun requestAppLaunchCatchUp() {
        applicationScope.launch(Dispatchers.IO) {
            syncIfOverdue(CloudSyncTrigger.APP_LAUNCH)
        }
    }

    suspend fun saveSettings(draft: CloudSyncSettingsDraft) = withContext(Dispatchers.IO) {
        preferencesRepository.updateCloudSyncConfiguration(draft.toConfiguration())
        draft.tokenInput?.let { typed ->
            if (typed.isNotBlank()) {
                secretStore.updateAccessToken(typed)
            }
        }
        refreshScheduleFromStoredPreferences()
        val snapshot = preferencesRepository.preferences.first().cloudSync
        val configuration = snapshot.configuration
        val hasUsableToken = secretStore.hasUsableToken()
        if (configuration.autoSyncEnabled && !configuration.isComplete(hasUsableToken)) {
            val now = System.currentTimeMillis()
            preferencesRepository.updateCloudSyncStatus(
                snapshot.status.copy(
                    lastResultCode = CloudSyncResultCodes.INCOMPLETE,
                    lastMessage = appContext.getString(R.string.cloud_sync_status_incomplete),
                    lastAttemptAtEpochMillis = now
                )
            )
        }
    }

    suspend fun clearAccessToken() = withContext(Dispatchers.IO) {
        secretStore.updateAccessToken(null)
        refreshScheduleFromStoredPreferences()
    }

    suspend fun runScheduledSync(): CloudSyncExecutionResult = syncInternal(
        trigger = CloudSyncTrigger.PERIODIC,
        requireAutoEnabled = true,
        allowSkipByDigest = true,
        onlyIfOverdue = false
    )

    suspend fun syncNow(): CloudSyncExecutionResult = syncInternal(
        trigger = CloudSyncTrigger.MANUAL,
        requireAutoEnabled = false,
        allowSkipByDigest = true,
        onlyIfOverdue = false
    )

    suspend fun syncIfOverdue(trigger: CloudSyncTrigger = CloudSyncTrigger.APP_LAUNCH): CloudSyncExecutionResult? {
        val current = preferencesRepository.preferences.first().cloudSync
        val hasToken = secretStore.hasUsableToken()
        if (!current.configuration.autoSyncEnabled || !current.configuration.isComplete(hasToken)) return null
        if (!shouldAttempt(current.status)) return null
        if (!networkMonitor.canSync(current.configuration.wifiOnly)) return null
        return syncInternal(
            trigger = trigger,
            requireAutoEnabled = true,
            allowSkipByDigest = true,
            onlyIfOverdue = true
        )
    }

    suspend fun restoreLatest(): CloudSyncExecutionResult = withContext(Dispatchers.IO) {
        val preferences = preferencesRepository.preferences.first().cloudSync
        val configuration = preferences.configuration.normalized()
        val token = secretStore.readAccessToken()
        if (!configuration.isComplete(!token.isNullOrBlank())) {
            val message = appContext.getString(R.string.cloud_sync_status_incomplete)
            updateFailureStatus(message, preferences.status)
            return@withContext CloudSyncExecutionResult(
                resultCode = CloudSyncResultCodes.INCOMPLETE,
                message = message
            )
        }
        if (!networkMonitor.canSync(configuration.wifiOnly)) {
            val message = appContext.getString(R.string.cloud_sync_status_network_unavailable)
            updateFailureStatus(message, preferences.status)
            return@withContext CloudSyncExecutionResult(
                resultCode = CloudSyncResultCodes.FAILED,
                message = message
            )
        }
        mutex.withLock {
            _syncInProgress.value = true
            try {
                val client = clientFactory.create(configuration.provider)
                val remoteSnapshot = readCurrentRemoteSnapshot(
                    client = client,
                    configuration = configuration,
                    accessToken = token.orEmpty()
                ) ?: run {
                    val message = appContext.getString(R.string.cloud_sync_status_remote_empty)
                    updateFailureStatus(message, preferencesRepository.preferences.first().cloudSync.status)
                    return@withLock CloudSyncExecutionResult(
                        resultCode = CloudSyncResultCodes.FAILED,
                        message = message
                    )
                }
                val manifest = remoteSnapshot.manifest
                val chunkBytes = manifest.chunks.map { chunk ->
                    client.readFile(
                        configuration = configuration,
                        accessToken = token.orEmpty(),
                        relativePath = chunk.relativePath
                    ) ?: error("Missing remote chunk ${chunk.relativePath}")
                }
                val payloadJson = CloudSnapshotCodec.restorePayloadJson(manifest, chunkBytes)
                val payload = BackupPayloadCodec.decode(payloadJson)
                repository.importData(payload.repository)
                preferencesRepository.importSnapshot(
                    snapshot = payload.preferences,
                    importCloudSyncConfiguration = payload.containsCloudSyncConfiguration
                )
                refreshScheduleFromStoredPreferences()
                return@withLock updateRestoreStatus(manifest)
            } catch (error: Throwable) {
                val message = sanitizeErrorMessage(error.message, token)
                    .ifBlank { appContext.getString(R.string.cloud_sync_status_restore_failed) }
                val currentStatus = preferencesRepository.preferences.first().cloudSync.status
                updateFailureStatus(message, currentStatus)
                return@withLock CloudSyncExecutionResult(
                    resultCode = CloudSyncResultCodes.FAILED,
                    message = message
                )
            } finally {
                _syncInProgress.value = false
            }
        }
    }

    private suspend fun syncInternal(
        trigger: CloudSyncTrigger,
        requireAutoEnabled: Boolean,
        allowSkipByDigest: Boolean,
        onlyIfOverdue: Boolean
    ): CloudSyncExecutionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            _syncInProgress.value = true
            try {
                val snapshot = preferencesRepository.preferences.first().cloudSync
                val configuration = snapshot.configuration.normalized()
                if (requireAutoEnabled && !configuration.autoSyncEnabled) {
                    return@withLock CloudSyncExecutionResult(
                        resultCode = CloudSyncResultCodes.SKIPPED,
                        message = appContext.getString(R.string.cloud_sync_status_disabled)
                    )
                }
                val token = secretStore.readAccessToken()
                if (!configuration.isComplete(!token.isNullOrBlank())) {
                    val message = appContext.getString(R.string.cloud_sync_status_incomplete)
                    updateFailureStatus(message, snapshot.status)
                    return@withLock CloudSyncExecutionResult(
                        resultCode = CloudSyncResultCodes.INCOMPLETE,
                        message = message
                    )
                }
                if (onlyIfOverdue && !shouldAttempt(snapshot.status)) {
                    return@withLock CloudSyncExecutionResult(
                        resultCode = CloudSyncResultCodes.SKIPPED,
                        message = appContext.getString(R.string.cloud_sync_status_already_up_to_date)
                    )
                }
                if (!networkMonitor.canSync(configuration.wifiOnly)) {
                    val message = appContext.getString(R.string.cloud_sync_status_network_unavailable)
                    updateFailureStatus(message, snapshot.status)
                    return@withLock CloudSyncExecutionResult(
                        resultCode = CloudSyncResultCodes.FAILED,
                        message = message
                    )
                }

                val repositoryData = repository.exportData()
                val portablePreferences = preferencesRepository.preferences.first().toPortableBackup()
                val logicalJson = BackupPayloadCodec.encode(
                    preferences = portablePreferences,
                    repository = repositoryData,
                    exportedAtEpochMillis = 0L
                )
                val logicalDataSha256 = CloudSnapshotCodec.sha256(logicalJson.toByteArray(Charsets.UTF_8))
                val targetKey = configuration.identityKey()
                if (
                    allowSkipByDigest &&
                    snapshot.status.lastSyncedDataSha256 == logicalDataSha256 &&
                    snapshot.status.lastSuccessfulTargetKey == targetKey
                ) {
                    val now = System.currentTimeMillis()
                    val result = CloudSyncExecutionResult(
                        resultCode = CloudSyncResultCodes.SKIPPED,
                        message = appContext.getString(R.string.cloud_sync_status_no_changes),
                        dataSha256 = logicalDataSha256,
                        performedUpload = false,
                        exportedAtEpochMillis = now
                    )
                    updateStatusAfterExecution(result, snapshot.status, configuration)
                    return@withLock result
                }

                val now = System.currentTimeMillis()
                val payloadJson = BackupPayloadCodec.encode(
                    preferences = portablePreferences,
                    repository = repositoryData,
                    exportedAtEpochMillis = now
                )
                val prepared = CloudSnapshotCodec.prepareSnapshot(
                    payloadJson = payloadJson,
                    logicalDataSha256 = logicalDataSha256,
                    exportedAtEpochMillis = now
                )
                val client = clientFactory.create(configuration.provider)
                val accessToken = token.orEmpty()
                val previousSnapshot = runCatching {
                    readCurrentRemoteSnapshot(
                        client = client,
                        configuration = configuration,
                        accessToken = accessToken
                    )
                }.getOrElse { error ->
                    if (error is RemoteSnapshotCorruptionException) null else throw error
                }

                val commitMessage = buildCommitMessage(trigger, prepared.manifest)
                var latestPointerWriteStarted = false
                try {
                    prepared.chunks.forEach { chunk ->
                        client.writeFile(
                            configuration = configuration,
                            accessToken = accessToken,
                            relativePath = chunk.descriptor.relativePath,
                            bytes = chunk.bytes,
                            message = commitMessage
                        )
                    }
                    client.writeFile(
                        configuration = configuration,
                        accessToken = accessToken,
                        relativePath = prepared.manifestRelativePath,
                        bytes = prepared.manifestJson.toByteArray(Charsets.UTF_8),
                        message = commitMessage
                    )
                    latestPointerWriteStarted = true
                    client.writeFile(
                        configuration = configuration,
                        accessToken = accessToken,
                        relativePath = CloudSnapshotCodec.LATEST_POINTER_RELATIVE_PATH,
                        bytes = prepared.pointerJson.toByteArray(Charsets.UTF_8),
                        message = commitMessage
                    )
                } catch (error: Throwable) {
                    if (!latestPointerWriteStarted) {
                        cleanupPreparedSnapshot(
                            prepared = prepared,
                            client = client,
                            configuration = configuration,
                            accessToken = accessToken,
                            commitMessage = commitMessage
                        )
                    }
                    throw error
                }

                cleanupRemoteSnapshot(
                    snapshot = previousSnapshot,
                    client = client,
                    configuration = configuration,
                    accessToken = accessToken,
                    commitMessage = commitMessage,
                    excludePaths = buildSet {
                        add(prepared.manifestRelativePath)
                        prepared.chunks.forEach { add(it.descriptor.relativePath) }
                    }
                )
                val result = CloudSyncExecutionResult(
                    resultCode = CloudSyncResultCodes.SUCCESS,
                    message = appContext.getString(
                        R.string.cloud_sync_status_synced_format,
                        prepared.manifest.chunkCount,
                        prepared.manifest.compressedSizeBytes / 1024
                    ),
                    dataSha256 = logicalDataSha256,
                    performedUpload = true,
                    exportedAtEpochMillis = now
                )
                updateStatusAfterExecution(result, snapshot.status, configuration)
                return@withLock result
            } catch (error: Throwable) {
                val token = runCatching { secretStore.readAccessToken() }.getOrNull()
                val currentStatus = preferencesRepository.preferences.first().cloudSync.status
                val message = sanitizeErrorMessage(error.message, token)
                    .ifBlank { appContext.getString(R.string.cloud_sync_status_sync_failed) }
                updateFailureStatus(message, currentStatus)
                return@withLock CloudSyncExecutionResult(
                    resultCode = CloudSyncResultCodes.FAILED,
                    message = message
                )
            } finally {
                _syncInProgress.value = false
            }
        }
    }

    private suspend fun readCurrentRemoteSnapshot(
        client: RepoContentsClient,
        configuration: CloudSyncConfiguration,
        accessToken: String
    ): RemoteSnapshotRef? {
        val pointerBytes = client.readFile(
            configuration = configuration,
            accessToken = accessToken,
            relativePath = CloudSnapshotCodec.LATEST_POINTER_RELATIVE_PATH
        )
        if (pointerBytes != null) {
            val pointerJson = pointerBytes.toString(Charsets.UTF_8)
            val pointer = try {
                CloudSnapshotCodec.decodePointer(pointerJson)
            } catch (error: Throwable) {
                throw RemoteSnapshotCorruptionException("Invalid remote latest pointer", error)
            }
            if (pointer.snapshotId.isBlank() || pointer.manifestRelativePath.isBlank()) {
                throw RemoteSnapshotCorruptionException("Remote latest pointer is missing required fields")
            }
            val manifestBytes = client.readFile(
                configuration = configuration,
                accessToken = accessToken,
                relativePath = pointer.manifestRelativePath
            ) ?: throw RemoteSnapshotCorruptionException(
                "Missing remote manifest ${pointer.manifestRelativePath}"
            )
            val manifest = try {
                CloudSnapshotCodec.decodeManifest(manifestBytes.toString(Charsets.UTF_8))
            } catch (error: Throwable) {
                throw RemoteSnapshotCorruptionException(
                    "Invalid remote manifest ${pointer.manifestRelativePath}",
                    error
                )
            }
            if (manifest.snapshotId != pointer.snapshotId) {
                throw RemoteSnapshotCorruptionException(
                    "Remote latest pointer and manifest snapshotId mismatch"
                )
            }
            return RemoteSnapshotRef(
                manifestRelativePath = pointer.manifestRelativePath,
                manifest = manifest
            )
        }
        val legacyManifestBytes = client.readFile(
            configuration = configuration,
            accessToken = accessToken,
            relativePath = LEGACY_MANIFEST_RELATIVE_PATH
        ) ?: return null
        val legacyManifest = try {
            CloudSnapshotCodec.decodeManifest(legacyManifestBytes.toString(Charsets.UTF_8))
        } catch (error: Throwable) {
            throw RemoteSnapshotCorruptionException(
                "Invalid legacy remote manifest $LEGACY_MANIFEST_RELATIVE_PATH",
                error
            )
        }
        return RemoteSnapshotRef(
            manifestRelativePath = LEGACY_MANIFEST_RELATIVE_PATH,
            manifest = legacyManifest
        )
    }

    private suspend fun cleanupPreparedSnapshot(
        prepared: PreparedCloudSnapshot,
        client: RepoContentsClient,
        configuration: CloudSyncConfiguration,
        accessToken: String,
        commitMessage: String
    ) {
        cleanupPaths(
            relativePaths = buildList {
                add(prepared.manifestRelativePath)
                prepared.chunks.forEach { add(it.descriptor.relativePath) }
            },
            client = client,
            configuration = configuration,
            accessToken = accessToken,
            commitMessage = commitMessage
        )
    }

    private suspend fun cleanupRemoteSnapshot(
        snapshot: RemoteSnapshotRef?,
        client: RepoContentsClient,
        configuration: CloudSyncConfiguration,
        accessToken: String,
        commitMessage: String,
        excludePaths: Set<String> = emptySet()
    ) {
        if (snapshot == null) return
        cleanupPaths(
            relativePaths = buildList {
                add(snapshot.manifestRelativePath)
                snapshot.manifest.chunks.forEach { add(it.relativePath) }
            },
            client = client,
            configuration = configuration,
            accessToken = accessToken,
            commitMessage = commitMessage,
            excludePaths = excludePaths
        )
    }

    private suspend fun cleanupPaths(
        relativePaths: List<String>,
        client: RepoContentsClient,
        configuration: CloudSyncConfiguration,
        accessToken: String,
        commitMessage: String,
        excludePaths: Set<String> = emptySet()
    ) {
        relativePaths
            .distinct()
            .filter { it !in excludePaths }
            .forEach { relativePath ->
                runCatching {
                    client.deleteFile(
                        configuration = configuration,
                        accessToken = accessToken,
                        relativePath = relativePath,
                        message = commitMessage
                    )
                }
            }
    }

    private fun buildCommitMessage(
        trigger: CloudSyncTrigger,
        manifest: CloudSnapshotManifest
    ): String {
        val date = Instant.ofEpochMilli(manifest.exportedAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val triggerName = when (trigger) {
            CloudSyncTrigger.MANUAL -> "manual"
            CloudSyncTrigger.PERIODIC -> "daily"
            CloudSyncTrigger.APP_LAUNCH -> "catch-up"
            CloudSyncTrigger.RESTORE -> "restore"
        }
        return "timer sync: $triggerName snapshot $date"
    }

    private fun shouldAttempt(status: CloudSyncStatusSnapshot): Boolean {
        val today = LocalDate.now()
        val lastSuccessDate = status.lastSuccessAtEpochMillis?.toLocalDate()
        if (lastSuccessDate == today) return false
        val lastAttemptDate = status.lastAttemptAtEpochMillis?.toLocalDate()
        return lastAttemptDate != today || status.lastResultCode == CloudSyncResultCodes.FAILED
    }

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    private suspend fun updateStatusAfterExecution(
        result: CloudSyncExecutionResult,
        currentStatus: CloudSyncStatusSnapshot,
        configuration: CloudSyncConfiguration
    ) {
        val now = result.exportedAtEpochMillis ?: System.currentTimeMillis()
        preferencesRepository.updateCloudSyncStatus(
            currentStatus.copy(
                lastResultCode = result.resultCode,
                lastMessage = result.message,
                lastAttemptAtEpochMillis = now,
                lastSuccessAtEpochMillis = if (result.isSuccess) now else currentStatus.lastSuccessAtEpochMillis,
                lastSyncedDataSha256 = result.dataSha256 ?: currentStatus.lastSyncedDataSha256,
                lastSuccessfulTargetKey = if (result.isSuccess) configuration.identityKey() else currentStatus.lastSuccessfulTargetKey
            )
        )
    }

    private suspend fun updateRestoreStatus(manifest: CloudSnapshotManifest): CloudSyncExecutionResult {
        val now = System.currentTimeMillis()
        val current = preferencesRepository.preferences.first().cloudSync
        val result = CloudSyncExecutionResult(
            resultCode = CloudSyncResultCodes.RESTORED,
            message = appContext.getString(R.string.cloud_sync_status_restored),
            dataSha256 = manifest.logicalDataSha256,
            performedUpload = false,
            exportedAtEpochMillis = now
        )
        preferencesRepository.updateCloudSyncStatus(
            current.status.copy(
                lastResultCode = result.resultCode,
                lastMessage = result.message,
                lastAttemptAtEpochMillis = now,
                lastSuccessAtEpochMillis = current.status.lastSuccessAtEpochMillis,
                lastSyncedDataSha256 = manifest.logicalDataSha256,
                lastSuccessfulTargetKey = current.configuration.identityKey()
            )
        )
        return result
    }

    private suspend fun updateFailureStatus(
        message: String,
        currentStatus: CloudSyncStatusSnapshot
    ) {
        preferencesRepository.updateCloudSyncStatus(
            currentStatus.copy(
                lastResultCode = CloudSyncResultCodes.FAILED,
                lastMessage = message,
                lastAttemptAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    private fun sanitizeErrorMessage(message: String?, token: String?): String {
        val safe = message.orEmpty()
        return if (!token.isNullOrBlank()) safe.replace(token, "****") else safe
    }

    private companion object {
        const val LEGACY_MANIFEST_RELATIVE_PATH = "manifest.json"
    }
}
