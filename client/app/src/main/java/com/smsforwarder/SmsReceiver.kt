package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.ContextCompat
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()

        try {
            val appContext = context.applicationContext

            // Garantit que le service de fond est actif même si l'interface
            // n'a jamais été ouverte ou a été fermée.
            val serviceIntent = Intent(appContext, SmsForwarderService::class.java)
            ContextCompat.startForegroundService(appContext, serviceIntent)

            val webSocketManager = WebSocketManager.getInstance(appContext)

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) {
                return
            }

            // sendSms() persiste d'abord le message dans la queue avant toute
            // tentative réseau. Le BroadcastReceiver n'a donc plus à lancer
            // une coroutine réseau détachée de son cycle de vie.
            messages.forEach { sms ->
                val sender = sms.originatingAddress ?: "Unknown"
                val body = sms.messageBody ?: ""
                val timestamp = sms.timestampMillis

                Log.d(TAG, "New SMS from $sender")
                webSocketManager.sendSms(
                    sender = sender,
                    body = body,
                    timestamp = timestamp,
                    pdu = sms.pdu
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur traitement SMS", e)
        } finally {
            pendingResult.finish()
        }
    }
}
