package com.npl.appvault

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class VersionHistoryEntry(
    val version: String,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class AppRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val version: String = "1.0",
    val category: String = "個人工具",
    val createdDate: String = todayDateString(),
    val usageGuide: String = "",
    val iconBase64: String = "",
    val accentHex: String = "",
    val phoneAppLabel: String = "",
    val installedPhoneVersion: String = "",
    val geminiLatestVersion: String = "",
    val isPinned: Boolean = false,
    val isPublishedOnGitHub: Boolean = false,
    val isInstalledOnPhone: Boolean = false,
    val isInstalledOnTablet: Boolean = false,
    val supportsDesktopSync: Boolean = false,
    val syncId: String = "",
    val lastGistUpdatedAt: String = "",
    val applicationId: String = "",
    val keystoreAlias: String = "",
    val apkHash: String = "",
    val githubUrl: String = "",
    val latestGitHubVersion: String = "",
    val actionsStatus: String = "",      // "success", "in_progress", "failure", ""
    val actionsRunTitle: String = "",
    val notes: String = "",
    val changelog: List<VersionHistoryEntry> = emptyList(),
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val geminiUpdatedAt: Long = 0L
)

@Serializable
data class AppVaultBackup(
    val schemaVersion: Int = 9,
    val exportedAt: Long = System.currentTimeMillis(),
    val customCategories: List<String> = emptyList(),
    val apps: List<AppRecord> = emptyList()
)

@Serializable
data class MigrationConfigPayload(
    val gistToken: String = "",
    val gistId: String = "",
    val geminiApiKey: String = ""
)

data class InstalledApkInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val createdDate: String,
    val iconBitmap: Bitmap?
)

enum class SortMode(val label: String) {
    MANUAL("手動排序"),
    CREATED_DATE("建立日期"),
    UPDATED_DATE("更新日期"),
    NAME("名稱"),
    VERSION("版本號"),
    CATEGORY("分類")
}

enum class FilterMode(val label: String) {
    ALL("全部"),
    FOCUS("今日待辦"),
    UNPUBLISHED("未發 GitHub"),
    NEED_INSTALL("待裝手機"),
    NEED_TABLET("待裝平板"),
    DESKTOP_SYNC("桌面同步")
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟隨系統"),
    LIGHT("淺色"),
    DARK("深色")
}

enum class AccentTheme(
    val label: String,
    val darkPrimary: Long,
    val lightPrimary: Long,
    val secondary: Long
) {
    OCEAN("星夜藍", 0xFF38BDF8, 0xFF0284C7, 0xFF818CF8),
    EMERALD("翡翠綠", 0xFF34D399, 0xFF059669, 0xFF2DD4BF),
    AMBER("琥珀金", 0xFFFBBF24, 0xFFD97706, 0xFFF59E0B),
    VIOLET("極光紫", 0xFFA78BFA, 0xFF7C3AED, 0xFFC084FC),
    SLATE("極簡灰", 0xFF94A3B8, 0xFF475569, 0xFF64748B)
}

enum class SyncState(val label: String) {
    IDLE("未設定雲端"),
    SYNCED("雲端已同步"),
    PENDING("待自動同步..."),
    SYNCING("同步中..."),
    ERROR("同步異常")
}

val BASE_CATEGORIES = listOf("教學工具", "個人工具", "生活記錄", "系統工具", "AI 與開發", "相片媒體", "其他")

fun todayDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

fun timestampToDateString(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))



fun AppRecord.displayTitle(): String =
    phoneAppLabel.trim().ifBlank { name.trim() }.ifBlank { "未命名 App" }

fun AppRecord.effectiveGeminiVersion(): String =
    geminiLatestVersion.trim().ifBlank { version.trim() }

