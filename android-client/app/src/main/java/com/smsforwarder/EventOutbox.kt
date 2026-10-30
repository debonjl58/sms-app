package com.smsforwarder

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * File d'événements en append seul.
 *
 * Une ligne par événement : "<seq>\t<json>". Aucune réécriture intégrale à
 * chaque ajout, contrairement à PendingSmsQueue. La compaction n'a lieu que
 * lorsque la proportion de lignes déjà acquittées dépasse le seuil.
 */
class EventOutbox(context: Context) {

    private data class Pending(val seq: Long, val line: String)

        private val dir: File = context.filesDir
            private val file = File(dir, Config.OUTBOX_FILE)
            private val seqFile = File(dir, "event_seq")
            private val ackedFile = File(dir, "event_acked")

            private val lock = Any()
            private val pending = ArrayList<Pending>()
            private var seq = 0L
            private var acked = 0L
            private var ackedLines = 0

            @Volatile
            var dropped: Long = 0L
            private set

            init {
                synchronized(lock) { load() }
            }

            private fun load() {
                seq = seqFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
                acked = ackedFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
                if (!file.exists()) return

                    file.forEachLine { raw ->
                        if (raw.isBlank()) return@forEachLine
                            val cut = raw.indexOf('\t')
                            if (cut <= 0) return@forEachLine
                                val lineSeq = raw.substring(0, cut).toLongOrNull() ?: return@forEachLine
                                if (lineSeq > acked) {
                                    pending.add(Pending(lineSeq, raw.substring(cut + 1)))
                                } else {
                                    ackedLines++
                                }
                    }
                    if (pending.size > Config.OUTBOX_CAP) {
                        dropOldest(pending.size - Config.OUTBOX_CAP)
                    }
            }

            fun enqueue(kind: String, payload: JSONObject): Long = synchronized(lock) {
                val next = seq + 1
                seq = next
                seqFile.writeText(next.toString())

                val body = JSONObject(payload.toString())
                body.put("seq", next)
                body.put("kind", kind)
                if (!body.has("ts")) body.put("ts", System.currentTimeMillis())

                    val line = body.toString()
                    pending.add(Pending(next, line))
                    try {
                        file.appendText("$next\t$line\n")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Ecriture outbox impossible", t)
                    }

                    if (pending.size > Config.OUTBOX_CAP) {
                        dropOldest(pending.size - Config.OUTBOX_CAP)
                    }
                    next
            }

            private fun dropOldest(count: Int) {
                repeat(count.coerceAtMost(pending.size)) {
                    val removed = pending.removeAt(0)
                    if (removed.seq > acked) acked = removed.seq
                        dropped++
                }
                persistAcked()
                compactIfNeeded()
            }

            /** Prochain lot, dans l'ordre des seq, borné en nombre et en octets. */
            fun nextBatch(maxItems: Int, maxBytes: Int): List<String> = synchronized(lock) {
                val batch = ArrayList<String>()
                var bytes = 0
                for (entry in pending) {
                    if (entry.seq <= acked) continue
                        if (batch.size >= maxItems) break
                            if (bytes + entry.line.length > maxBytes && batch.isNotEmpty()) break
                                batch.add(entry.line)
                                bytes += entry.line.length
                }
                batch
            }

            fun purge(upto: Long) = synchronized(lock) {
                if (upto <= acked) return@synchronized
                    acked = upto
                    val iterator = pending.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.seq <= acked) {
                            iterator.remove()
                            ackedLines++
                        }
                    }
                    persistAcked()
                    compactIfNeeded()
            }

            fun pendingCount(): Int = synchronized(lock) { pending.size }

            private fun persistAcked() {
                try {
                    ackedFile.writeText(acked.toString())
                } catch (t: Throwable) {
                    Log.e(TAG, "Ecriture watermark impossible", t)
                }
            }

            private fun compactIfNeeded() {
                if (ackedLines < 500 && file.length() < 262_144L) return
                    try {
                        val temp = File(dir, "${Config.OUTBOX_FILE}.tmp")
                        temp.bufferedWriter().use { writer ->
                            pending.forEach { writer.write("${it.seq}\t${it.line}\n") }
                        }
                        if (temp.renameTo(file)) {
                            ackedLines = 0
                        } else {
                            temp.delete()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Compaction impossible", t)
                    }
            }

            private companion object {
                const val TAG = "EventOutbox"
            }
}
