package com.smsforwarder

import android.content.ContentUris
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
            private const val ORDER_SUFFIX = "_order"
            private const val MAX_IDS = 3_000
            private const val DATE_WINDOW_MS = 15_000L

            private val lock = Any()
        }

        /**
         * Supprime du téléphone LE SMS correspondant (une seule ligne).
         * Retourne le nombre de lignes supprimées (0 si rien trouvé).
         *
         * CORRECTION : l'ancien repli "expéditeur + fenêtre de 5 s" supprimait
         * TOUTES les lignes de cet expéditeur dans la fenêtre, donc aussi des
         * messages distincts. On cible maintenant l'id le plus proche.
         *
         * RAPPEL : depuis Android 4.4, seul le client SMS par défaut peut écrire
         * dans le provider. Si l'app n'est pas client par défaut, delete() renvoie 0.
         */
        fun deleteSmsFromDevice(sender: String, body: String, timestamp: Long): Int {
            val resolver = appContext.contentResolver
            val uri = Telephony.Sms.CONTENT_URI

            try {
                // 1) Correspondance exacte.
                val deletedExact = resolver.delete(
                    uri,
                    "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.BODY} = ?",
                    arrayOf(sender, timestamp.toString(), body)
                )
                if (deletedExact > 0) return deletedExact

                    // 2) On cible la ligne la plus proche, puis on supprime par _id
                    //    -> une seule ligne touchée.
                    var deleted = 0
                    resolver.query(
                        uri,
                        arrayOf(Telephony.Sms._ID),
                                   "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} BETWEEN ? AND ?",
                                   arrayOf(
                                       sender,
                                       (timestamp - DATE_WINDOW_MS).toString(),
                                           (timestamp + DATE_WINDOW_MS).toString()
                                   ),
                                   "${Telephony.Sms.DATE} DESC"
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(0)
                            deleted = resolver.delete(ContentUris.withAppendedId(uri, id), null, null)
                        }
                    }

                    return deleted
            } catch (t: Throwable) {
                Log.e(TAG, "Erreur suppression SMS", t)
                return 0
            }
        }

        fun isAlreadySent(smsId: String): Boolean = getSet(SENT_KEY).contains(smsId)

        fun isAcknowledged(smsId: String): Boolean = getSet(ACK_KEY).contains(smsId)

        fun markAsSent(smsId: String) = addToSet(SENT_KEY, smsId)

        fun markAsAcknowledged(smsId: String) = addToSet(ACK_KEY, smsId)

        private fun getSet(key: String): Set<String> =
            prefs.getStringSet(key, emptySet()) ?: emptySet()

            private fun getOrder(key: String): List<String> =
                prefs.getString(key + ORDER_SUFFIX, null)
                ?.split('\u0001')
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

                /**
                 * CORRECTION : les sets grossissaient sans limite. On plafonne en
                 * supprimant les plus anciens (ordre conservé dans une clé annexe).
                 */
                private fun addToSet(key: String, value: String) {
                    synchronized(lock) {
                        val set = getSet(key).toMutableSet()
                        if (!set.add(value)) return

                            val order = getOrder(key).toMutableList()
                            order.add(value)

                            while (order.size > MAX_IDS) {
                                set.remove(order.removeAt(0))
                            }

                            prefs.edit()
                            .putStringSet(key, set)
                            .putString(key + ORDER_SUFFIX, order.joinToString("\u0001"))
                            .apply()
                    }
                }
}
