# 每日多任务、三类计时与统计设计

## 目标

这次重构的目标是把计时 App 从“单个计时任务”升级为“每日任务实例”模型：

- 每天可以创建多项任务。
- 支持三类任务：
  - 正向计时 `COUNT_UP`
  - 倒计时 `COUNT_DOWN`
  - 时间窗口任务 `TIME_WINDOW`
- 倒计时到点自动完成。
- 正向计时由用户手动标记完成。
- 时间窗口任务配置“几点几分到几点几分”，必须在窗口内手动完成；错过结束时间后标记为 `MISSED`。
- 所有类型都进入统计，但要区分“实际计时时长”和“计划任务完成情况”。
- 尽量低占用：不做每秒数据库写入；只有运行中的计时任务使用前台服务；时间窗口使用低占用闹钟提示 + 最终一致的状态校准。

## 任务类型

| 类型 | 配置 | 完成方式 | 错过/结束规则 | 统计口径 |
|---|---|---|---|---|
| `COUNT_UP` | 任务名、颜色等 | 用户开始计时后手动完成 | 不自动完成 | session 实际时长、手动完成次数 |
| `COUNT_DOWN` | 任务名、目标时长 | 用户开始后，累计时长达到目标自动完成 | 取消不算完成 | session 实际时长、自动/恢复完成次数 |
| `TIME_WINDOW` | 任务名、开始 `HH:mm`、结束 `HH:mm` | 用户必须在窗口内手动完成 | 到结束时间仍未完成则 `MISSED` | 计划次数、完成次数、错过次数、完成率、计划窗口时长 |

## 数据模型

### `task_template`

可复用的任务模板，保存默认配置。模板不是必须的，用户也可以每天临时创建一次性任务。

核心字段：

- `id`
- `name`
- `type`
- `defaultTargetDurationMillis`
- `defaultStartMinuteOfDay`
- `defaultEndMinuteOfDay`
- `colorArgb`
- `icon`
- `tag`
- `note`
- `archived`
- `createdAtEpochMillis`
- `updatedAtEpochMillis`

### `task_instance`

某一天真正要完成的一项任务，是列表、状态和统计的核心事实。

核心字段：

- `id`
- `templateId`
- `localDate`
- `nameSnapshot`
- `type`
- `status`
- `targetDurationMillis`
- `plannedStartEpochMillis`
- `plannedEndEpochMillis`
- `colorArgb`
- `tagSnapshot`
- `createdAtEpochMillis`
- `updatedAtEpochMillis`
- `completedAtEpochMillis`
- `missedAtEpochMillis`
- `cancelledAtEpochMillis`
- `completionSource`
- `missSource`
- `archived`
- `archivedAtEpochMillis`

设计原因：

- 历史任务名称必须稳定，因此实例保存 `nameSnapshot`。
- 同一个模板可以在不同日期生成多个实例。
- 统计按实例聚合，不会被后续模板编辑污染。

### `task_runtime_state`

只用于 `COUNT_UP` 和 `COUNT_DOWN`。

核心字段：

- `instanceId`
- `status`
- `accumulatedMillis`
- `startedAtEpochMillis`
- `startedAtElapsedRealtimeMillis`
- `lastPersistedAtEpochMillis`
- `version`

UI 每秒刷新时不写数据库，而是用：

```text
effectiveElapsed = accumulatedMillis + nowElapsedRealtime - startedAtElapsedRealtimeMillis
```

暂停、完成、取消、恢复等状态变化才写入数据库。

### `task_session`

不可变的实际计时片段，用于真实时长统计。

核心字段：

- `id`
- `instanceId`
- `templateId`
- `startedAtEpochMillis`
- `endedAtEpochMillis`
- `durationMillis`
- `source`
- `createdAtEpochMillis`

`source` 包括：

- `MANUAL`
- `COUNTDOWN_AUTO`
- `RECOVERED_PARTIAL`
- `RECOVERED_COMPLETED`

### `task_event_log`

审计/调试/恢复事件：

- 创建模板
- 创建实例
- 开始
- 暂停
- 继续
- 完成
- 错过
- 取消
- 恢复
- 归档

## 状态定义

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

归档不是 status。归档只表示“从当前列表隐藏”，通过 `archived` / `archivedAtEpochMillis` 表示，不能覆盖 `COMPLETED` / `MISSED` / `CANCELLED` 这些历史事实。

## 状态机

### `COUNT_UP`

```text
READY
  -> start -> RUNNING

RUNNING
  -> pause -> PAUSED + 写入 session
  -> complete manually -> COMPLETED + 写入 session
  -> cancel -> CANCELLED

PAUSED
  -> resume -> RUNNING
  -> complete manually -> COMPLETED
  -> cancel -> CANCELLED

COMPLETED/CANCELLED
  -> archive -> archived = true，status 保持不变
```

规则：

- 正向计时永不自动完成。
- 用户必须手动完成。
- 完成时会固化当前打开的计时片段。

### `COUNT_DOWN`

```text
READY
  -> start -> RUNNING

RUNNING
  -> pause -> PAUSED + 写入部分 session
  -> elapsed >= target -> COMPLETED + source = COUNTDOWN_AUTO
  -> cancel -> CANCELLED

PAUSED
  -> resume -> RUNNING
  -> cancel -> CANCELLED

COMPLETED/CANCELLED
  -> archive -> archived = true，status 保持不变
```

