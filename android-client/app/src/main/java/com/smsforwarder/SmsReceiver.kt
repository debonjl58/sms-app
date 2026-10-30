package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat

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
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, SmsForwarderService::class.java)
                )

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) {
                    Log.w(TAG, "Broadcast SMS_RECEIVED sans message exploitable")
                    return
                }

                // Certains constructeurs livrent deux fois le même PDU.
                // On déduplique les parties avant de reconstruire le message.
                val parts = messages
                .filterNotNull()
                .distinctBy { part ->
                    val pdu = part.pdu
                    if (pdu != null) {
                        pdu.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    } else {
                        "${part.timestampMillis}|${part.messageBody}"
                    }
                }

                // CORRECTION : un SMS long arrive en plusieurs parties, alors que le
                // provider SMS ne stocke qu'UNE ligne avec le corps concaténé.
                // Sans cette concaténation, le corps envoyé au serveur ne correspond
                // jamais à celui du provider et la suppression échoue.
                val sender = parts.firstNotNullOfOrNull { it.originatingAddress }
                ?: "Unknown"

                val timestamp = parts
                .firstNotNullOfOrNull { it.timestampMillis.takeIf { t -> t > 0L } }
                ?: System.currentTimeMillis()

                val body = buildString {
                    parts.forEach { append(it.messageBody ?: "") }
                }

                // Le hash d'identifiant doit couvrir TOUT le message, sinon deux SMS
                // longs distincts partageant leur première partie seraient confondus.
                val pdu = parts
                .mapNotNull { it.pdu }
                .reduceOrNull { acc, bytes -> acc + bytes }

                Log.d(
                    TAG,
                    "Nouveau SMS de $sender (${parts.size} partie(s), ${body.length} caractère(s))"
                )

                // sendSms() persiste d'abord le message dans la queue avant toute
                // tentative réseau. Le BroadcastReceiver n'a donc pas à lancer une
                // coroutine détachée de son cycle de vie.
                WebSocketManager.getInstance(appContext).sendSms(
                    sender = sender,
                    body = body,
                    timestamp = timestamp,
                    pdu = pdu
                )

            } catch (e: Exception) {
                Log.e(TAG, "Erreur traitement SMS", e)
            } finally {
                pendingResult.finish()
            }
    }
}
