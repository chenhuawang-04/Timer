# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android native timer app for durable daily planning, timing, routines, statistics, and local-first recovery. Supports three task types: COUNT_UP (manual completion), COUNT_DOWN (auto-completes when duration elapsed), and TIME_WINDOW (must complete within HH:mm-HH:mm window or marked MISSED).

Built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, and AlarmManager for low-overhead durable timing.

## Build and Test Commands

**CI Build (authoritative):**
```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

This project is verified by GitHub Actions CI. Per project instruction, local Android SDK/Gradle test/build commands are not routinely run during implementation handoff.

**Run Specific Test:**
```bash
./gradlew test --tests "com.timer.app.domain.StatsCalculatorTest"
```

**Lint and Code Quality:**
```bash
./gradlew lint
```

## Release Process

Push a tag like `v1.2.0` or manually trigger the `Android Release` workflow to:
- Run unit tests
- Build signed/unsigned release APK (signing requires secrets)
- Publish to GitHub Releases

Signing secrets: `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEY_PASSWORD`

## Architecture

The app follows a daily task-instance model. Each day can have multiple tasks, each instance tied to a specific `localDate`.

### Core Data Flow

```
TaskTemplateEntity (reusable routine) 
    → TaskInstanceEntity (daily task fact)
        → TaskRuntimeStateEntity (durable running/paused state)
        → TaskSessionEntity (immutable measured intervals)
        → TaskEventLogEntity (audit trail)
```

**Key entities:**

- `TaskCategoryEntity`: color/grouping categories
- `GoalEntity`: daily/weekly/monthly productivity targets
- `TaskTemplateEntity`: routine definitions with repeat rules (once/daily/weekdays/weekly/custom/monthly), reminders, pomodoro settings
- `TaskInstanceEntity`: actual daily task used for planning, completion, and analytics
  - Archive state stored separately from status so archived tasks remain in historical stats
- `TaskRuntimeStateEntity`: running/paused state for COUNT_UP and COUNT_DOWN
- `TaskSessionEntity`: immutable measured intervals for tracked-duration statistics
- `TaskEventLogEntity`: create/start/pause/resume/complete/miss/cancel/recover/archive events

**Task Type Behavior:**

- **COUNT_UP**: start/pause/resume, manually complete, never auto-completes
- **COUNT_DOWN**: start/pause/resume, auto-completes when elapsed ≥ target duration
  - Foreground service checks active countdowns while running
  - Boot recovery can mark overdue countdowns completed using wall-clock fallback
- **TIME_WINDOW**: configured with `HH:mm` start/end times
  - Converts to absolute `plannedStartEpochMillis` / `plannedEndEpochMillis`
  - Cross-midnight windows: `end <= start` → next-day end
  - Status flow: `PLANNED` → `READY` (when window opens) → `COMPLETED` or `MISSED`
  - Must be manually completed inside `[start, end)`, otherwise marked `MISSED`

### Repository Layer

- `RoomTimerRepository`: transactional state machine, routine generation, import/export, reconciliation core
  - `reconcileDeadlines()`: single correctness path for time-window open/expire and countdown completion
  - Runs from: app startup, dashboard ViewModel loop, foreground service, boot receiver, alarm receiver
- `AppPreferencesRepository`: DataStore-backed appearance, layout, energy, and interaction preferences

### Background and Recovery

- `TimerAutomationCoordinator`: startup/alarm/mutation orchestration for generation, reconciliation, widget refresh, foreground-service continuation
- `DeadlineAlarmScheduler` / `DeadlineAlarmReceiver`: `AlarmManager.setAndAllowWhileIdle` wake-up hints for reminders and reconciliation (no exact-alarm permission needed)
- `TimerForegroundService`: user-visible service for active timed tasks
- `BootCompletedReceiver`: reboot and package-replacement recovery for running timers and missed deadlines
- `TimerWidgetUpdater`: RemoteViews-based widget refresh

### Statistics

- `StatsCalculator`: local-date-aware stats with cross-midnight session splitting
  - Separates tracked duration (from `task_session` + open running states) from planned/completed/missed/cancelled task facts (from `task_instance`)
  - Supports: today/week/month duration, completion rates, time-window completion rate, planned/completed/missed window duration, top tracked tasks, last seven days chart

### Domain Logic

- `TimerMath`: elapsed/remaining calculations, countdown expiry detection, segment clamping
- `TaskRecurrence`: repeat rule evaluation (daily/weekdays/weekly/custom/monthly)
- `PomodoroMath`: work/break/cycle logic
- `DurationFormatter`: human-readable duration display
- `SuggestionEngine`: context-aware task suggestions

### Testing

Unit tests verify:
- `TimerMathTest`: running count-up elapsed, paused state, countdown remaining/expiry, segment clamping
- `StatsCalculatorTest`: cross-midnight splitting, open running state inclusion, immutable session stats, partial vs completed recovered countdown semantics, auto-completed countdown stats, multiple daily instances, time-window completed/missed stats
- `TaskRecurrenceTest`: repeat rule evaluation edge cases
- `BackupPayloadCodecTest`: JSON backup import/export correctness
- `CloudSnapshotCodecTest`: cloud sync payload encoding

## Code Conventions

- Schema version stored in `TimerDatabase.kt`
- Room migrations are destructive during development stage (version 3 allows fallback migration)
- Low-overhead timing: UI/service derive display values from monotonic elapsed time without per-second database writes
- Foreground service only runs while COUNT_UP/COUNT_DOWN tasks are actively running
- Reconciliation ensures eventual consistency without requiring exact alarms
- All background work respects Android Doze/App Standby restrictions

## Documentation

See also:
- `docs/daily-multi-task-and-time-window-design.md` — task type design and data model (Chinese)
- `docs/implementation-summary.md` — refactoring trace and implemented scope
- `docs/test-plan.md` — test scenarios
- `docs/adr/2026-06-08-daily-instances-and-time-window-tasks.md` — architecture decision record
- `docs/adr/2026-06-09-local-first-productivity-suite-expansion.md` — expansion features ADR
- `docs/task-envelope-2026-06-09-feature-suite.json` — feature envelope spec