规则：

- 倒计时达到目标自动完成。
- App 或设备恢复时，如果根据墙钟时间判断已经达到目标，则补标为 `COMPLETED`，来源为 `RECOVERED_AUTO`。
- 已取消的倒计时不计为完成。

### `TIME_WINDOW`

```text
PLANNED
  -> now >= plannedStart && now < plannedEnd -> READY
  -> now >= plannedEnd -> MISSED
  -> cancel -> CANCELLED

READY
  -> complete manually before plannedEnd -> COMPLETED
  -> now >= plannedEnd -> MISSED
  -> cancel -> CANCELLED

COMPLETED/MISSED/CANCELLED
  -> archive -> archived = true，status 保持不变
```

规则：

- 时间窗口不是秒表，不写 runtime state。
- 只能在窗口内手动完成。
- 错过后是统计事实，不应被当作已完成。
- `plannedEndEpochMillis <= now` 且未完成/未取消时，标记为 `MISSED`。

## 跨天窗口

如果 `endMinuteOfDay <= startMinuteOfDay`，解释为“结束于次日”。

示例：

```text
localDate = 2026-06-08
start = 23:00
end = 01:00
plannedStart = 2026-06-08 23:00 local
plannedEnd = 2026-06-09 01:00 local
```

实例的 `localDate` 归属为开始日期。

## 截止时间校准

`RoomTimerRepository.reconcileDeadlines()` 是唯一状态正确性入口：

1. 打开时间窗口：`TIME_WINDOW` 从 `PLANNED` 变为 `READY`。
2. 错过时间窗口：过期未完成窗口变为 `MISSED`。
3. 完成倒计时：运行中的倒计时达到目标后变为 `COMPLETED`。

触发来源：

- App 启动。
- 首页 ViewModel 定期校准。
- 前台服务循环。
- 开机/应用替换广播。
- `DeadlineAlarmReceiver`。

## Android 后台策略

### 运行中的正向计时/倒计时

- 使用前台服务。
- 前台服务通知显示当前运行任务。
- 有倒计时运行时，服务以较短周期检查自动完成。
- 没有运行任务时停止前台服务。

### 时间窗口任务

- 不为了时间窗口常驻前台服务。
- 创建/启动/恢复后由 `DeadlineAlarmScheduler` 为开始和结束时间安排 `AlarmManager.setAndAllowWhileIdle`。
- Alarm 是低占用唤醒提示，不申请 exact-alarm 权限。
- 即使 Alarm 延迟或未触发，下一次校准仍会补标 `MISSED`，保证最终状态牢固。

## 统计设计

必须区分两类指标。

### 实际计时时长

来源：

- `task_session`
- 当前打开的 running segment

适用：

- `COUNT_UP`
- `COUNT_DOWN`

跨午夜 session 按本地日期拆分。

### 计划任务完成情况

来源：

- `task_instance`

适用：

- 所有任务类型，尤其 `TIME_WINDOW`

指标：

- 今日计划数
- 今日完成数
- 今日错过数
- 今日取消数
- 正向计时完成数
- 倒计时自动/恢复完成数
- 时间窗口完成数
- 时间窗口错过数
- 时间窗口完成率
- 时间窗口计划时长
- 已完成窗口计划时长
- 错过窗口计划时长
- 近 7 日统计
- Top tracked tasks

时间窗口的“计划时长”不等于真实专注时长，不能混入 tracked duration。

## UI 设计

首页：

- 顶部统计卡：今日实际计时、任务数、完成数、错过数、周/月时长、时间窗口完成率。
- 通知权限提示。
- 前台服务降级提示。
- 近 7 日图表。
- 今日任务列表。
- 空状态。

创建弹窗：

- 任务名。
- 类型选择：Count up / Countdown / Window。
- 倒计时分钟。
- 时间窗口 `HH:mm` 开始/结束输入，并校验范围 `00:00..23:59`。

任务卡：

- 名称、类型、状态。
- 正向/倒计时显示时钟。
- 倒计时显示进度条。
- 时间窗口显示 `start-end / starts later|complete now|completed|missed|overdue`。
- 根据类型和状态显示 Start/Pause/Resume/Complete/Cancel/Archive。

## 验收要点

### 正向计时

- 今天可以创建多个正向计时任务。
- 开始、暂停、继续、完成行为正确。
- 不会自动完成。
- 手动完成后进入完成统计。
- 实际时长来自 session。

### 倒计时

- 今天可以创建多个倒计时任务。
- 达到目标时长自动完成。
- 后台运行时由前台服务检测完成。
- App/设备恢复后可补标已完成倒计时。
- 自动/恢复完成进入统计。

### 时间窗口

- 可以创建如 `09:00-09:30` 的窗口任务。
- 窗口前显示未开始。
- 窗口内可以手动完成。
- 超过结束时间未完成则标记 `MISSED`。
- App 未运行时，下次校准会补标 `MISSED`。
- 统计包含完成数、错过数、完成率、计划时长。

### 多任务与统计

- 同一天可创建多个不同类型实例。
- 今日列表只展示今日实例。
- 今日/周/月统计使用完整本地历史，不只依赖当前列表。
- 跨天 session 正确拆分。

## CI

仓库通过 GitHub Actions 验证：

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

本地实现交付阶段遵循用户要求，不运行 Gradle 构建/测试。
