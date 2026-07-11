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
