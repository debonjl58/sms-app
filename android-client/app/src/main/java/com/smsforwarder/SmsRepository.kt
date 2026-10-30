package com.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.provider.Telephony
import android.util.Log

class SmsRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("sms_prefs", Context.MODE_PRIVATE)

        companion object {
            private const val TAG = "SmsRepository"
            private const val SENT_KEY = "sent_sms_ids"
            private const val ACK_KEY = "ack_sms_ids"
            private const val DATE_WINDOW_MS = 5000L
        }

        /**
         * Supprime du téléphone le SMS correspondant.
         * Retourne le nombre de lignes supprimées (0 si rien trouvé).
         */
        fun deleteSmsFromDevice(
            sender: String,
            body: String,
            timestamp: Long
        ): Int {

            val resolver = appContext.contentResolver
            val uri = Telephony.Sms.CONTENT_URI

            try {
                // 1) Correspondance précise : expéditeur + date + corps.
                var deleted = resolver.delete(
                    uri,
                    "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.BODY} = ?",
                    arrayOf(sender, timestamp.toString(), body)
                )

                // 2) Repli : expéditeur + date avec tolérance de 5 s.
                //    Nécessaire pour les SMS longs découpés en plusieurs parties.
                if (deleted == 0) {
                    deleted = resolver.delete(
                        uri,
                        "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} BETWEEN ? AND ?",
                        arrayOf(
                            sender,
                            (timestamp - DATE_WINDOW_MS).toString(),
                                (timestamp + DATE_WINDOW_MS).toString()
                        )
                    )
                }

                return deleted
            } catch (e: Exception) {
                Log.e(TAG, "Erreur suppression SMS", e)
                return 0
            }
        }

        fun isAlreadySent(smsId: String): Boolean = getSet(SENT_KEY).contains(smsId)

        fun isAcknowledged(smsId: String): Boolean = getSet(ACK_KEY).contains(smsId)

        fun markAsSent(smsId: String) {
            addToSet(SENT_KEY, smsId)
        }

        fun markAsAcknowledged(smsId: String) {
            addToSet(ACK_KEY, smsId)
        }

        private fun getSet(key: String): Set<String> =
            prefs.getStringSet(key, mutableSetOf()) ?: mutableSetOf()

            private fun addToSet(key: String, value: String) {
                val set = getSet(key).toMutableSet()
                set.add(value)
                prefs.edit().putStringSet(key, set).apply()
            }
}
