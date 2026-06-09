# Implementation Summary

Trace: `trc_timer_app_20260608_02`

## Implemented scope

The app has been refactored from a single timer-task model into a daily task-instance model that supports multiple tasks per day and three task types.

### Data model

- Room schema version `3` with destructive migration allowed for this development-stage rewrite.
- New tables/entities:
  - `task_template` / `TaskTemplateEntity`
  - `task_instance` / `TaskInstanceEntity`
  - `task_runtime_state` / `TaskRuntimeStateEntity`
  - `task_session` / `TaskSessionEntity`
  - `task_event_log` / `TaskEventLogEntity`
- Runtime/session/event records now reference `instanceId`; daily statistics are based on task instances plus immutable sessions.
- Event sources distinguish manual completion, countdown auto-completion, recovered auto-completion, deadline misses, and recovered deadline misses.
- Archiving is represented by `TaskInstanceEntity.archived` and `archivedAtEpochMillis`; it hides a task from the active list without changing its terminal status or removing it from historical statistics.

### Task behavior

- `COUNT_UP`
  - Can be started, paused, resumed, cancelled, archived.
  - Never auto-completes.
  - User manually marks completion.
  - Completion writes the currently open measured segment before finalizing.
- `COUNT_DOWN`
  - Can be started, paused, resumed, cancelled, archived.
  - Automatically completes when measured elapsed time reaches target duration.
  - Foreground service checks active countdowns while running.
  - Boot/process recovery can mark overdue countdowns completed using wall-clock fallback.
- `TIME_WINDOW`
  - Stores absolute `plannedStartEpochMillis` and `plannedEndEpochMillis` derived from `HH:mm` and local date.
  - Supports same-day and cross-midnight windows by treating `end <= start` as next-day end.
  - Moves from `PLANNED` to `READY` when the window opens.
  - Can be manually completed inside `[start, end)`.
  - Becomes `MISSED` when `plannedEndEpochMillis <= now` and it was not completed/cancelled.

### Background and recovery

- `RoomTimerRepository.reconcileDeadlines()` is the single transactional correctness path for:
  - promoting opened time windows to `READY`;
  - marking expired time windows as `MISSED`;
  - completing expired running countdowns.
- Reconciliation runs from:
  - app startup;
  - dashboard ViewModel periodic loop;
  - foreground service loop;
  - boot/package-replaced receiver;
  - time-window alarm receiver.
- `DeadlineAlarmScheduler` schedules low-overhead `AlarmManager.setAndAllowWhileIdle` wake-up hints at time-window start/end without requiring exact-alarm permission.
- `BootCompletedReceiver` performs best-effort running-timer recovery, deadline reconciliation, alarm rescheduling, and foreground-service restart if needed.

### UI

- Jetpack Compose + Material 3 dashboard.
- Create dialog supports:
  - task name;
  - count-up;
  - countdown duration in minutes;
  - time-window start/end `HH:mm` inputs with validation.
- Task cards show type, status, timer display, countdown progress, window status text, and context-appropriate actions.
- Dashboard shows hero statistics, notification-permission warning, foreground-service degradation warning, last-seven-days chart, empty state, and task list.

### Statistics

- `StatsCalculator` separates:
  - actual tracked duration from `task_session` and open running timed states;
  - planned/completed/missed/cancelled task facts from `task_instance`.
- Supports:
  - today/week/month tracked duration;
  - planned/completed/missed/cancelled counts;
  - count-up completed count;
  - countdown auto/recovered completed count;
  - time-window completed/missed counts;
  - time-window completion rate;
  - planned/completed/missed window duration;
  - last seven days;
  - top tracked tasks.
- Cross-midnight measured sessions are split by local date.

### Tests updated

- `TimerMathTest`
  - running count-up elapsed math;
  - paused state math;
  - countdown remaining/expiry;
  - count-up never expiring as countdown;
  - countdown segment clamp.
- `StatsCalculatorTest`
  - cross-midnight splitting;
  - open running state inclusion;
  - immutable session statistics;
  - partial vs completed recovered countdown semantics;
  - auto-completed countdown statistics;
  - multiple daily instances and manual count-up completion;
  - time-window completed/missed statistics and planned-duration accounting.

## CI verification

Local Gradle build/test was intentionally not run per project instruction. GitHub Actions is configured to run:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Local static checks performed during implementation handoff:

- XML parse smoke check for `app/src/main/**/*.xml`.
- Kotlin source rough checks for BOM, brace mismatch, and suspicious placeholders.
- Stale source-reference grep for removed v1 entity/type names.

## Compatibility and migration note

The user explicitly allowed a breaking rewrite with no backward compatibility requirement. The app uses Room version `3` with `fallbackToDestructiveMigration()` for this stage. Before public release, replace destructive migration with a real migration if existing user data must be retained.

## Known production follow-ups

- Move hard-coded UI strings into Android string resources and add Chinese localization resources.
- Add instrumentation tests for Room transactions, receivers, AlarmManager scheduling, and foreground service lifecycle.
- Decide whether exact-alarm permission is warranted for a product tier that requires stricter deadline promptness; current implementation keeps correctness through reconciliation and uses inexact wake-up hints for battery health.
- Add data export/import and a release-grade migration policy before public release.
- Validate foreground-service `specialUse` declaration against final target SDK and Play release policy.
