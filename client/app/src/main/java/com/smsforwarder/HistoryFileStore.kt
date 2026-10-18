package com.smsforwarder

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UTF-8 text logs kept separately from sms-history.db. */
class HistoryFileStore(context: Context) {
    private val smsFile = File(context.filesDir, "sms-history.txt")
    private val appFile = File(context.filesDir, "app-history.txt")
    private val lock = Any()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val appDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val appTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun appendSms(deviceId: String, sender: String, body: String, timestamp: Long) {
        val time = format.format(Date(timestamp))
        val safeBody = body.replace("\r", "\\r").replace("\n", "\\n")
        appendLine(smsFile, "$time\t$deviceId\t$sender\t$safeBody")
    }

    fun readSmsHistory(): String = synchronized(lock) {
        if (smsFile.exists()) smsFile.readText(Charsets.UTF_8) else ""
    }

    fun readAppHistory(): String = synchronized(lock) {
        if (appFile.exists()) appFile.readText(Charsets.UTF_8) else ""
    }

    fun appendApp(deviceId: String, packageName: String, appName: String, timestamp: Long) {
        val date = appDateFormat.format(Date(timestamp))
        val time = appTimeFormat.format(Date(timestamp))
        appendLine(appFile, "{ $date,$time,$appName }")
    }

    private fun appendLine(file: File, line: String) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append(line)
                writer.newLine()
            }
        }
    }
}
