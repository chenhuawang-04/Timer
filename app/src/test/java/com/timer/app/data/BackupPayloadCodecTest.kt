package com.timer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.timer.app.sync.CloudSyncConfiguration
import com.timer.app.sync.CloudSyncPreferencesSnapshot
import com.timer.app.sync.CloudSyncProviders

class BackupPayloadCodecTest {
    @Test
    fun encodeAndDecodeRoundTripPreservesKeyFields() {
        val preferences = AppPreferencesSnapshot(
            themeMode = ThemeModes.DARK,
            accentPalette = AccentPalettes.VIOLET,
            cloudSync = CloudSyncPreferencesSnapshot(
                configuration = CloudSyncConfiguration(
                    autoSyncEnabled = true,
                    provider = CloudSyncProviders.GITEE,
                    repositoryOwner = "demo-owner",
                    repositoryName = "timer-sync",
                    branch = "main",
                    basePath = "timer-sync",
                    wifiOnly = true
                )
            )
        )
        val repository = RepositoryExportData(
            categories = listOf(TaskCategoryEntity("c1", "Work", 0xFF2563EB, false, 1L, 1L)),
            goals = listOf(GoalEntity("g1", "Goal", GoalScopes.DAILY, GoalMetricTypes.COMPLETED_TASKS, 3L, null, null, true, 1L, 1L)),
            templates = emptyList(),
            instances = emptyList(),
            states = emptyList(),
            sessions = emptyList(),
            events = emptyList()
        )

        val json = BackupPayloadCodec.encode(preferences, repository, exportedAtEpochMillis = 99L)
        val decoded = BackupPayloadCodec.decode(json)

        assertEquals(99L, decoded.exportedAtEpochMillis)
        assertEquals(ThemeModes.DARK, decoded.preferences.themeMode)
        assertEquals("Work", decoded.repository.categories.single().name)
        assertTrue(decoded.repository.goals.single().active)
        assertTrue(decoded.containsCloudSyncConfiguration)
        assertEquals(CloudSyncProviders.GITEE, decoded.preferences.cloudSync.configuration.provider)
        assertEquals("demo-owner", decoded.preferences.cloudSync.configuration.repositoryOwner)
    }

    @Test
    fun decodeLegacyPayloadWithoutCloudSyncKeepsFieldAbsenceSignal() {
        val json = """
            {
              "schemaVersion": 1,
              "exportedAtEpochMillis": 321,
              "preferences": {
                "themeMode": "LIGHT",
                "accentPalette": "BLUE",
                "dynamicColor": true,
                "dashboardLayout": "TODAY_FIRST",
                "sortMode": "SMART",
                "showCompletedTasks": true,
                "energyMode": "BALANCED",
                "keepScreenOnInFocus": true,
                "lastSelectedTab": "TODAY"
              },
              "categories": [],
              "goals": [],
              "templates": [],
              "instances": [],
              "states": [],
              "sessions": [],
              "events": []
            }
        """.trimIndent()

        val decoded = BackupPayloadCodec.decode(json)

        assertEquals(321L, decoded.exportedAtEpochMillis)
        assertFalse(decoded.containsCloudSyncConfiguration)
        assertEquals(ThemeModes.LIGHT, decoded.preferences.themeMode)
        assertEquals("", decoded.preferences.cloudSync.configuration.repositoryOwner)
    }
}
