package com.timer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadCodecTest {
    @Test
    fun encodeAndDecodeRoundTripPreservesKeyFields() {
        val preferences = AppPreferencesSnapshot(themeMode = ThemeModes.DARK, accentPalette = AccentPalettes.VIOLET)
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
    }
}
