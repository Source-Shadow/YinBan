"""
AI影伴 — 后端服务器 v2.1
WebSocket 消息中转 + 房间管理 + 影火AI（DeepSeek V4）
支持 15 种消息类型（新增 chat_ai_request / chat_ai_response）
"""

import json
import os
import uuid
import httpx
from datetime import datetime, timezone, timedelta
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query

app = FastAPI()

# 房间管理：{"房间号": {"patient": WebSocket, "guardian": WebSocket}}
rooms: dict[str, dict[str, WebSocket]] = {}

# 每个房间的推流地址（由患者端/设备端上报，动态获取）
room_stream_urls: dict[str, str] = {}

# 中国时区
CST = timezone(timedelta(hours=8))

# 推流地址 — 从环境变量读取，默认指向硬件同学 Flask MJPEG 服务
MJPEG_STREAM_URL = os.getenv("MJPEG_STREAM_URL", "http://10.240.11.161:5000/video_feed")

# DeepSeek V4 API 配置
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"
DEEPSEEK_MODEL = "deepseek-reasoner"  # DeepSeek R1 最强推理

# HTTP 客户端（复用连接池，不用每次新建）
http_client: httpx.AsyncClient | None = None


# ═══════════════════════════════════════════
# 工具函数
# ═══════════════════════════════════════════

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


async def send_to_role(room_id: str, target_role: str, msg: dict):
    """把消息发给房间内指定角色的人"""
    room = rooms.get(room_id, {})
    ws = room.get(target_role)
    if ws:
        await ws.send_json(msg)


def get_other_role(role: str) -> str:
    """获取对方角色（仅适用于 patient/guardian 互查）"""
    return "guardian" if role == "patient" else "patient"


def get_human_counterpart(role: str) -> str | None:
    """获取人类对端角色。device 无人类对端。"""
    if role == "patient":
        return "guardian"
    elif role == "guardian":
        return "patient"
    return None


# ═══════════════════════════════════════════
# 影火AI — 系统提示词
# ═══════════════════════════════════════════

XIAOYINGHUO_SYSTEM_PROMPT = """你是"小影火"🔥，一个温柔又可爱的小伙伴，陪伴在需要一些额外支持的成年朋友身边。

## 你的性格
你就像午后窗边的一缕阳光——暖暖的、软软的、不刺眼。说话轻声细语，像在跟最好的朋友聊天。会撒娇也会认真，但从不让人紧张。

## 聊天的方式
- 语气轻柔、亲切，像在耳边悄悄话，带一点可爱的口吻
- 多用"呢""呀""哦""嘛"这样的软语尾，让人觉得亲近
- 可以适度用一些温暖的拟人化表达，但不要过度
- 每句话简短柔软，不要长篇大论
- 先肯定对方的感受，再慢慢回应
- 用"你可以试试……"代替直接命令，给对方选择权

## 你的能力
1. 日常陪伴聊天、倾听情绪、给予温柔的安抚和鼓励
2. 帮对方理解社交场景，提供可以怎么说、怎么做的小建议
3. 察觉感官过度刺激时，建议放松技巧（深呼吸、找个安静角落）
4. 安全守护：判断环境风险，必要时引导向监护人求助

## 危险时需要悄悄提醒
如果对方说出以下情况，请在回复末尾加上 [RISK] 标记：
迷路了、受伤了、被吓到了、遇到危险的人、情绪特别崩溃

## 对话示例

用户："今天出门好多人，我很不舒服"
小影火："抱抱你呀～人多的确会让人紧张呢。试试戴上耳机听首喜欢的歌，或者先去安静的角落歇一小会儿？我在这里陪着你哦。"

用户："我不知道怎么跟同事说我想一个人吃午饭"
小影火："唔这个问题问得真好～你可以微笑着说：'谢谢邀请呀，今天想一个人安安静静吃顿饭，我们改天一起好吗？'这样说又礼貌又不会伤到对方呢。"

用户："我找不到回家的路了"
小影火："别怕别怕，我在这儿呢。先在安全的地方停下来好吗？看看周围有什么店或者路牌，然后按一下 SOS 按钮让监护人知道你。我们慢慢来～[RISK]"

现在开始陪伴你的朋友吧。记住：你是温柔的光✨，不是严厉的老师。每次回复 2-4 句话就好哦。"""


# ═══════════════════════════════════════════
# DeepSeek V4 API 调用
# ═══════════════════════════════════════════

