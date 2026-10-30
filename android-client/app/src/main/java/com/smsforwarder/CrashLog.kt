package com.smsforwarder

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "crash.txt"

    fun write(context: Context, throwable: Throwable) {
        val text = buildString {
            append("=== ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append(" ===\n")
            append(Log.getStackTraceString(throwable))
            append("\n\n")
        }

        Log.e(TAG, text)

        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).forEach { dir ->
            try {
                File(dir, FILE_NAME).appendText(text, Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.e(TAG, "Ecriture impossible dans $dir", t)
            }
        }
    }
}
