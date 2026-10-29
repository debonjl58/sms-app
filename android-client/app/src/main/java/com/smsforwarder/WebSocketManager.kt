package com.smsforwarder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Gestionnaire unique de la connexion WebSocket.
 *
 * Regles anti-boucle :
 *  1. Une seule WebSocketClient vivante : l'ancienne est TOUJOURS fermee.
 *  2. Tout callback d'un client perime est ignore (garde d'identite).
 *  3. Une deconnexion n'est traitee qu'UNE fois par socket.
 *  4. onError ne declenche rien si la socket est encore ouverte.
 *  5. Reconnexion avec backoff exponentiel + jitter, remis a zero apres auth.
 *  6. Battement de coeur applicatif + detection de silence serveur.
 *  7. Chien de garde sur le delai de connexion et sur l'authentification.
 */
class WebSocketManager private constructor(
    private val repository: SmsRepository,
        private val pendingQueue: PendingSmsQueue,
            private val deviceIdManager: DeviceIdManager
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

        @Volatile
        private var instance: WebSocketManager? = null
            private val instanceLock = Any()

            fun getInstance(context: Context): WebSocketManager {
                return instance ?: synchronized(instanceLock) {
                    instance ?: run {
                        val appContext = context.applicationContext
                        WebSocketManager(
                            SmsRepository(appContext),
                                         PendingSmsQueue(appContext),
                                         DeviceIdManager(appContext)
                        ).also { instance = it }
                    }
                }
            }
    }

    /**
     * Sans CoroutineExceptionHandler, toute exception dans un scope.launch { }
     * remonte au handler par defaut du thread et TUE le process.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Exception coroutine non rattrapee", e)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    @Volatile
    private var webSocketClient: WebSocketClient? = null

        @Volatile
        private var isConnected = false

        @Volatile
        private var isAuthenticated = false

        @Volatile
        private var isConnecting = false

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

                            /** Vrai des qu'une socket a deja signale sa deconnexion. */
                            private val disconnectHandled = AtomicBoolean(false)

                            private val connectionLock = Any()

                            /** SMS envoyes mais dont l'ACK n'a pas encore ete recu. */
                            private val pendingAcks = ConcurrentHashMap<String, SmsData>()

                            private val ackTimeoutJobs = ConcurrentHashMap<String, Job>()

                            /** Appele depuis le thread WebSocket : l'appelant doit revenir sur le thread UI. */
                            var onConnectionChange: ((Boolean) -> Unit)? = null

                            private val deviceId: String by lazy { deviceIdManager.getDeviceId() }

                            // ==================================================================
                            // CONNEXION
                            // ==================================================================

                            fun connect() {
                                synchronized(connectionLock) {

                                    if (isConnected && webSocketClient?.isOpen == true) return
                                        if (isConnecting) return

                                            // Annule une attente de reconnexion en cours.
                                            // scheduleReconnect() met reconnectJob a null AVANT d'appeler connect(),
                                            // donc on ne se cancelle jamais soi-meme.
                                            reconnectJob?.let { job ->
                                                reconnectJob = null
                                                if (job.isActive) job.cancel()
                                            }

                                            manualDisconnect = false
                                            isConnecting = true
                                            disconnectHandled.set(false)

                                            // On ferme TOUJOURS le client precedent.
                                            val previous = webSocketClient
                                            webSocketClient = null
                                            safeClose(previous)

                                            try {
                                                val uri = URI(Config.WEBSOCKET_URL)

                                                // CORRECTION COMPILATION :
                                                // Une expression d'objet anonyme ne peut pas referencer le
                                                // val qu'elle initialise -> "Unresolved reference: client".
                                                // On declare donc la variable AVANT, avec lateinit var.
                                                lateinit var client: WebSocketClient
                                                client = object : WebSocketClient(uri) {

                                                    override fun onOpen(handshakedata: ServerHandshake?) {
                                                        // Garde d'identite.
                                                        if (client !== webSocketClient) {
                                                            Log.d(TAG, "onOpen ignore (client perime)")
                                                            return
                                                        }

                                                        Log.d(TAG, "Connecte au serveur ${Config.WEBSOCKET_URL}")

                                                        connectTimeoutJob?.cancel()
                                                        isConnecting = false
                                                        isConnected = true
                                                        isAuthenticated = false
                                                        connectedAt = System.currentTimeMillis()
                                                        lastServerMessageAt = System.currentTimeMillis()

                                                        onConnectionChange?.invoke(true)

                                                        val identJson = JSONObject().apply {
                                                            put("type", "identification")
                                                            put("deviceId", deviceId)
                                                            put("token", Config.AUTH_TOKEN)
                                                            put("timestamp", System.currentTimeMillis())
                                                        }

                                                        try {
                                                            send(identJson.toString())
                                                            if (Config.DEBUG_PROTOCOL) Log.d(TAG, "-> $identJson")
                                                                Log.d(TAG, "Identification envoyee pour $deviceId")
                                                        } catch (t: Throwable) {
                                                            Log.e(TAG, "Erreur envoi identification", t)
                                                            forceReconnect(client, "envoi-identification")
                                                                return
                                                        }

                                                        // Sans reponse "authenticated", aucun SMS ne partirait.
                                                        authTimeoutJob?.cancel()
                                                        authTimeoutJob = scope.launch {
                                                            delay(AUTH_TIMEOUT_MS)
                                                            if (client === webSocketClient && !isAuthenticated) {
                                                                Log.w(TAG, "Pas d'authentification en ${AUTH_TIMEOUT_MS}ms -> reconnexion")
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
                                                        Log.w(TAG, "Deconnecte (code=$code, distant=$remote, raison=${reason ?: "-"})")
                                                        isConnecting = false
                                                        handleDisconnectionOnce()
                                                    }

                                                    override fun onError(ex: Exception?) {
                                                        if (client !== webSocketClient) return
                                                            Log.e(TAG, "Erreur WebSocket: ${ex?.message}", ex)

                                                            // onError est aussi emis pour des erreurs non fatales.
                                                            if (client.isOpen) return

                                                                isConnecting = false
                                                                handleDisconnectionOnce()
                                                    }
                                                }

                                                client.setConnectionLostTimeout(30)   // ping/pong natif Java-WebSocket
                                                webSocketClient = client
                                                client.connect()

                                                connectTimeoutJob?.cancel()
                                                connectTimeoutJob = scope.launch {
                                                    delay(CONNECT_TIMEOUT_MS)
                                                    if (client === webSocketClient && !isConnected) {
                                                        Log.w(TAG, "Timeout de connexion -> nouvelle tentative")
                                                        isConnecting = false
                                                        safeClose(client)
                                                        scheduleReconnect()
                                                    }
                                                }

                                            } catch (t: Throwable) {
                                                isConnecting = false
                                                Log.e(TAG, "Erreur a l'ouverture de la connexion", t)
                                                handleDisconnectionOnce()
                                            }
                                }
                            }

                            private fun handleDisconnectionOnce() {
                                if (!disconnectHandled.compareAndSet(false, true)) return
                                    handleDisconnection()
                            }

                            /**
                             * Gere une deconnexion. Idempotent : onError ET onClose la declenchent souvent.
                             */
                            private fun handleDisconnection() {

                                val wasConnected = isConnected

                                isConnecting = false
                                isConnected = false
                                isAuthenticated = false

                                connectTimeoutJob?.cancel()
                                authTimeoutJob?.cancel()
                                heartbeatJob?.cancel()

                                if (wasConnected) {
                                    onConnectionChange?.invoke(false)
                                }

                                // Session longue -> on repart d'un backoff court.
                                if (connectedAt > 0 && System.currentTimeMillis() - connectedAt > LONG_SESSION_MS) {
                                    reconnectAttempt = 0
                                }

                                requeuePendingAcks()

                                // Le client mort est referme pour ne pas laisser de thread orphelin.
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
                                if (lost.isNotEmpty()) Log.d(TAG, "${lost.size} SMS remis en file")
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

                            /**
                             * Reconnexion immediate (timeout d'auth, timeout de connexion, silence serveur).
                             */
                            private fun forceReconnect(client: WebSocketClient, reason: String) {
                                if (client !== webSocketClient) return
                                    Log.w(TAG, "Reconnexion forcee ($reason)")
                                    webSocketClient = null          // les callbacks de ce client deviennent perimes
                                    safeClose(client)
                                    handleDisconnectionOnce()
                            }

                            private fun safeClose(client: WebSocketClient?) {
                                if (client == null) return
                                    try {
                                        client.close(1000, "client-reconnect")
                                    } catch (_: Throwable) {
                                    }
                                    // Filet de securite si la poignee de fermeture ne se termine pas.
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
                                        delay(Config.PING_INTERVAL_MS)
                                        if (!isConnected) break

                                            val silentFor = System.currentTimeMillis() - lastServerMessageAt
                                            if (silentFor > SILENCE_TIMEOUT_MS) {
                                                Log.w(TAG, "Aucun message du serveur depuis ${silentFor}ms -> reconnexion")
                                                val c = webSocketClient ?: break
                                                forceReconnect(c, "silence-serveur")
                                                    break
                                            }

                                            try {
                                                val ping = JSONObject().apply {
                                                    put("type", "ping")
                                                    put("timestamp", System.currentTimeMillis())
                                                }
                                                webSocketClient?.send(ping.toString())
                                            } catch (t: Throwable) {
                                                Log.w(TAG, "Ping impossible", t)
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

                                requeuePendingAcks()

                                val c = webSocketClient
                                webSocketClient = null
                                safeClose(c)

                                isConnecting = false
                                isConnected = false
                                isAuthenticated = false

                                onConnectionChange?.invoke(false)
                            }

                            // ==================================================================
                            // ENVOI
                            // ==================================================================

                            private fun SmsData.toQueued() = PendingSmsQueue.QueuedSms(
                                id = id,
                                sender = sender,
                                body = body,
                                timestamp = timestamp,
                                deviceId = deviceId
                            )

                            private fun buildSmsId(
                                sender: String,
                                body: String,
                                timestamp: Long,
                                pdu: ByteArray?
                            ): String {
                                val pduHex = pdu?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: ""
                                val canonical = listOf(deviceId, sender, timestamp.toString(), body, pduHex)
                                .joinToString("|")
                                val digest = MessageDigest.getInstance("SHA-256")
                                .digest(canonical.toByteArray(Charsets.UTF_8))
                                return "SMS_${deviceId}_${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
                                }

                                fun sendSms(
                                    sender: String,
                                    body: String,
                                    timestamp: Long,
                                    pdu: ByteArray? = null
                                ) {
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
                                        // Il a peut-etre ete ACKe mais pas encore supprime du telephone.
                                        if (Config.DELETE_SMS_AFTER_ACK) {
                                            deleteSmsFromDeviceWithRetry(sender, body, timestamp)
                                        }
                                        return
                                    }

                                    // Persistance AVANT toute operation reseau.
                                    val queued = smsData.toQueued()
                                    pendingQueue.enqueue(queued)

                                    if (!isConnected || !isAuthenticated || webSocketClient?.isOpen != true) {
                                        Log.d(TAG, "Pas connecte, mise en file: $uniqueId")
                                        connect()
                                        return
                                    }

                                    // On compte la tentative des l'envoi immediat (sinon retry infini).
                                    pendingQueue.markAttempted(queued)

                                    scope.launch { sendSmsInternal(smsData) }
                                }

                                private suspend fun sendSmsInternal(smsData: SmsData): Boolean {
                                    if (!isConnected || !isAuthenticated || webSocketClient?.isOpen != true) {
                                        Log.w(TAG, "WebSocket non disponible: ${smsData.id}")
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
                                        Log.d(TAG, "Envoye: ${smsData.id}")
                                        if (Config.DEBUG_PROTOCOL) Log.d(TAG, "-> $json")

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
                                        pendingQueue.markAttempted(queued)
                                        false
                                    }
                                }

                                private fun handleAckTimeout(smsId: String) {
                                    ackTimeoutJobs.remove(smsId)
                                    val smsData = pendingAcks.remove(smsId) ?: return

                                    Log.w(TAG, "Timeout ACK, remise en file: $smsId")

                                    val queued = smsData.toQueued()
                                    pendingQueue.enqueue(queued)
                                    pendingQueue.markAttempted(queued)
                                }

                                private suspend fun sendPendingSms() {
                                    if (!isAuthenticated) return

                                        val pending = pendingQueue.getPending()
                                        if (pending.isEmpty()) return

                                            Log.d(TAG, "${pending.size} SMS en file a envoyer")

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
                                                Log.d(TAG, "Authentification confirmee par le serveur")
                                                startPeriodicFlush()
                                                scope.launch { sendPendingSms() }
                                            }

                                            "auth_error" -> {
                                                val raison = json.optString("reason").ifEmpty { json.optString("error") }
                                                Log.e(TAG, "Authentification REFUSEE par le serveur (raison: $raison)")
                                                Log.e(
                                                    TAG,
                                                    "Verifie que Config.AUTH_TOKEN correspond exactement a " +
                                                    "AUTH_TOKEN cote serveur."
                                                )
                                                isAuthenticated = false
                                                handleDisconnectionOnce()
                                            }

                                            "pong" -> {
                                                // lastServerMessageAt est deja mis a jour dans onMessage.
                                            }

                                            "ping" -> {
                                                val pong = JSONObject().apply {
                                                    put("type", "pong")
                                                    put("timestamp", System.currentTimeMillis())
                                                }
                                                webSocketClient?.send(pong.toString())
                                            }

                                            "ack" -> {
                                                val smsId = json.optString("smsId")
                                                if (smsId.isEmpty()) {
                                                    Log.w(TAG, "ACK sans smsId")
                                                    return
                                                }

                                                ackTimeoutJobs.remove(smsId)?.cancel()

                                                val ackedSms = pendingAcks.remove(smsId)
                                                val queuedSms = if (ackedSms == null) pendingQueue.get(smsId) else null

                                                repository.markAsAcknowledged(smsId)
                                                pendingQueue.remove(smsId)

                                                Log.d(TAG, "ACK recu: $smsId")

                                                if (Config.DELETE_SMS_AFTER_ACK) {
                                                    val sender = ackedSms?.sender ?: queuedSms?.sender
                                                    val body = ackedSms?.body ?: queuedSms?.body
                                                    val timestamp = ackedSms?.timestamp ?: queuedSms?.timestamp

                                                    if (sender != null && body != null && timestamp != null) {
                                                        deleteSmsFromDeviceWithRetry(sender, body, timestamp)
                                                    } else {
                                                        Log.w(TAG, "ACK sans donnees SMS pour suppression: $smsId")
                                                    }
                                                }
                                            }

                                            "error" -> {
                                                val smsId = json.optString("smsId")
                                                Log.e(TAG, "Erreur serveur: $smsId")
                                            }

                                            else -> Log.d(TAG, "Message serveur ignore: $msgType")
                                        }
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "Erreur parsing message", t)
                                    }
                                }

                                /**
                                 * Supprime le SMS du telephone, avec tentatives espacees : le SMS peut ne pas
                                 * encore etre present dans le provider au moment de l'ACK.
                                 */
                                private fun deleteSmsFromDeviceWithRetry(sender: String, body: String, timestamp: Long) {
                                    scope.launch {
                                        val maxAttempts = 8
                                        repeat(maxAttempts) { attempt ->
                                            val deleted = repository.deleteSmsFromDevice(sender, body, timestamp)
                                            if (deleted > 0) {
                                                Log.d(TAG, "SMS supprime du telephone ($deleted ligne(s)): $sender")
                                                return@launch
                                            }
                                            delay(if (attempt < 3) 2_000L else 5_000L)
                                        }
                                        Log.w(
                                            TAG,
                                            "SMS introuvable apres $maxAttempts tentatives: $sender " +
                                            "(l'app est-elle client SMS par defaut ?)"
                                        )
                                    }
                                }
                            }
