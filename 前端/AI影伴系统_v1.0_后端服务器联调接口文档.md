# AI 影伴系统 (YinBan) v1.0 — 后端/服务器联调接口文档

> 本文档供后端开发和服务器运维同学使用，说明前端 App 的所有接口需求和交互协议。

---

## 一、WebSocket 连接

### 连接地址

```
ws://<服务器IP>:8000/ws?room=<房间号>&role=<patient|guardian>
```

| 参数 | 说明 | 示例 |
|------|------|------|
| `room` | 统一匹配码，双端相同即配对 | `ABC123` |
| `role` | `patient` 或 `guardian` | `patient` |

### 消息外壳（所有消息共用）

```json
{
  "msg_id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "消息类型",
  "data": { /* 见下文 */ },
  "timestamp": "2026-07-11T10:30:00.000Z"
}
```

- `msg_id`: UUID v4，App 自动生成
- `timestamp`: ISO 8601 UTC

---

## 二、全部 13 种消息类型

### 心跳类

#### ping（App → 服务器）
每 20 秒自动发送一次。
```json
{"type":"ping","data":{"heartbeat":"ping"}}
```

#### pong（服务器 → App）
收到 ping 后回复。
```json
{"type":"pong","data":{}}
```

---

### 房间类

#### room_status（服务器 → 双方）
同一 room 下 patient 和 guardian 都连入后下发。
```json
{
  "type": "room_status",
  "data": { "room": "ABC123", "status": "paired" }
}
```
`status` 取值: `waiting` | `paired` | `disconnected`

---

### 推流类

#### stream_start（患者 → 服务器）
患者打开视频或音频开关时发送。
```json
{"type":"stream_start","data":{"stream_type":"video"}}
// 或
{"type":"stream_start","data":{"stream_type":"audio"}}
```

#### stream_url（服务器 → 监护人）
服务器分配推流地址后发给监护人。
```json
{
  "type": "stream_url",
  "data": { "url": "rtmp://cdn.example.com/live/room_abc", "stream_type": "video" }
}
```

---

### 数据转发类（服务器收到后 ⚡ 转发给同 room 对端）

#### location_update（患者 → 服务器 ⚡→ 监护人）
```json
{
  "type": "location_update",
  "data": {
    "lat": 31.230416,
    "lng": 121.473701,
    "accuracy": 5.0,
    "is_privacy_mode": false,
    "is_sos": false
  }
}
```
- `is_privacy_mode=true`: 经纬度仅 2 位小数（≈1.1km 街区级）
- `is_sos=true`: 全精度，监护人端特别标注为紧急定位

#### device_status（患者 → 服务器 ⚡→ 监护人）
```json
{
  "type": "device_status",
  "data": {
    "camera": true, "headphone": true, "microphone": false,
    "battery": 85, "network_type": "wifi"
  }
}
```

#### manual_message（双向 ⚡→ 对端）
文字或语音消息。
```json
{
  "type": "manual_message",
  "data": { "content": "我很好", "from_role": "patient" }
}
```

---

### AI 类

#### ai_voice_command（服务器 → 患者）
AI 分析后下发给患者的语音指令。
```json
{
  "type": "ai_voice_command",
  "data": {
    "voice_text": "请注意前方障碍物",
    "action_code": "play_warning_sound",
    "priority": 1
  }
}
```
| action_code | App 行为 |
|-------------|---------|
| `play_warning_sound` | 播放警告音 |
| `increase_volume` | 调大系统音量 |
| `vibrate` | 振动提醒 |
| `navigate_home` | 导航回家 |

#### danger_detected（服务器 → 患者）
AI 检测到患者处于危险状态时发送。
```json
{
  "type": "danger_detected",
  "data": {
    "danger_type": "fall",
    "confidence": 0.85,
    "message": "检测到疑似摔倒"
  }
}
```
- `danger_type`: `fall` | `unresponsive` | `abnormal_audio`
- `confidence > 0.7` 时患者端自动触发 SOS

---

### 🆘 紧急求助类

