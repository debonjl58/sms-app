package com.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PendingSmsQueue(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sms_queue", Context.MODE_PRIVATE)

    private val gson = Gson()
    private val queueKey = "pending_sms"
    private val lock = Any()

    companion object {
        private const val TAG = "PendingSmsQueue"
        private const val MAX_ATTEMPTS = 5
    }

    data class QueuedSms(
        val id: String,
        val sender: String,
        val body: String,
        val timestamp: Long,
        val deviceId: String,
        val queuedAt: Long = System.currentTimeMillis(),
        var attemptCount: Int = 0,
        var lastAttemptAt: Long = 0
    )

    fun enqueue(sms: QueuedSms) {
        synchronized(lock) {
            val queue = loadQueue()

            if (queue.any { it.id == sms.id }) {
                Log.d(TAG, "SMS déjà présent dans la queue : ${sms.id}")
                return
            }

            queue.add(sms.copy(
                attemptCount = sms.attemptCount,
                lastAttemptAt = sms.lastAttemptAt
            ))

            saveQueue(queue)

            Log.d(TAG, "SMS ajouté à la queue : ${sms.id}")
        }
    }

    fun getPending(): List<QueuedSms> {
        synchronized(lock) {
            val now = System.currentTimeMillis()

            return loadQueue().filter { sms ->
                sms.attemptCount < MAX_ATTEMPTS &&
                    now - sms.lastAttemptAt >= getBackoffDelay(sms.attemptCount)
            }
        }
    }

    fun markAttempted(smsId: String) {
        synchronized(lock) {
            val queue = loadQueue().map { sms ->
                if (sms.id == smsId) {
                    sms.copy(
                        attemptCount = sms.attemptCount + 1,
                        lastAttemptAt = System.currentTimeMillis()
                    )
                } else {
                    sms
                }
            }

            saveQueue(queue)
        }
    }

    fun remove(smsId: String) {
        synchronized(lock) {
            val queue = loadQueue()
            val updated = queue.filter { it.id != smsId }
            saveQueue(updated)

            Log.d(TAG, "SMS supprimé de la queue : $smsId")
        }
    }

    fun get(smsId: String): QueuedSms? {
        synchronized(lock) {
            return loadQueue().find { it.id == smsId }
        }
    }

    fun getFailedPermanently(): List<QueuedSms> {
        synchronized(lock) {
            return loadQueue().filter {
                it.attemptCount >= MAX_ATTEMPTS
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            saveQueue(emptyList())
        }
    }

    private fun getQueue(): List<QueuedSms> {
        synchronized(lock) {
            return loadQueue()
        }
    }

    private fun loadQueue(): MutableList<QueuedSms> {
        val json = prefs.getString(queueKey, null) ?: return mutableListOf()

        return try {
            val type = object : TypeToken<List<QueuedSms>>() {}.type
            gson.fromJson<List<QueuedSms>>(json, type)?.toMutableList()
                ?: mutableListOf()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture queue", e)
            mutableListOf()
        }
    }

    private fun saveQueue(queue: List<QueuedSms>) {
        val json = gson.toJson(queue)

        prefs.edit()
            .putString(queueKey, json)
            .apply()
    }

    private fun getBackoffDelay(attemptCount: Int): Long {
        return when (attemptCount) {
            0 -> 1_000L
            1 -> 5_000L
            2 -> 10_000L
            3 -> 30_000L
            4 -> 60_000L
            else -> 300_000L
        }
    }
}