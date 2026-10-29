package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Relance le service après un redémarrage du téléphone ou une mise à jour.
 * Sans lui, l'application restait muette jusqu'à la réception d'un SMS.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    ContextCompat.startForegroundService(
                        context.applicationContext,
                        Intent(context.applicationContext, SmsForwarderService::class.java)
                    )
                    Log.d(TAG, "Service relancé après ${intent.action}")
                } catch (e: Throwable) {
                    Log.e(TAG, "Impossible de relancer le service", e)
                }
            }
        }
    }
}
