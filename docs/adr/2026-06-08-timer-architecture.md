# ADR: Durable low-overhead Android timer architecture

- Status: Superseded by `2026-06-08-daily-instances-and-time-window-tasks.md`
- Date: 2026-06-08
- Trace: trc_timer_app_20260608_01

## Context

The original MVP scope required user-created count-up and countdown timer tasks with low resource use, long-running background behavior, recovery after interruption, robust local state, and daily/weekly/monthly statistics.

The later product scope added daily multi-task planning and a `TIME_WINDOW` type. That newer scope supersedes the original table names and data contracts in this ADR. The current implementation uses the v2 model documented in `2026-06-08-daily-instances-and-time-window-tasks.md`.

Android background execution is constrained. A reliable timer app cannot depend on a normal background service or per-second database writes. It must also tolerate process death, notification permission denial, and device reboot.

## Decision

Use a Kotlin Android native app with:

- Jetpack Compose + Material 3 for UI.
- Room as the authoritative local state store.
- Foreground Service only while one or more timers are actively running.
- Foreground-service start failures must be observable instead of silently swallowed.
- Notification channels for running timers and countdown completion alerts.
- Event/session-based persistence rather than per-second persistence.
- Monotonic elapsed realtime for active timing calculations.
- Wall-clock epoch timestamps for statistics and reboot fallback.
- Immutable `TaskSessionEntity` records as the primary tracked-duration statistics source.
- Distinct session sources for partial and completed reboot recovery to avoid corrupting completion counts.
- Cross-day session splitting at statistics query time.
- GitHub Actions as the required build/test gate.

## Data contracts

Current primary tables:

- `task_template`: optional reusable task definitions.
- `task_instance`: concrete daily task facts.
- `task_runtime_state`: durable active state per timed instance.
- `task_session`: immutable measured intervals.
- `task_event_log`: audit/recovery/debug events.

Runtime values are calculated as:

```text
effective_elapsed = accumulatedMillis + nowElapsedRealtime - startedAtElapsedRealtime
```

No database writes are required for each UI tick.

## Alternatives considered

### Per-second database updates

Rejected. It increases I/O, battery usage, and corruption risk without improving correctness.

### WorkManager as the main timer runtime

Rejected for active timers. WorkManager is useful for deferrable work, not precise user-visible long-running timing.

### Exact alarm as the default countdown mechanism

Deferred. Exact alarms add permission and policy complexity on modern Android. The current implementation uses foreground service behavior for active countdowns, plus inexact AlarmManager wake-up hints for time-window start/end reconciliation.

### Single active timer only

Rejected as a hard architecture constraint. The current design allows multiple runtime states. The UI and notification prioritize the first active task but the persistence model supports concurrent timers.

## Consequences

Positive:

- Low write amplification.
- Recovery is possible after process death and best-effort after reboot.
- Statistics remain traceable to immutable session facts.
- UI can refresh every second without changing persistence state.
- Foreground service runs only when user-visible timer work is active.

Tradeoffs:

- Device reboot recovery uses wall-clock fallback and can be affected by user clock changes.
- Background foreground-service starts may be restricted by Android version/OEM policy.
- Exact offline deadline alerts are not guaranteed without a future exact-alarm enhancement, but durable state is corrected by reconciliation.
- Current hard-coded UI strings should be moved to localized resources before production localization.

## Verification

Required CI gates:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Unit tests cover timer math and statistics cross-day splitting. Device/manual validation is still needed for foreground service, notification permission denial, lock screen behavior, process death, and reboot recovery.
