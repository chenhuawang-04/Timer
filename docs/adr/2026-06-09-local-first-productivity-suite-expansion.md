# ADR: Local-first productivity suite expansion for Timer

- Status: Accepted
- Date: 2026-06-09
- Trace: trc_timer_app_20260609_feature_suite

## Context

The application started as a timer-oriented daily task tool and now needs a broader but still disciplined product shape:

- recurring routines
- per-task reminders
- categories and projects
- goals and streaks
- calendar and history views
- widgets and launcher shortcuts
- manual backup / import for local-first multi-device use
- focus mode and pomodoro support
- richer analytics and auditability

The user also requires:

- low resource usage
- durable background continuation and recovery
- clear modular architecture
- Chinese-capable resource-driven UI
- CI as the source of truth for build / test verification

## Decision

Keep the app **local-first** and expand the domain model rather than adding a remote backend.

### Data model

Add and use these durable concepts:

- `task_category`
- `goal`
- richer `task_template`
- richer `task_instance`
- existing runtime/session/event tables

Key design choice:

- `task_template` remains the reusable routine definition
- `task_instance` remains the historical fact for a specific day

Snapshot fields are preserved on instances so template/category edits do not corrupt history.

### Preferences and appearance

Use DataStore for non-relational app preferences:

- theme mode
- accent palette
- dashboard layout
- sort mode
- energy mode
- completed-task visibility
- focus-mode screen behavior
- last backup metadata

### Background orchestration

Create a coordination layer that centralizes:

- planning window generation
- deadline reconciliation
- reminder alarm scheduling
- widget refresh
- foreground-service continuation checks

This avoids scattering recovery logic across activity, service, and receivers.

### Reliability model

Correctness remains reconciliation-driven:

- alarms are wake-up hints
- notifications are advisory
- database state is the source of truth
- boot recovery and service ticks repair stale runtime facts

### Sync strategy

Do not introduce remote account sync yet.

Instead, add:

- JSON export
- JSON import
- platform backup rules

This preserves the local-first philosophy while still enabling manual device transfer and recovery.

## Alternatives considered

### Add a backend and real-time multi-device sync now

Rejected for this stage. It would require accounts, conflict resolution, operational cost, and a very different reliability surface.

### Keep everything in one dashboard screen

Rejected. The product surface now needs clearer categories:

- Today
- Routines
- Calendar
- Insights
- Settings

### Depend on exact alarms for correctness

Rejected. Exact alarms are restricted on modern Android. They may improve promptness but should not be required for durable correctness.

## Consequences

### Positive

- clearer module boundaries
- routine generation scales naturally
- analytics become richer without rewriting historical facts
- widgets / shortcuts / tile actions integrate around the same coordinator
- settings and appearance customization stay outside the relational schema
- manual backup offers a practical local-first sync path

### Tradeoffs

- larger schema and UI surface
- more mapping logic in the ViewModel
- more resource strings to maintain
- some advanced features remain intentionally lightweight rather than enterprise-grade

## Implementation notes

- Room schema version advances to 4 with destructive migration preserved because backward compatibility was explicitly waived.
- `TimerAutomationCoordinator` owns cross-cutting orchestration.
- `DeadlineAlarmScheduler` now covers reminders in addition to time-window deadlines.
- Widgets use classic `RemoteViews` to avoid adding a new dependency.
- Static shortcuts and a quick settings tile provide quick capture without a heavy navigation layer.

## Verification

Primary verification is delegated to GitHub CI.

Local evidence used in this round:

- resource reference scan
- XML parse validation
- conflict-marker / TODO scan
- unit tests updated for CI execution
