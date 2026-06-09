# Timer App Blueprint

## Product goal

Build a native Android timer/planning app that lets a user create multiple daily tasks with durable timing state, low runtime overhead, long-running background behavior when timers are active, and useful local statistics.

## Supported task types

| Type | User configuration | Completion rule | Statistics role |
|---|---|---|---|
| `COUNT_UP` | Task name; optional template/color/tag | User starts timing and manually marks completed | Actual tracked duration from sessions; manual completion count |
| `COUNT_DOWN` | Task name; target duration | User starts timing; app automatically completes when elapsed duration reaches target | Actual tracked duration from sessions; auto/recovered completion count |
| `TIME_WINDOW` | Task name; start `HH:mm`; end `HH:mm`; local date | User must manually complete inside `[start, end)`; otherwise deadline reconciliation marks `MISSED` | Planned/completed/missed counts, completion rate, planned window duration |

## Data model

The app intentionally separates reusable definitions from daily facts.

### `TaskTemplateEntity`

Reusable optional configuration:

- name
- type
- default countdown duration
- default time-window start/end minute
- color/icon/tag/note
- archive flag

Templates are not required for one-off daily task creation.

### `TaskInstanceEntity`

The primary daily fact:

- `id`
- optional `templateId`
- `localDate` as ISO-8601 local date
- `nameSnapshot`
- `type`
- `status`
- countdown target duration
- time-window absolute `plannedStartEpochMillis` / `plannedEndEpochMillis`
- color/tag snapshots
- completion/missed/cancelled timestamps and sources

Statistics and task cards should use instances, not templates, so historical names/statuses remain stable.

### `TaskRuntimeStateEntity`

Durable runtime state for `COUNT_UP` and `COUNT_DOWN` only:

- `instanceId`
- `status`
- `accumulatedMillis`
- wall-clock start timestamp
- elapsed-realtime start timestamp
- last persisted timestamp
- version

No per-second database writes are needed. Display values are derived from persisted state plus `SystemClock.elapsedRealtime()`.

### `TaskSessionEntity`

Immutable measured intervals:

- `instanceId`
- optional `templateId`
- started/ended wall-clock timestamps
- duration
- source (`MANUAL`, `COUNTDOWN_AUTO`, `RECOVERED_PARTIAL`, `RECOVERED_COMPLETED`)

Tracked-duration statistics are based on sessions plus any currently open running segment.

### `TaskEventLogEntity`

Append-only operational/audit events:

- create template/instance
- start/pause/resume
- complete/miss/cancel/archive
- recover

## Status model

```kotlin
object TaskStatuses {
    const val PLANNED = "PLANNED"
    const val READY = "READY"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val MISSED = "MISSED"
    const val CANCELLED = "CANCELLED"
}
```

Archive/hide is intentionally not a status. It is represented by the separate task-instance fields `archived` and `archivedAtEpochMillis` so completed/missed/cancelled history remains intact for statistics.

## State machines

### Count-up

```text
READY -> RUNNING -> PAUSED -> RUNNING
RUNNING/PAUSED/READY -> COMPLETED (manual)
RUNNING/PAUSED/READY -> CANCELLED
COMPLETED/CANCELLED -> archived = true, status unchanged
```

Count-up never auto-completes.

### Countdown

```text
READY -> RUNNING -> PAUSED -> RUNNING
RUNNING + elapsed >= target -> COMPLETED (COUNTDOWN_AUTO)
RUNNING/PAUSED/READY -> CANCELLED
COMPLETED/CANCELLED -> archived = true, status unchanged
```

After reboot/process recovery, if wall-clock elapsed time shows the target was reached, the instance becomes `COMPLETED` with `RECOVERED_AUTO`.

### Time window

```text
PLANNED + now >= start && now < end -> READY
READY + manual complete before end -> COMPLETED
PLANNED/READY + now >= end -> MISSED
PLANNED/READY -> CANCELLED
COMPLETED/MISSED/CANCELLED -> archived = true, status unchanged
```

Time-window tasks are not stopwatch sessions. The source of truth is the absolute planned window and final instance status.

## Deadline reconciliation

`RoomTimerRepository.reconcileDeadlines()` is the single correctness path. It runs transactionally and:

1. Promotes opened `TIME_WINDOW` instances from `PLANNED` to `READY`.
2. Marks expired uncompleted `TIME_WINDOW` instances as `MISSED`.
3. Completes expired running `COUNT_DOWN` instances.

Reconciliation is triggered from:

- application startup;
- dashboard ViewModel periodic loop;
- foreground service loop;
- boot/package-replaced receiver;
- AlarmManager deadline receiver.

Alarm delivery is a low-overhead promptness hint, not the only correctness mechanism. If Android delays or drops an alarm, the next reconciliation pass still fixes durable state.

## Background strategy

- Use a foreground service only while `COUNT_UP` or `COUNT_DOWN` instances are actively running.
- Countdown completion is checked every second while any countdown is running in the foreground service.
- Count-up foreground notifications can refresh less aggressively.
- `TIME_WINDOW` does not keep a foreground service alive. It uses `AlarmManager.setAndAllowWhileIdle` start/end hints plus reconciliation on app/boot/service events.
- Boot recovery converts wall-clock elapsed time into immutable sessions or recovered completion facts.

## Statistics strategy

The app keeps two categories separate:

1. **Actual tracked duration**
   - `COUNT_UP` and `COUNT_DOWN`
   - source: `TaskSessionEntity` + open running segment
   - split across local dates for cross-midnight intervals

2. **Planned task outcome**
   - all task types
   - source: `TaskInstanceEntity`
   - counts planned/completed/missed/cancelled
   - time-window completion rate and planned duration

Dashboard statistics include:

- tracked today/week/month;
- planned/completed/missed/cancelled today;
- count-up manual completions;
- countdown auto/recovered completions;
- time-window completion/missed counts;
- time-window completion rate;
- planned/completed/missed window duration;
- last seven daily summaries;
- top tracked tasks.

## Verification strategy

GitHub Actions is the authoritative gate:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Local handoff may run only static checks when the user requests CI-only build/test verification.