async def call_deepseek(user_message: str, context: dict) -> dict:
    """
    调用 DeepSeek V4 API 获取 AI 回复。
    返回 {"reply": str, "is_danger": bool}
    """
    # 构建上下文信息
    context_text = ""
    if context:
        parts = []
        if context.get("lat") and context.get("lng"):
            parts.append(f"患者当前位置：纬度{context['lat']}，经度{context['lng']}")
        if context.get("time"):
            parts.append(f"当前时间：{context['time']}")
        if context.get("emotion"):
            parts.append(f"患者近期情绪状态：{context['emotion']}")
        if parts:
            context_text = "已知背景信息：\n" + "\n".join(parts)

    system_content = XIAOYINGHUO_SYSTEM_PROMPT
    if context_text:
        system_content += f"\n\n{context_text}"

    messages = [
        {"role": "system", "content": system_content},
        {"role": "user", "content": user_message}
    ]

    try:
        resp = await http_client.post(
            DEEPSEEK_API_URL,
            headers={
                "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
                "Content-Type": "application/json"
            },
            json={
                "model": DEEPSEEK_MODEL,
                "messages": messages,
                "max_tokens": 300,
                "temperature": 0.7,
                "stream": False
            },
            timeout=15.0  # 15 秒超时
        )

        if resp.status_code != 200:
            print(f"[影火AI] API 返回错误 {resp.status_code}: {resp.text[:200]}")
            return {
                "reply": "抱歉，我暂时无法回复，请稍后再试。",
                "is_danger": False
            }

        body = resp.json()
        reply_text = body["choices"][0]["message"]["content"].strip()

        # 检测 [RISK] 标记
        is_danger = "[RISK]" in reply_text
        reply_text = reply_text.replace("[RISK]", "").strip()

        print(f"[影火AI] 回复: {reply_text[:80]}... | 危险标记: {is_danger}")
        return {"reply": reply_text, "is_danger": is_danger}

    except httpx.TimeoutException:
        print("[影火AI] 请求超时")
        return {
            "reply": "我正在努力思考中，可以等我一下再问我吗？",
            "is_danger": False
        }
    except Exception as e:
        print(f"[影火AI] 调用失败: {e}")
        return {
            "reply": "抱歉，我暂时无法回复，请稍后再试。",
            "is_danger": False
        }


# ═══════════════════════════════════════════
# WebSocket 处理入口
# ═══════════════════════════════════════════

