# 休息提醒和恢复功能实现总结

## 实现的功能

### 1. 每50分钟休息提醒
- 在任务运行达到设定时间间隔（默认50分钟）时，发送通知提醒用户休息
- 如果用户10分钟内没有响应，任务将自动暂停
- 用户可以选择：
  - 继续工作
  - 终止任务
  - 休息指定时长（默认10分钟）

### 2. 应用重启恢复提醒
- 检测应用被杀死前正在运行的任务
- 通过对比壁钟时间和记录时间，识别被中断的任务
- 弹窗让用户选择：
  - 使用实际经过的时间恢复
  - 保持已记录的时间
  - 输入自定义的时间值

## 已修改的文件

### 数据层
1. **TimerEntities.kt** - 扩展了 `TaskRuntimeStateEntity`
   - 添加 `lastBreakReminderAtEpochMillis` - 上次休息提醒时间
   - 添加 `breakUntilEpochMillis` - 休息结束时间

2. **TimerDatabase.kt** - 升级数据库版本到 5

3. **AppPreferencesRepository.kt** - 添加新的偏好设置
   - `breakReminderEnabled` - 是否启用休息提醒
   - `breakReminderIntervalMinutes` - 休息间隔（分钟）
   - `breakDurationMinutes` - 休息时长（分钟）
   - `autoRecoveryEnabled` - 是否启用自动恢复检测

4. **TimerRepository.kt** - 添加新方法
   - `updateBreakReminderTimestamp()` - 更新休息提醒时间戳
   - `updateBreakUntil()` - 设置休息结束时间
   - `updateAccumulatedTime()` - 调整累计时间（用于恢复）

### 业务逻辑层
5. **RecoveryDetector.kt** (新文件) - 检测中断的任务
   - 对比壁钟时间与记录时间，识别异常差值
   - 返回被中断任务的详细信息

6. **BreakReminderMath.kt** (新文件) - 休息提醒逻辑
   - `shouldShowBreakReminder()` - 判断是否应该显示休息提醒
   - `shouldAutoPause()` - 判断是否应该自动暂停
   - `isInBreakPeriod()` - 判断是否在休息期间

7. **TimerInterruptionCoordinator.kt** (新文件) - 协调中断和恢复
   - `checkBreakReminders()` - 检查需要休息提醒的任务
   - `checkAutoPause()` - 检查需要自动暂停的任务
   - `detectInterruptedTasks()` - 检测被中断的任务
   - `applyRecovery()` - 应用恢复调整

### 服务层
8. **TimerForegroundService.kt** - 集成休息提醒检查
   - 在监控循环中添加休息提醒检查
   - 在监控循环中添加自动暂停检查

9. **TimerNotificationController.kt** - 添加休息提醒通知
   - `showBreakReminder()` - 显示休息提醒通知

### 应用层
10. **TimerApplication.kt** - 添加中断协调器到容器
    - 在 `AppContainer` 中初始化 `interruptionCoordinator`
    - 在应用启动时检测中断的任务

### 资源文件
11. **values/strings.xml** - 添加英文字符串
12. **values-zh/strings.xml** - 添加中文字符串

## 工作原理

### 休息提醒流程
1. `TimerForegroundService` 在后台循环中定期检查
2. `TimerInterruptionCoordinator.checkBreakReminders()` 检查是否达到间隔
3. 如果达到间隔，发送通知并记录提醒时间
4. 如果用户在设定时间内没有响应，`checkAutoPause()` 会自动暂停任务

### 恢复检测流程
1. 应用启动时，`TimerApplication.onCreate()` 调用 `detectInterruptedTasks()`
2. `RecoveryDetector` 对比每个运行中任务的：
   - 预期经过时间（基于壁钟时间）
   - 实际记录时间（基于 ElapsedRealtime）
3. 如果差值超过阈值（默认30秒），标记为被中断
4. UI 应该显示恢复对话框，让用户选择如何处理

## 下一步需要做的

### UI 层实现（需要添加）
1. **休息提醒对话框** - 在 UI 中显示休息选项
   - 继续按钮
   - 终止按钮
   - 休息按钮

2. **恢复对话框** - 在 MainActivity 中实现
   - 显示被中断的任务列表
   - 对每个任务显示三个选项
   - 自定义时间输入框

3. **设置页面** - 添加休息提醒和恢复相关设置
   - 休息提醒开关
   - 休息间隔设置
   - 休息时长设置
   - 自动恢复开关

### ViewModel 层（需要添加）
4. **TimerViewModel** - 添加相关方法
   - `handleBreakReminder()`
   - `continueTask()`
   - `takeBreak(duration)`
   - `applyRecovery(instanceId, newTime)`

## 注意事项

1. **数据库迁移** - 升级到版本5，使用 `fallbackToDestructiveMigration()`，会清空数据
2. **权限** - 休息提醒通知需要通知权限
3. **后台运行** - 依赖前台服务持续监控
4. **电池优化** - 可能受到系统电池优化影响

## 测试建议

1. 测试休息提醒在50分钟后正确触发
2. 测试10分钟无响应后自动暂停
3. 测试应用被杀死后重启的恢复检测
4. 测试不同恢复选项（实际时间、记录时间、自定义时间）
5. 测试设置项的正确保存和读取
