package com.smsforwarder

import java.util.UUID

data class SmsData(
    val id: String,  // ID unique fourni par Android ou généré
    val sender: String,
    val body: String,
    val timestamp: Long,
    val deviceId: String,  // ID unique de l'appareil (pas IP)
    val receivedAt: Long = System.currentTimeMillis(),
    var status: SmsStatus = SmsStatus.PENDING
)

enum class SmsStatus {
    PENDING,      // En attente d'envoi
    SENT,        // Envoyé au serveur
    ACKNOWLEDGED // Accusé de réception par le serveur
}