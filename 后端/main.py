"""
AI影伴 — 后端服务器 v2.0
WebSocket 消息中转 + 房间管理
支持 13 种消息类型（匹配前端 YinBan_v1.0）
"""

import json
import os
import uuid
from datetime import datetime, timezone, timedelta
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query

app = FastAPI()

# 房间管理：{"房间号": {"patient": WebSocket, "guardian": WebSocket}}
rooms: dict[str, dict[str, WebSocket]] = {}

# 中国时区
CST = timezone(timedelta(hours=8))

# 推流地址 — 从环境变量读取，默认指向硬件同学 Flask MJPEG 服务
MJPEG_STREAM_URL = os.getenv("MJPEG_STREAM_URL", "http://192.168.137.83:5000/video_feed")


def make_msg(msg_type: str, data: dict) -> dict:
    """生成统一格式的消息"""
    return {
        "msg_id": str(uuid.uuid4()),
        "type": msg_type,
        "data": data,
        "timestamp": datetime.now(CST).isoformat()
    }


async def forward_to_other(room_id: str, from_role: str, msg: dict):
    """把消息转发给房间里另一个人"""
    room = rooms.get(room_id, {})
    for role, ws in room.items():
        if role != from_role:
            await ws.send_json(msg)


def get_other_role(role: str) -> str:
    """获取对方角色"""
    return "guardian" if role == "patient" else "patient"


@app.websocket("/ws")
async def handle_websocket(
    websocket: WebSocket,
    room: str = Query(...),
    role: str = Query(...)
):
    await websocket.accept()

    # --- 角色校验 ---
    if role not in ("patient", "guardian"):
        await websocket.send_json(make_msg("error", {
            "message": f"无效角色: {role}，只能是 patient 或 guardian"
        }))
        await websocket.close()
        return

    # --- 加入房间 ---
    if room not in rooms:
        rooms[room] = {}
    rooms[room][role] = websocket

    other_role = get_other_role(role)
    paired = other_role in rooms[room]

    # 通知自己
    await websocket.send_json(make_msg("room_status", {
        "room": room,
        "status": "paired" if paired else "waiting",
        "your_role": role,
        "peer_role": other_role if paired else None,
        "other_user": "已连接" if paired else "等待中..."
    }))

    # 通知对方有人加入
    if paired:
        await rooms[room][other_role].send_json(make_msg("room_status", {
            "room": room,
            "status": "paired",
            "your_role": other_role,
            "peer_role": role,
            "other_user": "已连接"
        }))

    print(f"[房间 {room}] {role} 已连接 | 当前房间数: {len(rooms)}")

    # --- 消息转发循环 ---
    try:
        while True:
            raw = await websocket.receive_text()
            msg = json.loads(raw)
            msg_type = msg.get("type", "")

            # 心跳
            if msg_type == "ping":
                await websocket.send_json(make_msg("pong", {}))

            # 直接转发的消息（原封不动转发给对方）
            elif msg_type in (
                "location_update", "device_status",
                "manual_message", "device_control_request",
                "call_request", "call_response",
                "ai_voice_command", "danger_detected",
            ):
                await forward_to_other(room, role, msg)

            # stream_start → 生成推流地址发给监护人
            elif msg_type == "stream_start":
                stream_type = msg.get("data", {}).get("stream_type", "video")
                await forward_to_other(room, role, make_msg("stream_url", {
                    "url": MJPEG_STREAM_URL,
                    "stream_type": stream_type,
                    "status": "live"
                }))

            # sos_alert → 补充 stream_url 后转发给监护人
            elif msg_type == "sos_alert":
                data = dict(msg.get("data", {}))
                if not data.get("stream_url"):
                    data["stream_url"] = MJPEG_STREAM_URL
                forwarded = dict(msg)
                forwarded["data"] = data
                await forward_to_other(room, role, forwarded)

            else:
                await websocket.send_json(make_msg("error", {
                    "message": f"未知消息类型: {msg_type}"
                }))

    except WebSocketDisconnect:
        print(f"[房间 {room}] {role} 已断开")
        if room in rooms:
            rooms[room].pop(role, None)
            if not rooms[room]:
                del rooms[room]

        # 通知对方
        if paired and room in rooms:
            try:
                await rooms[room][other_role].send_json(make_msg("room_status", {
                    "room": room,
                    "status": "waiting",
                    "your_role": other_role,
                    "peer_role": None,
                    "other_user": "对方已断开"
                }))
            except Exception:
                pass


@app.get("/")
async def root():
    return {
        "status": "running",
        "version": "2.0",
        "rooms": {k: list(v.keys()) for k, v in rooms.items()},
        "room_count": len(rooms)
    }


if __name__ == "__main__":
    import uvicorn
    print("=" * 50)
    print("AI影伴 — 后端服务器 v2.0")
    print("访问 http://localhost:8000 查看状态")
    print("WebSocket: ws://<IP>:8000/ws?room=<房间号>&role=<patient|guardian>")
    print("支持 13 种消息类型")
    print(f"MJPEG 推流地址: {MJPEG_STREAM_URL}")
    print("=" * 50)
    uvicorn.run(app, host="0.0.0.0", port=8000)
