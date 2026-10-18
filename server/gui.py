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
        self.devices_tree.heading('id', text='ID')
        self.devices_tree.heading('ip', text='IP')
        self.devices_tree.heading('status', text='Statut')
        self.devices_tree.column('id', width=80, anchor='center')
        self.devices_tree.column('ip', width=120)
        self.devices_tree.column('status', width=80)
        self.devices_tree.pack(fill=tk.BOTH, expand=True)

        device_btn_frame = ttk.Frame(devices_frame)
        device_btn_frame.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(device_btn_frame, text="📄 Consulter les logs", command=self.view_selected_logs).pack(side=tk.LEFT)
        self.selected_device_label = ttk.Label(device_btn_frame, text="Aucun appareil sélectionné")
        self.selected_device_label.pack(side=tk.LEFT, padx=8)
        self.devices_tree.bind("<<TreeviewSelect>>", self.on_device_selected)
        self.display_to_device_id = {}
        
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
        self.display_to_device_id = {}

        # Affichage demandé : ID numérique 1, 2, 3... puis IP du client.
        # Le véritable device_id reste utilisé en interne pour ne rien changer
        # au fonctionnement du protocole ni aux journaux.
        devices = sorted(
            server.server.connections.items(),
            key=lambda item: server.server.get_display_device_number(item[0])
        )
        for did, info in devices:
            display_id = server.server.get_display_device_number(did)
            self.display_to_device_id[str(display_id)] = did
            self.devices_tree.insert('', tk.END, values=(
                display_id,
                info['ip'],
                "🟢 Connecté"
            ))
    
    def on_device_selected(self, _event=None):
        selection = self.devices_tree.selection()
        if not selection:
            self.selected_device_label.config(text="Aucun appareil sélectionné")
            return
        values = self.devices_tree.item(selection[0], "values")
        display_id = str(values[0]) if values else ""
        device_id = self.display_to_device_id.get(display_id, "")
        self.selected_device_label.config(text=f"Sélection: {display_id}")

    def view_selected_logs(self):
        selection = self.devices_tree.selection()
        if not selection:
            messagebox.showinfo("Appareil", "Sélectionnez d'abord un appareil.")
            return

        values = self.devices_tree.item(selection[0], "values")
        display_id = str(values[0]) if values else ""
        device_id = self.display_to_device_id.get(display_id, "")
        if not device_id:
            return

        logs = server.server.get_device_logs(device_id)
        window = tk.Toplevel(self.root)
        window.title(f"Logs - client {display_id}")
        window.geometry("900x650")

        notebook = ttk.Notebook(window)
        notebook.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

        for filename in ("sms-history.txt", "app-history.txt"):
            frame = ttk.Frame(notebook)
            notebook.add(frame, text=filename)
            text = tk.Text(frame, wrap=tk.NONE)
            scroll_y = ttk.Scrollbar(frame, orient=tk.VERTICAL, command=text.yview)
            scroll_x = ttk.Scrollbar(frame, orient=tk.HORIZONTAL, command=text.xview)
            text.configure(yscrollcommand=scroll_y.set, xscrollcommand=scroll_x.set)
            text.insert("1.0", logs.get(filename, ""))
            text.configure(state=tk.DISABLED)
            text.grid(row=0, column=0, sticky="nsew")
            scroll_y.grid(row=0, column=1, sticky="ns")
            scroll_x.grid(row=1, column=0, sticky="ew")
            frame.rowconfigure(0, weight=1)
            frame.columnconfigure(0, weight=1)

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