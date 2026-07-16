"""
AI影伴 — 摄像头视觉服务 v2.1 (纯视觉版)
YOLO 目标检测 + DeepSeek 场景分析 + MJPEG 推流
通过 WebSocket 连接到 main.py 服务器（role=device），接收摄像头控制指令
"""

import cv2
import time
import json
import os
import threading
import asyncio
import websockets
import numpy as np
import httpx
import uuid
from datetime import datetime, timezone
from flask import Flask, Response
from ultralytics import YOLO
from PIL import Image, ImageDraw, ImageFont

# ═══════════════════════════════════════════
# 1. 全局配置
# ═══════════════════════════════════════════

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
ROOM_ID = os.getenv("ROOM_ID", "1234")
SERVER_HOST = os.getenv("SERVER_HOST", "127.0.0.1")
SERVER_PORT = os.getenv("SERVER_PORT", "8000")
WEBSOCKET_SERVER_URL = f"ws://{SERVER_HOST}:{SERVER_PORT}/ws?room={ROOM_ID}&role=device"

# 摄像头数据源：可以填 "0"（电脑本地摄像头）或 网络流地址
CAMERA_SOURCE = os.getenv("CAMERA_SOURCE", "http://10.240.11.126:81/stream")

# DeepSeek API 配置
DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"
DEEPSEEK_MODEL = "deepseek-chat"

# HTTP 客户端（YOLO 线程同步调用）
http_client = httpx.Client(timeout=httpx.Timeout(10.0))

# YOLO 模型
yolo_model = YOLO("yolov8n.pt")

# Flask 应用
app = Flask(__name__)

# 输出帧（线程安全）
output_frame = None
lock = threading.Lock()

# 摄像头控制标志
camera_active = threading.Event()
camera_active.set()  # 默认开启

# AI 最新指导文本
latest_ai_guidance = "正在初始化AI..."
guidance_lock = threading.Lock()

# ═══════════════════════════════════════════
# 2. WebSocket 通信基础设施
# ═══════════════════════════════════════════

_ws_loop: asyncio.AbstractEventLoop | None = None
_ws_connection = None  # 持久 WebSocket 连接


def _start_ws_loop():
    """启动专用事件循环线程，用于 WebSocket 通信"""
    global _ws_loop
    _ws_loop = asyncio.new_event_loop()
    asyncio.set_event_loop(_ws_loop)
    _ws_loop.run_forever()


def _run_async(coro):
    """线程安全地在 WS 事件循环上调度协程"""
    if _ws_loop is None or _ws_loop.is_closed():
        return
    asyncio.run_coroutine_threadsafe(coro, _ws_loop)


# ═══════════════════════════════════════════
# 3. 中文字幕绘制
# ═══════════════════════════════════════════

def put_chinese_text(img, text, position, font_size=22, color=(0, 255, 230)):
    """利用 Pillow 在 OpenCV 图像上绘制中文文本"""
    img_pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img_pil)
    try:
        font = ImageFont.truetype("simhei.ttf", font_size, encoding="utf-8")
    except OSError:
        font = ImageFont.load_default()
    draw.text(position, text, font=font, fill=color)
    return cv2.cvtColor(np.array(img_pil), cv2.COLOR_RGB2BGR)


# ═══════════════════════════════════════════
# 4. 消息封装
# ═══════════════════════════════════════════

def build_ai_payload(content, is_danger=False):
    """按照统一协议封装 AI 消息"""
    msg_type = "danger_detected" if is_danger else "ai_voice_command"
    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    if is_danger:
        data_payload = {
            "danger_type": "environment_hazard",
            "confidence": 0.9,
            "message": content
        }
    else:
        data_payload = {
            "voice_text": content,
            "action_code": "play_warning_sound",
            "priority": 1
        }

    return {
        "msg_id": str(uuid.uuid4()),
        "type": msg_type,
        "data": data_payload,
        "timestamp": now_iso
    }


