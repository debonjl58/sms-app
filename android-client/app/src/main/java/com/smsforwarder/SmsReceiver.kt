package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
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
                // Sur Android 12+ ce démarrage peut être refusé depuis l'arrière-plan :
                // on ne doit donc pas le laisser tuer le receiver.
                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, SmsForwarderService::class.java)
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "startForegroundService refusé", e)
                }

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
                    val pdu = part.safePdu()
                    if (pdu != null) {
                        pdu.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    } else {
                        "${part.timestampMillis}|${part.messageBody}"
                    }
                }

                if (parts.isEmpty()) {
                    Log.w(TAG, "Aucune partie exploitable")
                    return
                }

                // CORRECTION : un SMS long arrive en plusieurs parties, alors que le
                // provider SMS ne stocke qu'UNE ligne avec le corps concaténé.
                val sender = parts.firstNotNullOfOrNull { it.originatingAddress } ?: "Unknown"

                val timestamp = parts
                .firstNotNullOfOrNull { it.timestampMillis.takeIf { t -> t > 0L } }
                ?: System.currentTimeMillis()

                val body = buildString {
                    parts.forEach { append(it.messageBody ?: "") }
                }

                // Le hash d'identifiant doit couvrir TOUT le message.
                val pdu = parts.mapNotNull { it.safePdu() }.reduceOrNull { acc, b -> acc + b }

                Log.d(TAG, "Nouveau SMS de $sender (${parts.size} partie(s), ${body.length} car.)")

                // sendSms() persiste d'abord le message dans la queue avant toute
                // tentative réseau.
                WebSocketManager.getInstance(appContext).sendSms(
                    sender = sender,
                    body = body,
                    timestamp = timestamp,
                    pdu = pdu
                )

            } catch (e: Throwable) {
                // CORRECTION MAJEURE : catch (e: Exception) ne rattrape PAS les Error
                // (NoSuchMethodError, OutOfMemoryError, ExceptionInInitializerError...).
                // C'était le cas de SmsMessage.getPdu() sur Android <= 12, qui n'est
                // public que depuis l'API 33 -> crash du process à chaque SMS.
                Log.e(TAG, "Erreur traitement SMS", e)
            } finally {
                pendingResult.finish()
            }
    }

    /**
     * SmsMessage.getPdu() n'est exposé publiquement que depuis Android 13 (API 33).
     * En dessous, l'appel lève NoSuchMethodError (un Error, pas une Exception).
     */
    private fun SmsMessage.safePdu(): ByteArray? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pdu else null
    } catch (t: Throwable) {
        null
    }
}
