package com.smsforwarder  // CORRIGÉ : commsforwarder → com.smsforwarder

import android.content.Context
import android.content.SharedPreferences

class SmsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sms_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val SENT_KEY = "sent_sms_ids"
        private const val ACK_KEY = "ack_sms_ids"
    }
    
    fun isAlreadySent(smsId: String): Boolean {
        return getSet(SENT_KEY).contains(smsId)
    }
    
    fun isAcknowledged(smsId: String): Boolean {
        return getSet(ACK_KEY).contains(smsId)
    }
    
    fun markAsSent(smsId: String) {
        addToSet(SENT_KEY, smsId)
    }
    
    fun markAsAcknowledged(smsId: String) {
        addToSet(ACK_KEY, smsId)
    }
    
    private fun getSet(key: String): Set<String> {
        return prefs.getStringSet(key, mutableSetOf()) ?: mutableSetOf()
    }
    
    private fun addToSet(key: String, value: String) {
        val set = getSet(key).toMutableSet()
        set.add(value)
        prefs.edit().putStringSet(key, set).apply()
    }
}