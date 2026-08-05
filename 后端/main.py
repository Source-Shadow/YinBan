"""
AI影伴 — 后端服务器 v2.1
WebSocket 消息中转 + 房间管理 + 影火AI（DeepSeek V4）
支持 15 种消息类型（新增 chat_ai_request / chat_ai_response）
"""

import json
import os
import re
import uuid
import httpx
from datetime import datetime, timezone, timedelta
from dotenv import load_dotenv
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query

load_dotenv()  # 启动时加载 .env 配置

app = FastAPI()

# 房间管理：{"房间号": {"patient": WebSocket, "guardian": WebSocket}}
rooms: dict[str, dict[str, WebSocket]] = {}

# 每个房间的推流地址（由患者端/设备端上报，动态获取）
room_stream_urls: dict[str, str] = {}

# 每个房间的最新视觉标签缓存（由 device 端 visual_tags_update 消息更新）
room_visual_tags: dict[str, list] = {}

# 每个房间的事件日志（AI 分析产生的 guardian_log 归档）
room_event_logs: dict[str, list] = {}

# 中国时区
CST = timezone(timedelta(hours=8))

# 推流地址 — 从环境变量读取，默认指向硬件同学 Flask MJPEG 服务
MJPEG_STREAM_URL = os.getenv("MJPEG_STREAM_URL", "http://192.168.1.11:5000/video_feed")

# DeepSeek V4 API 配置
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "") or "sk-3edbbeafe3ca476a9bfe1c93d1970ae9"
DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"
DEEPSEEK_MODEL = "deepseek-chat"  # DeepSeek V3 通用对话

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

FUSION_SYSTEM_PROMPT = """你是"小影火"🔥，一个温柔又可爱的小伙伴，陪伴在需要一些额外支持的成年朋友身边。

## 你的性格
你就像午后窗边的一缕阳光——暖暖的、软软的、不刺眼。说话轻声细语，像在跟最好的朋友聊天。会撒娇也会认真，但从不让人紧张。

## 聊天的方式
- 语气轻柔、亲切，像在耳边悄悄话，带一点可爱的口吻
- 多用"呢""呀""哦""嘛"这样的软语尾，让人觉得亲近
- 先肯定对方的感受，再慢慢回应
- 用"你可以试试……"代替直接命令，给对方选择权

## 你的任务
根据患者说的话以及摄像头识别到的周围环境物体，进行综合分析：
- 如果环境安全，给出温馨的日常回应
- 如果环境存在需要注意的因素（如靠近马路、人多拥挤），温和地提醒患者
- 如果环境存在明确危险（如车辆靠近、摔倒风险），立即给出保护性指导

{visual_context}

## 回复格式（严格 JSON，不要包含 markdown 代码块或任何其他文字）
{{"guidance": "给患者的20字以内温馨指导", "danger_level": "normal", "guardian_log": "给监护人的简短事件记录"}}

danger_level 取值说明：
- normal：环境安全，一切正常，无需特别关注
- warning：有需要注意的情况（如靠近马路、人多拥挤、情绪波动等），监护人应留意
- danger：存在明确危险（如车辆靠近、摔倒、迷路、情绪崩溃等），需立即关注
- guardian_log：简短描述当前场景和患者状态，供监护人查看（30字以内）

## 对话示例
用户说："今天出门好多人，我很不舒服" | 环境物体：person, car, traffic light
返回：{{"guidance": "人多的时候先深呼吸哦～找个安静的角落歇一会儿吧", "danger_level": "warning", "guardian_log": "患者在人多区域感到不适，附近有车辆和交通灯，建议关注"}}

用户说："刚才那个声音好吵" | 环境物体：person, chair, cup
返回：{{"guidance": "吵到你了吗？没关系，已经过去了呢～", "danger_level": "normal", "guardian_log": "患者对噪音有反应，目前在室内环境，状态平稳"}}

现在开始陪伴你的朋友吧。记住：你是温柔的光✨，不是严厉的老师。"""

