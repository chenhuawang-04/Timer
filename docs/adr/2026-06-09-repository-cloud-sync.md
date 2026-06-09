# ADR: Repository-based daily cloud sync for Timer

- Status: Proposed
- Date: 2026-06-09
- Trace: timer-sync-github-gitee-20260609

## Context

The app is intentionally local-first, but the user now requires automatic cloud protection with these constraints:

- sync should happen roughly once per day
- it must stay lightweight and background-friendly
- interrupted devices should be able to continue from durable local state
- GitHub / Gitee repository APIs and file-size limits must be respected
- user credentials must not leak into exported task snapshots
- restore must remain simple for non-technical users once configured

The existing codebase already contains:

- Room-backed task data as the source of truth
- JSON export / import through `BackupPayloadCodec`
- a DataStore-backed preferences layer
- existing background coordination infrastructure

## Decision

Implement cloud sync as a **repository snapshot transport layer** above the existing JSON backup pipeline.

### Snapshot format

Use the current backup payload as the logical data source, but wrap it in a repository-safe package:

- generate a portable backup JSON snapshot
- Gzip-compress it
- split the compressed bytes into small chunks
- upload a manifest plus chunk files into a configured repository path

Remote layout is **immutable per snapshot** and updated through a lightweight latest pointer:

- `<basePath>/latest.json`
- `<basePath>/snapshots/<snapshotId>/manifest.json`
- `<basePath>/snapshots/<snapshotId>/chunks/chunk-000.bin`
- `<basePath>/snapshots/<snapshotId>/chunks/chunk-001.bin`
- ...

Write order:

1. upload the new immutable snapshot files
2. upload `latest.json` last so readers switch atomically to the new snapshot
3. best-effort delete the previous snapshot files after the pointer update succeeds

If an upload fails before the pointer update, the partially uploaded new snapshot is cleaned up best-effort and the previously pointed snapshot remains authoritative.

This avoids the corruption mode where old manifests keep pointing at partially overwritten chunk paths.

### Scheduling model

Use Android `JobScheduler` periodic work instead of introducing WorkManager or exact alarms.

Reasoning:

- the app already targets API 26+
- daily repository sync is network-bound, not alarm-precision-bound
- JobScheduler can express network requirements and persisted daily work with low runtime overhead

The app also performs a light catch-up check on launch so a missed daily run can still recover when the user opens the app later.

### Payload stability and no-op detection

Cloud sync should not re-upload merely because sync metadata changed.

Therefore the app computes a **logical data digest** from a portable backup snapshot that strips volatile fields such as:

- cloud sync runtime status
- last backup timestamp
- last selected tab

If the logical digest and target repository identity match the last successful sync, the upload is skipped.

### Credential handling

Store the repository access token separately from normal preferences using an Android Keystore-backed encrypted store.

Consequences:

- backup/export payloads do not contain credentials
- restore keeps the device-local token independent from imported task data
- a restored configuration may still require re-entering a token on a new device

### Provider strategy

Support both providers through repository-contents style clients:

- GitHub: REST contents API with bearer token
- Gitee: repository contents API with authorization-based token authentication

The implementation intentionally keeps chunk files small to remain comfortably below common contents API response thresholds and single-file risk boundaries.

## Alternatives considered

### Add a custom backend or account system

Rejected for now.

It would add authentication flows, operational complexity, conflict resolution, and a much larger long-term maintenance surface.

### Store one large JSON file remotely

Rejected.

A monolithic file is more likely to hit repository contents API edge cases, wastes bandwidth on retry, and is less robust if uploads are interrupted.

### Depend on WorkManager

Rejected for this round.

WorkManager is valid, but the app can satisfy the daily sync requirement with platform APIs already available at API 26+, avoiding a new production dependency.

### Include cloud sync token in backup payloads

Rejected.

This would widen secret exposure during export, transfer, and restore.

## Consequences

### Positive

- keeps the product local-first while adding automatic off-device protection
- reuses the existing backup contract instead of introducing a second persistence model
- works for both GitHub and Gitee repositories
- keeps bandwidth and file sizes small through compression and chunking
- avoids unnecessary uploads when the logical data has not changed
- keeps access tokens out of exported snapshots

### Tradeoffs

- repository sync is one-way latest-snapshot backup, not bidirectional merge sync
- restore still replaces local data rather than merging records
- Gitee and GitHub API behavior differences increase maintenance overhead
- token-based repository writes remain a security-sensitive surface and should be reviewed carefully

## Implementation notes

- `CloudSyncCoordinator` owns packaging, digesting, upload, restore, and status persistence.
- `CloudSyncScheduler` uses a persisted periodic `JobScheduler` job.
- `CloudSyncSecretStore` encrypts the repository token with Android Keystore.
- cloud sync settings are backed up, but cloud sync runtime status is stripped from portable snapshots.
- backups that predate cloud-sync support are still importable; if the `cloudSync` field is absent, local cloud-sync configuration is preserved instead of being silently cleared.
- manual backup/import remains available alongside cloud sync.

## Verification

Primary verification remains GitHub CI, per project instruction.

Local evidence in this round is limited to static inspection and pure Kotlin unit tests for snapshot packaging / manifest round-trips. No local Gradle build result is treated as release truth.
