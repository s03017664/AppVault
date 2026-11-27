package com.npl.appvault

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class SnackbarRequest(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

class AppVaultViewModel : ViewModel() {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val fileName = "appvault_data.json"
    private val prefsName = "appvault_prefs"

    var apps by mutableStateOf<List<AppRecord>>(emptyList())
        private set
    var customCategories by mutableStateOf<List<String>>(emptyList())
        private set

    // UI 與視圖記憶狀態
    var sortMode by mutableStateOf(SortMode.MANUAL)
    var sortAscending by mutableStateOf(true)
    var filterMode by mutableStateOf(FilterMode.ALL)
    var selectedCategory by mutableStateOf("全部")
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    var accentTheme by mutableStateOf(AccentTheme.OCEAN)
    var compactCardMode by mutableStateOf(true)
    var statsExpanded by mutableStateOf(true)
    var searchQuery by mutableStateOf("")
    var selectedAppId by mutableStateOf<String?>(null)

    // 多選批次模式
    var bulkSelectionMode by mutableStateOf(false)
    var selectedBulkIds by mutableStateOf<Set<String>>(emptySet())

    // 自動化同步與背景檢查
    var autoSyncEnabled by mutableStateOf(true)
    var bgCheckEnabled by mutableStateOf(true)
    var autoGeminiOnStart by mutableStateOf(true)
    var geminiChecking by mutableStateOf(false)
    var lastGeminiCheckLabel by mutableStateOf("")
    var syncState by mutableStateOf(SyncState.IDLE)
    var isSyncing by mutableStateOf(false)
    var snackbarRequest by mutableStateOf<SnackbarRequest?>(null)

    // Share Target 接收到的共享文字
    var incomingSharedText by mutableStateOf<String?>(null)

    var gistToken by mutableStateOf("")
    var gistId by mutableStateOf("")
    var geminiApiKey by mutableStateOf("")

    private var initialized = false
    private var autoSyncJob: Job? = null

    fun showToast(msg: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        snackbarRequest = SnackbarRequest(msg, actionLabel, onAction)
    }

    fun allCategories(): List<String> = (BASE_CATEGORIES + customCategories).distinct()
    fun activeApps(): List<AppRecord> = apps.filter { it.deletedAt == null }
    fun trashApps(): List<AppRecord> = apps.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    fun focusCount(): Int = activeApps().count { it.needsFocusAttention() }


    // ==================== 一登入／開啟自動連上 Gemini 查最新版本（偵測升版自動取消已裝勾選） ====================
    fun autoCheckLatestVersionsFromGemini(context: Context, silent: Boolean = true) {
        if (geminiApiKey.isBlank()) {
            if (!silent) showToast("請先在「連動 Gemini 設定」填入 Gemini API Key 以啟用自動查版")
            return
        }
        if (geminiChecking) return
        geminiChecking = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val existingSummary = activeApps().joinToString("、") {
                        "${it.name}(目前v${it.version}, 建立:${it.createdDate}, 包名:${it.applicationId})"
                    }
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$geminiApiKey"
                    val prompt = """你是個人的 App 版本追蹤助理。請針對以下我開發的 Apps，查詢或回報它們目前最新的版本號（若沒有最新變動則保留原版本號），並補齊建立日期與簡短介紹用法。
請只輸出一個標準 JSON 陣列（可用 ```json 包起來），每個項目包含：
name, version, createdDate (YYYY-MM-DD), category, usageGuide (1-2句用法), supportsDesktopSync (boolean), isPublishedOnGitHub (boolean), githubUrl, applicationId, notes。

現有 Apps 清單：
$existingSummary"""
                    val payloadObj = buildJsonObject {
                        putJsonArray("contents") {
                            addJsonObject {
                                putJsonArray("parts") {
                                    addJsonObject { put("text", prompt) }
                                }
                            }
                        }
                    }
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 20000
                    }
                    conn.outputStream.use { it.write(payloadObj.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val replyText = json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content ?: error("Gemini 無回傳內容")
                    parseGeminiRecordsFromText(replyText)
                }
            }
            geminiChecking = false
            result.onSuccess { items ->
                if (items.isEmpty()) {
                    if (!silent) showToast("Gemini 未回傳可解析的版本資料")
                    return@onSuccess
                }
                val current = apps.toMutableList()
                var nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1
                var bumpedApps = mutableListOf<String>()
                var addedCount = 0

                val installedNow = queryUserInstalledApks(context)
                for (inc in items) {
                    val incNorm = normalizeAppNameKey(inc.name)
                    val idx = current.indexOfFirst { old ->
                        old.deletedAt == null && (
                            old.name.equals(inc.name, ignoreCase = true) ||
                                (inc.applicationId.isNotBlank() && old.applicationId.equals(inc.applicationId, true)) ||
                                (incNorm.isNotBlank() && (normalizeAppNameKey(old.name) == incNorm ||
                                    normalizeAppNameKey(old.name).contains(incNorm) ||
                                    incNorm.contains(normalizeAppNameKey(old.name))))
                        )
                    }
                    if (idx >= 0) {
                        val old = current[idx]
                        val matchedPkg = installedNow.find { pkg ->
                            (old.applicationId.isNotBlank() && pkg.packageName.equals(old.applicationId, true)) ||
                                (inc.applicationId.isNotBlank() && pkg.packageName.equals(inc.applicationId, true)) ||
                                (incNorm.isNotBlank() && normalizeAppNameKey(pkg.label) == incNorm)
                        }
                        val phoneVer = matchedPkg?.versionName?.ifBlank { old.installedPhoneVersion } ?: old.installedPhoneVersion
                        val candidateList = listOfNotNull(
                            inc.version.trim().takeIf { it.isNotBlank() },
                            old.version.trim().takeIf { it.isNotBlank() },
                            old.geminiLatestVersion.trim().takeIf { it.isNotBlank() },
                            phoneVer.trim().takeIf { it.isNotBlank() }
                        )
                        val latestVer = candidateList.maxWithOrNull { a, b -> compareSemanticVersions(a, b) } ?: "1.0"
                        val isNewer = latestVer.isNotBlank() && compareSemanticVersions(latestVer, old.version) > 0
                        val newLog = if (isNewer) {
                            bumpedApps.add("${old.name} 手機v${phoneVer.ifBlank { "?" }} / Gemini v$latestVer")
                            listOf(
                                VersionHistoryEntry(
                                    version = old.version,
                                    notes = old.notes.ifBlank { "Gemini 最新版本 v$latestVer" },
                                    timestamp = old.updatedAt
                                )
                            ) + old.changelog
                        } else old.changelog

                        val matchesLatest = if (phoneVer.isNotBlank()) {
                            compareSemanticVersions(phoneVer, latestVer) == 0
                        } else {
                            if (isNewer) false else old.isInstalledOnPhone && compareSemanticVersions(old.version, latestVer) == 0
                        }

                        val b64 = matchedPkg?.iconBitmap?.let { bitmapToBase64(it, 72) } ?: old.iconBase64
                        val acc = matchedPkg?.iconBitmap?.let { extractDominantColorHex(it) }?.ifBlank { old.accentHex } ?: old.accentHex

                        current[idx] = old.copy(
                            phoneAppLabel = matchedPkg?.label?.ifBlank { old.phoneAppLabel } ?: old.phoneAppLabel,
                            version = latestVer.ifBlank { old.version },
                            geminiLatestVersion = latestVer,
                            installedPhoneVersion = phoneVer,
                            iconBase64 = b64.ifBlank { old.iconBase64 },
                            accentHex = acc,
                            createdDate = old.createdDate.ifBlank { matchedPkg?.createdDate ?: inc.createdDate },
                            category = inc.category.ifBlank { old.category },
                            usageGuide = inc.usageGuide.ifBlank { old.usageGuide },
                            notes = if (isNewer && inc.notes.isNotBlank()) inc.notes else old.notes.ifBlank { inc.notes },
                            githubUrl = old.githubUrl.ifBlank { inc.githubUrl },
                            applicationId = old.applicationId.ifBlank { matchedPkg?.packageName ?: inc.applicationId },
                            syncId = old.syncId.ifBlank { inc.syncId },
                            supportsDesktopSync = old.supportsDesktopSync || inc.supportsDesktopSync,
                            isPublishedOnGitHub = old.isPublishedOnGitHub || inc.isPublishedOnGitHub,
                            isInstalledOnPhone = matchesLatest,
                            isInstalledOnTablet = if (isNewer) false else old.isInstalledOnTablet,
                            changelog = newLog,
                            geminiUpdatedAt = System.currentTimeMillis(),
                            updatedAt = if (isNewer) System.currentTimeMillis() else old.updatedAt
                        )
                    } else {
                        val matchedPkg = installedNow.find { pkg ->
                            (inc.applicationId.isNotBlank() && pkg.packageName.equals(inc.applicationId, true)) ||
                                (incNorm.isNotBlank() && normalizeAppNameKey(pkg.label) == incNorm)
                        }
                        val phoneVer = matchedPkg?.versionName ?: ""
                        val latestVer = inc.version.trim().ifBlank { "1.0" }
                        val matchesLatest = phoneVer.isNotBlank() && compareSemanticVersions(phoneVer, latestVer) == 0
                        current.add(
                            inc.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                phoneAppLabel = matchedPkg?.label ?: "",
                                version = latestVer,
                                geminiLatestVersion = latestVer,
                                installedPhoneVersion = phoneVer,
                                applicationId = inc.applicationId.ifBlank { matchedPkg?.packageName ?: "" },
                                iconBase64 = matchedPkg?.iconBitmap?.let { bitmapToBase64(it, 72) } ?: "",
                                accentHex = matchedPkg?.iconBitmap?.let { extractDominantColorHex(it) } ?: "",
                                createdDate = inc.createdDate.ifBlank { matchedPkg?.createdDate ?: todayDateString() },
                                isInstalledOnPhone = matchesLatest,
                                isInstalledOnTablet = false,
                                sortOrder = nextOrder++,
                                geminiUpdatedAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                                deletedAt = null
                            )
                        )
                        addedCount++
                    }
                    if (inc.category.isNotBlank() && !allCategories().contains(inc.category)) {
                        customCategories = customCategories + inc.category
                    }
                }

                apps = current
                lastGeminiCheckLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                saveUiPrefs(context)
                persist(context, triggerAutoSync = bumpedApps.isNotEmpty() || addedCount > 0)

                if (bumpedApps.isNotEmpty()) {
                    showToast("🔔 偵測到新版本（已自動取消「最新」勾選）：${bumpedApps.take(3).joinToString("、")}")
                } else if (!silent) {
                    showToast("✓ Gemini 查版完成（新增 $addedCount 款，其餘已是記錄最新版本）")
                }
            }.onFailure { err ->
                if (!silent) showToast("Gemini 自動查版失敗：${err.message}")
            }
        }
    }

    fun load(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        gistToken = prefs.getString("gist_token", "") ?: ""
        gistId = prefs.getString("gist_id", "") ?: ""
        geminiApiKey = prefs.getString("gemini_api_key", "") ?: ""
        autoSyncEnabled = prefs.getBoolean("auto_sync", true)
        autoGeminiOnStart = prefs.getBoolean("auto_gemini_start", true)
        lastGeminiCheckLabel = prefs.getString("last_gemini_check", "") ?: ""
        bgCheckEnabled = prefs.getBoolean("bg_check", true)
        compactCardMode = prefs.getBoolean("compact_mode", true)
        statsExpanded = prefs.getBoolean("stats_expanded", true)
        sortAscending = prefs.getBoolean("sort_asc", true)
        selectedCategory = prefs.getString("selected_cat", "全部") ?: "全部"
        runCatching { sortMode = SortMode.valueOf(prefs.getString("sort_mode", SortMode.MANUAL.name)!!) }
        runCatching { filterMode = FilterMode.valueOf(prefs.getString("filter_mode", FilterMode.ALL.name)!!) }
        runCatching { themeMode = ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!) }
        runCatching { accentTheme = AccentTheme.valueOf(prefs.getString("accent_theme", AccentTheme.OCEAN.name)!!) }

        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            runCatching {
                val backup = json.decodeFromString<AppVaultBackup>(file.readText())
                val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                apps = backup.apps.filter { it.deletedAt == null || it.deletedAt > thirtyDaysAgo }
                customCategories = backup.customCategories
            }
        }

        ReleaseCheckWorker.schedule(context, bgCheckEnabled)

        syncInstalledApks(context, silent = true)
        if (autoGeminiOnStart && geminiApiKey.isNotBlank()) {
            autoCheckLatestVersionsFromGemini(context, silent = true)
        }
        if (gistToken.isNotBlank() && gistId.isNotBlank()) {
            syncState = SyncState.SYNCED
            if (autoSyncEnabled) {
                smartMergeFromGist(context, silent = true)
            }
        } else {
            syncState = SyncState.IDLE
        }
    }

    fun saveUiPrefs(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_sync", autoSyncEnabled)
            .putBoolean("auto_gemini_start", autoGeminiOnStart)
            .putString("last_gemini_check", lastGeminiCheckLabel)
            .putBoolean("bg_check", bgCheckEnabled)
            .putBoolean("compact_mode", compactCardMode)
            .putBoolean("stats_expanded", statsExpanded)
            .putBoolean("sort_asc", sortAscending)
            .putString("selected_cat", selectedCategory)
            .putString("sort_mode", sortMode.name)
            .putString("filter_mode", filterMode.name)
            .putString("theme_mode", themeMode.name)
            .putString("accent_theme", accentTheme.name)
            .apply()
    }

    fun setBgCheck(context: Context, enabled: Boolean) {
        bgCheckEnabled = enabled
        saveUiPrefs(context)
        ReleaseCheckWorker.schedule(context, enabled)
        showToast(if (enabled) "已開啟每日背景升級檢查通知" else "已關閉背景檢查通知")
    }

    fun saveGistSettings(context: Context, token: String, id: String, showFeedback: Boolean = true) {
        gistToken = token.trim()
        gistId = extractSyncIdFromRaw(id)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("gist_token", gistToken)
            .putString("gist_id", gistId)
            .apply()
        syncState = if (gistToken.isNotBlank() && gistId.isNotBlank()) SyncState.SYNCED else SyncState.IDLE
        if (showFeedback) showToast("已儲存 GitHub Gist 設定")
    }

    fun saveGeminiApiKey(context: Context, key: String, showFeedback: Boolean = true) {
        geminiApiKey = key.trim()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("gemini_api_key", geminiApiKey)
            .apply()
        if (showFeedback) showToast("已儲存 Gemini API Key")
    }

    fun applyMigrationPayload(context: Context, payload: MigrationConfigPayload) {
        if (payload.gistToken.isNotBlank() || payload.gistId.isNotBlank()) {
            saveGistSettings(context, payload.gistToken, payload.gistId, showFeedback = false)
        }
        if (payload.geminiApiKey.isNotBlank()) {
            saveGeminiApiKey(context, payload.geminiApiKey, showFeedback = false)
        }
        showToast("已成功還原跨裝置連線設定！正在自動同步...")
        if (gistToken.isNotBlank() && gistId.isNotBlank()) {
            smartMergeFromGist(context, silent = false)
        }
    }

    private fun persist(context: Context, triggerAutoSync: Boolean = true) {
        val file = File(context.filesDir, fileName)
        file.writeText(json.encodeToString(AppVaultBackup(customCategories = customCategories, apps = apps)))
        if (triggerAutoSync && autoSyncEnabled && gistToken.isNotBlank() && gistId.isNotBlank()) {
            scheduleDebouncedUpload(context)
        }
    }

    // ==================== 防抖 3 秒自動雲端同步 ====================
    private fun scheduleDebouncedUpload(context: Context) {
        syncState = SyncState.PENDING
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            delay(3000)
            syncState = SyncState.SYNCING
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL("https://api.github.com/gists/$gistId").openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Authorization", "token $gistToken")
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true
                    }
                    val contentEscaped = json.encodeToString(exportJsonString())
                    val body = """{"files":{"appvault_sync.json":{"content":$contentEscaped}}}"""
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode in 200..299
                }.getOrDefault(false)
            }
            syncState = if (ok) SyncState.SYNCED else SyncState.ERROR
        }
    }

    // ==================== 時間戳智慧雙向合併 (不互相覆蓋) ====================
    fun smartMergeFromGist(context: Context, silent: Boolean = false) {
        if (gistToken.isBlank() || gistId.isBlank()) {
            if (!silent) showToast("請先設定 GitHub Token 與 Gist ID")
            return
        }
        syncState = SyncState.SYNCING
        isSyncing = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL("https://api.github.com/gists/$gistId").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "token $gistToken")
                        setRequestProperty("Accept", "application/vnd.github+json")
                    }
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val filesObj = json.parseToJsonElement(raw).jsonObject["files"]?.jsonObject ?: error("Gist 無檔案")
                    val content = (filesObj["appvault_sync.json"] ?: filesObj.values.firstOrNull())?.jsonObject?.get("content")?.jsonPrimitive?.content ?: error("空檔")
                    json.decodeFromString<AppVaultBackup>(content)
                }
            }
            isSyncing = false
            result.onSuccess { remote ->
                val mergedMap = apps.associateBy { it.id }.toMutableMap()
                var updatedFromRemote = 0
                var localAhead = false
                for (rApp in remote.apps) {
                    val local = mergedMap[rApp.id]
                    if (local == null || rApp.updatedAt > local.updatedAt) {
                        mergedMap[rApp.id] = rApp
                        updatedFromRemote++
                    } else if (local.updatedAt > rApp.updatedAt) {
                        localAhead = true
                    }
                }
                if (apps.any { la -> remote.apps.none { it.id == la.id } }) localAhead = true
                apps = mergedMap.values.sortedBy { it.sortOrder }
                customCategories = (customCategories + remote.customCategories).distinct()
                persist(context, triggerAutoSync = localAhead)
                syncState = if (localAhead) SyncState.PENDING else SyncState.SYNCED
                if (!silent) showToast("智慧合併完成：更新 $updatedFromRemote 筆雲端紀錄")
            }.onFailure { err ->
                syncState = SyncState.ERROR
                if (!silent) showToast("智慧合併失敗：${err.message}")
            }
        }
    }

    fun displayedApps(): List<AppRecord> {
        val filtered = activeApps().filter { app ->
            val matchSearch = searchQuery.isBlank() ||
                app.name.contains(searchQuery, ignoreCase = true) ||
                app.version.contains(searchQuery, ignoreCase = true) ||
                app.category.contains(searchQuery, ignoreCase = true) ||
                app.usageGuide.contains(searchQuery, ignoreCase = true) ||
                app.syncId.contains(searchQuery, ignoreCase = true) ||
                app.applicationId.contains(searchQuery, ignoreCase = true) ||
                app.keystoreAlias.contains(searchQuery, ignoreCase = true) ||
                app.notes.contains(searchQuery, ignoreCase = true)
            val matchCat = selectedCategory == "全部" || app.category == selectedCategory
            val matchFilter = when (filterMode) {
                FilterMode.ALL -> true
                FilterMode.FOCUS -> app.needsFocusAttention()
                FilterMode.UNPUBLISHED -> !app.isPublishedOnGitHub
                FilterMode.NEED_INSTALL -> !app.isInstalledOnPhone
                FilterMode.NEED_TABLET -> !app.isInstalledOnTablet
                FilterMode.DESKTOP_SYNC -> app.supportsDesktopSync
            }
            matchSearch && matchCat && matchFilter
        }
        val baseComparator: Comparator<AppRecord> = when (sortMode) {
            SortMode.MANUAL -> compareBy { it.sortOrder }
            SortMode.CREATED_DATE -> compareBy { it.createdDate }
            SortMode.UPDATED_DATE -> compareBy { it.updatedAt }
            SortMode.NAME -> compareBy { it.name.lowercase(Locale.getDefault()) }
            SortMode.VERSION -> Comparator { a, b -> compareSemanticVersions(a.version, b.version) }
            SortMode.CATEGORY -> compareBy<AppRecord> { it.category }.thenBy { it.name.lowercase(Locale.getDefault()) }
        }
        val directed = if (sortAscending) baseComparator else baseComparator.reversed()
        return filtered.sortedWith(compareByDescending<AppRecord> { it.isPinned }.then(directed))
    }

    fun upsertApp(context: Context, edited: AppRecord, autoResetInstallIfVersionChanged: Boolean = true, silent: Boolean = false) {
        val existing = apps.find { it.id == edited.id }
        val finalRecord = if (existing != null) {
            val versionChanged = existing.version.trim() != edited.version.trim()
            val updatedChangelog = if (versionChanged) {
                listOf(
                    VersionHistoryEntry(
                        version = existing.version,
                        notes = existing.notes.ifBlank { "升級至 v${edited.version}" },
                        timestamp = existing.updatedAt
                    )
                ) + edited.changelog
            } else edited.changelog
            val shouldReset = autoResetInstallIfVersionChanged && versionChanged
            if (shouldReset && !silent) {
                showToast("已升級「${edited.name}」至 v${edited.version}，已寫入歷史並取消安裝勾選")
            }
            edited.copy(
                isInstalledOnPhone = if (shouldReset) false else edited.isInstalledOnPhone,
                isInstalledOnTablet = if (shouldReset) false else edited.isInstalledOnTablet,
                changelog = updatedChangelog,
                updatedAt = System.currentTimeMillis(),
                deletedAt = null
            )
        } else {
            val nextOrder = (apps.maxOfOrNull { it.sortOrder } ?: -1) + 1
            edited.copy(
                createdDate = edited.createdDate.ifBlank { todayDateString() },
                sortOrder = nextOrder,
                updatedAt = System.currentTimeMillis(),
                deletedAt = null
            )
        }

        if (finalRecord.category.isNotBlank() && !allCategories().contains(finalRecord.category)) {
            customCategories = customCategories + finalRecord.category
        }

        apps = if (existing != null) {
            apps.map { if (it.id == finalRecord.id) finalRecord else it }
        } else {
            apps + finalRecord
        }
        selectedAppId = finalRecord.id
        persist(context)
    }

    fun moveToTrashWithUndo(context: Context, id: String) {
        val target = apps.find { it.id == id } ?: return
        apps = apps.map { if (it.id == id) it.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()) else it }
        if (selectedAppId == id) selectedAppId = null
        persist(context)
        showToast("已將「${target.name}」移至回收筒", actionLabel = "復原") {
            restoreFromTrash(context, id)
        }
    }

    fun restoreFromTrash(context: Context, id: String) {
        apps = apps.map { if (it.id == id) it.copy(deletedAt = null, updatedAt = System.currentTimeMillis()) else it }
        persist(context)
        showToast("已復原記錄")
    }

    fun emptyTrash(context: Context) {
        apps = apps.filter { it.deletedAt == null }
        persist(context)
        showToast("已永久清空資源回收筒")
    }

    fun togglePin(context: Context, app: AppRecord) = upsertApp(context, app.copy(isPinned = !app.isPinned), false, true)
    fun toggleInstalledPhone(context: Context, app: AppRecord) {
        val wasMatched = app.isPhoneVersionMatched()
        val targetVer = app.effectiveGeminiVersion()
        val updated = if (!wasMatched) {
            app.copy(
                isInstalledOnPhone = true,
                installedPhoneVersion = if (app.installedPhoneVersion.isNotBlank() || app.applicationId.isNotBlank()) targetVer else app.installedPhoneVersion
            )
        } else {
            app.copy(
                isInstalledOnPhone = false,
                installedPhoneVersion = if (app.installedPhoneVersion == targetVer) "" else app.installedPhoneVersion
            )
        }
        upsertApp(context, updated, false, true)
        showToast(
            if (!wasMatched) "「${app.displayTitle()}」已確認更新至最新版本" else "「${app.displayTitle()}」已取消最新勾選",
            actionLabel = "復原"
        ) {
            upsertApp(context, app, false, true)
        }
    }
    fun toggleInstalledTablet(context: Context, app: AppRecord) = upsertApp(context, app.copy(isInstalledOnTablet = !app.isInstalledOnTablet), false, true)
    fun toggleGitHub(context: Context, app: AppRecord) = upsertApp(context, app.copy(isPublishedOnGitHub = !app.isPublishedOnGitHub), false, true)
    fun toggleDesktopSync(context: Context, app: AppRecord) = upsertApp(context, app.copy(supportsDesktopSync = !app.supportsDesktopSync), false, true)

    // ==================== 長按多選批次操作 ====================
    fun toggleBulkSelect(id: String) {
        bulkSelectionMode = true
        selectedBulkIds = if (selectedBulkIds.contains(id)) selectedBulkIds - id else selectedBulkIds + id
        if (selectedBulkIds.isEmpty()) bulkSelectionMode = false
    }

    fun exitBulkMode() {
        bulkSelectionMode = false
        selectedBulkIds = emptySet()
    }

    fun toggleSelectAllDisplayed() {
        val visibleIds = displayedApps().map { it.id }.toSet()
        bulkSelectionMode = true
        selectedBulkIds = if (visibleIds.isNotEmpty() && selectedBulkIds.containsAll(visibleIds)) {
            emptySet()
        } else {
            visibleIds
        }
    }

    fun bulkSetCategory(context: Context, category: String) {
        val count = selectedBulkIds.size
        apps = apps.map { if (selectedBulkIds.contains(it.id)) it.copy(category = category, updatedAt = System.currentTimeMillis()) else it }
        exitBulkMode()
        persist(context)
        showToast("已將 $count 款 App 歸類為「$category」")
    }

    fun bulkSetPublished(context: Context, published: Boolean) {
        val count = selectedBulkIds.size
        apps = apps.map { if (selectedBulkIds.contains(it.id)) it.copy(isPublishedOnGitHub = published, updatedAt = System.currentTimeMillis()) else it }
        exitBulkMode()
        persist(context)
        showToast("已批次更新 $count 款 App 的 GitHub 發布狀態")
    }

    fun bulkSetInstalledPhone(context: Context, installed: Boolean) {
        val count = selectedBulkIds.size
        apps = apps.map { if (selectedBulkIds.contains(it.id)) it.copy(isInstalledOnPhone = installed, updatedAt = System.currentTimeMillis()) else it }
        exitBulkMode()
        persist(context)
        showToast("已批次更新 $count 款 App 的手機安裝狀態")
    }

    fun bulkMoveToTrash(context: Context) {
        val ids = selectedBulkIds.toSet()
        apps = apps.map { if (ids.contains(it.id)) it.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()) else it }
        exitBulkMode()
        persist(context)
        showToast("已將 ${ids.size} 款 App 移至回收筒", actionLabel = "復原") {
            apps = apps.map { if (ids.contains(it.id)) it.copy(deletedAt = null, updatedAt = System.currentTimeMillis()) else it }
            persist(context)
        }
    }

    fun moveItem(context: Context, app: AppRecord, direction: Int) {
        val ordered = activeApps().sortedBy { it.sortOrder }.toMutableList()
        val idx = ordered.indexOfFirst { it.id == app.id }
        val target = idx + direction
        if (idx < 0 || target !in ordered.indices) return
        val item = ordered.removeAt(idx)
        ordered.add(target, item)
        val reorderedMap = ordered.mapIndexed { i, rec -> rec.id to i }.toMap()
        apps = apps.map { rec -> reorderedMap[rec.id]?.let { rec.copy(sortOrder = it) } ?: rec }
        sortMode = SortMode.MANUAL
        sortAscending = true
        persist(context)
    }

    fun addCustomCategory(context: Context, cat: String) {
        val clean = cat.trim()
        if (clean.isBlank() || clean == "全部" || allCategories().contains(clean)) return
        customCategories = customCategories + clean
        persist(context)
        showToast("已新增分類「$clean」")
    }

    fun removeCustomCategory(context: Context, cat: String) {
        customCategories = customCategories.filterNot { it == cat }
        if (selectedCategory == cat) selectedCategory = "全部"
        persist(context)
        showToast("已移除自訂分類「$cat」")
    }

    // ==================== 單一 Repo 全套抓取（介紹 + Release + Actions 編譯燈號） ====================
    fun fetchGitHubRepoMetadata(githubUrl: String, onResult: (desc: String?, releaseTag: String?, actionsStatus: String?, runTitle: String?) -> Unit) {
        val m = Regex("""github\.com/([^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE).find(githubUrl)
        if (m == null) {
            showToast("請輸入有效的 GitHub Repo 網址")
            return
        }
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        isSyncing = true
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                fun getJson(endpoint: String): JsonObject? = runCatching {
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/vnd.github+json")
                        if (gistToken.isNotBlank()) setRequestProperty("Authorization", "token $gistToken")
                    }
                    if (conn.responseCode == 200) {
                        json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
                    } else null
                }.getOrNull()

                val repoObj = getJson("https://api.github.com/repos/$owner/$repo")
                val desc = repoObj?.get("description")?.jsonPrimitive?.contentOrNull?.trim()
                val relObj = getJson("https://api.github.com/repos/$owner/$repo/releases/latest")
                val tag = relObj?.get("tag_name")?.jsonPrimitive?.contentOrNull?.removePrefix("v")?.removePrefix("V")
                val runsObj = getJson("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=1")
                val firstRun = runsObj?.get("workflow_runs")?.jsonArray?.firstOrNull()?.jsonObject
                val statusStr = firstRun?.get("status")?.jsonPrimitive?.contentOrNull
                val conclusion = firstRun?.get("conclusion")?.jsonPrimitive?.contentOrNull
                val mappedStatus = when {
                    statusStr == "in_progress" || statusStr == "queued" -> "in_progress"
                    conclusion == "success" -> "success"
                    conclusion == "failure" || conclusion == "cancelled" || conclusion == "timed_out" -> "failure"
                    else -> ""
                }
                val runTitle = firstRun?.get("display_title")?.jsonPrimitive?.contentOrNull ?: ""
                listOf(desc, tag, mappedStatus, runTitle)
            }
            isSyncing = false
            onResult(res[0], res[1], res[2], res[3])
            showToast("已自動抓取 GitHub Repo 資訊與 Actions 編譯狀態")
        }
    }

    // ==================== 本機 APK 圖示＋主色＋版本掃描 ====================
    fun syncInstalledApks(context: Context, silent: Boolean = false) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val installed = queryUserInstalledApks(context)
                var matchedCount = 0
                val updatedList = apps.map { app ->
                    if (app.deletedAt != null) return@map app
                    val normApp = normalizeAppNameKey(app.name)
                    val match = installed.find { pkg ->
                        val normPkgLabel = normalizeAppNameKey(pkg.label)
                        val normPkgTail = pkg.packageName.substringAfterLast('.').lowercase(Locale.getDefault())
                        (app.applicationId.isNotBlank() && pkg.packageName.equals(app.applicationId, true)) ||
                            (normApp.isNotBlank() && normPkgLabel.isNotBlank() && (
                                normPkgLabel == normApp ||
                                (normApp.length >= 3 && normPkgLabel.contains(normApp)) ||
                                (normPkgLabel.length >= 3 && normApp.contains(normPkgLabel))
                            )) ||
                            (normApp.length >= 4 && normPkgTail.length >= 4 && (normApp.contains(normPkgTail) || normPkgTail.contains(normApp)))
                    }
                    if (match != null) {
                        matchedCount++
                        val b64 = match.iconBitmap?.let { bitmapToBase64(it, 72) } ?: app.iconBase64
                        val accent = match.iconBitmap?.let { extractDominantColorHex(it) }?.ifBlank { app.accentHex } ?: app.accentHex
                        val isLatestInstalled = match.versionName.isNotBlank() &&
                            compareSemanticVersions(match.versionName, app.version) >= 0
                        app.copy(
                            phoneAppLabel = match.label.ifBlank { app.phoneAppLabel },
                            applicationId = app.applicationId.ifBlank { match.packageName },
                            iconBase64 = b64.ifBlank { app.iconBase64 },
                            accentHex = accent,
                            installedPhoneVersion = match.versionName,
                            isInstalledOnPhone = if (match.versionName.isNotBlank()) isLatestInstalled else app.isInstalledOnPhone,
                            createdDate = app.createdDate.ifBlank { match.createdDate }
                        )
                    } else app
                }
                updatedList to matchedCount
            }
            apps = result.first
            persist(context)
            if (!silent) showToast("本機 APK 掃描完成：配對 ${result.second} 款 App 圖示、主色與版本")
        }
    }

    // ==================== ⚡ 一鍵同步更新（APK + Releases + Actions 燈號 + Gist 時間 + 智慧合併） ====================
    fun oneTapSyncAll(context: Context) {
        isSyncing = true
        syncState = SyncState.SYNCING
        viewModelScope.launch {
            val summary = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                val installed = queryUserInstalledApks(context)
                var apkMatched = 0
                var working = apps.map { app ->
                    if (app.deletedAt != null) return@map app
                    val normApp = normalizeAppNameKey(app.name)
                    val match = installed.find { pkg ->
                        val normPkgLabel = normalizeAppNameKey(pkg.label)
                        val normPkgTail = pkg.packageName.substringAfterLast('.').lowercase(Locale.getDefault())
                        (app.applicationId.isNotBlank() && pkg.packageName.equals(app.applicationId, true)) ||
                            (normApp.isNotBlank() && normPkgLabel.isNotBlank() && (
                                normPkgLabel == normApp ||
                                (normApp.length >= 3 && normPkgLabel.contains(normApp)) ||
                                (normPkgLabel.length >= 3 && normApp.contains(normPkgLabel))
                            )) ||
                            (normApp.length >= 4 && normPkgTail.length >= 4 && (normApp.contains(normPkgTail) || normPkgTail.contains(normApp)))
                    }
                    if (match != null) {
                        apkMatched++
                        val b64 = match.iconBitmap?.let { bitmapToBase64(it, 72) } ?: app.iconBase64
                        val accent = match.iconBitmap?.let { extractDominantColorHex(it) }?.ifBlank { app.accentHex } ?: app.accentHex
                        val isLatest = match.versionName.isNotBlank() && compareSemanticVersions(match.versionName, app.version) >= 0
                        app.copy(
                            phoneAppLabel = match.label.ifBlank { app.phoneAppLabel },
                            applicationId = app.applicationId.ifBlank { match.packageName },
                            iconBase64 = b64.ifBlank { app.iconBase64 },
                            accentHex = accent,
                            installedPhoneVersion = match.versionName,
                            isInstalledOnPhone = if (match.versionName.isNotBlank()) isLatest else app.isInstalledOnPhone,
                            createdDate = app.createdDate.ifBlank { match.createdDate }
                        )
                    } else app
                }
                summary.add("APK配對$apkMatched")

                var ghChecked = 0
                working = working.map { app ->
                    if (app.deletedAt != null || !app.githubUrl.contains("github.com/", true)) return@map app
                    val m = Regex("""github\.com/([^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE).find(app.githubUrl) ?: return@map app
                    val owner = m.groupValues[1]
                    val repo = m.groupValues[2].removeSuffix(".git")
                    fun getJson(endpoint: String): JsonObject? = runCatching {
                        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Accept", "application/vnd.github+json")
                            if (gistToken.isNotBlank()) setRequestProperty("Authorization", "token $gistToken")
                        }
                        if (conn.responseCode == 200) json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject else null
                    }.getOrNull()

                    val tag = getJson("https://api.github.com/repos/$owner/$repo/releases/latest")
                        ?.get("tag_name")?.jsonPrimitive?.contentOrNull?.removePrefix("v")?.removePrefix("V")
                    val runObj = getJson("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=1")
                        ?.get("workflow_runs")?.jsonArray?.firstOrNull()?.jsonObject
                    val st = runObj?.get("status")?.jsonPrimitive?.contentOrNull
                    val conc = runObj?.get("conclusion")?.jsonPrimitive?.contentOrNull
                    val actionsState = when {
                        st == "in_progress" || st == "queued" -> "in_progress"
                        conc == "success" -> "success"
                        conc == "failure" || conc == "cancelled" || conc == "timed_out" -> "failure"
                        else -> app.actionsStatus
                    }
                    val runTitle = runObj?.get("display_title")?.jsonPrimitive?.contentOrNull ?: app.actionsRunTitle
                    if (!tag.isNullOrBlank() || actionsState.isNotBlank()) {
                        ghChecked++
                        app.copy(
                            latestGitHubVersion = tag ?: app.latestGitHubVersion,
                            actionsStatus = actionsState,
                            actionsRunTitle = runTitle
                        )
                    } else app
                }
                if (ghChecked > 0) summary.add("GitHub檢查$ghChecked")

                var gistTimeCount = 0
                working = working.map { app ->
                    if (app.deletedAt != null || !app.supportsDesktopSync || app.syncId.isBlank()) return@map app
                    val cleanId = extractSyncIdFromRaw(app.syncId)
                    val upd = runCatching {
                        val conn = (URL("https://api.github.com/gists/$cleanId").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Accept", "application/vnd.github+json")
                            if (gistToken.isNotBlank()) setRequestProperty("Authorization", "token $gistToken")
                        }
                        if (conn.responseCode == 200) {
                            val raw = conn.inputStream.bufferedReader().use { it.readText() }
                            json.parseToJsonElement(raw).jsonObject["updated_at"]?.jsonPrimitive?.content?.let { formatIsoTimestampToLocal(it) }
                        } else null
                    }.getOrNull()
                    if (!upd.isNullOrBlank()) { gistTimeCount++; app.copy(lastGistUpdatedAt = upd) } else app
                }
                if (gistTimeCount > 0) summary.add("Gist時間$gistTimeCount")

                apps = working
                persist(context, triggerAutoSync = false)

                if (gistToken.isNotBlank() && gistId.isNotBlank()) {
                    // 先智慧拉取合併再推送
                    val remote = runCatching {
                        val conn = (URL("https://api.github.com/gists/$gistId").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "token $gistToken")
                            setRequestProperty("Accept", "application/vnd.github+json")
                        }
                        if (conn.responseCode in 200..299) {
                            val raw = conn.inputStream.bufferedReader().use { it.readText() }
                            val filesObj = json.parseToJsonElement(raw).jsonObject["files"]?.jsonObject
                            val content = (filesObj?.get("appvault_sync.json") ?: filesObj?.values?.firstOrNull())?.jsonObject?.get("content")?.jsonPrimitive?.content
                            content?.let { json.decodeFromString<AppVaultBackup>(it) }
                        } else null
                    }.getOrNull()

                    if (remote != null) {
                        val map = apps.associateBy { it.id }.toMutableMap()
                        remote.apps.forEach { ra ->
                            val la = map[ra.id]
                            if (la == null || ra.updatedAt > la.updatedAt) map[ra.id] = ra
                        }
                        apps = map.values.sortedBy { it.sortOrder }
                        customCategories = (customCategories + remote.customCategories).distinct()
                        persist(context, triggerAutoSync = false)
                    }

                    val pushOk = runCatching {
                        val conn = (URL("https://api.github.com/gists/$gistId").openConnection() as HttpURLConnection).apply {
                            requestMethod = "PATCH"
                            setRequestProperty("Authorization", "token $gistToken")
                            setRequestProperty("Accept", "application/vnd.github+json")
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            doOutput = true
                        }
                        val contentEscaped = json.encodeToString(exportJsonString())
                        val body = """{"files":{"appvault_sync.json":{"content":$contentEscaped}}}"""
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        conn.responseCode in 200..299
                    }.getOrDefault(false)
                    summary.add(if (pushOk) "雙向雲端已同步" else "雲端上傳失敗")
                    syncState = if (pushOk) SyncState.SYNCED else SyncState.ERROR
                } else {
                    syncState = SyncState.IDLE
                }
            }
            isSyncing = false
            showToast("⚡ 一鍵同步完成：${summary.joinToString(" · ")}")
        }
    }

    fun mergeGeminiImportedApps(context: Context, incoming: List<AppRecord>): Pair<Int, Int> {
        var added = 0; var updated = 0
        val current = apps.toMutableList()
        var nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1

        for (inc in incoming) {
            val incNorm = normalizeAppNameKey(inc.name)
            val idx = current.indexOfFirst { old ->
                old.deletedAt == null && (
                    old.name.equals(inc.name, ignoreCase = true) ||
                        (inc.applicationId.isNotBlank() && old.applicationId.equals(inc.applicationId, true)) ||
                        (incNorm.isNotBlank() && (normalizeAppNameKey(old.name) == incNorm ||
                            normalizeAppNameKey(old.name).contains(incNorm) ||
                            incNorm.contains(normalizeAppNameKey(old.name))))
                )
            }
            if (idx >= 0) {
                val old = current[idx]
                val versionBumped = inc.version.isNotBlank() && compareSemanticVersions(inc.version, old.version) != 0
                val newLog = if (versionBumped) {
                    listOf(VersionHistoryEntry(old.version, old.notes.ifBlank { "由 Gemini 同步升級至 v${inc.version}" }, old.updatedAt)) + old.changelog
                } else old.changelog
                current[idx] = old.copy(
                    version = inc.version.ifBlank { old.version },
                    createdDate = inc.createdDate.ifBlank { old.createdDate },
                    category = inc.category.ifBlank { old.category },
                    usageGuide = inc.usageGuide.ifBlank { old.usageGuide },
                    notes = inc.notes.ifBlank { old.notes },
                    githubUrl = inc.githubUrl.ifBlank { old.githubUrl },
                    applicationId = inc.applicationId.ifBlank { old.applicationId },
                    syncId = inc.syncId.ifBlank { old.syncId },
                    supportsDesktopSync = old.supportsDesktopSync || inc.supportsDesktopSync,
                    isPublishedOnGitHub = old.isPublishedOnGitHub || inc.isPublishedOnGitHub,
                    isInstalledOnPhone = if (versionBumped) false else old.isInstalledOnPhone,
                    isInstalledOnTablet = if (versionBumped) false else old.isInstalledOnTablet,
                    changelog = newLog,
                    updatedAt = System.currentTimeMillis()
                )
                updated++
            } else {
                current.add(inc.copy(id = UUID.randomUUID().toString(), createdDate = inc.createdDate.ifBlank { todayDateString() }, sortOrder = nextOrder++, updatedAt = System.currentTimeMillis()))
                added++
            }
            if (inc.category.isNotBlank() && !allCategories().contains(inc.category)) customCategories = customCategories + inc.category
        }
        apps = current
        persist(context)
        return added to updated
    }

    fun extractViaGeminiApi(context: Context, freeformText: String, onDone: () -> Unit) {
        if (geminiApiKey.isBlank()) { showToast("請先填寫 Gemini API Key"); return }
        if (freeformText.isBlank()) { showToast("請先貼上開發紀錄或對話內容"); return }
        isSyncing = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$geminiApiKey"
                    val prompt = """請從以下內容萃取出所有提及的自製 App / APK / 網頁工具，並只回覆一個 JSON 陣列（不要多餘說明）。
每個項目包含欄位：name, version, createdDate (YYYY-MM-DD，沒有請填 ${todayDateString()}), category (教學工具/個人工具/生活記錄/系統工具/AI 與開發/相片媒體/其他), usageGuide (1-2句介紹用法), supportsDesktopSync (boolean), isPublishedOnGitHub (boolean), githubUrl, applicationId, notes。

內容如下：
$freeformText"""
                    val payloadObj = buildJsonObject {
                        putJsonArray("contents") { addJsonObject { putJsonArray("parts") { addJsonObject { put("text", prompt) } } } }
                    }
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true
                    }
                    conn.outputStream.use { it.write(payloadObj.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) error("Gemini API HTTP ${conn.responseCode}")
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val replyText = json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: error("無回傳")
                    parseGeminiRecordsFromText(replyText)
                }
            }
            isSyncing = false
            result.onSuccess { extracted ->
                if (extracted.isEmpty()) showToast("Gemini 未能萃取出 App 資料")
                else {
                    val (added, updated) = mergeGeminiImportedApps(context, extracted)
                    syncInstalledApks(context, silent = true)
                    showToast("Gemini AI 同步完成：新增 $added 款、更新 $updated 款")
                    onDone()
                }
            }.onFailure { err -> showToast("Gemini API 失敗：${err.message}") }
        }
    }

    fun batchApplySyncIds(context: Context, mappings: List<Pair<String, String>>): Pair<Int, List<String>> {
        if (mappings.isEmpty()) return 0 to emptyList()
        var matchedCount = 0; val unmatchedNames = mutableListOf<String>()
        val updatedApps = apps.toMutableList()
        for ((rawName, newSyncId) in mappings) {
            val targetNorm = normalizeAppNameKey(rawName)
            val idx = updatedApps.indexOfFirst { app ->
                app.deletedAt == null && (app.name.equals(rawName, true) ||
                    (targetNorm.isNotBlank() && (normalizeAppNameKey(app.name) == targetNorm ||
                        normalizeAppNameKey(app.name).contains(targetNorm) || targetNorm.contains(normalizeAppNameKey(app.name)))))
            }
            if (idx >= 0) {
                updatedApps[idx] = updatedApps[idx].copy(supportsDesktopSync = true, syncId = newSyncId, updatedAt = System.currentTimeMillis())
                matchedCount++
            } else unmatchedNames.add(rawName)
        }
        if (matchedCount > 0) { apps = updatedApps; persist(context) }
        return matchedCount to unmatchedNames
    }

    fun pushToGist(context: Context) {
        if (gistToken.isBlank() || gistId.isBlank()) { showToast("請先設定 GitHub Token 與 Gist ID"); return }
        isSyncing = true; syncState = SyncState.SYNCING
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL("https://api.github.com/gists/$gistId").openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Authorization", "token $gistToken")
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true
                    }
                    val contentEscaped = json.encodeToString(exportJsonString())
                    val body = """{"files":{"appvault_sync.json":{"content":$contentEscaped}}}"""
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                }
            }
            isSyncing = false
            syncState = if (res.isSuccess) SyncState.SYNCED else SyncState.ERROR
            showToast(if (res.isSuccess) "已上傳同步至 GitHub Gist" else "上傳失敗：${res.exceptionOrNull()?.message}")
        }
    }

    fun buildTodoMarkdown(): String = buildString {
        appendLine("# AppVault 待辦清單 (${todayDateString()})")
        val unpub = activeApps().filter { !it.isPublishedOnGitHub }
        val needInst = activeApps().filter { !it.isInstalledOnPhone }
        appendLine("## 尚未發布 GitHub (${unpub.size})")
        if (unpub.isEmpty()) appendLine("- 全部已發布！") else unpub.forEach { appendLine("- [ ] **${it.name}** v${it.version}（建立：${it.createdDate}）") }
        appendLine()
        appendLine("## 手機待安裝／待升級 (${needInst.size})")
        if (needInst.isEmpty()) appendLine("- 全部已是最新！") else needInst.forEach {
            val instInfo = if (it.installedPhoneVersion.isNotBlank()) "，實裝 v${it.installedPhoneVersion}" else ""
            appendLine("- [ ] **${it.name}** 最新 v${it.version}$instInfo")
        }
    }

    fun exportJsonString(): String = json.encodeToString(AppVaultBackup(customCategories = customCategories, apps = apps))

    fun exportCsvString(): String {
        val header = "名稱,分類,版本,手機實裝版本,建立日期,介紹用法,置頂,已發布GitHub,Actions狀態,手機已裝,平板已裝,桌面同步,同步ID,雲端Gist最後更新,ApplicationId,KeystoreAlias,APK雜湊,GitHub連結,備註,更新日期"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val rows = activeApps().sortedBy { it.sortOrder }.map { a ->
            fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
            listOf(
                esc(a.name), esc(a.category), esc(a.version), esc(a.installedPhoneVersion),
                esc(a.createdDate), esc(a.usageGuide),
                if (a.isPinned) "是" else "否",
                if (a.isPublishedOnGitHub) "是" else "否",
                esc(a.actionsStatus),
                if (a.isInstalledOnPhone) "是" else "否",
                if (a.isInstalledOnTablet) "是" else "否",
                if (a.supportsDesktopSync) "是" else "否",
                esc(a.syncId), esc(a.lastGistUpdatedAt),
                esc(a.applicationId), esc(a.keystoreAlias), esc(a.apkHash),
                esc(a.githubUrl), esc(a.notes), esc(fmt.format(Date(a.updatedAt)))
            ).joinToString(",")
        }
        return "\uFEFF" + (listOf(header) + rows).joinToString("\n")
    }

    fun exportSyncTemplateCsv(): String {
        val header = "App 名稱,同步 ID"
        val syncApps = activeApps().filter { it.supportsDesktopSync }.ifEmpty { activeApps() }
        val rows = syncApps.map { a -> "\"${a.name.replace("\"", "\"\"")}\",${a.syncId}" }
        return "\uFEFF" + (listOf(header) + rows).joinToString("\n")
    }

    fun parseImportJson(raw: String): Result<AppVaultBackup> = runCatching {
        json.decodeFromString<AppVaultBackup>(raw)
    }

    fun applyImport(context: Context, imported: AppVaultBackup, overwrite: Boolean) {
        if (overwrite) {
            apps = imported.apps.mapIndexed { i, rec -> rec.copy(sortOrder = i) }
            customCategories = imported.customCategories
        } else {
            val map = apps.associateBy { it.id }.toMutableMap()
            var nextOrder = (apps.maxOfOrNull { it.sortOrder } ?: -1) + 1
            imported.apps.forEach { rec ->
                map[rec.id] = if (map.containsKey(rec.id)) rec else rec.copy(sortOrder = nextOrder++)
            }
            apps = map.values.sortedBy { it.sortOrder }
            customCategories = (customCategories + imported.customCategories).distinct()
        }
        persist(context)
        showToast(if (overwrite) "已覆蓋匯入 ${imported.apps.size} 筆記錄" else "已合併匯入 ${imported.apps.size} 筆記錄")
    }
}
