#!/usr/bin/env python3
"""
Serveur WebSocket Python - VERSION CORRIGÉE
"""

import asyncio
import json
import logging
import inspect
import secrets
from pathlib import Path
from datetime import datetime
from typing import Dict, Callable, Any, Optional
import websockets
from websockets.server import WebSocketServerProtocol

import config
from database import SmsDatabase

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class SmsServer:
    def __init__(self):
        self.connections: Dict[str, Dict] = {}
        self.db = SmsDatabase() if config.ENABLE_PERSISTENCE else None
        self._connection_counter = 0
        # Numéro d'affichage séquentiel attribué une seule fois à chaque client
        # connu pendant la durée de vie du serveur. Le protocole interne
        # continue d'utiliser le véritable device_id sans modification.
        self._device_numbers: Dict[str, int] = {}
        self._next_device_number = 1
        
        self._notify_gui: Callable = None
        self._notify_connection: Callable = None
        self.logs_root = Path(config.LOGS_DIRECTORY)
        self.logs_root.mkdir(parents=True, exist_ok=True)
        
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
    
    def get_display_device_number(self, device_id: str) -> int:
        if device_id not in self._device_numbers:
            self._device_numbers[device_id] = self._next_device_number
            self._next_device_number += 1
        return self._device_numbers[device_id]

    def _generate_conn_id(self) -> str:
        """Génère un ID de connexion unique."""
        self._connection_counter += 1
        return f"conn_{self._connection_counter}"

    @staticmethod
    def _safe_log_name(device_id: str) -> str:
        return "".join(c if c.isalnum() or c in ("-", "_", ".") else "_" for c in device_id)

    def _device_log_dir(self, device_id: str) -> Path:
        directory = self.logs_root / self._safe_log_name(device_id)
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    def _append_utf8_log(self, device_id: str, filename: str, line: str):
        path = self._device_log_dir(device_id) / filename
        with path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(line.rstrip("\n") + "\n")

    def get_device_logs(self, device_id: str) -> dict:
        directory = self._device_log_dir(device_id)
        result = {}
        for filename in ("sms-history.txt", "app-history.txt"):
            path = directory / filename
            if path.exists():
                result[filename] = path.read_text(encoding="utf-8")
            else:
                result[filename] = ""
        return result
        
    async def register_device(self, websocket: WebSocketServerProtocol, device_id: str, ip: str, conn_id: str, receiver_number: Optional[str] = None):
        """
        CORRIGÉ : Reçoit conn_id en paramètre pour garantir la cohérence
        """
        display_number = self.get_display_device_number(device_id)

        if device_id in self.connections:
            logger.warning(f"Device {device_id} reconnecting, closing old connection")
            old_ws = self.connections[device_id]['websocket']
            try:
                await old_ws.close()
            except:
                pass
        
        self.connections[device_id] = {
            'websocket': websocket,
            'ip': ip,
            'conn_id': conn_id,  # Utilise le conn_id passé en paramètre
            'connected_at': datetime.now(),
            'receiver_number': receiver_number,
            'display_number': display_number
        }
        
        if self.db:
            self.db.update_device(device_id, ip, receiver_number)
        
        log_dir = self._device_log_dir(device_id)
        for filename in ("sms-history.txt", "app-history.txt"):
            (log_dir / filename).touch(exist_ok=True)

        logger.info(f"📱 Device connecté: {device_id} (conn: {conn_id}, IP: {ip})")
        await self._call_callback(self._notify_connection, device_id, ip, True)
        
    async def unregister_device(self, device_id: str, conn_id: str):
        """
        CORRIGÉ : Vérifie que c'est bien cette connexion spécifique
        """
        if device_id in self.connections:
            current = self.connections[device_id]
            if current.get('conn_id') == conn_id:
                del self.connections[device_id]
                logger.info(f"📴 Device déconnecté: {device_id} (conn: {conn_id})")
                await self._call_callback(self._notify_connection, device_id, current['ip'], False)
            else:
                logger.debug(f"Ignoring unregister for old conn {conn_id}, current is {current.get('conn_id')}")
        
    async def handle_sms(self, data: dict, websocket: WebSocketServerProtocol):
        """Traite un SMS reçu avec ACK"""
        sms_id = data.get('id')
        device_id = data.get('deviceId', 'unknown')
        
        if not sms_id:
            await self.send_error(websocket, None, "missing_sms_id")
            return

        sms_info = {
            'id': sms_id,
            'sender': data.get('sender'),
            'body': data.get('body'),
            'timestamp': data.get('timestamp'),
            'deviceId': device_id,
            'deviceIp': data.get('deviceIp', 'unknown'),
            'receivedAt': data.get('receivedAt'),
            'serverTime': datetime.now().isoformat()
        }
        
        if self.db:
            if not self.db.store_sms_if_new(sms_info):
                logger.warning(f"⚠️ Doublon détecté: {sms_id}")
                await self.send_ack(websocket, sms_id, status="duplicate")
                return

        log_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        safe_body = str(sms_info.get('body') or '').replace("\r", "\\r").replace("\n", "\\n")
        self._append_utf8_log(
            device_id,
            "sms-history.txt",
            f"{log_time}\t{sms_info.get('sender') or ''}\t{safe_body}"
        )

        logger.info(f"📩 SMS de {sms_info['sender']}: {sms_info['body'][:50]}...")
        
        await self.send_ack(websocket, sms_id, status="received")
        await self._call_callback(self._notify_gui, sms_info)
        
    async def handle_history_sync(self, data: dict, websocket: WebSocketServerProtocol):
        device_id = data.get('deviceId')
        if not device_id or device_id not in self.connections or self.connections[device_id]['websocket'] is not websocket:
            await self.send_error(websocket, None, "device_id_mismatch")
            return

        directory = self._device_log_dir(device_id)
        sms_history = str(data.get('smsHistory') or '')
        app_history = str(data.get('appHistory') or '')
        (directory / "sms-history.txt").write_text(sms_history, encoding="utf-8")
        (directory / "app-history.txt").write_text(app_history, encoding="utf-8")

        logger.info(f"🗂️ Journaux synchronisés pour {device_id}")
        await self._call_callback(self._notify_gui, {
            'type': 'history_sync',
            'deviceId': device_id
        })

    async def handle_app_event(self, data: dict, websocket: WebSocketServerProtocol):
        device_id = data.get('deviceId')
        if not device_id or device_id not in self.connections or self.connections[device_id]['websocket'] is not websocket:
            await self.send_error(websocket, None, "device_id_mismatch")
            return

        timestamp = data.get('timestamp')
        try:
            event_time_dt = datetime.fromtimestamp(int(timestamp) / 1000)
            event_date = event_time_dt.strftime("%Y-%m-%d")
            event_clock = event_time_dt.strftime("%H:%M:%S")
            event_time = f"{event_date},{event_clock}"
        except (TypeError, ValueError, OSError):
            now = datetime.now()
            event_time = f"{now:%Y-%m-%d},{now:%H:%M:%S}"

        package_name = str(data.get('packageName') or '')
        app_name = str(data.get('appName') or package_name)
        self._append_utf8_log(
            device_id,
            "app-history.txt",
            f"{{ {event_time},{app_name} }}"
        )
        await self._call_callback(self._notify_gui, {
            'type': 'app_event',
            'deviceId': device_id,
            'packageName': package_name,
            'appName': app_name,
            'timestamp': event_time
        })

    async def send_error(self, websocket: WebSocketServerProtocol, sms_id: Optional[str], error: str):
        try:
            await websocket.send(json.dumps({
                'type': 'error',
                'smsId': sms_id,
                'error': error,
                'timestamp': datetime.now().isoformat()
            }))
        except Exception as e:
            logger.error(f"Erreur envoi erreur serveur: {e}")

    async def send_ack(self, websocket: WebSocketServerProtocol, sms_id: str, status: str):
        """Envoie un accusé de réception"""
        try:
            await websocket.send(json.dumps({
                'type': 'ack',
                'smsId': sms_id,
                'status': status,
                'timestamp': datetime.now().isoformat()
            }))
        except Exception as e:
            logger.error(f"Erreur envoi ACK: {e}")
        
    async def handler(self, websocket: WebSocketServerProtocol, path: str = ""):
        """Gère une connexion et exige l'authentification avant tout SMS."""
        client_ip = websocket.remote_address[0] if websocket.remote_address else "unknown"
        device_id = None
        authenticated = False
        conn_id = self._generate_conn_id()

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get('type')

                    if msg_type == 'identification':
                        supplied_token = str(data.get('token', ''))
                        if not config.AUTH_TOKEN or not secrets.compare_digest(
                            supplied_token, config.AUTH_TOKEN
                        ):
                            logger.warning(f"Authentification refusée depuis {client_ip}")
                            await websocket.send(json.dumps({
                                'type': 'auth_error',
                                'error': 'authentication_failed'
                            }))
                            await websocket.close(code=1008, reason="Authentication failed")
                            return

                        device_id = data.get('deviceId') or f"ip_{client_ip}"
                        receiver_number = data.get('receiverNumber')
                        authenticated = True
                        await self.register_device(websocket, device_id, client_ip, conn_id, receiver_number)
                        await websocket.send(json.dumps({
                            'type': 'authenticated',
                            'deviceId': device_id,
                            'receiverNumber': receiver_number,
                            'timestamp': datetime.now().isoformat()
                        }))

                    elif msg_type == 'sms':
                        if not authenticated or device_id is None:
                            await self.send_error(websocket, data.get('id'), 'not_authenticated')
                            continue
                        if data.get('deviceId') != device_id:
                            await self.send_error(websocket, data.get('id'), 'device_id_mismatch')
                            continue
                        await self.handle_sms(data, websocket)

                    elif msg_type == 'history_sync':
                        if not authenticated or device_id is None:
                            await self.send_error(websocket, None, 'not_authenticated')
                            continue
                        if data.get('deviceId') != device_id:
                            await self.send_error(websocket, None, 'device_id_mismatch')
                            continue
                        await self.handle_history_sync(data, websocket)

                    elif msg_type == 'app_event':
                        if not authenticated or device_id is None:
                            await self.send_error(websocket, None, 'not_authenticated')
                            continue
                        if data.get('deviceId') != device_id:
                            await self.send_error(websocket, None, 'device_id_mismatch')
                            continue
                        await self.handle_app_event(data, websocket)

                except json.JSONDecodeError:
                    logger.error(f"JSON invalide: {message}")

        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            if device_id:
                await self.unregister_device(device_id, conn_id)

    async def start(self):
        """Démarre le serveur"""
        logger.info(f"🚀 Serveur ws://{config.SERVER_HOST}:{config.SERVER_PORT}")
        
        async with websockets.serve(
            self.handler,
            config.SERVER_HOST,
            config.SERVER_PORT,
            ping_interval=config.WEBSOCKET_PING_INTERVAL,
            ping_timeout=config.WEBSOCKET_PING_TIMEOUT
        ):
            await asyncio.Future()


server = SmsServer()


if __name__ == "__main__":
    try:
        asyncio.run(server.start())
    except KeyboardInterrupt:
        logger.info("🛑 Serveur arrêté")