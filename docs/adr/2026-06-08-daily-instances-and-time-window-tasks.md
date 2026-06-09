# ADR: Daily task instances and time-window timer type

- Status: Accepted
- Date: 2026-06-08
- Trace: trc_timer_app_20260608_02

## Context

The product scope now requires:

- Multiple timer tasks can be created for each day.
- Count-up tasks are completed manually.
- Countdown tasks complete automatically when their target duration elapses.
- A new time-window task type can be configured from a start time to an end time, such as 09:00-09:30.
- Time-window tasks must be manually marked complete during the configured window.
- If a time-window task passes its end time without manual completion, it is marked missed / incomplete.
- All task types must participate in daily, weekly, monthly, and per-type statistics.

The existing MVP model treats `timer_task` as both the task definition and the runtime/statistics identity. That is not sufficient for daily multi-task planning, repeatable templates, or accurate time-window missed/completed statistics.

## Decision

Introduce a two-level task model:

1. `task_template`
   - Reusable task definition: name, type, default countdown duration, default time window, color, tag, etc.

2. `task_instance`
   - A concrete task occurrence for a specific local date.
   - Stores `nameSnapshot`, task type, date, status, planned start/end timestamps, completion/miss/cancel timestamps, and completion source.

Runtime and statistics records will reference task instances:

- `task_runtime_state.instanceId`
- `task_session.instanceId`
- `task_event_log.instanceId`

Task types:

- `COUNT_UP`: user starts/pauses/resumes and manually completes.
- `COUNT_DOWN`: user starts/pauses/resumes; system completes when elapsed duration reaches target.
- `TIME_WINDOW`: user manually completes within `[plannedStart, plannedEnd)`; otherwise a deadline reconciler marks it `MISSED`.

Add a `TaskDeadlineReconciler` responsible for finalizing stale facts:

- Expired running countdowns become completed.
- Expired uncompleted time-window instances become missed.

The reconciler must run at app startup, foreground/dashboard lifecycle, foreground service ticks, boot/package-replaced receiver, and alarm/worker triggers.

The current implementation includes `DeadlineAlarmScheduler` and `DeadlineAlarmReceiver`, which use `AlarmManager.setAndAllowWhileIdle` as low-overhead start/end wake-up hints for `TIME_WINDOW` reconciliation. These alarms do not require exact-alarm permission and are not the sole correctness mechanism.

## Alternatives considered

### Keep one `timer_task` table only

Rejected. It cannot cleanly represent multiple occurrences of the same task across dates, cannot preserve historical task names after template edits, and makes daily planning/statistics ambiguous.

### Treat time-window tasks as countdowns

Rejected. A countdown measures elapsed duration from user start; a time-window task is a scheduled availability/deadline window. Conflating them would make missed status and completion-rate statistics inaccurate.

### Depend on exact alarms for missed marking

Rejected as the sole correctness path. Exact alarm capabilities are restricted on modern Android versions and should be optional. Correctness should be eventually consistent through `TaskDeadlineReconciler`, with alarms used for prompt reminders when allowed.

### Count planned time-window duration as actual focus time

Rejected. Time-window completion means the user manually confirmed the planned item, not necessarily that the full window was actively timed. Statistics must distinguish actual tracked duration from planned window duration.

## Consequences

Positive:

- Daily task lists become first-class.
- Multiple tasks per day are natural.
- Reusable templates are supported without corrupting history.
- Time-window missed/completed status is explicit and auditable.
- Statistics can distinguish actual tracked duration from planned task completion.
- Future recurring tasks and calendar-like planning are easier.

Tradeoffs:

- Requires Room schema version 3 after separating archive/hidden state from terminal task status. The current development build uses destructive migration because backward compatibility was explicitly waived for this rewrite.
- Repository and UI must move from `taskId` to `instanceId` for runtime actions.
- Statistics become richer but more complex.
- Time-window deadline marking is eventually consistent through reconciliation and is made more prompt by inexact AlarmManager wake-up hints when Android delivers them.
- Device/manual testing is required for alarms, boot recovery, and Android background restrictions.

## Implementation notes

Recommended schema additions:

- `task_template`
- `task_instance`
- `task_runtime_state`
- `task_session`
- `task_event_log`

Recommended statuses:

- `PLANNED`
- `READY`
- `RUNNING`
- `PAUSED`
- `COMPLETED`
- `MISSED`
- `CANCELLED`

Archive is not a status in the implemented schema. It is represented by separate `archived` and `archivedAtEpochMillis` fields so hiding a task does not delete completion/missed/cancelled facts from statistics.

Recommended statistics split:

- Actual tracked duration from sessions.
- Planned/completed/missed/cancelled task counts from instances.
- Time-window planned duration, completed planned duration, missed planned duration, and completion rate.

## Verification

CI must cover:

- Multiple instances on the same date.
- Count-up manual completion.
- Countdown automatic completion.
- Countdown recovery completion after process interruption.
- Time-window manual completion inside the window.
- Time-window missed marking after the end timestamp.
- Lazy missed marking when the app was not running.
- Daily/weekly/monthly statistics by type.
- Cross-day session splitting for actual tracked duration.
