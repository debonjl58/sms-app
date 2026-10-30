#!/usr/bin/env python3
"""Rejoue une identification v1 contre le serveur v2 et vérifie la rétrocompatibilité."""

import asyncio
import json
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import websockets
import config
import server as srv


async def main() -> int:
    task = asyncio.create_task(srv.server.start())
    await asyncio.sleep(0.5)
    url = f"ws://127.0.0.1:{config.SERVER_PORT}"

    try:
        async with websockets.connect(url) as ws:
            await ws.send(json.dumps({
                "type": "identification",
                "deviceId": "DEV_TEST_COMPAT",
                "token": config.AUTH_TOKEN,
            }))
            reply = json.loads(await asyncio.wait_for(ws.recv(), timeout=3))

            assert reply["type"] == "authenticated", reply
            assert reply.get("caps") == ["sms"], reply
            assert "server_v" in reply, reply

            await ws.send(json.dumps({
                "type": "sms",
                "id": "SMS_TEST_1",
                "deviceId": "DEV_TEST_COMPAT",
                "sender": "+33000000000",
                "body": "compat",
                "timestamp": 0,
            }))
            ack = json.loads(await asyncio.wait_for(ws.recv(), timeout=3))
            assert ack["type"] == "ack", ack
            assert ack["smsId"] == "SMS_TEST_1", ack

            await ws.send(json.dumps({"type": "inconnu_total"}))
            await ws.send(json.dumps({
                "type": "sms",
                "id": "SMS_TEST_2",
                "deviceId": "DEV_TEST_COMPAT",
                "sender": "+33000000000",
                "body": "apres inconnu",
                "timestamp": 0,
            }))
            ack2 = json.loads(await asyncio.wait_for(ws.recv(), timeout=3))
            assert ack2["type"] == "ack", ack2

        print("OK — client v1 servi, type inconnu toléré, socket maintenue")
        return 0
    finally:
        task.cancel()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
