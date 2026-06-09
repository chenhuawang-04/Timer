package com.timer.app.sync

import java.util.concurrent.TimeUnit

object CloudSyncProviders {
    const val GITEE = "GITEE"
    const val GITHUB = "GITHUB"
}

object CloudSyncResultCodes {
    const val IDLE = "IDLE"
    const val SUCCESS = "SUCCESS"
    const val SKIPPED = "SKIPPED"
    const val FAILED = "FAILED"
    const val INCOMPLETE = "INCOMPLETE"
    const val RESTORED = "RESTORED"
}

object CloudSyncDefaults {
    const val DEFAULT_BRANCH = "main"
    const val DEFAULT_BASE_PATH = "timer-sync"
    const val JOB_ID = 42107
    val PERIODIC_INTERVAL_MILLIS: Long = TimeUnit.DAYS.toMillis(1)
    val PERIODIC_FLEX_MILLIS: Long = TimeUnit.HOURS.toMillis(6)
}

data class CloudSyncConfiguration(
    val autoSyncEnabled: Boolean = false,
    val provider: String = CloudSyncProviders.GITEE,
    val repositoryOwner: String = "",
    val repositoryName: String = "",
    val branch: String = CloudSyncDefaults.DEFAULT_BRANCH,
    val basePath: String = CloudSyncDefaults.DEFAULT_BASE_PATH,
    val wifiOnly: Boolean = true
) {
    fun normalized(): CloudSyncConfiguration = copy(
        provider = provider.ifBlank { CloudSyncProviders.GITEE },
        repositoryOwner = repositoryOwner.trim(),
        repositoryName = repositoryName.trim(),
        branch = branch.trim().ifBlank { CloudSyncDefaults.DEFAULT_BRANCH },
        basePath = basePath.trim().trim('/').ifBlank { CloudSyncDefaults.DEFAULT_BASE_PATH }
    )

    fun identityKey(): String {
        val normalized = normalized()
        return listOf(
            normalized.provider,
            normalized.repositoryOwner,
            normalized.repositoryName,
            normalized.branch,
            normalized.basePath
        ).joinToString("|")
    }

    fun isComplete(hasToken: Boolean): Boolean {
        val normalized = normalized()
        return normalized.repositoryOwner.isNotBlank() &&
            normalized.repositoryName.isNotBlank() &&
            normalized.branch.isNotBlank() &&
            normalized.basePath.isNotBlank() &&
            hasToken
    }
}

data class CloudSyncStatusSnapshot(
    val lastResultCode: String = CloudSyncResultCodes.IDLE,
    val lastMessage: String? = null,
    val lastAttemptAtEpochMillis: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
    val lastSyncedDataSha256: String? = null,
    val lastSuccessfulTargetKey: String? = null
)

data class CloudSyncPreferencesSnapshot(
    val configuration: CloudSyncConfiguration = CloudSyncConfiguration(),
    val status: CloudSyncStatusSnapshot = CloudSyncStatusSnapshot()
)

data class CloudSyncCredentialSnapshot(
    val hasToken: Boolean = false
)

data class CloudSyncSettingsDraft(
    val autoSyncEnabled: Boolean,
    val provider: String,
    val repositoryOwner: String,
    val repositoryName: String,
    val branch: String,
    val basePath: String,
    val wifiOnly: Boolean,
    val tokenInput: String? = null
) {
    fun toConfiguration(): CloudSyncConfiguration = CloudSyncConfiguration(
        autoSyncEnabled = autoSyncEnabled,
        provider = provider,
        repositoryOwner = repositoryOwner,
        repositoryName = repositoryName,
        branch = branch,
        basePath = basePath,
        wifiOnly = wifiOnly
    ).normalized()
}

enum class CloudSyncTrigger {
    MANUAL,
    PERIODIC,
    APP_LAUNCH,
    RESTORE
}

data class CloudSyncExecutionResult(
    val resultCode: String,
    val message: String,
    val dataSha256: String? = null,
    val performedUpload: Boolean = false,
    val exportedAtEpochMillis: Long? = null
) {
    val isSuccess: Boolean
        get() = resultCode in setOf(
            CloudSyncResultCodes.SUCCESS,
            CloudSyncResultCodes.SKIPPED,
            CloudSyncResultCodes.RESTORED
        )
}

