"""
CONFIGURATION MODIFIABLE AVANT LANCEMENT
"""

# === CONFIGURATION RÉSEAU ===
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 9090          # <-- doit être le port forwardé par ngrok

# === WEBSOCKET ===
# Ping natif du serveur : 20 s, tolérance 20 s (10 s était trop court sur mobile).
WEBSOCKET_PING_INTERVAL = 20
WEBSOCKET_PING_TIMEOUT = 20

# Délai maxi de la poignée de fermeture. 10 s par défaut bloquait
# register_device quand l'ancienne socket était à moitié morte.
WEBSOCKET_CLOSE_TIMEOUT = 3

# Taille maxi d'un message entrant (un SMS long tient largement dedans).
WEBSOCKET_MAX_SIZE = 1 << 20

# === ANTI-BOUCLE ===
# Fermer la session précédente quand un appareil se reconnecte.
CLOSE_PREVIOUS_SESSION = True
# Nombre de reconnexions du même appareil dans la fenêtre avant alerte.
FLAP_WARN_THRESHOLD = 4
FLAP_WINDOW_SECONDS = 60

# === PERSISTANCE ===
DATABASE_PATH = "sms_history.db"
ENABLE_PERSISTENCE = True

# Jeton partagé avec le client Android.
AUTH_TOKEN = "1X6zYLPLBE2xiSQETMEl0AFevPQ_3au6AvjmRow2HFgghcE4c"

# === LOGS ===
LOG_LEVEL = "INFO"
