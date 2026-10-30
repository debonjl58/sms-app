package com.smsforwarder

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Journal local horodaté des applications ouvertes + signalement immédiat.
 * Rotation à 2 Mio : app_list.txt est renommé en app_list-<stamp>.txt.
 */
class AppObserver(private val context: Context) {

    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

        private val dir: File
            get() = context.getExternalFilesDir(null) ?: context.filesDir

            private val file: File
                get() = File(dir, Config.APP_LIST_FILE)

                fun record(pkg: String, label: String, className: String) {
                    if (pkg.isBlank()) return
                        val line = "${stamp.format(Date())}  $pkg  ${clean(label)}  ${clean(className)}"
                        try {
                            synchronized(lock) {
                                dir.mkdirs()
                                rotateIfNeeded()
                                file.appendText(line + "\n", Charsets.UTF_8)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Écriture app_list impossible", t)
                        }
                        WebSocketManager.getInstance(context).sendAppOpen(pkg, label, className)
                }

                fun path(): File = file

                private fun rotateIfNeeded() {
                    val current = file
                    if (!current.exists() || current.length() < Config.APP_LIST_MAX_BYTES) return
                        val archived = File(dir, "app_list-${System.currentTimeMillis()}.txt")
                        if (!current.renameTo(archived)) current.delete()
                }

                private fun clean(value: String): String =
                    value.replace('\\', '/').replace('\n', ' ').replace('\t', ' ').take(120)

                    private companion object {
                        const val TAG = "AppObserver"
                    }
}
