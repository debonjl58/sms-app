"""Journalisation des ouvertures d'applications — app_list.txt horodaté, rotation quotidienne."""

import os
from datetime import datetime, timezone, timedelta
from typing import Optional

import config

HEADER = "# Tagala app_list.txt — UTC — pkg, label, classe\n"


def _utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _escape(value: Optional[str]) -> str:
    if value is None:
        return ""
    return str(value).replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ").replace("\t", " ")


class AppListLog:
    def __init__(self):
        self.dir = config.EVENT_DIR
        os.makedirs(self.dir, exist_ok=True)
        self.path = os.path.join(self.dir, config.APP_LIST_FILE)
        self._day = self._today()
        self._ensure()

    @staticmethod
    def _today() -> str:
        return datetime.now(timezone.utc).strftime("%Y-%m-%d")

    def _ensure(self) -> None:
        if os.path.exists(self.path):
            return
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write(HEADER)

    def _rotate(self) -> None:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        try:
            os.replace(self.path, os.path.join(self.dir, f"app_list-{stamp}.txt"))
        except OSError:
            pass
        self._ensure()
        self._purge()

    def _purge(self) -> None:
        if config.APP_LIST_RETENTION_DAYS <= 0:
            return
        limit = datetime.now(timezone.utc) - timedelta(days=config.APP_LIST_RETENTION_DAYS)
        for entry in os.listdir(self.dir):
            if not entry.startswith("app_list-") or not entry.endswith(".txt"):
                continue
            full = os.path.join(self.dir, entry)
            try:
                if datetime.fromtimestamp(os.path.getmtime(full), timezone.utc) < limit:
                    os.remove(full)
            except OSError:
                pass

    def record(self, device_id: str, alias: str, package: str, label: str, class_name: str) -> None:
        today = self._today()
        if today != self._day:
            self._day = today
            self._rotate()
        line = (
            f"{_utc()}  {alias}  {device_id}  pkg={_escape(package)}"
            f'  label="{_escape(label)}"  class={_escape(class_name)}\n'
        )
        try:
            with open(self.path, "a", encoding="utf-8") as handle:
                handle.write(line)
        except OSError:
            pass


app_log = AppListLog()