fun AppRecord.isPhoneVersionMatched(): Boolean {
    val targetVer = effectiveGeminiVersion()
    if (targetVer.isBlank()) return isInstalledOnPhone
    if (installedPhoneVersion.isNotBlank()) {
        return compareSemanticVersions(installedPhoneVersion, targetVer) == 0
    }
    return isInstalledOnPhone && compareSemanticVersions(version, targetVer) == 0
}

fun AppRecord.needsFocusAttention(): Boolean =
    deletedAt == null && (
        !isPublishedOnGitHub ||
            !isPhoneVersionMatched() ||
            (supportsDesktopSync && syncId.isBlank()) ||
            actionsStatus == "failure" ||
            (installedPhoneVersion.isNotBlank() && compareSemanticVersions(installedPhoneVersion, version) < 0)
        )

fun normalizeAppNameKey(s: String): String =
    s.lowercase(Locale.getDefault())
        .replace(Regex("[（(].*?[）)]"), "")
        .replace(Regex("""v?\d+(\.\d+)*"""), "")
        .replace(Regex("""[\s_-]+"""), "")
        .trim()

fun compareSemanticVersions(a: String, b: String): Int {
    val pa = a.trim().removePrefix("v").removePrefix("V").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
    val pb = b.trim().removePrefix("v").removePrefix("V").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
    val maxLen = maxOf(pa.size, pb.size)
    for (i in 0 until maxLen) {
        val va = pa.getOrElse(i) { 0 }
        val vb = pb.getOrElse(i) { 0 }
        if (va != vb) return va.compareTo(vb)
    }
    return a.compareTo(b, ignoreCase = true)
}

fun bumpSemanticVersion(current: String, part: String): String {
    val clean = current.trim().removePrefix("v").removePrefix("V")
    val nums = clean.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
    val major = nums.getOrElse(0) { 1 }
    val minor = nums.getOrElse(1) { 0 }
    val patch = nums.getOrElse(2) { 0 }
    return when (part) {
        "major" -> "${major + 1}.0.0"
        "minor" -> "$major.${minor + 1}.0"
        else -> "$major.$minor.${patch + 1}"
    }
}

// ==================== 跨平台 AES-256-GCM 加密遷移（與 Web Crypto API 100% 互通） ====================
const val MIGRATION_PREFIX = "APPVAULT_CFG_V1:"

fun encryptMigrationConfig(payload: MigrationConfigPayload, pin: String): Result<String> = runCatching {
    require(pin.length >= 4) { "PIN 碼至少需要 4 個字元" }
    val plainBytes = Json.encodeToString(payload).toByteArray(Charsets.UTF_8)
    val rng = SecureRandom()
    val salt = ByteArray(16).also { rng.nextBytes(it) }
    val iv = ByteArray(12).also { rng.nextBytes(it) }
    val keySpec = PBEKeySpec(pin.toCharArray(), salt, 60000, 256)
    val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
    val encrypted = cipher.doFinal(plainBytes)
    val combined = salt + iv + encrypted
    MIGRATION_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
}

fun decryptMigrationConfig(encoded: String, pin: String): Result<MigrationConfigPayload> = runCatching {
    val raw = encoded.trim()
    require(raw.startsWith(MIGRATION_PREFIX)) { "無效的 AppVault 遷移碼格式" }
    require(pin.length >= 4) { "請輸入正確的 PIN 碼" }
    val combined = Base64.decode(raw.removePrefix(MIGRATION_PREFIX), Base64.DEFAULT)
    require(combined.size > 28) { "密文資料不完整" }
    val salt = combined.copyOfRange(0, 16)
    val iv = combined.copyOfRange(16, 28)
    val cipherText = combined.copyOfRange(28, combined.size)
    val keySpec = PBEKeySpec(pin.toCharArray(), salt, 60000, 256)
    val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
    val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
    Json { ignoreUnknownKeys = true }.decodeFromString<MigrationConfigPayload>(plain)
}

