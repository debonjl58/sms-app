#!/usr/bin/env python3
"""
Serveur WebSocket Python - VERSION CORRIGÉE (anti-boucle)
"""

import asyncio
import json
import logging
import inspect
import secrets
import time
from collections import defaultdict, deque
from datetime import datetime
from typing import Dict, Callable, Any, Optional

import websockets
from websockets.server import WebSocketServerProtocol

import config
from database import SmsDatabase

logging.basicConfig(
    level=getattr(logging, getattr(config, "LOG_LEVEL", "INFO")),
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class SmsServer:
    def __init__(self):
        self.connections: Dict[str, Dict] = {}
        self.db = SmsDatabase() if config.ENABLE_PERSISTENCE else None
        self._connection_counter = 0

        self._notify_gui: Callable = None
        self._notify_connection: Callable = None

        # Détection de boucle de reconnexion, par appareil.
        self._reconnect_log: Dict[str, deque] = defaultdict(lambda: deque(maxlen=20))

    def set_notify_gui(self, callback):
        self._notify_gui = callback

    def set_notify_connection(self, callback):
        self._notify_connection = callback

    async def _call_callback(self, callback, *args, **kwargs):
        if callback is None:
            return
        try:
            result = callback(*args, **kwargs)
            if inspect.iscoroutine(result):
                await result
        except Exception as e:
            logger.error(f"Erreur callback: {e}")

    def _generate_conn_id(self) -> str:
        self._connection_counter += 1
        return f"conn_{self._connection_counter}"

    # ------------------------------------------------------------------
    # ANTI-BOUCLE : détecte les reconnexions rapprochées
    # ------------------------------------------------------------------
    def _track_reconnect(self, device_id: str):
        now = time.monotonic()
        history = self._reconnect_log[device_id]
        history.append(now)

        window = getattr(config, "FLAP_WINDOW_SECONDS", 60)
        recent = [t for t in history if now - t <= window]

        if len(recent) >= getattr(config, "FLAP_WARN_THRESHOLD", 4):
            logger.warning(
                f"🔁 BOUCLE DÉTECTÉE : {device_id} s'est reconnecté {len(recent)} fois "
                f"en {window}s. Vérifie que le client ne garde pas deux sockets ouvertes."
            )

    # ------------------------------------------------------------------
    # ENREGISTREMENT
    # ------------------------------------------------------------------
    async def register_device(self, websocket, device_id: str, ip: str, conn_id: str):
        """
        CORRIGÉ :
          1. On enregistre le NOUVEAU d'abord -> aucune attente avant `authenticated`.
          2. On ferme l'ancienne session APRÈS, en tâche de fond (jamais bloquant).
          3. On ne ferme pas la socket si c'est la même (identification en double).
        """
        previous = self.connections.get(device_id)
        old_ws = previous['websocket'] if previous else None
        old_conn = previous['conn_id'] if previous else None

        if old_ws is not None:
            self._track_reconnect(device_id)
            logger.warning(
                f"⚠️ {device_id} se reconnecte (ancienne session {old_conn})"
            )

        # 1) Enregistrement immédiat.
        self.connections[device_id] = {
            'websocket': websocket,
            'ip': ip,
            'conn_id': conn_id,
            'connected_at': datetime.now(),
        }

        if self.db:
            self.db.update_device(device_id, ip)

        logger.info(f"📱 Device connecté: {device_id} (conn: {conn_id}, IP: {ip})")
        await self._call_callback(self._notify_connection, device_id, ip, True)

        # 2) Fermeture différée de l'ancienne session.
        if (
            getattr(config, "CLOSE_PREVIOUS_SESSION", True)
            and old_ws is not None
            and old_ws is not websocket
            and not old_ws.closed
        ):
            asyncio.create_task(self._close_quietly(old_ws, 4001, "replaced"))

    async def _close_quietly(self, ws, code: int = 4001, reason: str = "replaced"):
        try:
            await ws.close(code=code, reason=reason)
        except Exception:
            try:
                await ws.wait_closed()
            except Exception:
                pass

    async def unregister_device(self, device_id: str, conn_id: str):
        current = self.connections.get(device_id)
        if current is None:
            return
        if current.get('conn_id') == conn_id:
            del self.connections[device_id]
            logger.info(f"📴 Device déconnecté: {device_id} (conn: {conn_id})")
            await self._call_callback(
                self._notify_connection, device_id, current.get('ip', 'unknown'), False
            )
        else:
            logger.debug(
                f"unregister ignoré pour {conn_id} "
                f"(session courante: {current.get('conn_id')})"
            )

    # ------------------------------------------------------------------
    # SMS
    # ------------------------------------------------------------------
    async def handle_sms(self, data: dict, websocket):
        sms_id = data.get('id')
        device_id = data.get('deviceId', 'unknown')

        if not sms_id:
            await self.send_error(websocket, None, "missing_sms_id")
            return

        sender = data.get('sender') or ''
        body = data.get('body') or ''          # CORRIGÉ : None-safe

        sms_info = {
            'id': sms_id,
            'sender': sender,
            'body': body,
            'timestamp': data.get('timestamp'),
            'deviceId': device_id,
            'deviceIp': data.get('deviceIp', 'unknown'),
            'receivedAt': data.get('receivedAt'),
            'serverTime': datetime.now().isoformat(),
        }

        if self.db:
            if not self.db.store_sms_if_new(sms_info):
                logger.warning(f"⚠️ Doublon détecté: {sms_id}")
                await self.send_ack(websocket, sms_id, status="duplicate")
                return

        logger.info(f"📩 SMS de {sender}: {body[:50]}")

        await self.send_ack(websocket, sms_id, status="received")
        await self._call_callback(self._notify_gui, sms_info)

    async def send_error(self, websocket, sms_id: Optional[str], error: str):
        try:
            await websocket.send(json.dumps({
                'type': 'error',
                'smsId': sms_id,
                'error': error,
                'timestamp': datetime.now().isoformat(),
            }))
        except Exception as e:
            logger.error(f"Erreur envoi erreur serveur: {e}")

    async def send_ack(self, websocket, sms_id: str, status: str):
        try:
            await websocket.send(json.dumps({
                'type': 'ack',
                'smsId': sms_id,
                'status': status,
                'timestamp': datetime.now().isoformat(),
            }))
        except Exception as e:
            logger.error(f"Erreur envoi ACK: {e}")

    async def send_json(self, websocket, payload: dict):
        try:
            await websocket.send(json.dumps(payload))
        except Exception as e:
            logger.error(f"Erreur envoi message: {e}")

    # ------------------------------------------------------------------
    # HANDLER
    # ------------------------------------------------------------------
    async def handler(self, websocket: WebSocketServerProtocol, path: str = ""):
        client_ip = websocket.remote_address[0] if websocket.remote_address else "unknown"
        device_id = None
        authenticated = False
        conn_id = self._generate_conn_id()

        logger.debug(f"Nouvelle socket {conn_id} depuis {client_ip}")

        try:
            async for message in websocket:
                # CORRIGÉ : une exception sur un seul message ne doit JAMAIS
                # fermer la connexion. On attrape Exception, pas seulement
                # json.JSONDecodeError.
                try:
                    await self._dispatch(message, websocket, conn_id, client_ip)
                except websockets.exceptions.ConnectionClosed:
                    raise
                except Exception as e:
                    logger.error(f"Erreur traitement message ({conn_id}): {e!r}")

                # L'état d'authentification est porté par les attributs locaux :
                # on les relit depuis _dispatch via un petit truc simple.
                state = getattr(websocket, "_sf_state", None)
                if state:
                    device_id = state.get('device_id')
                    authenticated = state.get('authenticated')

        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            logger.error(f"Erreur handler {conn_id}: {e!r}")
        finally:
            if device_id:
                await self.unregister_device(device_id, conn_id)

    async def _dispatch(self, message, websocket, conn_id: str, client_ip: str):
        """Traite un message. Lève uniquement en cas de problème inattendu."""
        if isinstance(message, bytes):
            try:
                message = message.decode('utf-8')
            except UnicodeDecodeError:
                logger.error("Message binaire non UTF-8")
                return

        try:
            data = json.loads(message)
        except json.JSONDecodeError:
            logger.error(f"JSON invalide: {message[:200]}")
            return

        if not isinstance(data, dict):
            logger.error(f"JSON non-objet: {type(data).__name__}")
            return

        state = getattr(websocket, "_sf_state", None)
        if state is None:
            state = {'device_id': None, 'authenticated': False}
            websocket._sf_state = state

        msg_type = data.get('type')

        # ---------------- IDENTIFICATION ----------------
        if msg_type in ('identification', 'identify'):
            supplied = str(data.get('token', ''))
            expected = config.AUTH_TOKEN or ''

            if not expected or not secrets.compare_digest(supplied, expected):
                logger.warning(f"Authentification refusée depuis {client_ip}")
                await self.send_json(websocket, {
                    'type': 'auth_error',
                    'error': 'authentication_failed',
                })
                await websocket.close(code=1008, reason="Authentication failed")
                return

            device_id = data.get('deviceId') or f"ip_{client_ip}"
            state['device_id'] = device_id
            state['authenticated'] = True

            await self.register_device(websocket, device_id, client_ip, conn_id)

            # Envoyé immédiatement : register_device ne bloque plus.
            await self.send_json(websocket, {
                'type': 'authenticated',
                'deviceId': device_id,
                'timestamp': datetime.now().isoformat(),
            })
            return

        # ---------------- BATTEMENT DE CŒUR APPLICATIF ----------------
        if msg_type == 'ping':
            await self.send_json(websocket, {
                'type': 'pong',
                'timestamp': datetime.now().isoformat(),
            })
            return

        if msg_type == 'pong':
            return

        # ---------------- SMS ----------------
        if msg_type == 'sms':
            if not state['authenticated'] or state['device_id'] is None:
                await self.send_error(websocket, data.get('id'), 'not_authenticated')
                return

            if data.get('deviceId') != state['device_id']:
                logger.warning(
                    f"device_id_mismatch: {data.get('deviceId')} != {state['device_id']}"
                )
                await self.send_error(websocket, data.get('id'), 'device_id_mismatch')
                return

            await self.handle_sms(data, websocket)
            return

        logger.debug(f"Type inconnu ignoré: {msg_type!r}")

    # ------------------------------------------------------------------
    # DÉMARRAGE
    # ------------------------------------------------------------------
    async def start(self):
        logger.info(f"🚀 Serveur ws://{config.SERVER_HOST}:{config.SERVER_PORT}")

        async with websockets.serve(
            self.handler,
            config.SERVER_HOST,
            config.SERVER_PORT,
            ping_interval=config.WEBSOCKET_PING_INTERVAL,
            ping_timeout=config.WEBSOCKET_PING_TIMEOUT,
            close_timeout=getattr(config, "WEBSOCKET_CLOSE_TIMEOUT", 3),
            max_size=getattr(config, "WEBSOCKET_MAX_SIZE", 1 << 20),
        ):
            await asyncio.Future()


server = SmsServer()


if __name__ == "__main__":
    try:
        asyncio.run(server.start())
    except KeyboardInterrupt:
        logger.info("🛑 Serveur arrêté")
