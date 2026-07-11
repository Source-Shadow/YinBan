"""
AI影伴 — WebSocket 测试客户端
不用 App 也能测试服务器：启动两个终端，一个当患者，一个当监护人
"""

import asyncio
import json
import websockets
import sys

ROOM = "TEST001"
SERVER = "ws://localhost:8000"


async def simulate(role: str):
    url = f"{SERVER}/ws?room={ROOM}&role={role}"
    print(f"[{role}] 正在连接 {url} ...")

    async with websockets.connect(url) as ws:
        print(f"[{role}] [OK] 已连接")

        # 接收服务器发来的消息
        async def listen():
            async for raw in ws:
                msg = json.loads(raw)
                print(f"[{role}] [RECV] {msg['type']} -> {json.dumps(msg['data'], ensure_ascii=False)}")

        # 启动监听
        listener = asyncio.create_task(listen())

        # 等一小会儿看房间状态
        await asyncio.sleep(1)

        if role == "patient":
            # 模拟患者发送位置
            location_msg = {
                "msg_id": "loc-001",
                "type": "location_update",
                "data": {"lat": 39.9042, "lng": 116.4074, "accuracy": 10.0},
                "timestamp": "2026-07-10T14:30:00"
            }
            await ws.send(json.dumps(location_msg))
            print(f"[{role}] [SEND] location_update")
            await asyncio.sleep(1)

            # 模拟患者发送设备状态
            device_msg = {
                "msg_id": "dev-001",
                "type": "device_status",
                "data": {"camera": True, "headphone": True},
                "timestamp": "2026-07-10T14:30:01"
            }
            await ws.send(json.dumps(device_msg))
            print(f"[{role}] [SEND] device_status")

        elif role == "guardian":
            # 等患者加入后发一条消息
            await asyncio.sleep(2)
            manual_msg = {
                "msg_id": "msg-001",
                "type": "manual_message",
                "data": {"text": "你好，能看到我的消息吗？"},
                "timestamp": "2026-07-10T14:30:02"
            }
            await ws.send(json.dumps(manual_msg))
            print(f"[{role}] [SEND] manual_message")

        # 保持连接，收消息
        await asyncio.sleep(10)
        listener.cancel()


if __name__ == "__main__":
    role = sys.argv[1] if len(sys.argv) > 1 else "patient"
    asyncio.run(simulate(role))