// ==================== 圖示主色擷取 (Accent Strip) ====================
fun extractDominantColorHex(bitmap: Bitmap?): String {
    if (bitmap == null) return ""
    return runCatching {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var bestColor = 0
        var bestScore = -1f
        val hsv = FloatArray(3)
        for (y in 2 until 22) {
            for (x in 2 until 22) {
                val px = small.getPixel(x, y)
                val alpha = (px ushr 24) and 0xFF
                if (alpha < 180) continue
                android.graphics.Color.colorToHSV(px, hsv)
                // 偏好飽和度與明度適中、不接近純黑或純白的代表色
                val score = hsv[1] * 1.4f + (1f - kotlin.math.abs(hsv[2] - 0.65f))
                if (hsv[1] > 0.2f && hsv[2] > 0.22f && score > bestScore) {
                    bestScore = score
                    bestColor = px
                }
            }
        }
        if (bestColor == 0) "" else String.format("#%06X", 0xFFFFFF and bestColor)
    }.getOrDefault("")
}

fun resolveAppAccentColor(app: AppRecord): Color {
    if (app.accentHex.startsWith("#")) {
        runCatching { return Color(android.graphics.Color.parseColor(app.accentHex)) }
    }
    return when (app.category) {
        "教學工具" -> Color(0xFF0EA5E9)
        "AI 與開發" -> Color(0xFF818CF8)
        "生活記錄" -> Color(0xFF10B981)
        "相片媒體" -> Color(0xFFF472B6)
        "系統工具" -> Color(0xFF2DD4BF)
        "個人工具" -> Color(0xFFF59E0B)
        else -> Color(0xFF64748B)
    }
}

fun extractSyncIdFromRaw(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""

    runCatching {
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(trimmed).jsonObject
        val candidateKeys = listOf("syncId", "gistId", "sync_id", "gist_id", "GIST_ID", "SYNC_ID", "id")
        for (key in candidateKeys) {
            val v = obj[key]?.jsonPrimitive?.content?.trim()
            if (!v.isNullOrBlank()) return v
        }
    }

    val gistRegex = Regex("""gist\.github\.com/(?:[^/]+/)?([a-fA-F0-9]{16,64})""", RegexOption.IGNORE_CASE)
    gistRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

    val kvRegex = Regex("""(?:gist_?id|sync_?id)\s*[:=]\s*["']?([A-Za-z0-9_-]{6,80})["']?""", RegexOption.IGNORE_CASE)
    kvRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

    return trimmed.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
}

fun buildGistUrl(syncIdOrUrl: String): String {
    val clean = syncIdOrUrl.trim()
    return if (clean.startsWith("http://") || clean.startsWith("https://")) clean
    else "https://gist.github.com/$clean"
}

fun formatIsoTimestampToLocal(isoString: String): String = runCatching {
    val instant = Instant.parse(isoString.trim())
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    formatter.format(instant)
}.getOrElse { isoString }

fun computeUriSha256(context: Context, uri: Uri): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        var read = input.read(buffer)
        while (read != -1) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    } ?: return null
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

fun buildQrScanOptions(prompt: String = "請將鏡頭對準 QR Code"): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt(prompt)
    setBeepEnabled(true)
    setOrientationLocked(false)
}

fun generateQrBitmap(content: String, sizePx: Int = 560): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 2
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}.getOrNull()

fun drawableToBitmap(drawable: Drawable, sizePx: Int = 96): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
    }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}

