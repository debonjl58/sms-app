#!/usr/bin/env python3
"""
Sonde de diagnostic. Usage :
    python probe.py                        # serveur local
    python probe.py ws://8.tcp.ngrok.io:18477
"""
import asyncio
import json
import sys
import time

import websockets

import config

URL = sys.argv[1] if len(sys.argv) > 1 else f"ws://127.0.0.1:{config.SERVER_PORT}"


async def main():
    start = time.monotonic()
    try:
        async with websockets.connect(
            URL, ping_interval=20, ping_timeout=20, close_timeout=3
        ) as ws:
            print(f"✅ CONNECTÉ en {time.monotonic() - start:.2f}s à {URL}")

            await ws.send(json.dumps({
                "type": "identification",
                "deviceId": "PROBE",
                "token": config.AUTH_TOKEN,
                "timestamp": int(time.time() * 1000),
            }))
            print("→ identification envoyée")

            async def pinger():
                while True:
                    await asyncio.sleep(15)
                    await ws.send(json.dumps({
                        "type": "ping", "timestamp": int(time.time() * 1000)
                    }))
                    print("→ ping")

            task = asyncio.create_task(pinger())
            try:
                async for message in ws:
                    print(f"← {message}")
            finally:
                task.cancel()

            print(f"🔌 FERMÉ code={ws.close_code} raison={ws.close_reason!r} "
                  f"après {time.monotonic() - start:.1f}s")
    except Exception as e:
        print(f"❌ ÉCHEC après {time.monotonic() - start:.2f}s : "
              f"{type(e).__name__}: {e}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
