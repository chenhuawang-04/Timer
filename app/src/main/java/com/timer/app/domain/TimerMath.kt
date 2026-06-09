package com.timer.app.domain

import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import kotlin.math.max
import kotlin.math.min

object TimerMath {
    private fun countdownTargetMillis(instance: TaskInstanceEntity): Long? {
        if (instance.type != TaskTypes.COUNT_DOWN) return null
        return if (PomodoroMath.isPomodoro(instance)) {
            PomodoroMath.totalProgramMillis(instance)
        } else {
            instance.targetDurationMillis
        }
    }

    fun effectiveElapsedMillis(
        state: TaskRuntimeStateEntity?,
        nowElapsedRealtimeMillis: Long
    ): Long {
        if (state == null) return 0L
        val base = state.accumulatedMillis.coerceAtLeast(0L)
        if (state.status != TaskStatuses.RUNNING) return base
        val started = state.startedAtElapsedRealtimeMillis ?: return base
        return base + max(0L, nowElapsedRealtimeMillis - started)
    }

    fun currentOpenSegmentMillis(
        state: TaskRuntimeStateEntity,
        nowElapsedRealtimeMillis: Long
    ): Long {
        if (state.status != TaskStatuses.RUNNING) return 0L
        val started = state.startedAtElapsedRealtimeMillis ?: return 0L
        return max(0L, nowElapsedRealtimeMillis - started)
    }

    fun remainingMillis(
        instance: TaskInstanceEntity,
        state: TaskRuntimeStateEntity?,
        nowElapsedRealtimeMillis: Long
    ): Long? {
        val target = countdownTargetMillis(instance) ?: return null
        return max(0L, target - effectiveElapsedMillis(state, nowElapsedRealtimeMillis))
    }

    fun displayMillis(
        instance: TaskInstanceEntity,
        state: TaskRuntimeStateEntity?,
        nowElapsedRealtimeMillis: Long
    ): Long {
        return if (instance.type == TaskTypes.COUNT_DOWN) {
            remainingMillis(instance, state, nowElapsedRealtimeMillis) ?: 0L
        } else {
            effectiveElapsedMillis(state, nowElapsedRealtimeMillis)
        }
    }

    fun isExpiredCountdown(
        instance: TaskInstanceEntity,
        state: TaskRuntimeStateEntity?,
        nowElapsedRealtimeMillis: Long
    ): Boolean {
        val target = countdownTargetMillis(instance) ?: return false
        return instance.type == TaskTypes.COUNT_DOWN &&
            state?.status == TaskStatuses.RUNNING &&
            effectiveElapsedMillis(state, nowElapsedRealtimeMillis) >= target
    }

    fun clampCountdownSegment(
        instance: TaskInstanceEntity,
        state: TaskRuntimeStateEntity,
        segmentMillis: Long
    ): Long {
        if (instance.type != TaskTypes.COUNT_DOWN) return max(0L, segmentMillis)
        val target = countdownTargetMillis(instance) ?: return max(0L, segmentMillis)
        val needed = max(0L, target - state.accumulatedMillis)
        return min(max(0L, segmentMillis), needed)
    }
}
