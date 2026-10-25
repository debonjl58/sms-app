#!/usr/bin/env python3
"""
GUI Tkinter avec persistance SQLite
"""

import tkinter as tk
from tkinter import ttk, messagebox
import asyncio
import threading
from datetime import datetime
import json

import server
import config


class SmsServerGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("📨 SMS Forwarder Server")
        self.root.geometry("1000x700")
        
        self.setup_ui()
        
        # CORRIGÉ : Utilise les setters avec fonctions normales (sync)
        server.server.set_notify_gui(self.on_sms_received)
        server.server.set_notify_connection(self.on_device_connection)
        
        if config.ENABLE_PERSISTENCE:
            self.load_history()
        
        # Démarre serveur
        self.server_thread = threading.Thread(target=self.run_server, daemon=True)
        self.server_thread.start()
        
    def setup_ui(self):
        """Configure l'interface"""
        main_frame = ttk.Frame(self.root, padding="10")
        main_frame.pack(fill=tk.BOTH, expand=True)
        
        # Configuration
        config_frame = ttk.LabelFrame(main_frame, text="Configuration", padding="10")
        config_frame.pack(fill=tk.X, pady=(0, 10))
        
        ttk.Label(config_frame, text=f"🌐 ws://{config.SERVER_HOST}:{config.SERVER_PORT}").pack(side=tk.LEFT)
        ttk.Label(config_frame, text="💾 Persistance: " + ("✅ Activée" if config.ENABLE_PERSISTENCE else "❌ Désactivée")).pack(side=tk.LEFT, padx=20)
        
        self.status_label = ttk.Label(config_frame, text="🟢 En ligne", foreground="green")
        self.status_label.pack(side=tk.RIGHT)
        
        # Paned window pour split
        paned = ttk.PanedWindow(main_frame, orient=tk.HORIZONTAL)
        paned.pack(fill=tk.BOTH, expand=True)
        
        # Gauche: Devices
        left_frame = ttk.Frame(paned)
        paned.add(left_frame, weight=1)
        
        devices_frame = ttk.LabelFrame(left_frame, text="Appareils Connectés", padding="10")
        devices_frame.pack(fill=tk.BOTH, expand=True)
        
        self.devices_tree = ttk.Treeview(devices_frame, columns=('id', 'ip', 'status'), show='headings', height=10)
        self.devices_tree.heading('id', text='Device ID')
        self.devices_tree.heading('ip', text='IP')
        self.devices_tree.heading('status', text='Statut')
        self.devices_tree.column('id', width=150)
        self.devices_tree.column('ip', width=120)
        self.devices_tree.column('status', width=80)
        self.devices_tree.pack(fill=tk.BOTH, expand=True)
        
        # Droite: SMS
        right_frame = ttk.Frame(paned)
        paned.add(right_frame, weight=3)
        
        sms_frame = ttk.LabelFrame(right_frame, text="SMS Reçus (Persistés)", padding="10")
        sms_frame.pack(fill=tk.BOTH, expand=True)
        
        columns = ('time', 'device', 'sender', 'message', 'status')
        self.sms_tree = ttk.Treeview(sms_frame, columns=columns, show='headings', height=20)
        
        self.sms_tree.heading('time', text='Heure')
        self.sms_tree.heading('device', text='Device')
        self.sms_tree.heading('sender', text='Expéditeur')
        self.sms_tree.heading('message', text='Message')
        self.sms_tree.heading('status', text='✓')
        
        self.sms_tree.column('time', width=80)
        self.sms_tree.column('device', width=100)
        self.sms_tree.column('sender', width=120)
        self.sms_tree.column('message', width=400)
        self.sms_tree.column('status', width=30)
        
        scrollbar = ttk.Scrollbar(sms_frame, orient=tk.VERTICAL, command=self.sms_tree.yview)
        self.sms_tree.configure(yscrollcommand=scrollbar.set)
        
        self.sms_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        # Boutons
        btn_frame = ttk.Frame(main_frame)
        btn_frame.pack(fill=tk.X, pady=(10, 0))
        
        ttk.Button(btn_frame, text="🗑️ Effacer Affichage", command=self.clear_display).pack(side=tk.LEFT, padx=5)
        ttk.Button(btn_frame, text="💾 Exporter JSON", command=self.export_json).pack(side=tk.LEFT, padx=5)
        ttk.Button(btn_frame, text="🔄 Rafraîchir Historique", command=self.load_history).pack(side=tk.LEFT, padx=5)
        ttk.Button(btn_frame, text="❌ Quitter", command=self.on_closing).pack(side=tk.RIGHT, padx=5)
        
        self.counter_label = ttk.Label(btn_frame, text="Total: 0 | Persistés: 0")
        self.counter_label.pack(side=tk.LEFT, padx=20)
        
        self.root.protocol("WM_DELETE_WINDOW", self.on_closing)
        
    def run_server(self):
        asyncio.run(server.server.start())
    
    def load_history(self):
        """Charge l'historique depuis SQLite"""
        if not server.server.db:
            return
            
        for item in self.sms_tree.get_children():
            self.sms_tree.delete(item)
        
        history = server.server.db.get_all_sms(limit=1000)
        for sms in reversed(history):
            self.add_sms_to_tree(sms, persisted=True)
        
        self.update_counter(len(history), len(history))
        
    # CORRIGÉ : Fonction normale (pas async), thread-safe avec after()
    def on_sms_received(self, sms_info: dict):
        """Callback SMS - thread-safe"""
        self.root.after(0, lambda: self.add_sms_to_tree(sms_info, persisted=False))
        return None  # Retourne None (pas de coroutine)
        
    def add_sms_to_tree(self, sms_info: dict, persisted: bool):
        """Ajoute un SMS à l'affichage"""
        time_str = sms_info.get('serverTime', datetime.now().isoformat())
        if isinstance(time_str, str):
            try:
                time_str = datetime.fromisoformat(time_str).strftime('%H:%M:%S')
            except:
                time_str = time_str[:8]
        
        message = sms_info.get('body', '')
        if len(message) > 60:
            message = message[:60] + "..."
        
        device = sms_info.get('deviceId', 'unknown')[:15]
        sender = sms_info.get('sender', 'unknown')[:20]
        
        self.sms_tree.insert('', 0, values=(
            time_str,
            device,
            sender,
            message,
            "✓" if persisted else "🆕"
        ))
        
        if not persisted:
            self.root.bell()
            total_persisted = len(server.server.db.get_all_sms()) if server.server.db and config.ENABLE_PERSISTENCE else 0
            self.update_counter(len(self.sms_tree.get_children()), total_persisted)
    
    # CORRIGÉ : Fonction normale (pas async), thread-safe avec after()
    def on_device_connection(self, device_id: str, ip: str, connected: bool):
        """Callback connexion device"""
        self.root.after(0, lambda: self.update_devices(device_id, ip, connected))
        return None  # Retourne None (pas de coroutine)
        
    def update_devices(self, device_id: str, ip: str, connected: bool):
        """Met à jour la liste des devices"""
        for item in self.devices_tree.get_children():
            self.devices_tree.delete(item)
        
        for did, info in server.server.connections.items():
            self.devices_tree.insert('', tk.END, values=(
                did[:20],
                info['ip'],
                "🟢 Connecté"
            ))
    
    def update_counter(self, displayed: int, persisted: int):
        self.counter_label.config(text=f"Affichés: {displayed} | Persistés: {persisted}")
        
    def clear_display(self):
        """Efface l'affichage (pas la DB)"""
        for item in self.sms_tree.get_children():
            self.sms_tree.delete(item)
        total_persisted = len(server.server.db.get_all_sms()) if server.server.db and config.ENABLE_PERSISTENCE else 0
        self.update_counter(0, total_persisted)
        
    def export_json(self):
        """Exporte tout l'historique"""
        if not server.server.db or not config.ENABLE_PERSISTENCE:
            messagebox.showinfo("Info", "Persistance désactivée")
            return
            
        history = server.server.db.get_all_sms()
        if not history:
            messagebox.showinfo("Info", "Aucun SMS à exporter")
            return
        
        filename = f"sms_export_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        try:
            with open(filename, 'w', encoding='utf-8') as f:
                json.dump(history, f, indent=2, ensure_ascii=False)
            messagebox.showinfo("Succès", f"Exporté: {filename}\n{len(history)} SMS")
        except Exception as e:
            messagebox.showerror("Erreur", str(e))
            
    def on_closing(self):
        if messagebox.askokcancel("Quitter", "Arrêter le serveur?"):
            self.root.destroy()


def main():
    root = tk.Tk()
    app = SmsServerGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()