def build_camera_status(action: str) -> dict:
    """封装摄像头状态消息"""
    return {
        "msg_id": str(uuid.uuid4()),
        "type": "camera_status",
        "data": {"device": "camera", "action": action, "status": "live" if action == "on" else "stopped"},
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    }


# ═══════════════════════════════════════════
# 5. DeepSeek AI 场景分析
# ═══════════════════════════════════════════

def call_deepseek_ai(detected_objects_str: str) -> str:
    """调用 DeepSeek API，将物体标签转化为温馨指导语"""
    prompt = f"你是一个自闭症随身助手。视角捕捉到: 【{detected_objects_str}】。用15字以内给出极简、温馨的下一步动作指导。"
    try:
        resp = http_client.post(
            DEEPSEEK_API_URL,
            headers={
                "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
                "Content-Type": "application/json"
            },
            json={
                "model": DEEPSEEK_MODEL,
                "messages": [
                    {"role": "system", "content": "你是一个温暖专业的自闭症辅助AI。"},
                    {"role": "user", "content": prompt}
                ],
                "temperature": 0.7,
                "max_tokens": 50
            }
        )
        if resp.status_code == 200:
            body = resp.json()
            return body["choices"][0]["message"]["content"].strip()
        else:
            print(f"[DeepSeek] API 错误 {resp.status_code}: {resp.text[:100]}")
    except Exception as e:
        print(f"[DeepSeek] 调用失败: {e}")
    return "保持当前状态,环境安全。"


# ═══════════════════════════════════════════
# 6. WebSocket 消息处理
# ═══════════════════════════════════════════

async def send_ai_message(guidance_text: str, is_danger: bool = False):
    """发送 AI 分析结果给主服务器"""
    global _ws_connection
    if _ws_connection is None:
        return
    try:
        payload = build_ai_payload(guidance_text, is_danger)
        await _ws_connection.send(json.dumps(payload, ensure_ascii=False))
        print(f"[AI 消息发送成功] -> {guidance_text}")
    except Exception as e:
        print(f"[AI 消息发送失败]: {e}")


async def send_camera_status(action: str):
    """发送摄像头状态变更通知"""
    global _ws_connection
    if _ws_connection is None:
        return
    try:
        payload = build_camera_status(action)
        await _ws_connection.send(json.dumps(payload, ensure_ascii=False))
        print(f"[状态上报] 摄像头: {action}")
    except Exception as e:
        print(f"[状态上报失败]: {e}")


async def handle_incoming_messages(ws):
    """处理服务器下发的控制消息"""
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue

            msg_type = msg.get("type", "")
            if msg_type == "device_control_request":
                data = msg.get("data", {})
                device = data.get("device", "")
                action = data.get("action", "")
                from_role = data.get("from_role", "unknown")

                # 只保留摄像头控制指令
                if device == "camera":
                    if action == "on":
                        start_camera()
                        _run_async(send_camera_status("on"))
                    elif action == "off":
                        stop_camera()
                        _run_async(send_camera_status("off"))
                    print(f"[控制] {from_role} 请求摄像头: {action}")

    except websockets.exceptions.ConnectionClosed:
        print("[WS] 连接已关闭")
    except Exception as e:
        print(f"[消息处理] 异常: {e}")


async def ws_main_loop():
    """持久 WebSocket 连接主循环（自动重连）"""
    global _ws_connection
    while True:
        try:
            async with websockets.connect(WEBSOCKET_SERVER_URL) as ws:
                _ws_connection = ws
                print(f"[WS] 已连接到服务器 room={ROOM_ID}, role=device")
                await handle_incoming_messages(ws)
        except Exception as e:
            print(f"[WS] 连接异常: {e}，3 秒后重连...")
        finally:
            _ws_connection = None
            await asyncio.sleep(3)


# ═══════════════════════════════════════════
# 7. 摄像头开关控制
# ═══════════════════════════════════════════

def start_camera():
    if camera_active.is_set():
        return
    camera_active.set()
    print("[摄像头] 已启动")


