package com.timer.app.domain

import com.timer.app.data.RepeatModes
import com.timer.app.data.SessionModes
import com.timer.app.data.TaskPriorities
import com.timer.app.data.TaskTemplateEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskRecurrenceTest {
    @Test
    fun weekdaysMatchWeekdaysOnly() {
        val template = template(repeatMode = RepeatModes.WEEKDAYS, anchorDate = "2026-06-01")

        assertTrue(TaskRecurrence.matches(template, LocalDate.of(2026, 6, 8))) // Monday
        assertFalse(TaskRecurrence.matches(template, LocalDate.of(2026, 6, 7))) // Sunday
    }

    @Test
    fun customDaysUseEncodedWeekdaySet() {
        val template = template(
            repeatMode = RepeatModes.CUSTOM_DAYS,
            repeatDaysCsv = "1,3,5",
            anchorDate = "2026-06-01"
        )

        assertTrue(TaskRecurrence.matches(template, LocalDate.of(2026, 6, 8)))
        assertTrue(TaskRecurrence.matches(template, LocalDate.of(2026, 6, 10)))
        assertFalse(TaskRecurrence.matches(template, LocalDate.of(2026, 6, 9)))
    }

    private fun template(
        repeatMode: String,
        repeatDaysCsv: String? = null,
        anchorDate: String
    ) = TaskTemplateEntity(
        id = "template",
        name = "Read",
        type = com.timer.app.data.TaskTypes.COUNT_UP,
        defaultTargetDurationMillis = null,
        preferredStartMinuteOfDay = 540,
        defaultStartMinuteOfDay = null,
        defaultEndMinuteOfDay = null,
        colorArgb = 0xFF2563EB,
        categoryId = null,
        projectName = null,
        tagsCsv = null,
        note = null,
        priority = TaskPriorities.MEDIUM,
        anchorDate = anchorDate,
        repeatMode = repeatMode,
        repeatDaysCsv = repeatDaysCsv,
        repeatInterval = 1,
        remindersEnabled = false,
        remindAtStart = false,
        remindBeforeEndMinutes = null,
        remindAtDeadline = false,
        countTowardGoals = true,
        sessionMode = SessionModes.STANDARD,
        pomodoroWorkMinutes = null,
        pomodoroBreakMinutes = null,
        pomodoroCycles = null,
        autoGenerateAheadDays = 30,
        archived = false,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L
    )
}
