package com.smsforwarder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class WebSocketManager private constructor(
    private val appContext: Context,
    private val repository: SmsRepository,
    private val pendingQueue: PendingSmsQueue,
    private val deviceIdManager: DeviceIdManager
) {

    private var webSocketClient: WebSocketClient? = null

    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var reconnectJob: Job? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var isAuthenticated = false

    @Volatile
    private var isConnecting = false

    /*
     * SMS envoyés mais dont l'ACK n'a pas encore été reçu.
     * On conserve l'objet complet pour pouvoir remettre le SMS
     * dans la file en cas de déconnexion ou de timeout.
     */
    private val pendingAcks =
        ConcurrentHashMap<String, SmsData>()

    private val ackTimeoutJobs =
        ConcurrentHashMap<String, Job>()

    var onConnectionChange: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "WebSocketManager"

        private const val MAX_RETRY_DELAY = 300000L

        private const val ACK_TIMEOUT_MS = 30000L

        @Volatile
        private var instance: WebSocketManager? = null

        private val instanceLock = Any()

        fun getInstance(context: Context): WebSocketManager {
            return instance ?: synchronized(instanceLock) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val manager = WebSocketManager(
                        appContext,
                        SmsRepository(appContext),
                        PendingSmsQueue(appContext),
                        DeviceIdManager(appContext)
                    )
                    instance = manager
                    manager
                }
            }
        }
    }

    private val connectionLock = Any()

    private val deviceId: String by lazy {
        deviceIdManager.getDeviceId()
    }

    private val historyStore: HistoryFileStore by lazy {
        HistoryFileStore(appContext)
    }

    /**
     * Établit la connexion WebSocket.
     */
    fun connect() {
        synchronized(connectionLock) {
            if (isConnected && webSocketClient?.isOpen == true) {
                return
            }

            // WebSocketClient.connect() est asynchrone. Sans ce garde-fou,
            // plusieurs SMS reçus presque simultanément pourraient lancer
            // plusieurs connexions concurrentes pour le même device.
            if (isConnecting) {
                return
            }

            isConnecting = true
            reconnectJob?.cancel()

            try {
                val uri = URI(Config.WEBSOCKET_URL)

                webSocketClient = object : WebSocketClient(uri) {

                    override fun onOpen(
                        handshakedata: ServerHandshake?
                    ) {
                        Log.d(TAG, "✅ Connected to server")

                        isConnecting = false
                        isConnected = true
                        isAuthenticated = false

                        onConnectionChange?.invoke(true)

                        // L'envoi des SMS est volontairement bloqué jusqu'à la
                        // confirmation explicite de l'authentification par le serveur.
                        val identJson = JSONObject().apply {
                            put("type", "identification")
                            put("deviceId", deviceId)
                            put("receiverNumber", deviceIdManager.getReceiverNumber() ?: JSONObject.NULL)
                            put("token", Config.AUTH_TOKEN)
                            put("timestamp", System.currentTimeMillis())
                        }

                        try {
                            send(identJson.toString())
                            Log.d(TAG, "Identification envoyée")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Erreur identification", e)
                            handleDisconnection()
                        }
                    }

                    override fun onMessage(message: String?) {
                        handleServerMessage(message)
                    }

                    override fun onClose(
                        code: Int,
                        reason: String?,
                        remote: Boolean
                    ) {
                        Log.d(TAG, "❌ Disconnected: $reason")
                        isConnecting = false
                        handleDisconnection()
                    }

                    override fun onError(ex: Exception?) {
                        Log.e(TAG, "⚠️ WebSocket error", ex)
                        isConnecting = false
                        handleDisconnection()
                    }
                }

                webSocketClient?.connect()

            } catch (e: Exception) {
                isConnecting = false

                Log.e(
                    TAG,
                    "❌ Connection error",
                    e
                )

                handleDisconnection()
            }
        }
    }

    /**
     * Gère une déconnexion.
     *
     * Tous les SMS qui attendaient encore leur ACK
     * sont remis dans la file.
     */
    private fun handleDisconnection() {

        val wasConnected = isConnected

        isConnecting = false
        isConnected = false
        isAuthenticated = false

        if (wasConnected) {
            onConnectionChange?.invoke(false)
        }

        /*
         * Récupère les SMS actuellement en attente d'ACK.
         */
        val lostSms = pendingAcks.values.toList()

        /*
         * Annule les timers ACK.
         */
        ackTimeoutJobs.values.forEach { job ->
            job.cancel()
        }

        ackTimeoutJobs.clear()

        pendingAcks.clear()

        /*
         * Remet les SMS dans la file.
         * PendingSmsQueue se charge de la déduplication.
         */
        lostSms.forEach { smsData ->

            Log.w(
                TAG,
                "⚠️ Connexion perdue, remise en file: ${smsData.id}"
            )

            pendingQueue.enqueue(
                PendingSmsQueue.QueuedSms(
                    id = smsData.id,
                    sender = smsData.sender,
                    body = smsData.body,
                    timestamp = smsData.timestamp,
                    deviceId = smsData.deviceId
                )
            )
        }

        if (lostSms.isNotEmpty()) {

            Log.d(
                TAG,
                "📤 ${lostSms.size} SMS remis en file"
            )
        }

        /*
         * Si la connexion était réellement active ou si
         * des SMS doivent être renvoyés, programme une reconnexion.
         */
        scheduleReconnect()
    }

    /**
     * Construit un identifiant stable et pratiquement collision-résistant.
     * Le PDU est utilisé lorsqu'il est disponible, avec les champs du SMS
     * comme contexte de secours.
     */
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

    /**
     * Prépare et envoie un SMS.
     */
    fun sendSms(
        sender: String,
        body: String,
        timestamp: Long,
        pdu: ByteArray? = null
    ) {

        val uniqueId = buildSmsId(
            sender = sender,
            body = body,
            timestamp = timestamp,
            pdu = pdu
        )

        /*
         * Évite de renvoyer un SMS déjà confirmé.
         */
        if (repository.isAcknowledged(uniqueId)) {

            Log.d(
                TAG,
                "SMS déjà ACK, ignoré: $uniqueId"
            )

            return
        }

        val smsData = SmsData(
            id = uniqueId,
            sender = sender,
            body = body,
            timestamp = timestamp,
            deviceId = deviceId
        )

        historyStore.appendSms(deviceId, sender, body, timestamp)

        /*
         * Le SMS est persisté avant toute opération réseau. Ainsi, une
         * interruption du processus Android ne fait pas perdre le message.
         */
        pendingQueue.enqueue(
            PendingSmsQueue.QueuedSms(
                id = uniqueId,
                sender = sender,
                body = body,
                timestamp = timestamp,
                deviceId = deviceId
            )
        )

        if (
            !isConnected ||
            !isAuthenticated ||
            webSocketClient?.isOpen != true
        ) {

            Log.d(
                TAG,
                "Pas connecté, mise en file: $uniqueId"
            )

            connect()

            return
        }

        /*
         * Envoi immédiat.
         */
        scope.launch {

            sendSmsInternal(smsData)
        }
    }

    /**
     * Envoie réellement un SMS.
     *
     * Retourne true si WebSocketClient.send() accepte
     * le message, sinon false.
     */
    private suspend fun sendSmsInternal(
        smsData: SmsData
    ): Boolean {

        /*
         * Vérification de sécurité avant l'envoi.
         */
        if (
            !isConnected ||
            !isAuthenticated ||
            webSocketClient?.isOpen != true
        ) {

            Log.w(
                TAG,
                "⚠️ WebSocket non disponible: ${smsData.id}"
            )

            pendingQueue.enqueue(
                PendingSmsQueue.QueuedSms(
                    id = smsData.id,
                    sender = smsData.sender,
                    body = smsData.body,
                    timestamp = smsData.timestamp,
                    deviceId = smsData.deviceId
                )
            )

            return false
        }

        val json = JSONObject().apply {

            put("type", "sms")

            put("id", smsData.id)

            put("sender", smsData.sender)

            put("body", smsData.body)

            put("timestamp", smsData.timestamp)

            put("deviceId", smsData.deviceId)

            put(
                "receivedAt",
                System.currentTimeMillis()
            )
        }

        /*
         * On conserve le SMS avant l'envoi.
         * Si aucune réponse ACK n'arrive, il pourra
         * être remis dans la queue.
         */
        pendingAcks[smsData.id] = smsData

        try {

            webSocketClient?.send(json.toString())

            /*
             * Le WebSocket a accepté le message.
             * On attend maintenant l'ACK du serveur.
             */
            Log.d(
                TAG,
                "📤 Envoyé: ${smsData.id}"
            )

            ackTimeoutJobs[smsData.id] =
                scope.launch {

                    delay(ACK_TIMEOUT_MS)

                    handleAckTimeout(
                        smsData.id
                    )
                }

            return true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Erreur envoi: ${smsData.id}",
                e
            )

            pendingAcks.remove(
                smsData.id
            )

            ackTimeoutJobs
                .remove(smsData.id)
                ?.cancel()

            pendingQueue.enqueue(
                PendingSmsQueue.QueuedSms(
                    id = smsData.id,
                    sender = smsData.sender,
                    body = smsData.body,
                    timestamp = smsData.timestamp,
                    deviceId = smsData.deviceId
                )
            )

            return false
        }
    }

    /**
     * Gère le timeout d'un ACK.
     */
    private fun handleAckTimeout(
        smsId: String
    ) {

        /*
         * Retire d'abord le timer correspondant.
         */
        ackTimeoutJobs.remove(smsId)

        /*
         * Si le SMS n'est plus en attente,
         * rien à faire.
         */
        val smsData =
            pendingAcks.remove(smsId)
                ?: return

        Log.w(
            TAG,
            "⏱️ Timeout ACK, remise en file: $smsId"
        )

        pendingQueue.enqueue(
            PendingSmsQueue.QueuedSms(
                id = smsData.id,
                sender = smsData.sender,
                body = smsData.body,
                timestamp = smsData.timestamp,
                deviceId = smsData.deviceId
            )
        )
    }

    /**
     * Envoie les SMS présents dans la file.
     */
    private suspend fun sendPendingSms() {

        val pending =
            pendingQueue.getPending()

        if (pending.isEmpty()) {
            return
        }

        Log.d(
            TAG,
            "📤 ${pending.size} SMS en file à envoyer"
        )

        pending.forEach { queued ->

            /*
             * Reconstruit SmsData à partir de la queue.
             */
            val smsData = SmsData(
                id = queued.id,
                sender = queued.sender,
                body = queued.body,
                timestamp = queued.timestamp,
                deviceId = queued.deviceId
            )

            /*
             * Compte cette tentative.
             */
            pendingQueue.markAttempted(
                queued.id
            )

            /*
             * sendSmsInternal() remet lui-même
             * le SMS en queue en cas d'échec.
             */
            val sent =
                sendSmsInternal(smsData)

            if (!sent) {

                Log.w(
                    TAG,
                    "⚠️ Envoi échoué: ${queued.id}"
                )

            } else {

                Log.d(
                    TAG,
                    "📤 Tentative d'envoi réussie: ${queued.id}"
                )
            }

            /*
             * Petite pause entre deux envois.
             */
            delay(300)
        }

        /*
         * Affiche les SMS ayant atteint la limite
         * de tentatives.
         */
        val failed =
            pendingQueue.getFailedPermanently()

        if (failed.isNotEmpty()) {

            Log.e(
                TAG,
                "⚠️ ${failed.size} SMS échoués définitivement"
            )
        }
    }

    private fun sendHistorySync() {
        if (!isConnected || !isAuthenticated || webSocketClient?.isOpen != true) return
        val json = JSONObject().apply {
            put("type", "history_sync")
            put("deviceId", deviceId)
            put("smsHistory", historyStore.readSmsHistory())
            put("appHistory", historyStore.readAppHistory())
            put("timestamp", System.currentTimeMillis())
        }
        scope.launch {
            try {
                webSocketClient?.send(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Erreur synchronisation des journaux", e)
            }
        }
    }

    fun sendAppEvent(
        packageName: String,
        appName: String,
        timestamp: Long
    ) {
        val device = deviceId
        val json = JSONObject().apply {
            put("type", "app_event")
            put("deviceId", device)
            put("packageName", packageName)
            put("appName", appName)
            put("timestamp", timestamp)
            put("receivedAt", System.currentTimeMillis())
        }

        if (!isConnected || !isAuthenticated || webSocketClient?.isOpen != true) {
            return
        }

        scope.launch {
            try {
                webSocketClient?.send(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Erreur envoi historique application", e)
            }
        }
    }

    /**
     * Traite les messages reçus du serveur.
     */
    private fun handleServerMessage(
        message: String?
    ) {

        try {

            val json =
                JSONObject(message ?: return)

            val msgType =
                json.optString("type")

            when (msgType) {

                "authenticated" -> {
                    isAuthenticated = true
                    Log.d(TAG, "✅ Authentification confirmée par le serveur")
                    sendHistorySync()
                    scope.launch { sendPendingSms() }
                }

                "auth_error" -> {
                    Log.e(TAG, "❌ Authentification refusée par le serveur")
                    isAuthenticated = false
                    handleDisconnection()
                }

                "ack" -> {

                    val smsId =
                        json.optString("smsId")

                    if (smsId.isEmpty()) {
                        Log.w(
                            TAG,
                            "⚠️ ACK sans smsId"
                        )
                        return
                    }

                    /*
                     * Annule le timeout.
                     */
                    ackTimeoutJobs
                        .remove(smsId)
                        ?.cancel()

                    /*
                     * Retire des ACK en attente.
                     */
                    pendingAcks.remove(
                        smsId
                    )

                    /*
                     * Marque comme confirmé.
                     */
                    repository.markAsAcknowledged(
                        smsId
                    )

                    /*
                     * Retire également de la queue
                     * si le SMS y était présent.
                     */
                    pendingQueue.remove(
                        smsId
                    )

                    Log.d(
                        TAG,
                        "✅ ACK reçu: $smsId"
                    )
                }

                "error" -> {

                    val smsId =
                        json.optString("smsId")

                    Log.e(
                        TAG,
                        "❌ Server error: $smsId"
                    )

                    /*
                     * Pour une erreur serveur, le timeout
                     * peut déclencher la remise en queue.
                     */
                }

                else -> {

                    Log.d(
                        TAG,
                        "Message serveur ignoré: $msgType"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Erreur parsing message",
                e
            )
        }
    }

    /**
     * Programme une reconnexion.
     */
    private fun scheduleReconnect() {

        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectJob =
            scope.launch {

                val pendingCount =
                    pendingQueue.getPending().size +
                    pendingAcks.size

                val delayMs =
                    when {

                        pendingCount == 0 ->
                            5000L

                        else ->
                            (
                                5000L *
                                    pendingCount
                                        .coerceAtMost(6)
                            ).coerceAtMost(
                                MAX_RETRY_DELAY
                            )
                    }

                Log.d(
                    TAG,
                    "🔄 Reconnexion dans ${delayMs}ms..."
                )

                delay(delayMs)

                connect()
            }
    }

    /**
     * Ferme proprement la connexion.
     */
    fun disconnect() {

        /*
         * Empêche une reconnexion déjà programmée.
         */
        reconnectJob?.cancel()
        reconnectJob = null

        /*
         * Annule les timeouts.
         */
        ackTimeoutJobs.values.forEach { job ->
            job.cancel()
        }

        ackTimeoutJobs.clear()

        /*
         * Les SMS qui attendaient encore un ACK
         * sont remis dans la queue.
         */
        val lostSms =
            pendingAcks.values.toList()

        pendingAcks.clear()

        lostSms.forEach { smsData ->

            pendingQueue.enqueue(
                PendingSmsQueue.QueuedSms(
                    id = smsData.id,
                    sender = smsData.sender,
                    body = smsData.body,
                    timestamp = smsData.timestamp,
                    deviceId = smsData.deviceId
                )
            )
        }

        /*
         * Ferme la socket.
         */
        try {

            webSocketClient?.close()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erreur fermeture WebSocket",
                e
            )
        }

        webSocketClient = null

        isConnecting = false
        isConnected = false
        isAuthenticated = false

        onConnectionChange?.invoke(false)
    }
}