def stop_camera():
    if not camera_active.is_set():
        return
    camera_active.clear()
    global output_frame
    with lock:
        output_frame = None
    print("[摄像头] 已停止")


# ═══════════════════════════════════════════
# 8. 主线程: 摄像头捕获 + YOLO 推理
# ═══════════════════════════════════════════

def video_processing_loop():
    global output_frame, latest_ai_guidance

    try:
        source = int(CAMERA_SOURCE)
    except ValueError:
        source = CAMERA_SOURCE

    print(f"[视频] 正在尝试连接摄像头数据源: {source} ...")
    cap = cv2.VideoCapture(source)

    if not cap.isOpened():
        print(f"[硬件错误] 无法连接到摄像头: {source}")
        camera_active.clear()
        return

    print(f"[视频] 摄像头({source})建立连接成功！")

    latest_ai_guidance = "正在初始化AI..."
    last_api_time = 0.0
    api_interval = 4.0  # 每 4 秒更新一次 AI 分析

    while True:
        camera_active.wait()  # 暂停控制

        ret, frame = cap.read()
        if not ret:
            time.sleep(0.1)
            continue

        # YOLO 目标识别
        results = yolo_model(frame, verbose=False)
        annotated_frame = results[0].plot()
        labels = [yolo_model.names[int(box.cls[0])] for box in results[0].boxes]

        # 定时触发 DeepSeek AI 分析
        current_time = time.time()
        if current_time - last_api_time > api_interval:
            detected_str = ", ".join(set(labels)) if labels else "环境空旷安全"
            latest_ai_guidance = call_deepseek_ai(detected_str)

            is_danger = any(word in detected_str for word in ["knife", "fire", "car"])
            _run_async(send_ai_message(latest_ai_guidance, is_danger))
            last_api_time = current_time

        # 绘制 AI 字幕底部黑条与文字
        h, w = annotated_frame.shape[:2]
        cv2.rectangle(annotated_frame, (0, h - 60), (w, h), (20, 20, 20), -1)

        with guidance_lock:
            guidance_to_show = latest_ai_guidance

        annotated_frame = put_chinese_text(
            annotated_frame,
            f" AI 指导:{guidance_to_show}",
            position=(15, h - 45),
            font_size=20
        )

        # 写入全局推流变量
        with lock:
            output_frame = annotated_frame.copy()

        time.sleep(0.03)


def video_processing_loop():
    global output_frame, latest_ai_guidance

    try:
        source = int(CAMERA_SOURCE)
    except ValueError:
        source = CAMERA_SOURCE

    print(f"[视频] 正在尝试连接摄像头数据源: {source} ...")
    cap = cv2.VideoCapture(source)

    # 🌟 优化1：设置摄像头内部缓冲区大小为 1，防止画面积压延迟
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    if not cap.isOpened():
        print(f"[硬件错误] 无法连接到摄像头: {source}")
        camera_active.clear()
        return

    print(f"[视频] 摄像头({source})建立连接成功！")

    latest_ai_guidance = "正在初始化AI..."
    last_api_time = 0.0
    api_interval = 4.0  # DeepSeek 调用间隔

    # 🌟 性能优化变量
    frame_count = 0
    SKIP_FRAMES = 3  # 每 3 帧才跑一次 YOLO（数值越大越流畅，1 表示不跳帧）
    last_annotated_frame = None  # 保存上一次绘制好的帧

    while True:
        camera_active.wait()  # 暂停控制

        ret, frame = cap.read()
        if not ret:
            time.sleep(0.01)
            continue

        frame_count += 1

        # 🌟 优化2：如果图像分辨率过大（如 1080P），强制缩小到 640x480 提速
        h_raw, w_raw = frame.shape[:2]
        if w_raw > 640:
            frame = cv2.resize(frame, (640, int(h_raw * 640 / w_raw)))

        # 🌟 优化3：隔帧检测（只在指定帧跑 YOLO，其他帧直接使用缓存画面）
        if frame_count % SKIP_FRAMES == 0 or last_annotated_frame is None:
            # 执行 YOLO 推理 (imgsz=320 或 480 可进一步提速)
            results = yolo_model(frame, imgsz=320, verbose=False)
            annotated_frame = results[0].plot()
            labels = [yolo_model.names[int(box.cls[0])] for box in results[0].boxes]

            # 定时触发 DeepSeek AI 分析
            current_time = time.time()
            if current_time - last_api_time > api_interval:
                detected_str = ", ".join(set(labels)) if labels else "环境空旷安全"
                latest_ai_guidance = call_deepseek_ai(detected_str)

                is_danger = any(word in detected_str for word in ["knife", "fire", "car"])
                _run_async(send_ai_message(latest_ai_guidance, is_danger))
                last_api_time = current_time

            # 绘制 AI 字幕底部黑条与文字
            h, w = annotated_frame.shape[:2]
            cv2.rectangle(annotated_frame, (0, h - 50), (w, h), (20, 20, 20), -1)

            with guidance_lock:
                guidance_to_show = latest_ai_guidance

            annotated_frame = put_chinese_text(
                annotated_frame,
                f" AI 指导:{guidance_to_show}",
                position=(10, h - 40),
                font_size=18
            )
            last_annotated_frame = annotated_frame
        else:
            # 非 YOLO 帧，直接复用上一次画好框的图像（大大减轻 CPU 负担）
            annotated_frame = last_annotated_frame

        # 写入全局推流变量
        with lock:
            output_frame = annotated_frame

        time.sleep(0.01)  # 微小休眠避免 CPU 100% 满载
