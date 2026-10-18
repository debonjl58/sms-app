package com.smsforwarder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads foreground-app events only when the user has explicitly granted
 * Android's Usage Access special permission.
 */
class AppUsageObserver(
    private val context: Context,
    private val webSocketManager: WebSocketManager,
    private val historyStore: HistoryFileStore
) {
    companion object {
        private const val TAG = "AppUsageObserver"
        private const val POLL_INTERVAL_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var lastTimestamp = System.currentTimeMillis()

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun start() {
        if (job?.isActive == true) return
        lastTimestamp = System.currentTimeMillis()
        job = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun poll() {
        try {
            if (!hasUsageAccess()) return
            val manager = context.getSystemService(UsageStatsManager::class.java) ?: return
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(lastTimestamp, now)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                if (event.timeStamp <= lastTimestamp) continue

                val packageName = event.packageName ?: continue
                val appName = try {
                    context.packageManager
                        .getApplicationInfo(packageName, 0)
                        .loadLabel(context.packageManager)
                        .toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    packageName
                }

                historyStore.appendApp(
                    deviceId = DeviceIdManager(context).getDeviceId(),
                    packageName = packageName,
                    appName = appName,
                    timestamp = event.timeStamp
                )
                webSocketManager.sendAppEvent(
                    packageName = packageName,
                    appName = appName,
                    timestamp = event.timeStamp
                )
                lastTimestamp = event.timeStamp
            }

            lastTimestamp = maxOf(lastTimestamp, now - 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture historique applications", e)
        }
    }
}
