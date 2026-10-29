package com.smsforwarder

import android.content.Context
import android.provider.Settings
import java.util.UUID

class DeviceIdManager(private val context: Context) {  // CORRIGÉ : context stocké comme propriété
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    
    companion object {
        private const val DEVICE_ID_KEY = "unique_device_id"
    }
    
    fun getDeviceId(): String {
        var deviceId = prefs.getString(DEVICE_ID_KEY, null)
        
        if (deviceId == null) {
            val androidId = Settings.Secure.getString(
                context.contentResolver,  // Utilise le context stocké
                Settings.Secure.ANDROID_ID
            ) ?: UUID.randomUUID().toString()
            
            deviceId = "DEV_${androidId.takeLast(12)}_${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString(DEVICE_ID_KEY, deviceId).apply()
        }
        
        return deviceId!!
    }
}