package com.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Service au premier plan qui maintient le WebSocket actif même lorsque
 * l'interface MainActivity est fermée ou retirée des applications récentes.
 *
 * Le WebSocketManager est singleton : le service ne crée donc pas une
 * deuxième connexion pour le même appareil.
 */
class SmsForwarderService : Service() {

    private lateinit var appUsageObserver: AppUsageObserver

    companion object {
        private const val TAG = "SmsForwarderService"
        private const val CHANNEL_ID = "sms_forwarder_connection"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val manager = WebSocketManager.getInstance(applicationContext)
        appUsageObserver = AppUsageObserver(
            applicationContext,
            manager,
            HistoryFileStore(applicationContext)
        )
        appUsageObserver.start()

        Log.d(TAG, "Service démarré")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = WebSocketManager.getInstance(applicationContext)
        manager.connect()
        appUsageObserver.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ne pas fermer le WebSocket lorsque la tâche de l'interface est retirée.
        // START_STICKY permet également à Android de recréer le service si besoin.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Important : ne surtout pas fermer la connexion WebSocket ici.
        // La fermeture du service ne doit pas provoquer volontairement
        // la déconnexion du serveur.
        if (::appUsageObserver.isInitialized) appUsageObserver.stop()
        Log.d(TAG, "Service détruit par Android")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Connexion SMS Forwarder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintient la connexion avec le serveur SMS Forwarder"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Forwarder actif")
            .setContentText("Connexion au serveur maintenue en arrière-plan")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
