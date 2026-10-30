package com.smsforwarder

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object ServiceWatchdog {

    fun accessibilityEnabled(context: Context): Boolean {
        if (TagalaAccessibilityService.running) return true
            val expected = "${context.packageName}/${TagalaAccessibilityService::class.java.name}"
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (t: Throwable) {
            }
    }
}
