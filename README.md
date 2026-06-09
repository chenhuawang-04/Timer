# Timer

Android native timer app for durable daily planning, timing, routines, statistics, and local-first recovery.

## Current feature set

- Create multiple task instances per day.
- Three task types:
  - `COUNT_UP`: count upward from user start; user manually marks the task completed.
  - `COUNT_DOWN`: count down from a configured duration; completes automatically when elapsed time reaches the target.
  - `TIME_WINDOW`: configure a start and end time such as `09:00-09:30`; user must manually complete inside the window, otherwise the task is marked `MISSED`.
- Recurring routine templates with repeat rules:
  - once
  - daily
  - weekdays
  - weekly
  - custom weekdays
  - monthly
- Categories, projects, tags, priorities, notes, and result notes.
- Reminders:
  - task-start reminders
  - pre-end reminders
  - deadline reminders
  - countdown completion alerts
- Pomodoro countdown mode with configurable work/break/cycle settings.
- Local-first statistics and insights:
  - today / week / month tracked time
  - completion rates
  - streaks
  - daily score
  - category and project breakdowns
  - recent history and audit log
- Calendar view for day-level planning and outcomes.
- Focus mode for active tasks.
- Widgets, launcher shortcuts, share-to-create, and quick-settings tile.
- JSON backup export/import for manual multi-device transfer and recovery.
- Chinese string resources and appearance customization.
- Durable local state with Room + DataStore.
- Low-overhead timing: UI/service derive display values from monotonic elapsed time without per-second database writes.
- Foreground service while count-up/countdown tasks are actively running.
- AlarmManager wake-up hints for reminders, countdown completion, and time-window reconciliation without requiring exact-alarm permission.
- Boot/package-replaced recovery for running timers and missed deadlines.
- Modern Kotlin + Jetpack Compose + Material 3 UI.

## Build and test

This project is intended to be built and verified by GitHub Actions CI.

CI workflow command:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Per project instruction, local Android SDK/Gradle test/build commands are not run during implementation handoff. Static source/XML checks may be run locally; GitHub CI is the authoritative build/test gate.

## GitHub release workflow

This repository also includes an `Android Release` GitHub Actions workflow.

Release triggers:

- Push a tag such as `v0.1.0`
- Or run the workflow manually with `workflow_dispatch`

Release behavior:

- Runs unit tests
- Builds the release APK with `assembleRelease`
- Uploads the APK as a workflow artifact
- Publishes the APK to GitHub Releases

Optional signing secrets used by the release workflow:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

If the four signing secrets are present, the release build is signed with that keystore. If they are absent, the workflow still builds and publishes an unsigned release APK.

## Key architecture

- `TaskCategoryEntity`: reusable category definitions for color and grouping.
- `GoalEntity`: persisted daily / weekly / monthly productivity goals.
- `TaskTemplateEntity`: reusable routine definition, reminders, repeat rules, pomodoro settings, and defaults.
- `TaskInstanceEntity`: the actual daily task fact used for planning, completion, missed/cancelled state, analytics, and history.
- Task instance archive/hide state is stored separately from status so archived completed/missed tasks remain in historical statistics.
- `TaskRuntimeStateEntity`: durable runtime state for `COUNT_UP` and `COUNT_DOWN` instances.
- `TaskSessionEntity`: immutable measured intervals used for tracked-duration statistics.
- `TaskEventLogEntity`: audit/debug events for create/start/pause/resume/complete/miss/cancel/recover/archive/note updates.
- `RoomTimerRepository`: transactional task state machine, routine generation, import/export boundary, and reconciliation core.
- `AppPreferencesRepository`: DataStore-backed appearance, layout, energy, and interaction preferences.
- `TimerAutomationCoordinator`: startup / alarm / mutation orchestration for generation, reconciliation, widget refresh, and foreground-service continuation.
- `DeadlineAlarmScheduler` / `DeadlineAlarmReceiver`: low-overhead reminder and reconciliation wake-up hints.
- `TimerForegroundService`: user-visible long-running service for active timed tasks.
- `TimerWidgetUpdater`: `RemoteViews`-based widget refresh layer.
- `BootCompletedReceiver`: reboot and package-replacement recovery.
- `StatsCalculator`: local-date-aware daily/weekly/monthly stats with cross-day splitting.

See:

- `docs/daily-multi-task-and-time-window-design.md`
- `docs/implementation-summary.md`
- `docs/test-plan.md`
- `docs/adr/2026-06-08-daily-instances-and-time-window-tasks.md`
- `docs/adr/2026-06-09-local-first-productivity-suite-expansion.md`
- `docs/task-envelope-2026-06-09-feature-suite.json`
