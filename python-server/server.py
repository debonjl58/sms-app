#!/usr/bin/env python3
"""
Serveur WebSocket Python - VERSION CORRIGÉE
"""

import asyncio
import json
import logging
import inspect
import secrets
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
        
        self._notify_gui: Callable = None
        self._notify_connection: Callable = None
        
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
        """Génère un ID de connexion unique"""
        self._connection_counter += 1
        return f"conn_{self._connection_counter}"
        
    async def register_device(self, websocket: WebSocketServerProtocol, device_id: str, ip: str, conn_id: str):
        """
        CORRIGÉ : Reçoit conn_id en paramètre pour garantir la cohérence
        """
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
            'connected_at': datetime.now()
        }
        
        if self.db:
            self.db.update_device(device_id, ip)
        
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
        
        logger.info(f"📩 SMS de {sms_info['sender']}: {sms_info['body'][:50]}...")
        
        await self.send_ack(websocket, sms_id, status="received")
        await self._call_callback(self._notify_gui, sms_info)
        
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
                        authenticated = True
                        await self.register_device(websocket, device_id, client_ip, conn_id)
                        await websocket.send(json.dumps({
                            'type': 'authenticated',
                            'deviceId': device_id,
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