#### sos_alert（患者 → 服务器 ⚡→ 监护人）
患者按 SOS 或 AI 自动触发时发送。
```json
{
  "type": "sos_alert",
  "data": {
    "lat": 31.230416,
    "lng": 121.473701,
    "message": "患者手动触发紧急求助！",
    "stream_url": "rtmp://cdn.example.com/live/room_abc",
    "is_auto_detected": false
  }
}
```

**⚠️ 服务器关键逻辑**：收到 sos_alert 后:
1. 患者同时发送了 `stream_start` → 服务器分配推流地址
2. 将推流地址填入 `stream_url` 字段
3. 转发给监护人端
4. 监护人端自动：弹出红色 SOS 面板 + 显示精确位置 + 显示实时画面

---

### 📞 通话信令类

#### call_request（双向 ⚡→ 对端）
```json
{
  "type": "call_request",
  "data": { "call_type": "video", "from_role": "patient" }
}
```

#### call_response（双向 ⚡→ 对端）
```json
{
  "type": "call_response",
  "data": { "accepted": true, "from_role": "guardian" }
}
```

信令流程: A 发 call_request → 服务器转给 B → B 回 call_response → 服务器转给 A → 双方进入通话

---

## 三、服务器转发规则总表

| 收到消息 | 转发目标 | 备注 |
|---------|---------|------|
| `location_update` | 同 room 的 guardian | 直接转发 |
| `device_status` | 同 room 的 guardian | 直接转发 |
| `manual_message` | 同 room 的对端 | 双向转发 |
| `sos_alert` | 同 room 的 guardian | 需填入 stream_url |
| `call_request` | 同 room 的对端 | 直接转发 |
| `call_response` | 同 room 的对端 | 直接转发 |
| `stream_start` | — | 服务器分配推流地址后返回 stream_url |

---

## 四、核心业务时序

### 配对流程
```
患者 ──connect(room=X, role=patient)──→ 服务器
监护人 ──connect(room=X, role=guardian)──→ 服务器
                        ↓
            room_status{status:"paired"} → 双方
```

### 患者开视频 → 监护人看到
```
患者打开 🎥 开关
  → stream_start{video} → 服务器
  → 服务器分配推流地址
  → stream_url{url} → 监护人
  → 监护人 SurfaceView 自动显示画面
```

### AI 感知危险 → 全自动告警
```
AI 检测异常
  → danger_detected → 患者端
  → 患者端自动 triggerSos():
      ├─ sos_alert → 服务器 → 监护人 (红色面板+位置+画面)
      ├─ location_update{is_sos:true} → 服务器 → 监护人
      └─ stream_start → 服务器 → stream_url → 监护人自动显示画面
```

### 患者手动 SOS
```
患者点击 🆘
  → 同时发送: sos_alert + location_update(SOS) + stream_start
  → 监护人端红色 SOS 面板 + 实时画面
```

---

## 五、快速联调命令

```bash
# 1. 启动服务器 (端口 8000)

# 2. 模拟患者连接
wscat -c "ws://localhost:8000/ws?room=test&role=patient"

# 3. 模拟监护人连接（另开终端）
wscat -c "ws://localhost:8000/ws?room=test&role=guardian"

# 4. 在患者端窗口发送位置
{"type":"location_update","data":{"lat":31.23,"lng":121.47,"accuracy":5.0}}
# → 监护人端应收到同样消息

# 5. 测试 SOS
{"type":"sos_alert","data":{"lat":31.23,"lng":121.47,"message":"测试SOS","stream_url":"rtmp://test"}}
# → 监护人端应收到

# 6. 测试 AI 语音
{"type":"ai_voice_command","data":{"voice_text":"测试播报","action_code":"play_warning_sound","priority":1}}
# → 患者端 TTS 朗读
```

---

## 六、硬编码常量

| 常量 | 值 | 说明 |
|------|-----|------|
| 端口 | `8000` | WebSocket |
| 心跳间隔 | `20000ms` | App 端 ping 频率 |
| 隐私精度 | 2 位小数 | 约 1.1km |
| SOS 精度 | 6 位小数 | 约 0.1m |
| AI 阈值 | confidence > 0.7 | 自动触发 SOS |

---

*文档更新: 2026-07-11 · AI 影伴系统 v1.0*