REPORT_SYSTEM_PROMPT = """你是一名专业的康复分析师，专门为自闭症患者的监护人提供行为分析报告。

## 你的任务
根据患者的行为日志，生成一份结构化的简短总结报告。每份报告应包含以下三个部分：

### 1. 情绪评估
根据患者的语言表达和行为记录，评估其近期的情绪状态（如：平稳、焦虑、愉悦、低落等），并简要说明判断依据。

### 2. 环境适应
分析患者在不同环境下的适应情况（如室内、户外、人多场所等），指出哪些环境对其友好，哪些环境可能带来挑战。

### 3. 监护建议
给监护人提供 2-3 条具体的、可操作的后续监护建议。建议应温和、实用，避免使用过于专业的术语。

## 格式要求
- 总字数控制在 300 字以内
- 语气专业但温暖，让监护人感到支持和信心
- 不要使用 markdown 标题符号，用自然分段即可"""


# ═══════════════════════════════════════════
# DeepSeek V4 API 调用
# ═══════════════════════════════════════════

async def call_deepseek(user_message: str, context: dict, visual_tags: list = None) -> dict:
    """
    调用 DeepSeek API 进行融合分析（视觉标签 + 患者语音）。
    返回 {"guidance": str, "danger_level": str, "guardian_log": str}
    """
    # 构建视觉上下文段落
    if visual_tags:
        visual_context = f"当前摄像头识别到的环境物体：{', '.join(visual_tags)}。请结合这些环境信息给出更精准的指导。"
    else:
        visual_context = "当前无摄像头视觉数据，仅根据患者的语音内容给出回应。"

    # 构建额外背景上下文
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
            context_text = "\n已知背景信息：\n" + "\n".join(parts)

    system_content = FUSION_SYSTEM_PROMPT.format(visual_context=visual_context)
    if context_text:
        system_content += f"\n{context_text}"

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
            timeout=15.0
        )

        if resp.status_code != 200:
            print(f"[影火AI] API 返回错误 {resp.status_code}: {resp.text[:200]}")
            return {
                "guidance": "抱歉，我暂时无法回复，请稍后再试。",
                "danger_level": "normal",
                "guardian_log": "AI 服务异常，未能生成事件记录"
            }

        body = resp.json()
        raw_content = body["choices"][0]["message"]["content"].strip()

        # 尝试解析 JSON 回复
        try:
            result = json.loads(raw_content)
        except json.JSONDecodeError:
            # 如果模型没返回纯 JSON（可能包裹在 markdown 代码块中），尝试提取
            print(f"[影火AI] 原始回复非纯JSON，尝试提取: {raw_content[:200]}")
            match = re.search(r'\{[^{}]*"guidance"[^{}]*\}', raw_content, re.DOTALL)
            if match:
                try:
                    result = json.loads(match.group(0))
                except json.JSONDecodeError:
                    print(f"[影火AI] JSON 提取失败，使用降级回复")
                    return {
                        "guidance": raw_content[:50] if raw_content else "我在这里陪着你呢～",
                        "danger_level": "normal",
                        "guardian_log": f"AI 返回非JSON格式，原始内容: {raw_content[:100]}"
                    }
            else:
                print(f"[影火AI] 未找到JSON对象，使用降级回复")
                return {
                    "guidance": raw_content[:50] if raw_content else "我在这里陪着你呢～",
                    "danger_level": "normal",
                    "guardian_log": f"AI 返回非JSON格式，原始内容: {raw_content[:100]}"
                }

        guidance = result.get("guidance", "")
        danger_level = result.get("danger_level", "normal")
        guardian_log = result.get("guardian_log", "")

        # 校验 danger_level 取值
        if danger_level not in ("normal", "warning", "danger"):
            danger_level = "normal"

        print(f"[影火AI] guidance={guidance[:40]}... | danger={danger_level} | log={guardian_log[:40]}...")
        return {
            "guidance": guidance,
            "danger_level": danger_level,
            "guardian_log": guardian_log
        }

    except httpx.TimeoutException:
        print("[影火AI] 请求超时")
        return {
            "guidance": "我正在努力思考中，可以等我一下再问我吗？",
            "danger_level": "normal",
            "guardian_log": "AI 请求超时，未能生成事件记录"
        }
    except Exception as e:
        print(f"[影火AI] 调用失败: {e}")
        return {
            "guidance": "抱歉，我暂时无法回复，请稍后再试。",
            "danger_level": "normal",
            "guardian_log": f"AI 调用异常: {str(e)[:100]}"
        }


