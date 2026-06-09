package com.timer.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import com.timer.app.sync.CloudSyncConfiguration
import com.timer.app.sync.CloudSyncDefaults
import com.timer.app.sync.CloudSyncPreferencesSnapshot
import com.timer.app.sync.CloudSyncProviders
import com.timer.app.sync.CloudSyncResultCodes
import com.timer.app.sync.CloudSyncStatusSnapshot
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

object ThemeModes {
    const val SYSTEM = "SYSTEM"
    const val LIGHT = "LIGHT"
    const val DARK = "DARK"
}

object AccentPalettes {
    const val BLUE = "BLUE"
    const val VIOLET = "VIOLET"
    const val EMERALD = "EMERALD"
    const val SUNSET = "SUNSET"
}

object DashboardLayouts {
    const val TODAY_FIRST = "TODAY_FIRST"
    const val FOCUS_FIRST = "FOCUS_FIRST"
    const val STATS_FIRST = "STATS_FIRST"
}

object TaskSortModes {
    const val SMART = "SMART"
    const val PRIORITY = "PRIORITY"
    const val START_TIME = "START_TIME"
    const val CREATED_AT = "CREATED_AT"
}

object EnergyModes {
    const val BALANCED = "BALANCED"
    const val RELIABLE = "RELIABLE"
    const val LOW_POWER = "LOW_POWER"
}

data class AppPreferencesSnapshot(
    val themeMode: String = ThemeModes.SYSTEM,
    val accentPalette: String = AccentPalettes.BLUE,
    val dynamicColor: Boolean = true,
    val dashboardLayout: String = DashboardLayouts.TODAY_FIRST,
    val sortMode: String = TaskSortModes.SMART,
    val showCompletedTasks: Boolean = true,
    val energyMode: String = EnergyModes.BALANCED,
    val keepScreenOnInFocus: Boolean = true,
    val lastBackupAtEpochMillis: Long? = null,
    val lastSelectedTab: String = "TODAY",
    val cloudSync: CloudSyncPreferencesSnapshot = CloudSyncPreferencesSnapshot()
)

fun AppPreferencesSnapshot.toPortableBackup(): AppPreferencesSnapshot = copy(
    lastBackupAtEpochMillis = null,
    lastSelectedTab = "TODAY",
    cloudSync = cloudSync.copy(status = CloudSyncStatusSnapshot())
)

class AppPreferencesRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { appContext.preferencesDataStoreFile("app_prefs.preferences_pb") }
    )

    val preferences: Flow<AppPreferencesSnapshot> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppPreferencesSnapshot(
                themeMode = prefs[Keys.themeMode] ?: ThemeModes.SYSTEM,
                accentPalette = prefs[Keys.accentPalette] ?: AccentPalettes.BLUE,
                dynamicColor = prefs[Keys.dynamicColor] ?: true,
                dashboardLayout = prefs[Keys.dashboardLayout] ?: DashboardLayouts.TODAY_FIRST,
                sortMode = prefs[Keys.sortMode] ?: TaskSortModes.SMART,
                showCompletedTasks = prefs[Keys.showCompletedTasks] ?: true,
                energyMode = prefs[Keys.energyMode] ?: EnergyModes.BALANCED,
                keepScreenOnInFocus = prefs[Keys.keepScreenOnInFocus] ?: true,
                lastBackupAtEpochMillis = prefs[Keys.lastBackupAt],
                lastSelectedTab = prefs[Keys.lastSelectedTab] ?: "TODAY",
                cloudSync = readCloudSync(prefs)
            )
        }

    suspend fun updateThemeMode(value: String) = writeString(Keys.themeMode, value)

    suspend fun updateAccentPalette(value: String) = writeString(Keys.accentPalette, value)

    suspend fun updateDynamicColor(value: Boolean) = writeBoolean(Keys.dynamicColor, value)

    suspend fun updateDashboardLayout(value: String) = writeString(Keys.dashboardLayout, value)

    suspend fun updateSortMode(value: String) = writeString(Keys.sortMode, value)

    suspend fun updateShowCompletedTasks(value: Boolean) = writeBoolean(Keys.showCompletedTasks, value)

    suspend fun updateEnergyMode(value: String) = writeString(Keys.energyMode, value)

    suspend fun updateKeepScreenOnInFocus(value: Boolean) = writeBoolean(Keys.keepScreenOnInFocus, value)

    suspend fun updateLastBackupAt(value: Long?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(Keys.lastBackupAt) else prefs[Keys.lastBackupAt] = value
        }
    }

    suspend fun updateLastSelectedTab(value: String) = writeString(Keys.lastSelectedTab, value)

    suspend fun updateCloudSyncConfiguration(configuration: CloudSyncConfiguration) {
        store.edit { prefs ->
            writeCloudSyncConfiguration(prefs, configuration.normalized())
        }
    }

    suspend fun updateCloudSyncStatus(status: CloudSyncStatusSnapshot) {
        store.edit { prefs ->
            writeCloudSyncStatus(prefs, status)
        }
    }

    suspend fun importSnapshot(
        snapshot: AppPreferencesSnapshot,
        importCloudSyncConfiguration: Boolean = true
    ) {
        store.edit { prefs ->
            prefs[Keys.themeMode] = snapshot.themeMode
            prefs[Keys.accentPalette] = snapshot.accentPalette
            prefs[Keys.dynamicColor] = snapshot.dynamicColor
            prefs[Keys.dashboardLayout] = snapshot.dashboardLayout
            prefs[Keys.sortMode] = snapshot.sortMode
            prefs[Keys.showCompletedTasks] = snapshot.showCompletedTasks
            prefs[Keys.energyMode] = snapshot.energyMode
            prefs[Keys.keepScreenOnInFocus] = snapshot.keepScreenOnInFocus
            snapshot.lastBackupAtEpochMillis?.let { prefs[Keys.lastBackupAt] = it } ?: prefs.remove(Keys.lastBackupAt)
            prefs[Keys.lastSelectedTab] = snapshot.lastSelectedTab
            if (importCloudSyncConfiguration) {
                writeCloudSyncConfiguration(prefs, snapshot.cloudSync.configuration.normalized())
                writeCloudSyncStatus(prefs, snapshot.cloudSync.status)
            }
        }
    }

    private suspend fun writeString(key: Preferences.Key<String>, value: String) {
        store.edit { prefs -> prefs[key] = value }
    }

    private suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { prefs -> prefs[key] = value }
    }

    private fun readCloudSync(prefs: Preferences): CloudSyncPreferencesSnapshot {
        return CloudSyncPreferencesSnapshot(
            configuration = CloudSyncConfiguration(
                autoSyncEnabled = prefs[Keys.cloudSyncEnabled] ?: false,
                provider = prefs[Keys.cloudSyncProvider] ?: CloudSyncProviders.GITEE,
                repositoryOwner = prefs[Keys.cloudSyncRepositoryOwner] ?: "",
                repositoryName = prefs[Keys.cloudSyncRepositoryName] ?: "",
                branch = prefs[Keys.cloudSyncBranch] ?: CloudSyncDefaults.DEFAULT_BRANCH,
                basePath = prefs[Keys.cloudSyncBasePath] ?: CloudSyncDefaults.DEFAULT_BASE_PATH,
                wifiOnly = prefs[Keys.cloudSyncWifiOnly] ?: true
            ).normalized(),
            status = CloudSyncStatusSnapshot(
                lastResultCode = prefs[Keys.cloudSyncLastResultCode] ?: CloudSyncResultCodes.IDLE,
                lastMessage = prefs[Keys.cloudSyncLastMessage],
                lastAttemptAtEpochMillis = prefs[Keys.cloudSyncLastAttemptAt],
                lastSuccessAtEpochMillis = prefs[Keys.cloudSyncLastSuccessAt],
                lastSyncedDataSha256 = prefs[Keys.cloudSyncLastDataSha],
                lastSuccessfulTargetKey = prefs[Keys.cloudSyncLastTargetKey]
            )
        )
    }

    private fun writeCloudSyncConfiguration(
        prefs: MutablePreferences,
        configuration: CloudSyncConfiguration
    ) {
        prefs[Keys.cloudSyncEnabled] = configuration.autoSyncEnabled
        prefs[Keys.cloudSyncProvider] = configuration.provider
        prefs[Keys.cloudSyncRepositoryOwner] = configuration.repositoryOwner
        prefs[Keys.cloudSyncRepositoryName] = configuration.repositoryName
        prefs[Keys.cloudSyncBranch] = configuration.branch
        prefs[Keys.cloudSyncBasePath] = configuration.basePath
        prefs[Keys.cloudSyncWifiOnly] = configuration.wifiOnly
    }

    private fun writeCloudSyncStatus(
        prefs: MutablePreferences,
        status: CloudSyncStatusSnapshot
    ) {
        prefs[Keys.cloudSyncLastResultCode] = status.lastResultCode
        status.lastMessage?.let { prefs[Keys.cloudSyncLastMessage] = it } ?: prefs.remove(Keys.cloudSyncLastMessage)
        status.lastAttemptAtEpochMillis?.let { prefs[Keys.cloudSyncLastAttemptAt] = it } ?: prefs.remove(Keys.cloudSyncLastAttemptAt)
        status.lastSuccessAtEpochMillis?.let { prefs[Keys.cloudSyncLastSuccessAt] = it } ?: prefs.remove(Keys.cloudSyncLastSuccessAt)
        status.lastSyncedDataSha256?.let { prefs[Keys.cloudSyncLastDataSha] = it } ?: prefs.remove(Keys.cloudSyncLastDataSha)
        status.lastSuccessfulTargetKey?.let { prefs[Keys.cloudSyncLastTargetKey] = it } ?: prefs.remove(Keys.cloudSyncLastTargetKey)
    }

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val accentPalette = stringPreferencesKey("accent_palette")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val dashboardLayout = stringPreferencesKey("dashboard_layout")
        val sortMode = stringPreferencesKey("sort_mode")
        val showCompletedTasks = booleanPreferencesKey("show_completed_tasks")
        val energyMode = stringPreferencesKey("energy_mode")
        val keepScreenOnInFocus = booleanPreferencesKey("keep_screen_on_in_focus")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val lastSelectedTab = stringPreferencesKey("last_selected_tab")
        val cloudSyncEnabled = booleanPreferencesKey("cloud_sync_enabled")
        val cloudSyncProvider = stringPreferencesKey("cloud_sync_provider")
        val cloudSyncRepositoryOwner = stringPreferencesKey("cloud_sync_repository_owner")
        val cloudSyncRepositoryName = stringPreferencesKey("cloud_sync_repository_name")
        val cloudSyncBranch = stringPreferencesKey("cloud_sync_branch")
        val cloudSyncBasePath = stringPreferencesKey("cloud_sync_base_path")
        val cloudSyncWifiOnly = booleanPreferencesKey("cloud_sync_wifi_only")
        val cloudSyncLastResultCode = stringPreferencesKey("cloud_sync_last_result_code")
        val cloudSyncLastMessage = stringPreferencesKey("cloud_sync_last_message")
        val cloudSyncLastAttemptAt = longPreferencesKey("cloud_sync_last_attempt_at")
        val cloudSyncLastSuccessAt = longPreferencesKey("cloud_sync_last_success_at")
        val cloudSyncLastDataSha = stringPreferencesKey("cloud_sync_last_data_sha")
        val cloudSyncLastTargetKey = stringPreferencesKey("cloud_sync_last_target_key")
    }
}
