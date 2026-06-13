package com.timer.app.domain

import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity

data class InterruptedTask(
    val instance: TaskInstanceEntity,
    val state: TaskRuntimeStateEntity,
    val expectedElapsedMillis: Long,
    val recordedElapsedMillis: Long,
    val discrepancyMillis: Long
)

object RecoveryDetector {
    /**
     * Detect tasks that were interrupted (app killed while timer was running)
     * Returns tasks where the wall-clock time suggests more time should have elapsed
     */
    fun detectInterruptedTasks(
        instances: List<TaskInstanceEntity>,
        states: List<TaskRuntimeStateEntity>,
        nowEpochMillis: Long,
        nowElapsedRealtimeMillis: Long,
        thresholdSeconds: Int = 30
    ): List<InterruptedTask> {
        val statesById = states.associateBy { it.instanceId }
        val interrupted = mutableListOf<InterruptedTask>()

        instances.forEach { instance ->
            val state = statesById[instance.id] ?: return@forEach

            // Only check RUNNING tasks
            if (state.status != "RUNNING") return@forEach

            // Calculate what the elapsed time should be based on wall clock
            val startedAt = state.startedAtEpochMillis ?: return@forEach
            val expectedElapsed = state.accumulatedMillis + (nowEpochMillis - startedAt)

            // Calculate what we actually have recorded
            val recordedElapsed = TimerMath.effectiveElapsedMillis(state, nowElapsedRealtimeMillis)

            // If there's a significant discrepancy, this task was likely interrupted
            val discrepancy = expectedElapsed - recordedElapsed
            val thresholdMillis = thresholdSeconds * 1000L

            if (discrepancy > thresholdMillis) {
                interrupted.add(
                    InterruptedTask(
                        instance = instance,
                        state = state,
                        expectedElapsedMillis = expectedElapsed,
                        recordedElapsedMillis = recordedElapsed,
                        discrepancyMillis = discrepancy
                    )
                )
            }
        }

        return interrupted
    }
}
