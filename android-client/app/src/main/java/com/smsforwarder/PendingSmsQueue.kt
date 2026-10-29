package com.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

class PendingSmsQueue(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sms_queue", Context.MODE_PRIVATE)

        private val gson = Gson()
        private val queueKey = "pending_sms"
        private val lock = Any()

        companion object {
            private const val TAG = "PendingSmsQueue"

            /** Seuil d'alerte : au-delà, on log un avertissement (plus d'exclusion). */
            private const val WARN_AFTER_ATTEMPTS = 5

            private const val BASE_BACKOFF_MS = 1_000L
            private const val MAX_BACKOFF_MS = 300_000L
        }

        /**
         * CORRECTION : @SerializedName sur tous les champs pour survivre à R8.
         * Sans cela, un build obfusqué change les clés JSON et corrompt la file
         * stockée lors d'une mise à jour.
         */
        data class QueuedSms(
            @SerializedName("id") val id: String,
                             @SerializedName("sender") val sender: String,
                             @SerializedName("body") val body: String,
                             @SerializedName("timestamp") val timestamp: Long,
                             @SerializedName("deviceId") val deviceId: String,
                             @SerializedName("queuedAt") val queuedAt: Long = System.currentTimeMillis(),
                             @SerializedName("attemptCount") var attemptCount: Int = 0,
                             @SerializedName("lastAttemptAt") var lastAttemptAt: Long = 0L
        )

        fun enqueue(sms: QueuedSms) {
            synchronized(lock) {
                val queue = loadQueue()
                if (queue.any { it.id == sms.id }) {
                    Log.d(TAG, "SMS déjà présent dans la queue : ${sms.id}")
                    return
                }
                queue.add(sms)
                saveQueue(queue)
                Log.d(TAG, "SMS ajouté à la queue : ${sms.id}")
            }
        }

        /**
         * CORRECTION : plus d'exclusion définitive.
         * Un SMS qui avait atteint la limite restait bloqué pour toujours.
         * Désormais le backoff est plafonné et la file continue d'être servie.
         */
        fun getPending(): List<QueuedSms> {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                return loadQueue().filter { sms ->
                    now - sms.lastAttemptAt >= getBackoffDelay(sms.attemptCount)
                }
            }
        }

        fun count(): Int = synchronized(lock) { loadQueue().size }

        /** Incrémente la tentative. Crée l'entrée si elle a disparu (upsert). */
        fun markAttempted(sms: QueuedSms) {
            synchronized(lock) {
                val queue = loadQueue()
                val index = queue.indexOfFirst { it.id == sms.id }
                val now = System.currentTimeMillis()

                if (index >= 0) {
                    val existing = queue[index]
                    queue[index] = existing.copy(
                        attemptCount = existing.attemptCount + 1,
                        lastAttemptAt = now
                    )
                } else {
                    queue.add(
                        sms.copy(attemptCount = sms.attemptCount + 1, lastAttemptAt = now)
                    )
                }
                saveQueue(queue)
            }
        }

        fun remove(smsId: String) {
            synchronized(lock) {
                val updated = loadQueue().filter { it.id != smsId }
                saveQueue(updated)
                Log.d(TAG, "SMS supprimé de la queue : $smsId")
            }
        }

        fun get(smsId: String): QueuedSms? = synchronized(lock) {
            loadQueue().find { it.id == smsId }
        }

        fun getFailedPermanently(): List<QueuedSms> = synchronized(lock) {
            loadQueue().filter { it.attemptCount >= WARN_AFTER_ATTEMPTS }
        }

        fun clear() = synchronized(lock) { saveQueue(emptyList()) }

        private fun loadQueue(): MutableList<QueuedSms> {
            val json = prefs.getString(queueKey, null) ?: return mutableListOf()
            return try {
                val type = object : TypeToken<List<QueuedSms>>() {}.type
                gson.fromJson<List<QueuedSms>>(json, type)?.toMutableList() ?: mutableListOf()
            } catch (t: Throwable) {
                Log.e(TAG, "Erreur lecture queue", t)
                mutableListOf()
            }
        }

        private fun saveQueue(queue: List<QueuedSms>) {
            prefs.edit().putString(queueKey, gson.toJson(queue)).apply()
        }

        /** Backoff exponentiel plafonné. */
        private fun getBackoffDelay(attemptCount: Int): Long {
            if (attemptCount <= 0) return BASE_BACKOFF_MS
                val shift = attemptCount.coerceAtMost(9)
                val delay = BASE_BACKOFF_MS * (1L shl shift)
                return delay.coerceAtMost(MAX_BACKOFF_MS)
        }
}
