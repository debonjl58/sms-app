"""
Base de données SQLite pour persistance des SMS
"""

import sqlite3
from datetime import datetime
from typing import List, Dict
import config


class SmsDatabase:
    def __init__(self):
        self.db_path = config.DATABASE_PATH  # Utilise config.ENABLE_PERSISTENCE corrigé
        self.init_db()
    
    def init_db(self):
        """Crée les tables si elles n'existent pas"""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS sms (
                    id TEXT PRIMARY KEY,
                    sender TEXT NOT NULL,
                    body TEXT NOT NULL,
                    timestamp INTEGER,
                    device_id TEXT NOT NULL,
                    received_at TEXT,
                    server_time TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """)
            
            conn.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    device_id TEXT PRIMARY KEY,
                    ip_address TEXT,
                    first_seen TEXT DEFAULT CURRENT_TIMESTAMP,
                    last_seen TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """)
            conn.commit()
    
    def store_sms_if_new(self, sms_data: dict) -> bool:
        """Enregistre un SMS une seule fois, de façon persistante et atomique.

        La clé primaire de la table ``sms`` sert directement de mécanisme de
        déduplication persistante. Aucune collection parallèle de déduplication
        n'est conservée en mémoire ou dans une seconde table.
        """
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute("""
                INSERT OR IGNORE INTO sms
                (id, sender, body, timestamp, device_id, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (
                sms_data['id'],
                sms_data.get('sender') or '',
                sms_data.get('body') or '',
                sms_data.get('timestamp'),
                sms_data['deviceId'],
                sms_data.get('receivedAt', datetime.now().isoformat())
            ))
            conn.commit()
            return cursor.rowcount == 1

    def save_sms(self, sms_data: dict):
        """Compatibilité avec l'ancien appelant : sauvegarde directe."""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                INSERT OR REPLACE INTO sms
                (id, sender, body, timestamp, device_id, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (
                sms_data['id'],
                sms_data.get('sender') or '',
                sms_data.get('body') or '',
                sms_data.get('timestamp'),
                sms_data['deviceId'],
                sms_data.get('receivedAt', datetime.now().isoformat())
            ))
            conn.commit()

    def get_all_sms(self, limit: int = 1000) -> List[dict]:
        """Récupère l'historique des SMS"""
        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            cursor = conn.execute("""
                SELECT * FROM sms 
                ORDER BY server_time DESC 
                LIMIT ?
            """, (limit,))
            return [dict(row) for row in cursor.fetchall()]
    
    def update_device(self, device_id: str, ip_address: str):
        """Met à jour les infos device"""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                INSERT INTO devices (device_id, ip_address, last_seen)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(device_id) DO UPDATE SET
                    ip_address = excluded.ip_address,
                    last_seen = CURRENT_TIMESTAMP
            """, (device_id, ip_address))
            conn.commit()
    
    def get_devices(self) -> List[dict]:
        """Liste les devices connus"""
        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            cursor = conn.execute("""
                SELECT * FROM devices ORDER BY last_seen DESC
            """)
            return [dict(row) for row in cursor.fetchall()]