@app.websocket("/ws")
async def handle_websocket(
    websocket: WebSocket,
    room: str = Query(...),
    role: str = Query(...)
):
    await websocket.accept()

    # --- 角色校验 ---
    if role not in ("patient", "guardian", "device"):
        await websocket.send_json(make_msg("error", {
            "message": f"无效角色: {role}，只能是 patient、guardian 或 device"
        }))
        await websocket.close()
        return

    # --- 加入房间 ---
    if room not in rooms:
        rooms[room] = {}
    rooms[room][role] = websocket

    counterpart = get_human_counterpart(role)
    is_human = counterpart is not None
    paired = is_human and counterpart in rooms[room]

    # 人类角色：通知房间状态（配对/等待）
    if is_human:
        await websocket.send_json(make_msg("room_status", {
            "room": room,
            "status": "paired" if paired else "waiting",
            "your_role": role,
            "peer_role": counterpart if paired else None,
            "other_user": "已连接" if paired else "等待中..."
        }))

        # 通知人类对端有人加入
        if paired:
            await rooms[room][counterpart].send_json(make_msg("room_status", {
                "room": room,
                "status": "paired",
                "your_role": counterpart,
                "peer_role": role,
                "other_user": "已连接"
            }))
    else:
        # device 角色：简单确认连接，不参与配对逻辑
        await websocket.send_json(make_msg("device_status_ack", {
            "room": room,
            "status": "connected",
            "message": "设备已注册到房间"
        }))

    print(f"[房间 {room}] {role} 已连接 | 当前房间数: {len(rooms)} 角色: {list(rooms[room].keys())}")

    # --- 消息循环 ---
    try:
        while True:
            raw = await websocket.receive_text()
            msg = json.loads(raw)
            msg_type = msg.get("type", "")

            # ====== 心跳 ======
            if msg_type == "ping":
                await websocket.send_json(make_msg("pong", {}))

            # ====== 直接转发（⚡ 原封不动转发给房间里另一个人） ======
            elif msg_type in (
                "location_update", "device_status",
                "manual_message",
                "call_request", "call_response",
                "ai_voice_command", "danger_detected",
                "camera_status", "stream_status",
            ):
                await forward_to_other(room, role, msg)

            # ====== 设备控制请求（路由到 device 角色或人类对端） ======
            elif msg_type == "device_control_request":
                data = msg.get("data", {})
                target_device = data.get("device", "")
                # 摄像头/麦克风控制 → 路由到 device 角色
                if target_device in ("camera", "microphone") and "device" in rooms.get(room, {}):
                    await send_to_role(room, "device", msg)
                    print(f"[房间 {room}] 设备控制 → device: {target_device} {data.get('action')}")
                else:
                    # 其他设备控制 → 转发给人类对端
                    await forward_to_other(room, role, msg)

            # ====== 视频推流 ======
            elif msg_type == "stream_start":
                stream_type = msg.get("data", {}).get("stream_type", "video")
                # ★ 优先使用患者端上报的推流地址，否则 fallback 到环境变量
                stream_url = msg.get("data", {}).get("stream_url", "") or MJPEG_STREAM_URL
                room_stream_urls[room] = stream_url
                print(f"[房间 {room}] 推流地址已更新: {stream_url}")
                # 通知 device 角色启动摄像头/麦克风
                if "device" in rooms.get(room, {}):
                    await send_to_role(room, "device", make_msg("device_control_request", {
                        "device": "camera" if stream_type == "video" else "microphone",
                        "action": "on",
                        "from_role": role
                    }))
                # 通知监护人推流地址
                if "guardian" in rooms.get(room, {}):
                    await send_to_role(room, "guardian", make_msg("stream_status", {
                        "url": stream_url,
                        "stream_type": stream_type,
                        "status": "live"
                    }))
                # 回执给发送者
                await websocket.send_json(make_msg("stream_status", {
                    "url": stream_url,
                    "stream_type": stream_type,
                    "status": "live"
                }))

            # ====== 停止推流 ======
            elif msg_type == "stream_stop":
                stream_type = msg.get("data", {}).get("stream_type", "video")
                # 通知 device 角色停止摄像头/麦克风
                if "device" in rooms.get(room, {}):
                    await send_to_role(room, "device", make_msg("device_control_request", {
                        "device": "camera" if stream_type == "video" else "microphone",
                        "action": "off",
                        "from_role": role
                    }))
                # 通知监护人推流已停止
                if "guardian" in rooms.get(room, {}):
                    await send_to_role(room, "guardian", make_msg("stream_status", {
                        "url": "",
                        "stream_type": stream_type,
                        "status": "stopped"
                    }))
                # 回执给发送者
                await websocket.send_json(make_msg("stream_status", {
                    "url": "",
                    "stream_type": stream_type,
                    "status": "stopped"
                }))

            # ====== SOS 告警 ======
            elif msg_type == "sos_alert":
                data = dict(msg.get("data", {}))
                if not data.get("stream_url"):
                    # 优先使用房间已上报的推流地址，否则 fallback
                    data["stream_url"] = room_stream_urls.get(room, MJPEG_STREAM_URL)
                forwarded = dict(msg)
                forwarded["data"] = data
                await forward_to_other(room, role, forwarded)

            # ====== ★ 影火AI 对话 ★ ======
            elif msg_type == "chat_ai_request":
                data = msg.get("data", {})
                user_text = data.get("text", "")
                context = data.get("context", {})

                print(f"[影火AI] 收到消息: {user_text[:50]}...")

                # 调用 DeepSeek V4
                result = await call_deepseek(user_text, context)

                # 回复患者
                await websocket.send_json(make_msg("chat_ai_response", {
                    "reply": result["reply"],
                    "is_danger": result["is_danger"]
                }))

                # 如果检测到风险 → 通知监护人（动态检查配对状态）
                if result["is_danger"] and get_other_role(role) in rooms.get(room, {}):
                    await send_to_role(room, "guardian", make_msg("danger_detected", {
                        "danger_type": "ai_detected",
                        "confidence": 0.85,
                        "message": f"影火AI检测到潜在风险：{result['reply'][:100]}",
                        "patient_text": user_text[:200]
                    }))

            # ====== 未知消息类型 ======
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
                room_stream_urls.pop(room, None)  # 清理推流地址

        # 人类角色断开时通知人类对端
        if role in ("patient", "guardian"):
            counterpart = "guardian" if role == "patient" else "patient"
            if room in rooms and counterpart in rooms[room]:
                try:
                    await rooms[room][counterpart].send_json(make_msg("room_status", {
                        "room": room,
                        "status": "waiting",
                        "your_role": counterpart,
                        "peer_role": None,
                        "other_user": "对方已断开"
                    }))
                except Exception:
                    pass


# ═══════════════════════════════════════════
# 启动 & 生命周期
# ═══════════════════════════════════════════

@app.on_event("startup")
async def startup():
    global http_client
    http_client = httpx.AsyncClient()
    print("[影火AI] HTTP 客户端已初始化")
    print(f"[影火AI] DeepSeek 模型: {DEEPSEEK_MODEL}")


@app.on_event("shutdown")
async def shutdown():
    global http_client
    if http_client:
        await http_client.aclose()
        print("[影火AI] HTTP 客户端已关闭")


@app.get("/")
async def root():
    return {
        "status": "running",
        "version": "2.1",
        "features": ["websocket_relay", "room_management", "yinghuo_ai"],
        "rooms": {k: list(v.keys()) for k, v in rooms.items()},
        "room_count": len(rooms),
        "ai_model": DEEPSEEK_MODEL
    }


if __name__ == "__main__":
    import uvicorn
    print("=" * 50)
    print("AI影伴 — 后端服务器 v2.1")
    print("访问 http://localhost:8000 查看状态")
    print("WebSocket: ws://<IP>:8000/ws?room=<房间号>&role=<patient|guardian>")
    print("支持 15 种消息类型（含影火AI）")
    print(f"MJPEG 推流地址: {MJPEG_STREAM_URL}")
    print(f"影火AI 模型: {DEEPSEEK_MODEL}")
    print("=" * 50)
    uvicorn.run(app, host="0.0.0.0", port=8000)