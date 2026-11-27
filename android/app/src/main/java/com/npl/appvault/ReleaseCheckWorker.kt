package com.npl.appvault

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ReleaseCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val file = File(applicationContext.filesDir, "appvault_data.json")
        if (!file.exists()) return Result.success()

        val json = Json { ignoreUnknownKeys = true }
        val backup = runCatching { json.decodeFromString<AppVaultBackup>(file.readText()) }.getOrNull()
            ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("appvault_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("gist_token", "") ?: ""

        val activeApps = backup.apps.filter { it.deletedAt == null }
        val installed = queryUserInstalledApks(applicationContext)
        val pendingUpgrades = mutableListOf<String>()

        for (app in activeApps) {
            var latestKnown = app.version
            if (app.githubUrl.contains("github.com/", true)) {
                val m = Regex("""github\.com/([^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE).find(app.githubUrl)
                if (m != null) {
                    val tag = runCatching {
                        val url = URL("https://api.github.com/repos/${m.groupValues[1]}/${m.groupValues[2].removeSuffix(".git")}/releases/latest")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Accept", "application/vnd.github+json")
                            if (token.isNotBlank()) setRequestProperty("Authorization", "token $token")
                        }
                        if (conn.responseCode == 200) {
                            val txt = conn.inputStream.bufferedReader().use { it.readText() }
                            json.parseToJsonElement(txt).jsonObject["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")?.removePrefix("V")
                        } else null
                    }.getOrNull()
                    if (!tag.isNullOrBlank() && compareSemanticVersions(tag, latestKnown) > 0) {
                        latestKnown = tag
                    }
                }
            }

            val norm = normalizeAppNameKey(app.name)
            val inst = installed.find { pkg ->
                (app.applicationId.isNotBlank() && pkg.packageName.equals(app.applicationId, true)) ||
                    (norm.isNotBlank() && normalizeAppNameKey(pkg.label) == norm)
            }
            val needsInstall = !app.isInstalledOnPhone ||
                (inst != null && inst.versionName.isNotBlank() && compareSemanticVersions(inst.versionName, latestKnown) < 0)

            if (needsInstall) {
                pendingUpgrades.add("${app.name} (v$latestKnown)")
            }
        }

        if (pendingUpgrades.isNotEmpty()) {
            sendUpgradeNotification(
                applicationContext,
                "AppVault 升級提醒（${pendingUpgrades.size} 款待更新）",
                pendingUpgrades.take(4).joinToString("、")
            )
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "appvault_daily_release_check"
        private const val CHANNEL_ID = "appvault_updates"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val req = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
            } else {
                wm.cancelUniqueWork(WORK_NAME)
            }
        }

        fun sendUpgradeNotification(context: Context, title: String, content: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "App 更新與發布提醒", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 1001, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(1001, notif) }
        }
    }
}
