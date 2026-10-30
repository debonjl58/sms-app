"""Assemblage des fichiers téléchargés, vérification SHA-256, nommage sûr."""

import hashlib
import os
import uuid
from pathlib import Path
from typing import Optional

import config
import protocol


class DownloadSink:
    def __init__(self, device_id: str, remote_name: str):
        self.dir = Path(config.DOWNLOADS_DIR) / protocol.safe_name(device_id)
        self.dir.mkdir(parents=True, exist_ok=True)

        name = protocol.safe_name(os.path.basename(remote_name) or "fichier")
        self.final = self.dir / name
        if self.final.exists():
            self.final = self.dir / f"{self.final.stem}-{uuid.uuid4().hex[:6]}{self.final.suffix}"

        self.part = self.final.with_suffix(self.final.suffix + ".part")
        self.handle = open(self.part, "wb")
        self.sha = hashlib.sha256()
        self.size = 0
        self.expected: Optional[str] = None
        self.closed = False

    def write(self, chunk: bytes) -> None:
        if self.closed:
            return
        self.handle.write(chunk)
        self.sha.update(chunk)
        self.size += len(chunk)

    def finish(self) -> tuple[bool, str]:
        if self.closed:
            return False, str(self.final)
        self.closed = True
        self.handle.close()
        digest = self.sha.hexdigest()
        if self.expected and digest != self.expected:
            self.part.unlink(missing_ok=True)
            return False, f"sha256 {digest} != {self.expected}"
        os.replace(self.part, self.final)
        return True, str(self.final)

    def abort(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.handle.close()
            self.part.unlink(missing_ok=True)
        except OSError:
            pass
