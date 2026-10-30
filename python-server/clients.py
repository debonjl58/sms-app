"""Registre des clients : un deviceId = une connexion active, un alias stable."""

import asyncio
from datetime import datetime, timezone
from typing import Dict, List, Optional

import protocol


class Connection:
    __slots__ = ("device_id", "alias", "ws", "ip", "caps", "connected_at", "listening", "lock")

    def __init__(self, device_id: str, alias: str, ws, ip: str, caps: List[str]):
        self.device_id = device_id
        self.alias = alias
        self.ws = ws
        self.ip = ip
        self.caps = caps
        self.connected_at = datetime.now(timezone.utc)
        self.listening = True
        self.lock = asyncio.Lock()

    @property
    def has_shell(self) -> bool:
        return "shell" in self.caps and "fs" in self.caps

    async def send(self, payload: dict) -> None:
        async with self.lock:
            await self.ws.send(protocol.dumps(**payload))

    def summary(self) -> dict:
        return {
            "deviceId": self.device_id,
            "alias": self.alias,
            "ip": self.ip,
            "caps": self.caps,
            "listening": self.listening,
            "connectedAt": self.connected_at.isoformat(),
        }


class Registry:
    """Alias C1, C2… attribués une fois par deviceId et réutilisés."""

    def __init__(self):
        self._by_device: Dict[str, Connection] = {}
        self._aliases: Dict[str, str] = {}
        self._counter = 0

    def alias_for(self, device_id: str) -> str:
        alias = self._aliases.get(device_id)
        if alias is None:
            self._counter += 1
            alias = f"C{self._counter}"
            self._aliases[device_id] = alias
        return alias

    def put(self, conn: Connection) -> Optional[Connection]:
        previous = self._by_device.get(conn.device_id)
        self._by_device[conn.device_id] = conn
        return previous

    def drop(self, device_id: str, conn: Connection) -> bool:
        current = self._by_device.get(device_id)
        if current is conn:
            del self._by_device[device_id]
            return True
        return False

    def get(self, device_id: str) -> Optional[Connection]:
        return self._by_device.get(device_id)

    def by_alias(self, alias: str) -> Optional[Connection]:
        for conn in self._by_device.values():
            if conn.alias.lower() == alias.lower():
                return conn
        return None

    def resolve(self, ref: str) -> Optional[Connection]:
        return self.by_alias(ref) or self.get(ref)

    def all(self) -> List[Connection]:
        return sorted(self._by_device.values(), key=lambda c: c.alias)

    def __len__(self) -> int:
        return len(self._by_device)
