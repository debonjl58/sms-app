package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.UUID

class DeviceIdManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    companion object {
        private const val DEVICE_ID_KEY = "unique_device_id"
        private const val STABLE_ID_KEY = "stable_device_suffix"
    }

    /**
     * Returns the phone/SMS receiver number when Android exposes it and the
     * user has granted the appropriate phone-number permission. Some carriers
     * deliberately do not expose a line number; in that case null is returned.
     */
    fun getReceiverNumber(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_NUMBERS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val subscriptionManager =
                    context.getSystemService(SubscriptionManager::class.java)
                val subscriptions = subscriptionManager?.activeSubscriptionInfoList.orEmpty()
                for (subscription in subscriptions) {
                    val number = try {
                        subscriptionManager?.getPhoneNumber(subscription.subscriptionId)
                    } catch (_: SecurityException) {
                        null
                    }
                    if (!number.isNullOrBlank()) return number
                }
            }

            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            if (telephonyManager != null) {
                val number = try { telephonyManager.line1Number } catch (_: SecurityException) { null }
                if (!number.isNullOrBlank()) return number
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /** Stable ID suffix survives application restarts and server restarts. */
    private fun getStableSuffix(): String {
        var suffix = prefs.getString(STABLE_ID_KEY, null)
        if (suffix == null) {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: UUID.randomUUID().toString()
            suffix = (androidId + UUID.randomUUID().toString())
                .replace("-", "")
                .takeLast(12)
            prefs.edit().putString(STABLE_ID_KEY, suffix).apply()
        }
        return suffix
    }

    fun getDeviceId(): String {
        val receiverNumber = getReceiverNumber()
        val stableSuffix = getStableSuffix()
        val formatted = if (!receiverNumber.isNullOrBlank()) {
            "ID=${receiverNumber.trim()}.$stableSuffix"
        } else {
            "ID=UNKNOWN.$stableSuffix"
        }

        prefs.edit().putString(DEVICE_ID_KEY, formatted).apply()
        return formatted
    }
}