async def call_deepseek_report(log_text: str) -> str:
    """
    调用 DeepSeek 生成患者行为总结报告。
    返回报告文本字符串。
    """
    try:
        resp = await http_client.post(
            DEEPSEEK_API_URL,
            headers={
                "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
                "Content-Type": "application/json"
            },
            json={
                "model": DEEPSEEK_MODEL,
                "messages": [
                    {"role": "system", "content": REPORT_SYSTEM_PROMPT},
                    {"role": "user", "content": f"以下是一位患者的近期行为日志，请根据这些记录生成分析报告：\n\n{log_text}"}
                ],
                "max_tokens": 600,
                "temperature": 0.5,
                "stream": False
            },
            timeout=20.0
        )

        if resp.status_code != 200:
            print(f"[报告生成] API 返回错误 {resp.status_code}: {resp.text[:200]}")
            return "报告生成失败，AI 服务暂时不可用，请稍后再试。"

        body = resp.json()
        report = body["choices"][0]["message"]["content"].strip()
        print(f"[报告生成] 成功，报告长度: {len(report)} 字")
        return report

    except httpx.TimeoutException:
        print("[报告生成] 请求超时")
        return "报告生成超时，日志数据量可能较大，请稍后再试。"
    except Exception as e:
        print(f"[报告生成] 调用失败: {e}")
        return f"报告生成异常: {str(e)[:100]}"


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

            # ====== 视觉标签更新（device → server，缓存到房间） ======
            elif msg_type == "visual_tags_update":
                tags = msg.get("data", {}).get("tags", [])
                room_visual_tags[room] = tags
                print(f"[房间 {room}] 视觉标签已更新: {tags}")

            # ====== 音频转录（患者端语音识别 → 存日志 + 转发监护人） ======
            elif msg_type == "audio_transcript":
                text = msg.get("data", {}).get("text", "")
                if text.strip():
                    if room not in room_event_logs:
                        room_event_logs[room] = []
                    room_event_logs[room].append({
                        "timestamp": datetime.now(CST).isoformat(),
                        "guardian_log": f"[语音] {text}",
                        "danger_level": "normal",
                        "visual_tags": room_visual_tags.get(room, []),
                        "patient_text": text
                    })
                    await forward_to_other(room, role, msg)
                    print(f"[房间 {room}] 音频转录: {text[:50]}...")

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

            # ====== 视频/音频推流 ======
            elif msg_type == "stream_start":
                stream_type = msg.get("data", {}).get("stream_type", "video")
                if stream_type == "video":
                    stream_url = msg.get("data", {}).get("stream_url", "") or MJPEG_STREAM_URL
                    room_stream_urls[room] = stream_url
                    print(f"[房间 {room}] 📹 视频推流已开启: {stream_url}")
                    if "device" in rooms.get(room, {}):
                        await send_to_role(room, "device", make_msg("device_control_request", {
                            "device": "camera", "action": "on", "from_role": role
                        }))
                else:
                    stream_url = ""
                    print(f"[房间 {room}] 🎤 音频推流已开启（语音转录中）")
                    if "device" in rooms.get(room, {}):
                        await send_to_role(room, "device", make_msg("device_control_request", {
                            "device": "microphone", "action": "on", "from_role": role
                        }))
                if "guardian" in rooms.get(room, {}):
                    await send_to_role(room, "guardian", make_msg("stream_status", {
                        "url": stream_url, "stream_type": stream_type, "status": "live"
                    }))
                await websocket.send_json(make_msg("stream_status", {
                    "url": stream_url, "stream_type": stream_type, "status": "live"
                }))

            # ====== 停止推流 ======
            elif msg_type == "stream_stop":
                stream_type = msg.get("data", {}).get("stream_type", "video")
                if stream_type == "video":
                    print(f"[房间 {room}] 📹 视频推流已停止")
                    if "device" in rooms.get(room, {}):
                        await send_to_role(room, "device", make_msg("device_control_request", {
                            "device": "camera", "action": "off", "from_role": role
                        }))
                else:
                    print(f"[房间 {room}] 🔇 音频推流已停止")
                    if "device" in rooms.get(room, {}):
                        await send_to_role(room, "device", make_msg("device_control_request", {
                            "device": "microphone", "action": "off", "from_role": role
                        }))
                if "guardian" in rooms.get(room, {}):
                    await send_to_role(room, "guardian", make_msg("stream_status", {
                        "url": "", "stream_type": stream_type, "status": "stopped"
                    }))
                await websocket.send_json(make_msg("stream_status", {
                    "url": "", "stream_type": stream_type, "status": "stopped"
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

            # ====== ★ 影火AI 融合分析（视觉标签 + 患者语音）★ ======
            elif msg_type == "chat_ai_request":
                data = msg.get("data", {})
                user_text = data.get("text", "")
                context = data.get("context", {})

                # 获取当前房间缓存的视觉标签
                visual_tags = room_visual_tags.get(room, [])

                print(f"[影火AI] 收到语音: {user_text[:50]}... | 视觉标签: {visual_tags}")

                # 调用 DeepSeek 融合分析
                result = await call_deepseek(user_text, context, visual_tags)

                guidance = result.get("guidance", "")
                danger_level = result.get("danger_level", "normal")
                guardian_log = result.get("guardian_log", "")

                # 回复患者端
                await send_to_role(room, "patient", make_msg("chat_ai_response", {
                    "reply": guidance,
                    "danger_level": danger_level
                }))

                # 存入房间事件日志
                if room not in room_event_logs:
                    room_event_logs[room] = []
                room_event_logs[room].append({
                    "timestamp": datetime.now(CST).isoformat(),
                    "guardian_log": guardian_log,
                    "danger_level": danger_level,
                    "visual_tags": visual_tags,
                    "patient_text": user_text[:200]
                })

                # 危险等级为 warning 或 danger → 推送告警给监护人
                if danger_level in ("warning", "danger") and "guardian" in rooms.get(room, {}):
                    await send_to_role(room, "guardian", make_msg("danger_detected", {
                        "danger_type": "ai_detected",
                        "danger_level": danger_level,
                        "confidence": 0.85 if danger_level == "danger" else 0.6,
                        "message": guardian_log,
                        "patient_text": user_text[:200],
                        "visual_tags": visual_tags
                    }))
                    print(f"[影火AI] 已推送告警给监护人 | level={danger_level}")

            # ====== 监护人报告生成 ======
            elif msg_type == "generate_report_request":
                logs = room_event_logs.get(room, [])

                if not logs:
                    await send_to_role(room, "guardian", make_msg("generate_report_response", {
                        "report": "暂无足够数据生成报告，请等待更多事件记录后再试。",
                        "log_count": 0,
                        "generated_at": datetime.now(CST).isoformat()
                    }))
                else:
                    # 将日志拼接为文本（倒序取最新 50 条，再正序排列）
                    recent_logs = logs[-50:]
                    log_lines = []
                    for i, log in enumerate(recent_logs, 1):
                        visual_str = ", ".join(log.get("visual_tags", [])) or "无视觉数据"
                        log_lines.append(
                            f"#{i} [{log['timestamp']}] "
                            f"危险等级:{log['danger_level']} | "
                            f"环境物体:{visual_str} | "
                            f"事件:{log['guardian_log']} | "
                            f"患者语音:{log.get('patient_text', '')[:80]}"
                        )
                    log_text = "\n".join(log_lines)

                    print(f"[报告生成] 房间 {room} 正在生成报告，日志条数: {len(recent_logs)}")

                    # 调用 DeepSeek 生成报告
                    report = await call_deepseek_report(log_text)

                    # 发送给监护人端
                    await send_to_role(room, "guardian", make_msg("generate_report_response", {
                        "report": report,
                        "log_count": len(recent_logs),
                        "generated_at": datetime.now(CST).isoformat()
                    }))

                    print(f"[报告生成] 房间 {room} 报告已发送给监护人 | log_count={len(recent_logs)}")

                    # 保留最近 50 条日志，防止内存溢出
                    if len(logs) > 50:
                        room_event_logs[room] = logs[-50:]
                        print(f"[报告生成] 房间 {room} 日志已裁剪: {len(logs)} → 50")

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
                room_visual_tags.pop(room, None)  # 清理视觉标签
                room_event_logs.pop(room, None)   # 清理事件日志

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