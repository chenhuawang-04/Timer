package com.timer.app.data

import org.json.JSONArray
import org.json.JSONObject
import com.timer.app.sync.CloudSyncConfiguration
import com.timer.app.sync.CloudSyncDefaults
import com.timer.app.sync.CloudSyncPreferencesSnapshot
import com.timer.app.sync.CloudSyncProviders
import com.timer.app.sync.CloudSyncResultCodes
import com.timer.app.sync.CloudSyncStatusSnapshot

data class AppBackupPayload(
    val schemaVersion: Int,
    val exportedAtEpochMillis: Long,
    val preferences: AppPreferencesSnapshot,
    val repository: RepositoryExportData,
    val containsCloudSyncConfiguration: Boolean = true
)

object BackupPayloadCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(
        preferences: AppPreferencesSnapshot,
        repository: RepositoryExportData,
        exportedAtEpochMillis: Long
    ): String {
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAtEpochMillis", exportedAtEpochMillis)
            .put("preferences", encodePreferences(preferences))
            .put("categories", JSONArray().apply { repository.categories.forEach { put(encodeCategory(it)) } })
            .put("goals", JSONArray().apply { repository.goals.forEach { put(encodeGoal(it)) } })
            .put("templates", JSONArray().apply { repository.templates.forEach { put(encodeTemplate(it)) } })
            .put("instances", JSONArray().apply { repository.instances.forEach { put(encodeInstance(it)) } })
            .put("states", JSONArray().apply { repository.states.forEach { put(encodeState(it)) } })
            .put("sessions", JSONArray().apply { repository.sessions.forEach { put(encodeSession(it)) } })
            .put("events", JSONArray().apply { repository.events.forEach { put(encodeEvent(it)) } })
        return root.toString(2)
    }

    fun decode(json: String): AppBackupPayload {
        val root = JSONObject(json)
        val preferencesJson = root.optJSONObject("preferences") ?: JSONObject()
        return AppBackupPayload(
            schemaVersion = root.optInt("schemaVersion", 0),
            exportedAtEpochMillis = root.optLong("exportedAtEpochMillis", 0L),
            preferences = decodePreferences(preferencesJson),
            repository = RepositoryExportData(
                categories = root.optJSONArray("categories").toCategoryList(),
                goals = root.optJSONArray("goals").toGoalList(),
                templates = root.optJSONArray("templates").toTemplateList(),
                instances = root.optJSONArray("instances").toInstanceList(),
                states = root.optJSONArray("states").toStateList(),
                sessions = root.optJSONArray("sessions").toSessionList(),
                events = root.optJSONArray("events").toEventList()
            ),
            containsCloudSyncConfiguration = preferencesJson.has("cloudSync")
        )
    }

    private fun encodePreferences(snapshot: AppPreferencesSnapshot) = JSONObject()
        .put("themeMode", snapshot.themeMode)
        .put("accentPalette", snapshot.accentPalette)
        .put("dynamicColor", snapshot.dynamicColor)
        .put("dashboardLayout", snapshot.dashboardLayout)
        .put("sortMode", snapshot.sortMode)
        .put("showCompletedTasks", snapshot.showCompletedTasks)
        .put("energyMode", snapshot.energyMode)
        .put("keepScreenOnInFocus", snapshot.keepScreenOnInFocus)
        .put("lastSelectedTab", snapshot.lastSelectedTab)
        .putNullable("lastBackupAtEpochMillis", snapshot.lastBackupAtEpochMillis)
        .put("cloudSync", encodeCloudSync(snapshot.cloudSync))

    private fun decodePreferences(json: JSONObject) = AppPreferencesSnapshot(
        themeMode = json.optString("themeMode", ThemeModes.SYSTEM),
        accentPalette = json.optString("accentPalette", AccentPalettes.BLUE),
        dynamicColor = json.optBoolean("dynamicColor", true),
        dashboardLayout = json.optString("dashboardLayout", DashboardLayouts.TODAY_FIRST),
        sortMode = json.optString("sortMode", TaskSortModes.SMART),
        showCompletedTasks = json.optBoolean("showCompletedTasks", true),
        energyMode = json.optString("energyMode", EnergyModes.BALANCED),
        keepScreenOnInFocus = json.optBoolean("keepScreenOnInFocus", true),
        lastBackupAtEpochMillis = json.optNullableLong("lastBackupAtEpochMillis"),
        lastSelectedTab = json.optString("lastSelectedTab", "TODAY"),
        cloudSync = decodeCloudSync(json.optJSONObject("cloudSync"))
    )

    private fun encodeCloudSync(snapshot: CloudSyncPreferencesSnapshot) = JSONObject()
        .put(
            "configuration",
            JSONObject()
                .put("autoSyncEnabled", snapshot.configuration.autoSyncEnabled)
                .put("provider", snapshot.configuration.provider)
                .put("repositoryOwner", snapshot.configuration.repositoryOwner)
                .put("repositoryName", snapshot.configuration.repositoryName)
                .put("branch", snapshot.configuration.branch)
                .put("basePath", snapshot.configuration.basePath)
                .put("wifiOnly", snapshot.configuration.wifiOnly)
        )
        .put(
            "status",
            JSONObject()
                .put("lastResultCode", snapshot.status.lastResultCode)
                .putNullable("lastMessage", snapshot.status.lastMessage)
                .putNullable("lastAttemptAtEpochMillis", snapshot.status.lastAttemptAtEpochMillis)
                .putNullable("lastSuccessAtEpochMillis", snapshot.status.lastSuccessAtEpochMillis)
                .putNullable("lastSyncedDataSha256", snapshot.status.lastSyncedDataSha256)
                .putNullable("lastSuccessfulTargetKey", snapshot.status.lastSuccessfulTargetKey)
        )

    private fun decodeCloudSync(json: JSONObject?): CloudSyncPreferencesSnapshot {
        val root = json ?: JSONObject()
        val configuration = root.optJSONObject("configuration") ?: JSONObject()
        val status = root.optJSONObject("status") ?: JSONObject()
        return CloudSyncPreferencesSnapshot(
            configuration = CloudSyncConfiguration(
                autoSyncEnabled = configuration.optBoolean("autoSyncEnabled", false),
                provider = configuration.optString("provider", CloudSyncProviders.GITEE),
                repositoryOwner = configuration.optString("repositoryOwner", ""),
                repositoryName = configuration.optString("repositoryName", ""),
                branch = configuration.optString("branch", CloudSyncDefaults.DEFAULT_BRANCH),
                basePath = configuration.optString("basePath", CloudSyncDefaults.DEFAULT_BASE_PATH),
                wifiOnly = configuration.optBoolean("wifiOnly", true)
            ).normalized(),
            status = CloudSyncStatusSnapshot(
                lastResultCode = status.optString("lastResultCode", CloudSyncResultCodes.IDLE),
                lastMessage = status.optNullableString("lastMessage"),
                lastAttemptAtEpochMillis = status.optNullableLong("lastAttemptAtEpochMillis"),
                lastSuccessAtEpochMillis = status.optNullableLong("lastSuccessAtEpochMillis"),
                lastSyncedDataSha256 = status.optNullableString("lastSyncedDataSha256"),
                lastSuccessfulTargetKey = status.optNullableString("lastSuccessfulTargetKey")
            )
        )
    }

    private fun encodeCategory(entity: TaskCategoryEntity) = JSONObject()
        .put("id", entity.id)
        .put("name", entity.name)
        .put("colorArgb", entity.colorArgb)
        .put("archived", entity.archived)
        .put("createdAtEpochMillis", entity.createdAtEpochMillis)
        .put("updatedAtEpochMillis", entity.updatedAtEpochMillis)

    private fun encodeGoal(entity: GoalEntity) = JSONObject()
        .put("id", entity.id)
        .put("name", entity.name)
        .put("scope", entity.scope)
        .put("metricType", entity.metricType)
        .put("targetValue", entity.targetValue)
        .putNullable("categoryId", entity.categoryId)
        .putNullable("projectName", entity.projectName)
        .put("active", entity.active)
        .put("createdAtEpochMillis", entity.createdAtEpochMillis)
        .put("updatedAtEpochMillis", entity.updatedAtEpochMillis)

    private fun encodeTemplate(entity: TaskTemplateEntity) = JSONObject()
        .put("id", entity.id)
        .put("name", entity.name)
        .put("type", entity.type)
        .putNullable("defaultTargetDurationMillis", entity.defaultTargetDurationMillis)
        .putNullable("preferredStartMinuteOfDay", entity.preferredStartMinuteOfDay)
        .putNullable("defaultStartMinuteOfDay", entity.defaultStartMinuteOfDay)
        .putNullable("defaultEndMinuteOfDay", entity.defaultEndMinuteOfDay)
        .put("colorArgb", entity.colorArgb)
        .putNullable("categoryId", entity.categoryId)
        .putNullable("projectName", entity.projectName)
        .putNullable("tagsCsv", entity.tagsCsv)
        .putNullable("note", entity.note)
        .put("priority", entity.priority)
        .put("anchorDate", entity.anchorDate)
        .put("repeatMode", entity.repeatMode)
        .putNullable("repeatDaysCsv", entity.repeatDaysCsv)
        .put("repeatInterval", entity.repeatInterval)
        .put("remindersEnabled", entity.remindersEnabled)
        .put("remindAtStart", entity.remindAtStart)
        .putNullable("remindBeforeEndMinutes", entity.remindBeforeEndMinutes)
        .put("remindAtDeadline", entity.remindAtDeadline)
        .put("countTowardGoals", entity.countTowardGoals)
        .put("sessionMode", entity.sessionMode)
        .putNullable("pomodoroWorkMinutes", entity.pomodoroWorkMinutes)
        .putNullable("pomodoroBreakMinutes", entity.pomodoroBreakMinutes)
        .putNullable("pomodoroCycles", entity.pomodoroCycles)
        .put("autoGenerateAheadDays", entity.autoGenerateAheadDays)
        .put("archived", entity.archived)
        .put("createdAtEpochMillis", entity.createdAtEpochMillis)
        .put("updatedAtEpochMillis", entity.updatedAtEpochMillis)

    private fun encodeInstance(entity: TaskInstanceEntity) = JSONObject()
        .put("id", entity.id)
        .putNullable("templateId", entity.templateId)
        .put("localDate", entity.localDate)
        .put("nameSnapshot", entity.nameSnapshot)
        .put("type", entity.type)
        .put("status", entity.status)
        .putNullable("targetDurationMillis", entity.targetDurationMillis)
        .putNullable("preferredStartEpochMillis", entity.preferredStartEpochMillis)
        .putNullable("plannedStartEpochMillis", entity.plannedStartEpochMillis)
        .putNullable("plannedEndEpochMillis", entity.plannedEndEpochMillis)
        .put("colorArgb", entity.colorArgb)
        .putNullable("categoryIdSnapshot", entity.categoryIdSnapshot)
        .putNullable("categoryNameSnapshot", entity.categoryNameSnapshot)
        .putNullable("projectNameSnapshot", entity.projectNameSnapshot)
        .putNullable("tagsSnapshot", entity.tagsSnapshot)
        .putNullable("noteSnapshot", entity.noteSnapshot)
        .put("priority", entity.priority)
        .put("remindersEnabled", entity.remindersEnabled)
        .put("remindAtStart", entity.remindAtStart)
        .putNullable("remindBeforeEndMinutes", entity.remindBeforeEndMinutes)
        .put("remindAtDeadline", entity.remindAtDeadline)
        .put("countTowardGoals", entity.countTowardGoals)
        .put("sessionMode", entity.sessionMode)
        .putNullable("pomodoroWorkMinutes", entity.pomodoroWorkMinutes)
        .putNullable("pomodoroBreakMinutes", entity.pomodoroBreakMinutes)
        .putNullable("pomodoroCycles", entity.pomodoroCycles)
        .put("sortOrder", entity.sortOrder)
        .put("createdAtEpochMillis", entity.createdAtEpochMillis)
        .put("updatedAtEpochMillis", entity.updatedAtEpochMillis)
        .putNullable("completedAtEpochMillis", entity.completedAtEpochMillis)
        .putNullable("missedAtEpochMillis", entity.missedAtEpochMillis)
        .putNullable("cancelledAtEpochMillis", entity.cancelledAtEpochMillis)
        .putNullable("completionSource", entity.completionSource)
        .putNullable("missSource", entity.missSource)
        .putNullable("resultNote", entity.resultNote)
        .put("archived", entity.archived)
        .putNullable("archivedAtEpochMillis", entity.archivedAtEpochMillis)

    private fun encodeState(entity: TaskRuntimeStateEntity) = JSONObject()
        .put("instanceId", entity.instanceId)
        .put("status", entity.status)
        .put("accumulatedMillis", entity.accumulatedMillis)
        .putNullable("startedAtEpochMillis", entity.startedAtEpochMillis)
        .putNullable("startedAtElapsedRealtimeMillis", entity.startedAtElapsedRealtimeMillis)
        .put("lastPersistedAtEpochMillis", entity.lastPersistedAtEpochMillis)
        .putNullable("lastBreakReminderAtEpochMillis", entity.lastBreakReminderAtEpochMillis)
        .putNullable("breakUntilEpochMillis", entity.breakUntilEpochMillis)
        .put("version", entity.version)

    private fun encodeSession(entity: TaskSessionEntity) = JSONObject()
        .put("id", entity.id)
        .put("instanceId", entity.instanceId)
        .putNullable("templateId", entity.templateId)
        .put("startedAtEpochMillis", entity.startedAtEpochMillis)
        .put("endedAtEpochMillis", entity.endedAtEpochMillis)
        .put("durationMillis", entity.durationMillis)
        .put("source", entity.source)
        .put("createdAtEpochMillis", entity.createdAtEpochMillis)

    private fun encodeEvent(entity: TaskEventLogEntity) = JSONObject()
        .put("id", entity.id)
        .put("instanceId", entity.instanceId)
        .putNullable("templateId", entity.templateId)
        .put("eventType", entity.eventType)
        .put("atEpochMillis", entity.atEpochMillis)
        .putNullable("elapsedRealtimeMillis", entity.elapsedRealtimeMillis)
        .putNullable("payloadJson", entity.payloadJson)

    private fun JSONArray?.toCategoryList(): List<TaskCategoryEntity> = buildList {
        val array = this@toCategoryList ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                TaskCategoryEntity(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    colorArgb = item.getLong("colorArgb"),
                    archived = item.optBoolean("archived", false),
                    createdAtEpochMillis = item.optLong("createdAtEpochMillis", 0L),
                    updatedAtEpochMillis = item.optLong("updatedAtEpochMillis", 0L)
                )
            )
        }
    }

    private fun JSONArray?.toGoalList(): List<GoalEntity> = buildList {
        val array = this@toGoalList ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                GoalEntity(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    scope = item.getString("scope"),
                    metricType = item.getString("metricType"),
                    targetValue = item.optLong("targetValue", 0L),
                    categoryId = item.optNullableString("categoryId"),
                    projectName = item.optNullableString("projectName"),
                    active = item.optBoolean("active", true),
                    createdAtEpochMillis = item.optLong("createdAtEpochMillis", 0L),
                    updatedAtEpochMillis = item.optLong("updatedAtEpochMillis", 0L)
                )
            )
        }
    }

    private fun JSONArray?.toTemplateList(): List<TaskTemplateEntity> = buildList {
        val array = this@toTemplateList ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                TaskTemplateEntity(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    type = item.getString("type"),
                    defaultTargetDurationMillis = item.optNullableLong("defaultTargetDurationMillis"),
                    preferredStartMinuteOfDay = item.optNullableInt("preferredStartMinuteOfDay"),
                    defaultStartMinuteOfDay = item.optNullableInt("defaultStartMinuteOfDay"),
                    defaultEndMinuteOfDay = item.optNullableInt("defaultEndMinuteOfDay"),
                    colorArgb = item.getLong("colorArgb"),
                    categoryId = item.optNullableString("categoryId"),
                    projectName = item.optNullableString("projectName"),
                    tagsCsv = item.optNullableString("tagsCsv"),
                    note = item.optNullableString("note"),
                    priority = item.optString("priority", TaskPriorities.MEDIUM),
                    anchorDate = item.optString("anchorDate", "1970-01-01"),
                    repeatMode = item.optString("repeatMode", RepeatModes.NONE),
                    repeatDaysCsv = item.optNullableString("repeatDaysCsv"),
                    repeatInterval = item.optInt("repeatInterval", 1),
                    remindersEnabled = item.optBoolean("remindersEnabled", false),
                    remindAtStart = item.optBoolean("remindAtStart", false),
                    remindBeforeEndMinutes = item.optNullableInt("remindBeforeEndMinutes"),
                    remindAtDeadline = item.optBoolean("remindAtDeadline", false),
                    countTowardGoals = item.optBoolean("countTowardGoals", true),
                    sessionMode = item.optString("sessionMode", SessionModes.STANDARD),
                    pomodoroWorkMinutes = item.optNullableInt("pomodoroWorkMinutes"),
                    pomodoroBreakMinutes = item.optNullableInt("pomodoroBreakMinutes"),
                    pomodoroCycles = item.optNullableInt("pomodoroCycles"),
                    autoGenerateAheadDays = item.optInt("autoGenerateAheadDays", 7),
                    archived = item.optBoolean("archived", false),
                    createdAtEpochMillis = item.optLong("createdAtEpochMillis", 0L),
                    updatedAtEpochMillis = item.optLong("updatedAtEpochMillis", 0L)
                )
            )
        }
    }

    private fun JSONArray?.toInstanceList(): List<TaskInstanceEntity> = buildList {
        val array = this@toInstanceList ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                TaskInstanceEntity(
                    id = item.getString("id"),
                    templateId = item.optNullableString("templateId"),
                    localDate = item.getString("localDate"),
                    nameSnapshot = item.getString("nameSnapshot"),
                    type = item.getString("type"),
                    status = item.getString("status"),
                    targetDurationMillis = item.optNullableLong("targetDurationMillis"),
                    preferredStartEpochMillis = item.optNullableLong("preferredStartEpochMillis"),
                    plannedStartEpochMillis = item.optNullableLong("plannedStartEpochMillis"),
                    plannedEndEpochMillis = item.optNullableLong("plannedEndEpochMillis"),
                    colorArgb = item.getLong("colorArgb"),
                    categoryIdSnapshot = item.optNullableString("categoryIdSnapshot"),
                    categoryNameSnapshot = item.optNullableString("categoryNameSnapshot"),
                    projectNameSnapshot = item.optNullableString("projectNameSnapshot"),
                    tagsSnapshot = item.optNullableString("tagsSnapshot"),
                    noteSnapshot = item.optNullableString("noteSnapshot"),
                    priority = item.optString("priority", TaskPriorities.MEDIUM),
                    remindersEnabled = item.optBoolean("remindersEnabled", false),
                    remindAtStart = item.optBoolean("remindAtStart", false),
                    remindBeforeEndMinutes = item.optNullableInt("remindBeforeEndMinutes"),
                    remindAtDeadline = item.optBoolean("remindAtDeadline", false),
                    countTowardGoals = item.optBoolean("countTowardGoals", true),
                    sessionMode = item.optString("sessionMode", SessionModes.STANDARD),
                    pomodoroWorkMinutes = item.optNullableInt("pomodoroWorkMinutes"),
                    pomodoroBreakMinutes = item.optNullableInt("pomodoroBreakMinutes"),
                    pomodoroCycles = item.optNullableInt("pomodoroCycles"),
                    sortOrder = item.optInt("sortOrder", 0),
                    createdAtEpochMillis = item.optLong("createdAtEpochMillis", 0L),
                    updatedAtEpochMillis = item.optLong("updatedAtEpochMillis", 0L),
                    completedAtEpochMillis = item.optNullableLong("completedAtEpochMillis"),
                    missedAtEpochMillis = item.optNullableLong("missedAtEpochMillis"),
                    cancelledAtEpochMillis = item.optNullableLong("cancelledAtEpochMillis"),
                    completionSource = item.optNullableString("completionSource"),
                    missSource = item.optNullableString("missSource"),
                    resultNote = item.optNullableString("resultNote"),
                    archived = item.optBoolean("archived", false),
                    archivedAtEpochMillis = item.optNullableLong("archivedAtEpochMillis")
                )
            )
        }
    }

    private fun JSONArray?.toStateList(): List<TaskRuntimeStateEntity> = buildList {
        val array = this@toStateList ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                TaskRuntimeStateEntity(
                    instanceId = item.getString("instanceId"),
                    status = item.getString("status"),
                    accumulatedMillis = item.optLong("accumulatedMillis", 0L),
                    startedAtEpochMillis = item.optNullableLong("startedAtEpochMillis"),
                    startedAtElapsedRealtimeMillis = item.optNullableLong("startedAtElapsedRealtimeMillis"),
                    lastPersistedAtEpochMillis = item.optLong("lastPersistedAtEpochMillis", 0L),
                    lastBreakReminderAtEpochMillis = item.optNullableLong("lastBreakReminderAtEpochMillis"),
                    breakUntilEpochMillis = item.optNullableLong("breakUntilEpochMillis"),
                    version = item.optLong("version", 0L)
                )
            )
        }
    }

    private fun JSONArray?.toSessionList(): List<TaskSessionEntity> = buildList {
        val array = this@toSessionList ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                TaskSessionEntity(
                    id = item.getString("id"),
                    instanceId = item.getString("instanceId"),
                    templateId = item.optNullableString("templateId"),
                    startedAtEpochMillis = item.optLong("startedAtEpochMillis", 0L),
                    endedAtEpochMillis = item.optLong("endedAtEpochMillis", 0L),
                    durationMillis = item.optLong("durationMillis", 0L),
                    source = item.optString("source", SessionSources.MANUAL),
                    createdAtEpochMillis = item.optLong("createdAtEpochMillis", 0L)
                )
            )
        }
    }

    private fun JSONArray?.toEventList(): List<TaskEventLogEntity> = buildList {
        val array = this@toEventList ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                TaskEventLogEntity(
                    id = item.getString("id"),
                    instanceId = item.getString("instanceId"),
                    templateId = item.optNullableString("templateId"),
                    eventType = item.getString("eventType"),
                    atEpochMillis = item.optLong("atEpochMillis", 0L),
                    elapsedRealtimeMillis = item.optNullableLong("elapsedRealtimeMillis"),
                    payloadJson = item.optNullableString("payloadJson")
                )
            )
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        put(key, value ?: JSONObject.NULL)
        return this
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)
}
