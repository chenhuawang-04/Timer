package com.timer.app.domain

import com.timer.app.data.RepeatModes
import com.timer.app.data.TaskTemplateEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

object TaskRecurrence {
    fun encodeDays(days: Set<DayOfWeek>): String? =
        days.sortedBy { it.value }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.value.toString() }

    fun decodeDays(csv: String?): Set<DayOfWeek> =
        csv.orEmpty()
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull { value -> DayOfWeek.values().firstOrNull { it.value == value } }
            .toSet()

    fun matches(template: TaskTemplateEntity, date: LocalDate): Boolean {
        val anchor = LocalDate.parse(template.anchorDate)
        if (date.isBefore(anchor)) return false
        val interval = max(1, template.repeatInterval)
        return when (template.repeatMode) {
            RepeatModes.NONE -> date == anchor
            RepeatModes.DAILY -> ChronoUnit.DAYS.between(anchor, date) % interval == 0L
            RepeatModes.WEEKLY -> ChronoUnit.WEEKS.between(anchor, date) % interval == 0L &&
                date.dayOfWeek == anchor.dayOfWeek
            RepeatModes.WEEKDAYS -> date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            RepeatModes.CUSTOM_DAYS -> {
                val selected = decodeDays(template.repeatDaysCsv)
                date.dayOfWeek in selected
            }
            RepeatModes.MONTHLY -> {
                val monthsBetween = ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), date.withDayOfMonth(1))
                monthsBetween % interval == 0L && date.dayOfMonth == anchor.dayOfMonth
            }
            else -> false
        }
    }
}