fun bitmapToBase64(bitmap: Bitmap, sizePx: Int = 72): String = runCatching {
    val scaled = Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrDefault("")

fun base64ToBitmap(b64: String): Bitmap? = runCatching {
    if (b64.isBlank()) return null
    val pure = b64.substringAfter("base64,", b64)
    val bytes = Base64.decode(pure, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

fun queryUserInstalledApks(context: Context): List<InstalledApkInfo> {
    val pm = context.packageManager
    val packages = runCatching { pm.getInstalledPackages(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
    return packages.mapNotNull { pkg ->
        val appInfo = pkg.applicationInfo ?: return@mapNotNull null
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem && !isUpdatedSystem) return@mapNotNull null
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg.packageName)
        val iconBmp = runCatching { drawableToBitmap(pm.getApplicationIcon(appInfo), 96) }.getOrNull()
        InstalledApkInfo(
            packageName = pkg.packageName,
            label = label,
            versionName = (pkg.versionName ?: "").removePrefix("v").removePrefix("V"),
            createdDate = timestampToDateString(pkg.firstInstallTime),
            iconBitmap = iconBmp
        )
    }.sortedBy { it.label.lowercase(Locale.getDefault()) }
}

fun loadInstalledApkIcon(context: Context, packageName: String): Bitmap? {
    if (packageName.isBlank()) return null
    return runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        drawableToBitmap(drawable, 96)
    }.getOrNull()
}

fun buildGeminiSyncPrompt(existingApps: List<AppRecord>): String {
    val knownList = existingApps.take(25).joinToString("、") { "${it.name}(v${it.version})" }
    return """請幫我跨對話整理最近新建立或有版本更新的自製 Apps / APK / 網頁工具，並只輸出一個標準 JSON 陣列（可用 ```json 包起來），格式如下：
```json
[
  {
    "name": "App 名稱",
    "version": "1.2.0",
    "createdDate": "2026-11-26",
    "category": "系統工具",
    "usageGuide": "簡明介紹用途與操作方式（1至2句）",
    "supportsDesktopSync": true,
    "isPublishedOnGitHub": true,
    "githubUrl": "https://github.com/...",
    "applicationId": "com.example.app",
    "notes": "本版更新重點"
  }
]
```
目前 AppVault 已有紀錄：$knownList
請補上我新建立的 App，並更新有新版號的 App！完成後可直接按「分享 → AppVault」一鍵匯入。"""
}

fun parseGeminiRecordsFromText(raw: String): List<AppRecord> {
    val cleaned = raw.trim()
    if (cleaned.isBlank()) return emptyList()

    val codeBlockRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)
    val candidates = mutableListOf<String>()
    codeBlockRegex.findAll(cleaned).forEach { candidates.add(it.groupValues[1].trim()) }
    val firstBracket = cleaned.indexOf('[')
    val lastBracket = cleaned.lastIndexOf(']')
    if (firstBracket >= 0 && lastBracket > firstBracket) candidates.add(cleaned.substring(firstBracket, lastBracket + 1))
    val firstBrace = cleaned.indexOf('{')
    val lastBrace = cleaned.lastIndexOf('}')
    if (firstBrace >= 0 && lastBrace > firstBrace) candidates.add(cleaned.substring(firstBrace, lastBrace + 1))
    candidates.add(cleaned)

    val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    for (cand in candidates) {
        val parsed = runCatching {
            val el = jsonParser.parseToJsonElement(cand)
            val listEl = when (el) {
                is JsonArray -> el
                is JsonObject -> (el["apps"] as? JsonArray) ?: JsonArray(listOf(el))
                else -> return@runCatching emptyList()
            }
            listEl.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                fun str(vararg keys: String): String =
                    keys.firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull?.trim() } ?: ""
                fun bool(vararg keys: String): Boolean? =
                    keys.firstNotNullOfOrNull {
                        when (obj[it]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                            "true", "是", "1", "yes" -> true
                            "false", "否", "0", "no" -> false
                            else -> null
                        }
                    }
                val name = str("name", "appName", "title", "App名稱", "名稱")
                if (name.isBlank()) return@mapNotNull null
                val ver = str("version", "latestVersion", "版本", "版本號").removePrefix("v").removePrefix("V").ifBlank { "1.0" }
                val created = str("createdDate", "created_date", "date", "建立日期").ifBlank { todayDateString() }
                val cat = str("category", "分類").ifBlank { "個人工具" }
                val usage = str("usageGuide", "usage", "description", "介紹用法", "用法", "簡介")
                val notes = str("notes", "changelog", "備註", "更新重點")
                val ghUrl = str("githubUrl", "github", "repo", "GitHub連結")
                val appId = str("applicationId", "packageName", "appId")
                val syncId = extractSyncIdFromRaw(str("syncId", "gistId", "同步ID"))
                AppRecord(
                    name = name,
                    version = ver,
                    createdDate = created,
                    category = cat,
                    usageGuide = usage,
                    notes = notes,
                    githubUrl = ghUrl,
                    applicationId = appId,
                    syncId = syncId,
                    supportsDesktopSync = bool("supportsDesktopSync", "desktopSync", "桌面同步") ?: false,
                    isPublishedOnGitHub = bool("isPublishedOnGitHub", "published", "已發布GitHub") ?: ghUrl.isNotBlank()
                )
            }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return parsed
    }
    return emptyList()
}