# ═══════════════════════════════════════════
# 9. Flask MJPEG 视频流服务
# ═══════════════════════════════════════════

def generate_mjpeg_stream():
    global output_frame
    while True:
        if camera_active.is_set():
            with lock:
                if output_frame is not None:
                    # 将 JPEG 质量设置为 55（默认是 95），图像体积会缩小 70%，推流速度瞬间暴增！
                    encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), 55]
                    _, encoded_img = cv2.imencode(".jpg", output_frame, encode_param)
                    yield (b'--frame\r\n'
                           b'Content-Type: image/jpeg\r\n\r\n' + encoded_img.tobytes() + b'\r\n')
                    time.sleep(0.03)
                    continue

        # 摄像头关闭时返回的占位图
        blank = np.zeros((480, 640, 3), dtype=np.uint8)
        blank = put_chinese_text(blank, "摄像头已关闭", position=(180, 220), font_size=28, color=(180, 180, 180))
        blank = put_chinese_text(blank, "请在 App 中开启摄像头", position=(140, 270), font_size=18, color=(120, 120, 120))
        _, encoded = cv2.imencode(".jpg", blank)
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + encoded.tobytes() + b'\r\n')
        time.sleep(0.5)


@app.route('/video_feed')
def video_feed():
    return Response(generate_mjpeg_stream(), mimetype='multipart/x-mixed-replace; boundary=frame')


@app.route('/')
def status():
    return {
        "service": "visual_service",
        "version": "2.1",
        "camera_active": camera_active.is_set(),
        "room": ROOM_ID,
        "server": f"{SERVER_HOST}:{SERVER_PORT}"
    }


# ═══════════════════════════════════════════
# 10. 启动入口
# ═══════════════════════════════════════════

if __name__ == "__main__":
    print("=" * 55)
    print("AI影伴 — 摄像头视觉服务 v2.1 (纯视觉版)")
    print(f"房间号: {ROOM_ID}")
    print(f"视频流地址: http://0.0.0.0:5000/video_feed")
    print("=" * 55)

    # 1. 启动 WebSocket 线程
    ws_thread = threading.Thread(target=_start_ws_loop, daemon=True, name="ws-loop")
    ws_thread.start()
    time.sleep(0.5)

    # 2. 建立 WebSocket 连接
    _run_async(ws_main_loop())

    # 3. 启动视频处理线程
    visual_thread = threading.Thread(target=video_processing_loop, daemon=True, name="video")
    visual_thread.start()

    # 4. 启动 Flask Web 推流
    app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)