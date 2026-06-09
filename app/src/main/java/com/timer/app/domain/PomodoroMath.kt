package com.timer.app.domain

import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskStatuses
import kotlin.math.max
import kotlin.math.min

object PomodoroPhaseTypes {
    const val WORK = "WORK"
    const val BREAK = "BREAK"
    const val DONE = "DONE"
}

data class PomodoroPhase(
    val phaseType: String,
    val cycleNumber: Int,
    val totalCycles: Int,
    val phaseDurationMillis: Long,
    val phaseElapsedMillis: Long,
    val totalProgramMillis: Long,
    val totalElapsedMillis: Long
) {
    val phaseRemainingMillis: Long get() = max(0L, phaseDurationMillis - phaseElapsedMillis)
    val progress: Float get() = if (phaseDurationMillis <= 0L) 1f else (phaseElapsedMillis.toFloat() / phaseDurationMillis.toFloat()).coerceIn(0f, 1f)
}

object PomodoroMath {
    fun isPomodoro(instance: TaskInstanceEntity): Boolean = instance.sessionMode == com.timer.app.data.SessionModes.POMODORO

    fun totalProgramMillis(instance: TaskInstanceEntity): Long? {
        if (!isPomodoro(instance)) return instance.targetDurationMillis
        val work = (instance.pomodoroWorkMinutes ?: 25).coerceAtLeast(1) * 60_000L
        val breakMillis = (instance.pomodoroBreakMinutes ?: 5).coerceAtLeast(0) * 60_000L
        val cycles = (instance.pomodoroCycles ?: 4).coerceAtLeast(1)
        return work * cycles + breakMillis * max(0, cycles - 1)
    }

    fun phaseFor(instance: TaskInstanceEntity, state: TaskRuntimeStateEntity?, nowElapsedRealtimeMillis: Long): PomodoroPhase? {
        if (!isPomodoro(instance)) return null
        val totalTarget = totalProgramMillis(instance) ?: return null
        val totalElapsed = min(totalTarget, TimerMath.effectiveElapsedMillis(state, nowElapsedRealtimeMillis))
        val work = (instance.pomodoroWorkMinutes ?: 25).coerceAtLeast(1) * 60_000L
        val breakMillis = (instance.pomodoroBreakMinutes ?: 5).coerceAtLeast(0) * 60_000L
        val cycles = (instance.pomodoroCycles ?: 4).coerceAtLeast(1)
        if (instance.status == TaskStatuses.COMPLETED || totalElapsed >= totalTarget) {
            return PomodoroPhase(
                phaseType = PomodoroPhaseTypes.DONE,
                cycleNumber = cycles,
                totalCycles = cycles,
                phaseDurationMillis = 1L,
                phaseElapsedMillis = 1L,
                totalProgramMillis = totalTarget,
                totalElapsedMillis = totalTarget
            )
        }

        var remaining = totalElapsed
        for (cycle in 1..cycles) {
            if (remaining < work) {
                return PomodoroPhase(
                    phaseType = PomodoroPhaseTypes.WORK,
                    cycleNumber = cycle,
                    totalCycles = cycles,
                    phaseDurationMillis = work,
                    phaseElapsedMillis = remaining,
                    totalProgramMillis = totalTarget,
                    totalElapsedMillis = totalElapsed
                )
            }
            remaining -= work
            if (cycle == cycles) break
            if (remaining < breakMillis) {
                return PomodoroPhase(
                    phaseType = PomodoroPhaseTypes.BREAK,
                    cycleNumber = cycle,
                    totalCycles = cycles,
                    phaseDurationMillis = breakMillis,
                    phaseElapsedMillis = remaining,
                    totalProgramMillis = totalTarget,
                    totalElapsedMillis = totalElapsed
                )
            }
            remaining -= breakMillis
        }

        return PomodoroPhase(
            phaseType = PomodoroPhaseTypes.DONE,
            cycleNumber = cycles,
            totalCycles = cycles,
            phaseDurationMillis = 1L,
            phaseElapsedMillis = 1L,
            totalProgramMillis = totalTarget,
            totalElapsedMillis = totalTarget
        )
    }
}