fun parseBatchSyncMappings(raw: String): List<Pair<String, String>> {
    val trimmed = raw.trim().removePrefix("\uFEFF")
    if (trimmed.isBlank()) return emptyList()
    val results = mutableListOf<Pair<String, String>>()

    val jsonParsed = runCatching {
        val element = Json { ignoreUnknownKeys = true }.parseToJsonElement(trimmed)
        fun extractFromObj(obj: JsonObject): Pair<String, String>? {
            val nameKeys = listOf("name", "appName", "app_name", "title", "App名稱", "App 名稱", "名稱")
            val idKeys = listOf("syncId", "gistId", "sync_id", "gist_id", "GistID", "Gist ID", "同步ID", "同步 ID", "id")
            val n = nameKeys.firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull?.trim() }
            val rawId = idKeys.firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull?.trim() }
            val cleanId = rawId?.let { extractSyncIdFromRaw(it) }
            return if (!n.isNullOrBlank() && !cleanId.isNullOrBlank()) n to cleanId else null
        }
        when (element) {
            is JsonArray -> element.forEach { el -> (el as? JsonObject)?.let { extractFromObj(it)?.let(results::add) } }
            is JsonObject -> {
                val innerArray = (element["mappings"] as? JsonArray) ?: (element["apps"] as? JsonArray)
                if (innerArray != null) {
                    innerArray.forEach { el -> (el as? JsonObject)?.let { extractFromObj(it)?.let(results::add) } }
                } else {
                    element.forEach { (k, v) ->
                        val strVal = (v as? JsonPrimitive)?.contentOrNull
                        if (!strVal.isNullOrBlank()) {
                            val cleanId = extractSyncIdFromRaw(strVal)
                            if (cleanId.isNotBlank()) results.add(k.trim() to cleanId)
                        }
                    }
                }
            }
            else -> {}
        }
        results.isNotEmpty()
    }.getOrDefault(false)

    if (jsonParsed) return results

    val lines = trimmed.lines().filter { it.isNotBlank() }
    lines.forEachIndexed { index, line ->
        val cols = parseCsvLine(line)
        if (cols.size >= 2) {
            val c0 = cols[0].trim()
            val c1 = cols[1].trim()
            if (index == 0 && (c0.contains("名稱") || c0.equals("name", true) || c0.equals("app", true))) return@forEachIndexed
            val cleanId = extractSyncIdFromRaw(c1)
            if (c0.isNotBlank() && cleanId.isNotBlank()) results.add(c0 to cleanId)
        }
    }
    return results
}

private fun parseCsvLine(line: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        if (ch == '"') {
            if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                sb.append('"'); i++
            } else inQuotes = !inQuotes
        } else if ((ch == ',' || ch == '\t') && !inQuotes) {
            out.add(sb.toString()); sb.clear()
        } else sb.append(ch)
        i++
    }
    out.add(sb.toString())
    return out
}
