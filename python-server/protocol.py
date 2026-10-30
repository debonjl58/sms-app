"""Construction et validation des trames v3."""

import json
import secrets
from datetime import datetime, timezone

import config

CLIENT_CAPS = {"sms", "evt", "app", "shell", "fs"}


def utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def dumps(**payload) -> str:
    payload.setdefault("timestamp", utc())
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def new_cmd_id() -> str:
    return f"c_{secrets.token_hex(6)}"


def negotiate_caps(raw) -> list:
    """Renvoie un sous-ensemble valide de CLIENT_CAPS, 'sms' toujours présent."""
    if not isinstance(raw, list):
        return ["sms"]
    caps = [c for c in raw if isinstance(c, str) and c in CLIENT_CAPS]
    if "sms" not in caps:
        caps.insert(0, "sms")
    return caps


def is_valid_version(raw) -> bool:
    try:
        return int(raw) <= config.PROTOCOL_VERSION
    except (TypeError, ValueError):
        return True


def safe_name(name: str) -> str:
    cleaned = "".join(c for c in name if c.isalnum() or c in "._-")
    return cleaned or "fichier"
