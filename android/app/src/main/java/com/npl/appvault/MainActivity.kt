package com.npl.appvault

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var vm: AppVaultViewModel

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[AppVaultViewModel::class.java]
        handleShareIntent(intent)

        setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) { vm.load(context) }

            val windowSize = calculateWindowSizeClass(this)
            val isWide = windowSize.widthSizeClass != WindowWidthSizeClass.Compact
            val darkTheme = when (vm.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            val accent = vm.accentTheme
            val colorScheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(accent.darkPrimary),
                    secondary = Color(accent.secondary),
                    tertiary = Color(0xFF2DD4BF),
                    background = Color(0xFF0B1120),
                    surface = Color(0xFF111827),
                    surfaceVariant = Color(0xFF1E293B)
                )
            } else {
                lightColorScheme(
                    primary = Color(accent.lightPrimary),
                    secondary = Color(accent.secondary),
                    tertiary = Color(0xFF0D9488),
                    background = Color(0xFFF8FAFC),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFE2E8F0)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                AppVaultRootScreen(vm = vm, isWideScreen = isWide)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                vm.incomingSharedText = shared
            }
        }
    }
}

@Composable
fun AppApkIconBadge(
    appName: String,
    applicationId: String,
    iconBase64: String,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(applicationId, iconBase64) {
        loadInstalledApkIcon(context, applicationId) ?: base64ToBitmap(iconBase64)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "$appName 圖示",
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(10.dp))
        )
    } else {
        val colors = listOf(Color(0xFF0284C7), Color(0xFF4F46E5), Color(0xFF0D9488), Color(0xFFD97706), Color(0xFF7C3AED))
        val bg = colors[kotlin.math.abs(appName.hashCode()) % colors.size]
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(size).clip(RoundedCornerShape(10.dp)).background(bg.copy(alpha = 0.2f))
        ) {
            Text(
                text = appName.trim().take(2).ifBlank { "AP" },
                color = bg,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun CompletionRingCard(
    label: String,
    numerator: Int,
    denominator: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val ratio = if (denominator > 0) (numerator.toFloat() / denominator.toFloat()).coerceIn(0f, 1f) else 0f
    val pct = (ratio * 100).toInt()
    Surface(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable { onClick() },
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
                CircularProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.size(34.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.22f),
                    strokeWidth = 3.5.dp
                )
                Text("$pct%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("$numerator/$denominator", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppVaultRootScreen(vm: AppVaultViewModel, isWideScreen: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var editingApp by remember { mutableStateOf<AppRecord?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var showGeminiDialog by remember { mutableStateOf(false) }
    var geminiPreFillText by remember { mutableStateOf("") }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showMigrationDialog by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showBatchSyncDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var showBulkCatDialog by remember { mutableStateOf(false) }
    var qrTargetApp by remember { mutableStateOf<AppRecord?>(null) }
    var pendingImport by remember { mutableStateOf<AppVaultBackup?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // 請求通知權限 (Android 13+)
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.showToast("已授權通知權限，每日背景檢查已就緒")
    }

    // 接收 Android Share Target（從 Gemini 直接分享）
    LaunchedEffect(vm.incomingSharedText) {
        vm.incomingSharedText?.let { shared ->
            geminiPreFillText = shared
            showGeminiDialog = true
            vm.incomingSharedText = null
        }
    }

    // 支援 Undo 復原的 Snackbar
    LaunchedEffect(vm.snackbarRequest) {
        vm.snackbarRequest?.let { req ->
            vm.snackbarRequest = null
            val res = snackbarHostState.showSnackbar(
                message = req.message,
                actionLabel = req.actionLabel,
                duration = if (req.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (res == SnackbarResult.ActionPerformed) {
                req.onAction?.invoke()
            }
        }
    }

    val ts = remember { SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()) }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { os -> os.write(vm.exportJsonString().toByteArray()) }
            vm.showToast("已匯出 JSON 備份檔")
        }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { os -> os.write(vm.exportCsvString().toByteArray()) }
            vm.showToast("已匯出 CSV 試算表")
        }
    }
    val exportTemplateCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { os -> os.write(vm.exportSyncTemplateCsv().toByteArray()) }
            vm.showToast("已匯出批次同步 ID 範本")
        }
    }
    val importJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            if (text != null) {
                vm.parseImportJson(text)
                    .onSuccess { backup -> pendingImport = backup }
                    .onFailure { err -> vm.showToast("匯入失敗：${err.message}") }
            }
        }
    }
    val batchImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            if (!text.isNullOrBlank()) {
                val pairs = parseBatchSyncMappings(text)
                if (pairs.isEmpty()) vm.showToast("檔案內未找到有效的「App 名稱 + 同步 ID」配對")
                else {
                    val (matched, unmatched) = vm.batchApplySyncIds(context, pairs)
                    vm.showToast(if (unmatched.isEmpty()) "批次更新 $matched 款 App" else "已配對 $matched 款（未找到：${unmatched.take(3).joinToString("、")}）")
                    showBatchSyncDialog = false
                }
            }
        }
    }

    val activeList = vm.activeApps()
    val totalCount = activeList.size
    val publishedCount = activeList.count { it.isPublishedOnGitHub }
    val installedPhoneCount = activeList.count { it.isInstalledOnPhone }
    val focusCount = vm.focusCount()
    val list = vm.displayedApps()
    val categoriesWithAll = listOf("全部") + vm.allCategories()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (vm.bulkSelectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    navigationIcon = {
                        IconButton(onClick = { vm.exitBulkMode() }) { Icon(Icons.Default.Close, "退出多選") }
                    },
                    title = { Text("已選 ${vm.selectedBulkIds.size}/${list.size}", fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = { vm.toggleSelectAllDisplayed() }) {
                            val allSelected = list.isNotEmpty() && vm.selectedBulkIds.containsAll(list.map { it.id })
                            Text(if (allSelected) "取消全選" else "全選", fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showBulkCatDialog = true }, enabled = vm.selectedBulkIds.isNotEmpty()) { Icon(Icons.Default.Apps, "批次改分類") }
                        IconButton(onClick = { vm.bulkSetInstalledPhone(context, true) }, enabled = vm.selectedBulkIds.isNotEmpty()) { Icon(Icons.Default.PhoneAndroid, "批次標記手機已裝") }
                        IconButton(onClick = { vm.bulkMoveToTrash(context) }, enabled = vm.selectedBulkIds.isNotEmpty()) { Icon(Icons.Default.Delete, "批次刪除") }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("AppVault", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(6.dp))
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        BuildConfig.APP_VERSION_LABEL,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            // 無感自動同步狀態微提示
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val dotColor = when (vm.syncState) {
                                    SyncState.SYNCED -> Color(0xFF10B981)
                                    SyncState.PENDING, SyncState.SYNCING -> Color(0xFFF59E0B)
                                    SyncState.ERROR -> Color(0xFFEF4444)
                                    SyncState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                                val gemTag = if (vm.geminiChecking) " · Gemini 查版中" else if (vm.lastGeminiCheckLabel.isNotBlank()) " · Gemini ${vm.lastGeminiCheckLabel}" else ""
                                Text("${vm.syncState.label}$gemTag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    actions = {
                        if (vm.isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = { showStatsDialog = true }) {
                            Icon(Icons.Default.BarChart, contentDescription = "開發統計面板")
                        }
                        IconButton(onClick = {
                            vm.compactCardMode = !vm.compactCardMode
                            vm.saveUiPrefs(context)
                            vm.showToast(if (vm.compactCardMode) "已切換：極簡緊湊視圖" else "已切換：完整詳情視圖")
                        }) {
                            Icon(
                                if (vm.compactCardMode) Icons.Default.ViewAgenda else Icons.Default.ViewHeadline,
                                contentDescription = "極簡／詳情卡片切換"
                            )
                        }
                        IconButton(onClick = { vm.oneTapSyncAll(context) }) {
                            Icon(Icons.Default.Sync, contentDescription = "一鍵同步更新", tint = Color(0xFF10B981))
                        }
                        IconButton(onClick = {
                            vm.bulkSelectionMode = true
                            vm.selectedBulkIds = emptySet()
                        }) {
                            Icon(Icons.Default.Checklist, contentDescription = "多選／全選刪除")
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("✨ 連動 Gemini 即時紀錄") },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                    onClick = { showMoreMenu = false; geminiPreFillText = ""; showGeminiDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("☁️ GitHub Gist 與自動同步設定") },
                                    leadingIcon = { Icon(Icons.Default.CloudSync, null) },
                                    onClick = { showMoreMenu = false; showSyncDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("🔐 跨裝置設定加密 QR 遷移") },
                                    leadingIcon = { Icon(Icons.Default.QrCode2, null) },
                                    onClick = { showMoreMenu = false; showMigrationDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("🎨 外觀主題與強調色 (${vm.accentTheme.label})") },
                                    leadingIcon = { Icon(Icons.Default.Palette, null) },
                                    onClick = { showMoreMenu = false; showThemeDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("📱 掃描本機已裝 APK 圖示與版本") },
                                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                                    onClick = { showMoreMenu = false; vm.syncInstalledApks(context) }
                                )
                                DropdownMenuItem(
                                    text = { Text("📥 批次匯入多個同步 ID") },
                                    leadingIcon = { Icon(Icons.Default.PlaylistAddCheck, null) },
                                    onClick = { showMoreMenu = false; showBatchSyncDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("🏷 管理自訂分類標籤") },
                                    leadingIcon = { Icon(Icons.Default.Apps, null) },
                                    onClick = { showMoreMenu = false; showCategoryDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("🔔 背景升級提醒：${if (vm.bgCheckEnabled) "開啟" else "關閉"}") },
                                    leadingIcon = { Icon(Icons.Default.NotificationsActive, null) },
                                    onClick = {
                                        showMoreMenu = false
                                        val next = !vm.bgCheckEnabled
                                        if (next && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        vm.setBgCheck(context, next)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("📋 複製待辦清單 (Markdown)") },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                    onClick = {
                                        showMoreMenu = false
                                        clipboard.setText(AnnotatedString(vm.buildTodoMarkdown()))
                                        vm.showToast("已複製 Markdown 待辦清單")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("🗑 資源回收筒 (${vm.trashApps().size})") },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                    onClick = { showMoreMenu = false; showTrashDialog = true }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("匯入 JSON 備份") },
                                    leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                    onClick = { showMoreMenu = false; importJsonLauncher.launch(arrayOf("application/json", "*/*")) }
                                )
                                DropdownMenuItem(
                                    text = { Text("匯出 JSON 備份") },
                                    leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                    onClick = { showMoreMenu = false; exportJsonLauncher.launch("AppVault_${ts.format(Date())}.json") }
                                )
                                DropdownMenuItem(
                                    text = { Text("匯出 CSV 表格") },
                                    leadingIcon = { Icon(Icons.Default.TableChart, null) },
                                    onClick = { showMoreMenu = false; exportCsvLauncher.launch("AppVault_${ts.format(Date())}.csv") }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!vm.bulkSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val fresh = AppRecord()
                        editingApp = fresh
                        if (isWideScreen) vm.selectedAppId = fresh.id else showEditorDialog = true
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("新增 App") }
                )
            }
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isWideScreen) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    FilterMode.entries.forEach { mode ->
                        NavigationRailItem(
                            selected = vm.filterMode == mode,
                            onClick = { vm.filterMode = mode; vm.saveUiPrefs(context) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (mode == FilterMode.FOCUS && focusCount > 0) {
                                            Badge { Text("$focusCount") }
                                        }
                                    }
                                ) {
                                    Icon(
                                        when (mode) {
                                            FilterMode.ALL -> Icons.Default.Apps
                                            FilterMode.FOCUS -> Icons.Default.Bolt
                                            FilterMode.UNPUBLISHED -> Icons.Default.CloudOff
                                            FilterMode.NEED_INSTALL -> Icons.Default.PhoneAndroid
                                            FilterMode.NEED_TABLET -> Icons.Default.TabletAndroid
                                            FilterMode.DESKTOP_SYNC -> Icons.Default.Computer
                                        }, contentDescription = mode.label
                                    )
                                }
                            },
                            label = { Text(mode.label, maxLines = 1) }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(if (isWideScreen) 1.12f else 1f)
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp)
            ) {
                // 可摺疊圓環完成率儀表板
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (vm.filterMode == FilterMode.FOCUS) "⚡ 今日待處理專注視圖 ($focusCount)" else "開發儀表板 · 共 $totalCount 款",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { vm.statsExpanded = !vm.statsExpanded; vm.saveUiPrefs(context) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(if (vm.statsExpanded) "收合儀表 ▲" else "展開儀表 ▼", style = MaterialTheme.typography.labelSmall)
                    }
                }

                AnimatedVisibility(visible = vm.statsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompletionRingCard(
                                "GitHub 發布", publishedCount, totalCount, MaterialTheme.colorScheme.primary,
                                Modifier.weight(1f)
                            ) { vm.filterMode = FilterMode.UNPUBLISHED; vm.saveUiPrefs(context) }
                            CompletionRingCard(
                                "手機最新", installedPhoneCount, totalCount, Color(0xFF10B981),
                                Modifier.weight(1f)
                            ) { vm.filterMode = FilterMode.NEED_INSTALL; vm.saveUiPrefs(context) }
                            Surface(
                                modifier = Modifier.weight(0.85f).clip(RoundedCornerShape(14.dp)).clickable {
                                    vm.filterMode = if (vm.filterMode == FilterMode.FOCUS) FilterMode.ALL else FilterMode.FOCUS
                                    vm.saveUiPrefs(context)
                                },
                                color = if (focusCount > 0) Color(0xFFF59E0B).copy(alpha = 0.16f) else Color(0xFF10B981).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        if (focusCount > 0) "$focusCount 待辦" else "✓ 清零",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (focusCount > 0) Color(0xFFF59E0B) else Color(0xFF10B981)
                                    )
                                    Text("今日專注", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { geminiPreFillText = ""; showGeminiDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("連動 Gemini", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = { vm.oneTapSyncAll(context) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Sync, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("一鍵同步", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = vm.searchQuery,
                    onValueChange = { vm.searchQuery = it },
                    placeholder = { Text("搜尋名稱、版本、用法、同步 ID...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (vm.searchQuery.isNotBlank()) {
                            IconButton(onClick = { vm.searchQuery = "" }) { Icon(Icons.Default.Close, "清除") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // 窄屏專注與狀態篩選列
                if (!isWideScreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterMode.entries.forEach { mode ->
                            val badge = if (mode == FilterMode.FOCUS && focusCount > 0) " ($focusCount)" else ""
                            FilterChip(
                                selected = vm.filterMode == mode,
                                onClick = { vm.filterMode = mode; vm.saveUiPrefs(context) },
                                label = { Text("${mode.label}$badge", maxLines = 1) }
                            )
                        }
                    }
                }

                // 分類列 + 排序列
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    categoriesWithAll.forEach { cat ->
                        val count = if (cat == "全部") activeList.size else activeList.count { it.category == cat }
                        FilterChip(
                            selected = vm.selectedCategory == cat,
                            onClick = { vm.selectedCategory = cat; vm.saveUiPrefs(context) },
                            label = { Text("$cat ($count)") }
                        )
                    }
                    AssistChip(onClick = { showCategoryDialog = true }, label = { Text("＋分類") })
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { vm.sortAscending = !vm.sortAscending; vm.saveUiPrefs(context) },
                        leadingIcon = { Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(15.dp)) },
                        label = { Text(if (vm.sortAscending) "正序" else "倒序") }
                    )
                    SortMode.entries.forEach { mode ->
                        AssistChip(
                            onClick = {
                                if (vm.sortMode == mode) vm.sortAscending = !vm.sortAscending
                                else {
                                    vm.sortMode = mode
                                    vm.sortAscending = mode in listOf(SortMode.MANUAL, SortMode.NAME, SortMode.CATEGORY)
                                }
                                vm.saveUiPrefs(context)
                            },
                            label = { Text(mode.label) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (vm.sortMode == mode) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            )
                        )
                    }
                }

                if (list.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (vm.filterMode == FilterMode.FOCUS) Icons.Default.CheckCircle else Icons.Default.Widgets,
                                null, modifier = Modifier.size(48.dp),
                                tint = if (vm.filterMode == FilterMode.FOCUS) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (vm.filterMode == FilterMode.FOCUS) "太棒了！今日所有待辦項目都已處理完畢" else "暫無符合條件的 App",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(list, key = { _, item -> item.id }) { index, app ->
                            val swipeState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.toggleInstalledPhone(context, app)
                                            false
                                        }
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.moveToTrashWithUndo(context, app.id)
                                            false
                                        }
                                        else -> false
                                    }
                                }
                            )

                            SwipeToDismissBox(
                                state = swipeState,
                                enableDismissFromStartToEnd = !vm.bulkSelectionMode,
                                enableDismissFromEndToStart = !vm.bulkSelectionMode,
                                backgroundContent = {
                                    val dir = swipeState.dismissDirection
                                    val bg = when (dir) {
                                        SwipeToDismissBoxValue.StartToEnd -> Color(0xFF10B981).copy(alpha = 0.22f)
                                        SwipeToDismissBoxValue.EndToStart -> Color(0xFFEF4444).copy(alpha = 0.22f)
                                        else -> Color.Transparent
                                    }
                                    Box(
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 20.dp),
                                        contentAlignment = if (dir == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        if (dir == SwipeToDismissBoxValue.StartToEnd) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF10B981))
                                                Spacer(Modifier.width(6.dp))
                                                Text(if (app.isInstalledOnPhone) "取消手機已裝" else "標記手機已裝最新", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                                            }
                                        } else if (dir == SwipeToDismissBoxValue.EndToStart) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("移至回收筒", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.width(6.dp))
                                                Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444))
                                            }
                                        }
                                    }
                                }
                            ) {
                                AppRecordCard(
                                    app = app,
                                    compactMode = vm.compactCardMode,
                                    isSelected = isWideScreen && vm.selectedAppId == app.id,
                                    bulkMode = vm.bulkSelectionMode,
                                    isBulkChecked = vm.selectedBulkIds.contains(app.id),
                                    showReorder = !vm.bulkSelectionMode && vm.sortMode == SortMode.MANUAL && vm.sortAscending && vm.filterMode == FilterMode.ALL && vm.selectedCategory == "全部" && vm.searchQuery.isBlank(),
                                    canMoveUp = index > 0,
                                    canMoveDown = index < list.lastIndex,
                                    onMoveUp = { vm.moveItem(context, app, -1) },
                                    onMoveDown = { vm.moveItem(context, app, +1) },
                                    onTogglePin = { vm.togglePin(context, app) },
                                    onTogglePhone = { vm.toggleInstalledPhone(context, app) },
                                    onToggleTablet = { vm.toggleInstalledTablet(context, app) },
                                    onToggleGitHub = { vm.toggleGitHub(context, app) },
                                    onToggleDesktopSync = { vm.toggleDesktopSync(context, app) },
                                    onLaunchApp = {
                                        if (app.applicationId.isNotBlank()) {
                                            val launch = context.packageManager.getLaunchIntentForPackage(app.applicationId)
                                            if (launch != null) {
                                                context.startActivity(launch)
                                            } else {
                                                vm.showToast("此手機尚未安裝 ${app.applicationId}")
                                            }
                                        } else {
                                            vm.showToast("尚未綁定 applicationId，請先點「一鍵同步」自動配對")
                                        }
                                    },
                                    onCopySyncId = {
                                        if (app.syncId.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(app.syncId))
                                            vm.showToast("已複製「${app.name}」的同步 ID")
                                        }
                                    },
                                    onOpenGist = {
                                        if (app.syncId.isNotBlank()) {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(buildGistUrl(app.syncId))))
                                        }
                                    },
                                    onOpenActions = {
                                        if (app.githubUrl.startsWith("http")) {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${app.githubUrl.trimEnd('/')}/actions")))
                                        } else vm.showToast("尚未填寫 GitHub 連結")
                                    },
                                    onShowQr = { if (app.syncId.isNotBlank()) qrTargetApp = app },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleBulkSelect(app.id)
                                    },
                                    onClick = {
                                        if (vm.bulkSelectionMode) {
                                            vm.toggleBulkSelect(app.id)
                                        } else {
                                            vm.selectedAppId = app.id
                                            editingApp = app
                                            if (!isWideScreen) showEditorDialog = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 右欄：Fold 7 展開／平板自動儲存編輯區
            if (isWideScreen) {
                VerticalDivider()
                Box(modifier = Modifier.weight(0.95f).fillMaxHeight().padding(16.dp)) {
                    val active = editingApp ?: activeList.find { it.id == vm.selectedAppId }
                    if (active != null) {
                        AppEditorPanel(
                            initial = active,
                            categories = vm.allCategories(),
                            isExisting = activeList.any { it.id == active.id },
                            autoSaveEnabled = true,
                            onNotify = { vm.showToast(it) },
                            onFetchGitHub = { url, cb -> vm.fetchGitHubRepoMetadata(url, cb) },
                            onShowQr = { previewSyncId -> qrTargetApp = active.copy(syncId = previewSyncId) },
                            onSave = { updated, silent ->
                                vm.upsertApp(context, updated, silent = silent)
                                editingApp = vm.activeApps().find { it.id == updated.id }
                            },
                            onDelete = {
                                vm.moveToTrashWithUndo(context, active.id)
                                editingApp = null
                            }
                        )
                    } else {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Widgets, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            Text("選擇左側 App 即時編輯（支援自動儲存），\n點擊 App 圖示可直接啟動測試", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }

    // 窄屏編輯視窗
    if (showEditorDialog && editingApp != null) {
        AlertDialog(
            onDismissRequest = { showEditorDialog = false },
            confirmButton = {},
            text = {
                AppEditorPanel(
                    initial = editingApp!!,
                    categories = vm.allCategories(),
                    isExisting = activeList.any { it.id == editingApp!!.id },
                    autoSaveEnabled = false,
                    onNotify = { vm.showToast(it) },
                    onFetchGitHub = { url, cb -> vm.fetchGitHubRepoMetadata(url, cb) },
                    onShowQr = { previewSyncId -> qrTargetApp = editingApp!!.copy(syncId = previewSyncId) },
                    onSave = { updated, _ ->
                        vm.upsertApp(context, updated)
                        showEditorDialog = false
                    },
                    onDelete = {
                        vm.moveToTrashWithUndo(context, editingApp!!.id)
                        showEditorDialog = false
                    }
                )
            }
        )
    }

    // 1. ✨ 連動 Gemini（含 Share Target 接收）
    if (showGeminiDialog) {
        var inputText by remember(geminiPreFillText) { mutableStateOf(geminiPreFillText) }
        var apiKeyInput by remember { mutableStateOf(vm.geminiApiKey) }
        var showApiSettings by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showGeminiDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("連動 Gemini 即時紀錄")
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("在 Gemini 聊天中可直接按「分享 → AppVault」，或點擊下方複製指令：", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(buildGeminiSyncPrompt(vm.activeApps())))
                            vm.showToast("已複製同步指令！正在開啟 Gemini...")
                            val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")
                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app"))
                            runCatching { context.startActivity(launch) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("1. 複製指令並開啟 Gemini")
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("2. 貼上或由系統分享傳入的內容") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { clipboard.getText()?.text?.let { if (it.isNotBlank()) inputText = it } },
                            modifier = Modifier.weight(0.9f)
                        ) { Text("貼上") }
                        Button(
                            onClick = {
                                val parsed = parseGeminiRecordsFromText(inputText)
                                if (parsed.isNotEmpty()) {
                                    val (added, updated) = vm.mergeGeminiImportedApps(context, parsed)
                                    vm.syncInstalledApks(context, silent = true)
                                    vm.showToast("✨ 即時同步：新增 $added 款、更新 $updated 款")
                                    showGeminiDialog = false
                                } else {
                                    vm.showToast("未偵測到 JSON；如為純文字請使用下方 AI 萃取")
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier.weight(1.4f)
                        ) { Text("解析 JSON 並更新") }
                    }
                    HorizontalDivider()
                    TextButton(onClick = { showApiSettings = !showApiSettings }) {
                        Text(if (showApiSettings) "隱藏 Gemini API 直連 ▲" else "進階：Gemini API 智慧萃取任意文字 ▼")
                    }
                    if (showApiSettings) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("登入／開啟自動連 Gemini 查新版", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Text("有新版本自動更新版號並取消「最新」勾選", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = vm.autoGeminiOnStart,
                                onCheckedChange = { vm.autoGeminiOnStart = it; vm.saveUiPrefs(context) }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("登入自動連 Gemini 查新版＆比對吻合", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Text("吻合自動打勾；有新版本自動取消勾選", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = vm.autoGeminiOnStart,
                                onCheckedChange = { vm.autoGeminiOnStart = it; vm.saveUiPrefs(context) }
                            )
                        }
                        OutlinedTextField(value = apiKeyInput, onValueChange = { apiKeyInput = it }, label = { Text("Gemini API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = {
                                vm.saveGeminiApiKey(context, apiKeyInput, showFeedback = false)
                                vm.autoCheckLatestVersionsFromGemini(context, silent = false)
                                showGeminiDialog = false
                            },
                            enabled = apiKeyInput.isNotBlank() && !vm.geminiChecking,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("立即自動查版並比對手機現裝版本") }
                        OutlinedButton(
                            onClick = {
                                vm.saveGeminiApiKey(context, apiKeyInput, showFeedback = false)
                                vm.autoCheckLatestVersionsFromGemini(context, silent = false)
                                showGeminiDialog = false
                            },
                            enabled = apiKeyInput.isNotBlank() && !vm.geminiChecking,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("立即自動檢查所有 App 最新版本") }
                        Button(
                            onClick = {
                                vm.saveGeminiApiKey(context, apiKeyInput, showFeedback = false)
                                vm.extractViaGeminiApi(context, inputText) { showGeminiDialog = false }
                            },
                            enabled = inputText.isNotBlank() && apiKeyInput.isNotBlank() && !vm.isSyncing,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("呼叫 Gemini 2.5 Flash 萃取更新") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGeminiDialog = false }) { Text("關閉") } }
        )
    }

    // 2. 🔐 跨裝置設定加密 QR 遷移視窗
    if (showMigrationDialog) {
        var modeTab by remember { mutableStateOf(0) } // 0 = 匯出加密, 1 = 匯入解密
        var pin by remember { mutableStateOf("") }
        var generatedCode by remember { mutableStateOf("") }
        var importCode by remember { mutableStateOf("") }
        val scanMigLauncher = rememberLauncherForActivityResult(ScanContract()) { res ->
            res.contents?.let { importCode = it }
        }

        AlertDialog(
            onDismissRequest = { showMigrationDialog = false },
            title = { Text("🔐 跨裝置設定一鍵遷移") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("使用 AES-256-GCM 加密打包 GitHub Token、Gist ID 與 Gemini Key，支援 Android 與桌面 PWA 互掃遷移。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = modeTab == 0, onClick = { modeTab = 0 }, label = { Text("產生遷移碼 / QR") })
                        FilterChip(selected = modeTab == 1, onClick = { modeTab = 1 }, label = { Text("掃碼 / 匯入設定") })
                    }
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("設定／輸入臨時 PIN 碼（至少 4 位）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (modeTab == 0) {
                        Button(
                            onClick = {
                                encryptMigrationConfig(MigrationConfigPayload(vm.gistToken, vm.gistId, vm.geminiApiKey), pin)
                                    .onSuccess { generatedCode = it }
                                    .onFailure { vm.showToast("加密失敗：${it.message}") }
                            },
                            enabled = pin.length >= 4,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("加密產生遷移 QR Code") }
                        if (generatedCode.isNotBlank()) {
                            val bmp = remember(generatedCode) { generateQrBitmap(generatedCode, 520) }
                            if (bmp != null) {
                                Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "遷移 QR", modifier = Modifier.size(200.dp).padding(10.dp))
                                }
                            }
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(generatedCode)); vm.showToast("已複製加密遷移碼") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("複製加密遷移碼文字") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { scanMigLauncher.launch(buildQrScanOptions("掃描另一部裝置的加密遷移 QR")) }, modifier = Modifier.weight(1f)) { Text("📷 掃描 QR") }
                            OutlinedButton(onClick = { clipboard.getText()?.text?.let { importCode = it } }, modifier = Modifier.weight(1f)) { Text("📋 貼上密文") }
                        }
                        OutlinedTextField(value = importCode, onValueChange = { importCode = it }, label = { Text("加密遷移碼 (APPVAULT_CFG_V1:...)") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                decryptMigrationConfig(importCode, pin)
                                    .onSuccess { payload ->
                                        vm.applyMigrationPayload(context, payload)
                                        showMigrationDialog = false
                                    }
                                    .onFailure { vm.showToast("解密失敗：請檢查 PIN 碼或密文") }
                            },
                            enabled = pin.length >= 4 && importCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("解密並套用設定") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMigrationDialog = false }) { Text("關閉") } }
        )
    }

    // 3. 📊 獨立開發統計視覺化面板
    if (showStatsDialog) {
        val catCounts = vm.allCategories().map { c -> c to activeList.count { it.category == c } }.filter { it.second > 0 }.sortedByDescending { it.second }
        val maxCat = (catCounts.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        val monthCounts = activeList.groupBy { it.createdDate.take(7).ifBlank { "未知" } }.mapValues { it.value.size }.toList().sortedByDescending { it.first }.take(6)
        val maxMonth = (monthCounts.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        val tabletCount = activeList.count { it.isInstalledOnTablet }
        val syncCapCount = activeList.count { it.supportsDesktopSync }

        AlertDialog(
            onDismissRequest = { showStatsDialog = false },
            title = { Text("📊 開發統計視覺化面板", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompletionRingCard("GitHub", publishedCount, totalCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        CompletionRingCard("手機最新", installedPhoneCount, totalCount, Color(0xFF10B981), Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompletionRingCard("平板最新", tabletCount, totalCount, Color(0xFF14B8A6), Modifier.weight(1f))
                        CompletionRingCard("電腦同步", syncCapCount, totalCount, Color(0xFF818CF8), Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    Text("各分類 App 數量分佈", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    catCounts.forEach { (cat, cnt) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(cat, style = MaterialTheme.typography.bodySmall)
                                Text("$cnt 款", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(
                                progress = { cnt.toFloat() / maxCat.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }
                    HorizontalDivider()
                    Text("每月建立 App 數量趨勢", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    monthCounts.forEach { (ym, cnt) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ym, style = MaterialTheme.typography.bodySmall)
                                Text("$cnt 款", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(
                                progress = { cnt.toFloat() / maxMonth.toFloat() },
                                color = Color(0xFF10B981),
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStatsDialog = false }) { Text("關閉") } }
        )
    }

    // 4. 🎨 外觀主題與 5 套強調色選擇
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("🎨 外觀主題與強調色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("明暗模式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(
                                selected = vm.themeMode == m,
                                onClick = { vm.themeMode = m; vm.saveUiPrefs(context) },
                                label = { Text(m.label) }
                            )
                        }
                    }
                    Text("精選強調色", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccentTheme.entries.forEach { theme ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (vm.accentTheme == theme) Color(theme.darkPrimary).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                    vm.accentTheme = theme
                                    vm.saveUiPrefs(context)
                                }
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(Color(theme.darkPrimary)))
                                    Spacer(Modifier.width(10.dp))
                                    Text(theme.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (vm.accentTheme == theme) Icon(Icons.Default.Check, null, tint = Color(theme.darkPrimary))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("完成") } }
        )
    }

    // 批次改分類視窗
    if (showBulkCatDialog) {
        AlertDialog(
            onDismissRequest = { showBulkCatDialog = false },
            title = { Text("批次變更 ${vm.selectedBulkIds.size} 款 App 的分類") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.allCategories().forEach { c ->
                        OutlinedButton(onClick = { vm.bulkSetCategory(context, c); showBulkCatDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(c) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBulkCatDialog = false }) { Text("取消") } }
        )
    }

    if (showCategoryDialog) {
        var newCat by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("管理自訂分類標籤") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = newCat, onValueChange = { newCat = it }, label = { Text("新分類名稱") }, singleLine = true, modifier = Modifier.weight(1f))
                        Button(onClick = { vm.addCustomCategory(context, newCat); newCat = "" }) { Text("新增") }
                    }
                    if (vm.customCategories.isNotEmpty()) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            vm.customCategories.forEach { c ->
                                InputChip(selected = false, onClick = { vm.removeCustomCategory(context, c) }, label = { Text(c) }, trailingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(14.dp)) })
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCategoryDialog = false }) { Text("完成") } }
        )
    }

    if (showTrashDialog) {
        val trashed = vm.trashApps()
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = { Text("資源回收筒 (${trashed.size})") },
            text = {
                if (trashed.isEmpty()) Text("回收筒目前為空。")
                else LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(trashed, key = { it.id }) { app ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${app.name} v${app.version}", fontWeight = FontWeight.Bold)
                                    Text("刪除於 ${timestampToDateString(app.deletedAt ?: 0L)}", style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { vm.restoreFromTrash(context, app.id) }) { Icon(Icons.Default.RestoreFromTrash, "復原", tint = Color(0xFF10B981)) }
                            }
                        }
                    }
                }
            },
            confirmButton = { if (trashed.isNotEmpty()) TextButton(onClick = { vm.emptyTrash(context) }) { Text("全部清空", color = Color(0xFFEF4444)) } },
            dismissButton = { TextButton(onClick = { showTrashDialog = false }) { Text("關閉") } }
        )
    }

    qrTargetApp?.let { target ->
        var useFullUrl by remember(target.id) { mutableStateOf(false) }
        val payload = if (useFullUrl) buildGistUrl(target.syncId) else target.syncId.trim()
        val qrBitmap = remember(payload) { generateQrBitmap(payload, 560) }
        AlertDialog(
            onDismissRequest = { qrTargetApp = null },
            title = { Text("${target.name} · 同步 QR Code", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useFullUrl, onClick = { useFullUrl = false }, label = { Text("純同步 ID") })
                        FilterChip(selected = useFullUrl, onClick = { useFullUrl = true }, label = { Text("Gist 網址") })
                    }
                    if (qrBitmap != null) {
                        Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(210.dp).padding(12.dp))
                        }
                    }
                    Text(payload, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            },
            confirmButton = { Button(onClick = { clipboard.setText(AnnotatedString(payload)); vm.showToast("已複製內容") }) { Text("複製") } },
            dismissButton = { TextButton(onClick = { qrTargetApp = null }) { Text("關閉") } }
        )
    }

    if (showBatchSyncDialog) {
        var pasteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBatchSyncDialog = false },
            title = { Text("批次匯入多個同步 ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { batchImportLauncher.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text("選擇檔案") }
                        OutlinedButton(onClick = { exportTemplateCsvLauncher.launch("AppVault_SyncID_Template.csv") }, modifier = Modifier.weight(1f)) { Text("下載範本") }
                    }
                    OutlinedTextField(value = pasteText, onValueChange = { pasteText = it }, label = { Text("或直接貼上 JSON / CSV 對照內容") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pairs = parseBatchSyncMappings(pasteText)
                        if (pairs.isEmpty()) vm.showToast("未能解析有效內容")
                        else {
                            val (m, u) = vm.batchApplySyncIds(context, pairs)
                            vm.showToast(if (u.isEmpty()) "成功配對 $m 款 App" else "已配對 $m 款（未找到：${u.take(3).joinToString("、")}）")
                            showBatchSyncDialog = false
                        }
                    },
                    enabled = pasteText.isNotBlank()
                ) { Text("開始配對") }
            },
            dismissButton = { TextButton(onClick = { showBatchSyncDialog = false }) { Text("取消") } }
        )
    }

    if (showSyncDialog) {
        var tokenInput by remember { mutableStateOf(vm.gistToken) }
        var idInput by remember { mutableStateOf(vm.gistId) }
        val gistQrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let { raw ->
                val extracted = extractSyncIdFromRaw(raw)
                if (extracted.isNotBlank()) { idInput = extracted; vm.showToast("已掃碼填入 Gist ID") }
            }
        }
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("☁️ GitHub Gist 與自動同步") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("防抖自動雙向同步", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("啟動自動智慧合併，修改 3 秒後靜默上傳", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = vm.autoSyncEnabled, onCheckedChange = { vm.autoSyncEnabled = it; vm.saveUiPrefs(context) })
                    }
                    OutlinedTextField(value = tokenInput, onValueChange = { tokenInput = it }, label = { Text("GitHub Token (gist)") }, singleLine = true)
                    OutlinedTextField(
                        value = idInput,
                        onValueChange = { idInput = it },
                        label = { Text("Gist ID（可貼上網址或掃碼）") },
                        singleLine = true,
                        trailingIcon = { IconButton(onClick = { gistQrScanLauncher.launch(buildQrScanOptions()) }) { Icon(Icons.Default.QrCodeScanner, "掃碼") } }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.saveGistSettings(context, tokenInput, idInput, false); vm.smartMergeFromGist(context); showSyncDialog = false }, modifier = Modifier.weight(1f)) { Text("智慧合併拉取") }
                        Button(onClick = { vm.saveGistSettings(context, tokenInput, idInput, false); vm.pushToGist(context); showSyncDialog = false }, modifier = Modifier.weight(1f)) { Text("立即上傳") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveGistSettings(context, tokenInput, idInput); showSyncDialog = false }) { Text("儲存設定") } }
        )
    }

    pendingImport?.let { imported ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("匯入資料（共 ${imported.apps.size} 筆）") },
            text = { Text("「合併」保留現有記錄並更新相同 ID；「覆蓋」以備份檔取代全部資料。") },
            confirmButton = { Button(onClick = { vm.applyImport(context, imported, false); pendingImport = null }) { Text("合併匯入") } },
            dismissButton = { OutlinedButton(onClick = { vm.applyImport(context, imported, true); pendingImport = null }) { Text("全部覆蓋") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRecordCard(
    app: AppRecord,
    compactMode: Boolean,
    isSelected: Boolean,
    bulkMode: Boolean,
    isBulkChecked: Boolean,
    showReorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTogglePin: () -> Unit,
    onTogglePhone: () -> Unit,
    onToggleTablet: () -> Unit,
    onToggleGitHub: () -> Unit,
    onToggleDesktopSync: () -> Unit,
    onLaunchApp: () -> Unit,
    onCopySyncId: () -> Unit,
    onOpenGist: () -> Unit,
    onOpenActions: () -> Unit,
    onShowQr: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val accentColor = remember(app.accentHex, app.category) { resolveAppAccentColor(app) }
    var showIconMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        border = when {
            isBulkChecked -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isSelected -> BorderStroke(2.dp, accentColor)
            else -> null
        }
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 依 APK 圖示動態取色色帶 (Accent Strip)
            Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(accentColor))

            Column(modifier = Modifier.weight(1f).padding(if (compactMode) 11.dp else 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bulkMode) {
                        Checkbox(checked = isBulkChecked, onCheckedChange = { onClick() }, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(6.dp))
                    }

                    // 點圖示直接啟動 App，長按跳出快捷選單
                    Box {
                        AppApkIconBadge(
                            appName = app.name,
                            applicationId = app.applicationId,
                            iconBase64 = app.iconBase64,
                            size = if (compactMode) 38.dp else 44.dp,
                            modifier = Modifier.combinedClickable(
                                onClick = { if (bulkMode) onClick() else onLaunchApp() },
                                onLongClick = { if (!bulkMode) showIconMenu = true }
                            )
                        )
                        DropdownMenu(expanded = showIconMenu, onDismissRequest = { showIconMenu = false }) {
                            DropdownMenuItem(text = { Text("🚀 啟動 App") }, onClick = { showIconMenu = false; onLaunchApp() })
                            DropdownMenuItem(text = { Text("⚙️ 開啟 GitHub Actions") }, onClick = { showIconMenu = false; onOpenActions() })
                            if (app.syncId.isNotBlank()) {
                                DropdownMenuItem(text = { Text("📋 複製同步 ID") }, onClick = { showIconMenu = false; onCopySyncId() })
                                DropdownMenuItem(text = { Text("↗ 開啟 GitHub Gist") }, onClick = { showIconMenu = false; onOpenGist() })
                            }
                        }
                    }

                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val titleStr = app.displayTitle()
                        val gemVer = app.effectiveGeminiVersion()
                        val phoneVerLabel = if (app.installedPhoneVersion.isNotBlank()) "手機v${app.installedPhoneVersion}" else "手機未裝"
                        val matched = app.isPhoneVersionMatched()

                        // 第一行：跟回手機名稱的粗體字一開頭 + 精簡勾選狀態（吻合只顯示 tick；不吻合顯示 ☐ 需要更新）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                titleStr,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            if (matched) {
                                IconButton(
                                    onClick = onTogglePhone,
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckBox,
                                        contentDescription = "已吻合",
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.20f),
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onTogglePhone() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CheckBoxOutlineBlank,
                                            contentDescription = "需要更新",
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            "需要更新",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFF59E0B)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (app.isInstalledOnPhone) Color(0xFF10B981).copy(alpha = 0.18f) else Color(0xFFF59E0B).copy(alpha = 0.18f),
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onTogglePhone() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (app.isInstalledOnPhone) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = "已更新至最新版本",
                                        tint = if (app.isInstalledOnPhone) Color(0xFF10B981) else Color(0xFFF59E0B),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        if (app.isInstalledOnPhone) "最新" else "待確認",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (app.isInstalledOnPhone) Color(0xFF10B981) else Color(0xFFF59E0B)
                                    )
                                }
                            }
                            // GitHub Actions 編譯狀態燈號
                            if (app.actionsStatus.isNotBlank()) {
                                Spacer(Modifier.width(5.dp))
                                val (dotCol, _) = when (app.actionsStatus) {
                                    "success" -> Color(0xFF10B981) to "編譯成功"
                                    "in_progress" -> Color(0xFFF59E0B) to "編譯中"
                                    "failure" -> Color(0xFFEF4444) to "編譯失敗"
                                    else -> Color.Gray to ""
                                }
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(dotCol)
                                        .clickable { onOpenActions() }
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(5.dp)) {
                                Text("Gemini v$gemVer", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                            Surface(
                                color = if (matched) Color(0xFF10B981).copy(alpha = 0.14f) else Color(0xFFEF4444).copy(alpha = 0.14f),
                                shape = RoundedCornerShape(5.dp)
                            ) {
                                Text(
                                    phoneVerLabel,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (matched) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            }
                            Surface(color = accentColor.copy(alpha = 0.14f), shape = RoundedCornerShape(5.dp)) {
                                Text(app.category, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = accentColor)
                            }
                            if (compactMode) {
                                // 極簡模式狀態小點
                                Text(if (app.isInstalledOnPhone) "📱✓" else "📱○", style = MaterialTheme.typography.labelSmall, color = if (app.isInstalledOnPhone) Color(0xFF10B981) else Color(0xFFEF4444))
                                Text(if (app.isPublishedOnGitHub) "☁✓" else "☁⚠", style = MaterialTheme.typography.labelSmall, color = if (app.isPublishedOnGitHub) Color(0xFF38BDF8) else Color(0xFFF59E0B))
                                if (app.supportsDesktopSync) Text(if (app.syncId.isNotBlank()) "💻✓" else "💻○", style = MaterialTheme.typography.labelSmall, color = Color(0xFF818CF8))
                            } else {
                                if (app.createdDate.isNotBlank()) {
                                    Text("建立 ${app.createdDate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (app.installedPhoneVersion.isNotBlank()) {
                                    val outdated = compareSemanticVersions(app.installedPhoneVersion, app.version) < 0
                                    Text(
                                        if (outdated) "實裝 v${app.installedPhoneVersion} (待升)" else "實裝 v${app.installedPhoneVersion}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (outdated) Color(0xFFF59E0B) else Color(0xFF10B981),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (app.isPinned) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "置頂",
                            modifier = Modifier.size(18.dp),
                            tint = if (app.isPinned) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showReorder) {
                        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.KeyboardArrowUp, "上移") }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.KeyboardArrowDown, "下移") }
                    }
                }

                if (app.usageGuide.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        app.usageGuide,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compactMode) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 詳情模式顯示完整提示、同步 ID 與勾選列
                if (!compactMode) {
                    if (!app.isPublishedOnGitHub) {
                        Spacer(Modifier.height(4.dp))
                        Text("⚠ 尚未發布到 GitHub", color = Color(0xFFF59E0B), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    } else if (app.latestGitHubVersion.isNotBlank() && app.latestGitHubVersion != app.version) {
                        Spacer(Modifier.height(4.dp))
                        Text("🔔 GitHub Release 最新：v${app.latestGitHubVersion}", color = Color(0xFF38BDF8), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    if (app.actionsStatus == "failure") {
                        Text("🔴 Actions 編譯失敗：${app.actionsRunTitle.ifBlank { "點擊查看" }}", color = Color(0xFFEF4444), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpenActions() })
                    }

                    if (app.supportsDesktopSync) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = if (app.syncId.isNotBlank()) Color(0xFF818CF8).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(14.dp), tint = if (app.syncId.isNotBlank()) Color(0xFF818CF8) else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (app.syncId.isNotBlank()) app.syncId else "尚未設定同步 ID", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (app.syncId.isNotBlank()) {
                                        IconButton(onClick = onCopySyncId, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.ContentCopy, "複製", modifier = Modifier.size(14.dp), tint = Color(0xFF818CF8)) }
                                        IconButton(onClick = onShowQr, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.QrCode2, "QR", modifier = Modifier.size(15.dp), tint = Color(0xFF818CF8)) }
                                        IconButton(onClick = onOpenGist, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.OpenInNew, "開 Gist", modifier = Modifier.size(14.dp), tint = Color(0xFF38BDF8)) }
                                    }
                                }
                                if (app.lastGistUpdatedAt.isNotBlank()) {
                                    Text("🕒 雲端 Gist 最新更新：${app.lastGistUpdatedAt}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusToggleChip("手機", app.isInstalledOnPhone, Color(0xFF10B981), onTogglePhone, Modifier.weight(1f))
                        StatusToggleChip("平板", app.isInstalledOnTablet, Color(0xFF14B8A6), onToggleTablet, Modifier.weight(1f))
                        StatusToggleChip("GitHub", app.isPublishedOnGitHub, Color(0xFF38BDF8), onToggleGitHub, Modifier.weight(1f))
                        StatusToggleChip("桌面同步", app.supportsDesktopSync, Color(0xFF818CF8), onToggleDesktopSync, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun StatusToggleChip(label: String, checked: Boolean, activeColor: Color, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() },
        color = if (checked) activeColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, tint = if (checked) activeColor else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
fun AppEditorPanel(
    initial: AppRecord,
    categories: List<String>,
    isExisting: Boolean,
    autoSaveEnabled: Boolean,
    onNotify: (String) -> Unit,
    onFetchGitHub: (String, (String?, String?, String?, String?) -> Unit) -> Unit,
    onShowQr: (String) -> Unit,
    onSave: (AppRecord, Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var version by remember(initial.id) { mutableStateOf(initial.version) }
    var createdDate by remember(initial.id) { mutableStateOf(initial.createdDate.ifBlank { todayDateString() }) }
    var usageGuide by remember(initial.id) { mutableStateOf(initial.usageGuide) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var iconBase64 by remember(initial.id) { mutableStateOf(initial.iconBase64) }
    var accentHex by remember(initial.id) { mutableStateOf(initial.accentHex) }
    var installedPhoneVersion by remember(initial.id) { mutableStateOf(initial.installedPhoneVersion) }
    var isPinned by remember(initial.id) { mutableStateOf(initial.isPinned) }
    var published by remember(initial.id) { mutableStateOf(initial.isPublishedOnGitHub) }
    var installedPhone by remember(initial.id) { mutableStateOf(initial.isInstalledOnPhone) }
    var installedTablet by remember(initial.id) { mutableStateOf(initial.isInstalledOnTablet) }
    var desktopSync by remember(initial.id) { mutableStateOf(initial.supportsDesktopSync) }
    var syncId by remember(initial.id) { mutableStateOf(initial.syncId) }
    var applicationId by remember(initial.id) { mutableStateOf(initial.applicationId) }
    var keystoreAlias by remember(initial.id) { mutableStateOf(initial.keystoreAlias) }
    var apkHash by remember(initial.id) { mutableStateOf(initial.apkHash) }
    var githubUrl by remember(initial.id) { mutableStateOf(initial.githubUrl) }
    var latestGitHubVersion by remember(initial.id) { mutableStateOf(initial.latestGitHubVersion) }
    var actionsStatus by remember(initial.id) { mutableStateOf(initial.actionsStatus) }
    var actionsRunTitle by remember(initial.id) { mutableStateOf(initial.actionsRunTitle) }
    var notes by remember(initial.id) { mutableStateOf(initial.notes) }

    var isHashing by remember { mutableStateOf(false) }
    var showApkPicker by remember { mutableStateOf(false) }
    var installedApks by remember { mutableStateOf<List<InstalledApkInfo>>(emptyList()) }
    var apkFilter by remember { mutableStateOf("") }
    var userTouched by remember(initial.id) { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    fun buildCurrentRecord(): AppRecord = initial.copy(
        name = name.trim(),
        version = version.trim().ifBlank { "1.0" },
        createdDate = createdDate.trim().ifBlank { todayDateString() },
        usageGuide = usageGuide.trim(),
        category = category,
        iconBase64 = iconBase64,
        accentHex = accentHex,
        installedPhoneVersion = installedPhoneVersion,
        isPinned = isPinned,
        isPublishedOnGitHub = published,
        isInstalledOnPhone = installedPhone,
        isInstalledOnTablet = installedTablet,
        supportsDesktopSync = desktopSync,
        syncId = if (desktopSync) extractSyncIdFromRaw(syncId) else syncId.trim(),
        phoneAppLabel = if (applicationId.isNotBlank() && name.isNotBlank()) name.trim() else initial.phoneAppLabel,
        applicationId = applicationId.trim(),
        keystoreAlias = keystoreAlias.trim(),
        apkHash = apkHash.trim(),
        githubUrl = githubUrl.trim(),
        latestGitHubVersion = latestGitHubVersion,
        actionsStatus = actionsStatus,
        actionsRunTitle = actionsRunTitle,
        notes = notes.trim()
    )

    // 雙欄編輯區防抖即時自動儲存 (Auto-Save)
    LaunchedEffect(
        userTouched, name, version, createdDate, usageGuide, category, iconBase64,
        isPinned, published, installedPhone, installedTablet, desktopSync, syncId,
        applicationId, keystoreAlias, apkHash, githubUrl, notes
    ) {
        if (autoSaveEnabled && isExisting && userTouched && name.isNotBlank()) {
            delay(700)
            onSave(buildCurrentRecord(), true)
        }
    }

    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            val extracted = extractSyncIdFromRaw(raw)
            if (extracted.isNotBlank()) { syncId = extracted; userTouched = true; onNotify("掃碼成功！已填入同步 ID") }
        }
    }
    val importSyncIdFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val raw = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            val extracted = extractSyncIdFromRaw(raw.orEmpty())
            if (extracted.isNotBlank()) { syncId = extracted; userTouched = true; onNotify("已匯入同步 ID") }
        }
    }
    val apkHashPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { fileUri ->
            isHashing = true
            scope.launch {
                val digest = withContext(Dispatchers.IO) { computeUriSha256(context, fileUri) }
                isHashing = false
                if (!digest.isNullOrBlank()) { apkHash = digest; userTouched = true; onNotify("已完成 SHA-256 計算") }
            }
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppApkIconBadge(appName = name, applicationId = applicationId, iconBase64 = iconBase64, size = 48.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isExisting) "編輯 App 記錄" else "新增 App 記錄", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (autoSaveEnabled && isExisting) {
                        Spacer(Modifier.width(8.dp))
                        Text("自動儲存", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            installedApks = withContext(Dispatchers.IO) { queryUserInstalledApks(context) }
                            showApkPicker = true
                        }
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("從本機已裝 APK 配對圖示與版本")
                }
            }
        }

        OutlinedTextField(value = name, onValueChange = { name = it; userTouched = true }, label = { Text("App 名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = version, onValueChange = { version = it; userTouched = true }, label = { Text("最新版本號") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = createdDate, onValueChange = { createdDate = it; userTouched = true }, label = { Text("建立日期") }, singleLine = true, modifier = Modifier.weight(1.1f))
        }

        // 版本號「一鍵進位」膠囊按鈕
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("快速升版：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("patch" to "+0.0.1", "minor" to "+0.1", "major" to "+1.0").forEach { (part, lbl) ->
                AssistChip(
                    onClick = {
                        version = bumpSemanticVersion(version, part)
                        installedPhone = false
                        installedTablet = false
                        userTouched = true
                        onNotify("版本已進位至 v$version")
                    },
                    label = { Text(lbl) }
                )
            }
        }

        OutlinedTextField(value = usageGuide, onValueChange = { usageGuide = it; userTouched = true }, label = { Text("介紹用法（用途與操作簡介）") }, minLines = 2, modifier = Modifier.fillMaxWidth())

        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { cat ->
                FilterChip(selected = category == cat, onClick = { category = cat; userTouched = true }, label = { Text(cat) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { isPinned = !isPinned; userTouched = true }) { Checkbox(checked = isPinned, onCheckedChange = { isPinned = it; userTouched = true }); Text("置頂星標") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { installedPhone = !installedPhone; userTouched = true }) {
            Checkbox(checked = installedPhone, onCheckedChange = { installedPhone = it; userTouched = true })
            Text(if (installedPhoneVersion.isNotBlank()) "手機已裝最新（實裝 v$installedPhoneVersion）" else "手機已安裝最新版本")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { installedTablet = !installedTablet; userTouched = true }) { Checkbox(checked = installedTablet, onCheckedChange = { installedTablet = it; userTouched = true }); Text("平板已安裝最新版本") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { published = !published; userTouched = true }) { Checkbox(checked = published, onCheckedChange = { published = it; userTouched = true }); Text("已發布到 GitHub") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { desktopSync = !desktopSync; userTouched = true }) { Checkbox(checked = desktopSync, onCheckedChange = { desktopSync = it; userTouched = true }); Text("支援桌上電腦同步") }

        // GitHub Repo 連結＋自動抓取全套資料（含編譯燈號）
        OutlinedTextField(
            value = githubUrl,
            onValueChange = { githubUrl = it; userTouched = true },
            label = { Text("GitHub Repo 連結") },
            singleLine = true,
            trailingIcon = {
                if (githubUrl.contains("github.com/", true)) {
                    IconButton(onClick = {
                        onFetchGitHub(githubUrl) { desc, tag, st, title ->
                            if (!desc.isNullOrBlank() && usageGuide.isBlank()) usageGuide = desc
                            if (!tag.isNullOrBlank()) latestGitHubVersion = tag
                            if (!st.isNullOrBlank()) actionsStatus = st
                            if (!title.isNullOrBlank()) actionsRunTitle = title
                            published = true
                            userTouched = true
                        }
                    }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "自動抓取 Repo 介紹與編譯狀態")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (actionsStatus.isNotBlank()) {
            val statusTxt = when (actionsStatus) {
                "success" -> "🟢 GitHub Actions 最新編譯成功"
                "in_progress" -> "🟡 GitHub Actions 正在編譯中..."
                "failure" -> "🔴 GitHub Actions 編譯失敗"
                else -> ""
            }
            if (statusTxt.isNotBlank()) Text("$statusTxt ${if (actionsRunTitle.isNotBlank()) "($actionsRunTitle)" else ""}", style = MaterialTheme.typography.labelSmall)
        }
        if (githubUrl.startsWith("http")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${githubUrl.trimEnd('/')}/actions"))) }, modifier = Modifier.weight(1f)) { Text("開啟 Actions") }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${githubUrl.trimEnd('/')}/releases"))) }, modifier = Modifier.weight(1f)) { Text("開啟 Releases") }
            }
        }

        if (desktopSync) {
            Surface(color = Color(0xFF818CF8).copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🔑 電腦同步 ID（GitHub Gist ID）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = syncId, onValueChange = { syncId = it; userTouched = true }, label = { Text("同步 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { qrScanLauncher.launch(buildQrScanOptions()) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text("掃碼", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(onClick = { importSyncIdFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text("匯入", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(onClick = { val ex = extractSyncIdFromRaw(clipboard.getText()?.text.orEmpty()); if (ex.isNotBlank()) { syncId = ex; userTouched = true; onNotify("已擷取 ID") } }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text("擷取", style = MaterialTheme.typography.labelSmall) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(syncId.trim())); onNotify("已複製") }, enabled = syncId.isNotBlank(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text("複製", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(onClick = { val c = extractSyncIdFromRaw(syncId); if (c.isNotBlank()) onShowQr(c) }, enabled = syncId.isNotBlank(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text("QR 分享", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(onClick = { val c = extractSyncIdFromRaw(syncId); if (c.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(buildGistUrl(c)))) }, enabled = syncId.isNotBlank(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text("開啟 Gist", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }

        Surface(color = Color(0xFF14B8A6).copy(alpha = 0.09f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔐 APK 雜湊與簽名金鑰備忘錄", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = applicationId, onValueChange = { applicationId = it; userTouched = true }, label = { Text("applicationId（點擊圖示可直接啟動）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = keystoreAlias, onValueChange = { keystoreAlias = it; userTouched = true }, label = { Text("Keystore Alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apkHash, onValueChange = { apkHash = it; userTouched = true }, label = { Text("APK SHA-256 雜湊") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { apkHashPickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }, enabled = !isHashing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isHashing) "計算中..." else "選取 APK 計算 SHA-256")
                }
            }
        }

        OutlinedTextField(value = notes, onValueChange = { notes = it; userTouched = true }, label = { Text("更新備註") }, minLines = 2, modifier = Modifier.fillMaxWidth())

        if (initial.changelog.isNotEmpty()) {
            HorizontalDivider()
            Text("版本歷史時間軸", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            initial.changelog.forEach { entry ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("v${entry.version}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text(dateFmt.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall)
                        }
                        if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (isExisting) {
                OutlinedButton(onClick = onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)), modifier = Modifier.weight(1f)) { Text("移至回收筒") }
            }
            Button(
                onClick = { if (name.isNotBlank()) onSave(buildCurrentRecord(), false) },
                modifier = Modifier.weight(1.5f)
            ) { Text("立即儲存") }
        }
    }

    if (showApkPicker) {
        val filteredApks = installedApks.filter { apkFilter.isBlank() || it.label.contains(apkFilter, true) || it.packageName.contains(apkFilter, true) }
        AlertDialog(
            onDismissRequest = { showApkPicker = false },
            title = { Text("挑選本機已安裝 APK") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = apkFilter, onValueChange = { apkFilter = it }, placeholder = { Text("搜尋 App 名稱或包名...") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredApks, key = { it.packageName }) { pkg ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    applicationId = pkg.packageName
                                    if (pkg.label.isNotBlank()) name = pkg.label
                                    if (pkg.versionName.isNotBlank()) {
                                        installedPhoneVersion = pkg.versionName
                                        if (compareSemanticVersions(pkg.versionName, version) >= 0) installedPhone = true
                                    }
                                    if (createdDate.isBlank()) createdDate = pkg.createdDate
                                    pkg.iconBitmap?.let {
                                        iconBase64 = bitmapToBase64(it, 72)
                                        val hex = extractDominantColorHex(it)
                                        if (hex.isNotBlank()) accentHex = hex
                                    }
                                    userTouched = true
                                    showApkPicker = false
                                    onNotify("已綁定「${pkg.label}」圖示、主色與版本 v${pkg.versionName}")
                                }
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (pkg.iconBitmap != null) {
                                        Image(bitmap = pkg.iconBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pkg.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${pkg.packageName} · v${pkg.versionName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showApkPicker = false }) { Text("關閉") } }
        )
    }
}
