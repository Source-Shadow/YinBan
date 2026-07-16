# AI 影伴 — 后端服务器

WebSocket 消息中转 + 房间管理，FastAPI 实现。

## 快速开始

```bash
# 1. 安装依赖
pip install fastapi uvicorn

# 2. 启动服务器
python main.py
```

启动后访问 http://localhost:8000 查看运行状态。

## 配置

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `MJPEG_STREAM_URL` | `http://localhost:5000/video_feed` | 硬件同学 Flask MJPEG 推流地址 |

切换硬件服务器 IP：

```powershell
# Windows PowerShell（临时生效）
$env:MJPEG_STREAM_URL = "http://192.168.1.100:5000/video_feed"
python main.py
```

或直接修改 `main.py` 第 22 行的默认值。

## WebSocket 连接

```
ws://<服务器IP>:8000/ws?room=<房间号>&role=<patient|guardian>
```

- `room` — 房间号，患者和监护人填同一个即配对
- `role` — 只能是 `patient` 或 `guardian`

例：`ws://192.168.137.1:8000/ws?room=TEST001&role=patient`

## 消息类型

所有消息统一外壳：`{"msg_id": "UUID", "type": "类型", "data": {...}, "timestamp": "ISO8601"}`

| type | 方向 | 说明 |
|------|------|------|
| `ping` / `pong` | 双向 | 心跳保活（客户端每 20s 发一次） |
| `room_status` | 服务器→端 | 房间配对状态（waiting / paired / disconnected） |
| `location_update` | 患者→监护人 | 位置同步 |
| `device_status` | 患者→监护人 | 设备状态（摄像头/耳机） |
| `manual_message` | 双向 | 文字消息 |
| `stream_start` | 患者→服务器 | 推流请求 → 服务器生成 `stream_url` 发给监护人 |
| `stream_url` | 服务器→监护人 | MJPEG 流地址下发 |
| `call_request` | 双向 | 音/视频通话请求 |
| `call_response` | 双向 | 接听/拒绝 |
| `ai_voice_command` | 服务器→患者 | AI 语音指令（TTS 播报） |
| `danger_detected` | 服务器→监护人 | 危险检测通知 |
| `device_control_request` | 监护人→患者 | 远程设备控制请求 |
| `sos_alert` | 患者→监护人 | 紧急求助（服务器自动补 `stream_url`） |

详细字段定义见 `接口对接单.md`。

## 摄像头视觉服务（main_visual_service.py）

独立的 AI 视觉服务，运行在带摄像头的硬件设备上（如 PC/边缘设备）：

```bash
# 安装完整依赖
pip install -r requirements.txt

# 启动视觉服务（默认连接本地服务器）
python main_visual_service.py
```

**环境变量**：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `DEEPSEEK_API_KEY` | 内置测试 Key | DeepSeek API 密钥 |
| `SERVER_HOST` | `127.0.0.1` | WebSocket 服务器 IP |
| `SERVER_PORT` | `8000` | WebSocket 服务器端口 |
| `ROOM_ID` | `1234` | 房间号 |

**功能**：
- 📸 摄像头采集 + YOLOv8 实时目标检测
- 🧠 DeepSeek AI 场景分析（每4秒）
- 📺 MJPEG 视频流（`http://<IP>:5000/video_feed`），监护人端 WebView 加载
- 🎤 麦克风音频采集与推流
- 🔗 WebSocket 连接服务器（role=device），接收摄像头启停指令

**架构角色**：`main_visual_service.py` 以 `role=device` 连接服务器。
房间内支持三种角色：`patient`、`guardian`、`device`。device 不参与配对逻辑，专门处理摄像头/麦克风控制。

## 房间管理

- 房间在内存中管理，重启服务器后清空
- 第一个连入的人状态为 `waiting`，第二个连入后双方变为 `paired`
- 一方断开 → 另一方回到 `waiting`
- 房间内无人 → 房间自动删除

## 测试

```bash
# 用 Python 脚本模拟客户端
python test/test_client.py
```

或使用 websocat：

```bash
websocat "ws://localhost:8000/ws?room=TEST&role=patient"
```
