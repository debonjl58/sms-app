package com.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsForwarderService : Service() {

    companion object {
        private const val TAG = "SmsForwarderService"
        private const val CHANNEL_ID = "sms_forwarder_connection"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running
    }

    override fun onCreate() {
        super.onCreate()
        running = true

        try {
            createNotificationChannel()
            val notification = buildNotification()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "Service démarré (FGS typé)")
            } catch (e: Throwable) {
                CrashLog.write(this, e)
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Service démarré (FGS sans type)")
                } catch (t: Throwable) {
                    CrashLog.write(this, t)
                }
            }
        } catch (t: Throwable) {
            // createNotificationChannel / buildNotification peuvent lever aussi.
            CrashLog.write(this, t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            WebSocketManager.getInstance(applicationContext).connect()
        } catch (t: Throwable) {
            CrashLog.write(this, t)
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Timeout FGS dataSync -> reconnexion")
        try {
            WebSocketManager.getInstance(applicationContext).connect()
        } catch (t: Throwable) {
            CrashLog.write(this, t)
        }
        super.onTimeout(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service détruit par Android")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Connexion Tagala",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintient la connexion avec le serveur Tagala"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
                                                      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Tagala Immigration Services")
        .setContentText("Connexion au serveur active")
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    }
}
