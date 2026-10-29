package com.smsforwarder

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

class SmsForwarderApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.write(this, throwable)
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, SmsForwarderService::class.java)
                )
            } catch (t: Throwable) {
                CrashLog.write(this, t)
            }
        }
    }
}
