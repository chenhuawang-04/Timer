# Timer

Android native timer app for robust daily timing, countdown, and scheduled time-window tasks.

## Current feature set

- Create multiple task instances per day.
- Three task types:
  - `COUNT_UP`: count upward from user start; user manually marks the task completed.
  - `COUNT_DOWN`: count down from a configured duration; completes automatically when elapsed time reaches the target.
  - `TIME_WINDOW`: configure a start and end time such as `09:00-09:30`; user must manually complete inside the window, otherwise the task is marked `MISSED`.
- Durable local state with Room.
- Low-overhead timing: UI/service derive display values from monotonic elapsed time without per-second database writes.
- Foreground service while count-up/countdown tasks are actively running.
- AlarmManager wake-up hints for time-window start/end reconciliation without requiring exact-alarm permission.
- Boot/package-replaced recovery for running timers and missed deadlines.
- Local-first statistics: today, week, month, last seven days, task completion/missed counts, time-window completion rate, planned window duration, and top tracked tasks.
- Modern Kotlin + Jetpack Compose + Material 3 UI.

## Build and test

This project is intended to be built and verified by GitHub Actions CI.

CI workflow command:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Per project instruction, local Android SDK/Gradle test/build commands are not run during implementation handoff. Static source/XML checks may be run locally; GitHub CI is the authoritative build/test gate.

## Key architecture

- `TaskTemplateEntity`: reusable optional task definitions.
- `TaskInstanceEntity`: the actual daily task fact used for planning, completion, missed/cancelled state, and statistics.
- Task instance archive/hide state is stored separately from status so archived completed/missed tasks remain in historical statistics.
- `TaskRuntimeStateEntity`: durable runtime state for `COUNT_UP` and `COUNT_DOWN` instances.
- `TaskSessionEntity`: immutable measured intervals used for tracked-duration statistics.
- `TaskEventLogEntity`: audit/debug events for create/start/pause/resume/complete/miss/cancel/recover/archive.
- `RoomTimerRepository`: transactional state machine and deadline reconciler.
- `DeadlineAlarmScheduler` / `DeadlineAlarmReceiver`: low-overhead time-window reconciliation wake-up hints.
- `TimerForegroundService`: user-visible long-running service for active timed tasks.
- `BootCompletedReceiver`: reboot and package-replacement recovery.
- `StatsCalculator`: local-date-aware daily/weekly/monthly stats with cross-day splitting.

See:

- `docs/daily-multi-task-and-time-window-design.md`
- `docs/implementation-summary.md`
- `docs/test-plan.md`
- `docs/adr/2026-06-08-daily-instances-and-time-window-tasks.md`
