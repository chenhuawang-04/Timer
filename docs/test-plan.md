# Timer App Verification Plan

This repository is configured to use GitHub Actions for build and test verification.

## CI gate

Workflow: `.github/workflows/android-ci.yml`

Required command:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Local Gradle build/test is intentionally not run during implementation handoff per project instruction. CI is the authoritative verification gate.

## Automated unit coverage

### `TimerMathTest`

- Running count-up adds elapsed realtime delta to accumulated duration.
- Paused timers do not advance.
- Countdown remaining time is clamped at zero.
- Running countdown expires once effective elapsed duration reaches the target.
- Count-up timers never expire as countdowns.
- Countdown open segments are clamped to the remaining target duration.

### `StatsCalculatorTest`

- Cross-midnight measured sessions split into the correct local dates.
- Open running state contributes current in-memory elapsed duration without per-second database writes.
- Historical tracked duration comes from immutable `TaskSessionEntity` records.
- Partial recovered countdown sessions do not count as completed unless the instance is actually completed.
- Recovered/auto-completed countdown instances count toward countdown completion statistics.
- Multiple task instances on the same day contribute to planned/completed/cancelled counts.
- Manual count-up completion contributes to count-up completion statistics.
- Time-window completed/missed statistics use planned window duration and do not inflate tracked time.
- Archived/hidden completed timed tasks still contribute tracked duration and completion counts.
- Archived/hidden missed time-window tasks still contribute missed counts and missed planned duration.

## Static handoff checks

Run these checks when making non-CI handoffs without invoking Gradle:

```powershell
# Removed v1 source identifiers should not remain in app source.
Get-ChildItem -Recurse -File app\src | Select-String -Pattern 'TimerTaskEntity|TimerRuntimeStateEntity|TimerSessionEntity|TimerEventLogEntity|TimerTypes|TimerStatuses|timer_task|timer_runtime_state|timer_session|AUTO_COMPLETED'

# XML parse smoke check.
@'
from pathlib import Path
import xml.etree.ElementTree as ET
errors=[]
files=list(Path('app/src/main').rglob('*.xml'))
for p in files:
    try:
        ET.parse(p)
    except Exception as e:
        errors.append((str(p), str(e)))
print('xml_files', len(files))
print('xml_parse_ok' if not errors else errors)
'@ | python -

# Rough Kotlin source sanity check.
@'
from pathlib import Path
issues=0
for p in Path('app/src').rglob('*.kt'):
    text=p.read_text(encoding='utf-8-sig')
    if text.startswith('\ufeff'):
        print('BOM', p); issues += 1
    if text.count('{') != text.count('}'):
        print('BRACE_MISMATCH', p, text.count('{'), text.count('}')); issues += 1
    for i,line in enumerate(text.splitlines(),1):
        if '???' in line:
            print('SUSPICIOUS', p, i, line); issues += 1
print('issues', issues)
'@ | python -
```

## Manual/device checks still required

1. Create several task instances on the same day: count-up, countdown, and time-window.
2. Start a count-up timer, background the app for 30 minutes, reopen, and confirm elapsed time is correct.
3. Start a count-up timer, pause/resume it, then manually complete it; confirm the final tracked duration and completion count.
4. Start a countdown, lock the phone, and confirm completion notification appears.
5. Start a countdown, kill the app process, wait past the target duration, reopen, and confirm it is completed.
6. Reboot the device with a running countdown and confirm recovered auto-completion when appropriate.
7. Create a time-window task before its start time; confirm it shows planned/starts-later state.
8. During the time window, confirm the task can be manually completed and appears in time-window completion stats.
9. Let a time-window task expire without completion; confirm it becomes `MISSED` and appears in missed stats.
10. Reboot or package-replace with future time-window tasks scheduled; confirm alarms are rescheduled and eventual reconciliation still marks missed tasks.
11. Deny notification permission and confirm the UI shows degraded notification state instead of pretending alerts work.
12. Confirm no foreground service remains when all running timed tasks are paused, completed, cancelled, or archived.
13. Confirm no per-second database write strategy exists in code review; display ticks should be derived from persisted state plus elapsed realtime.
14. Archive a completed task and a missed time-window task; confirm they disappear from the active list but remain in historical statistics.
