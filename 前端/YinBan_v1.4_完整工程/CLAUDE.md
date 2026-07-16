# AI 影伴系统 (YinBan) — 项目架构文档

## 项目概述

AI 影伴系统 v1.0 商业化版本，基于 Android 原生 + WebSocket 的远程智能伴护平台。
双端架构：患者端（被监护人）与监护人端，通过统一房间号配对。

## 技术栈

- **语言**: Kotlin
- **最低 SDK**: 26 (Android 8.0)
- **目标 SDK**: 34 (Android 14)
- **UI**: Material Design 3 + ViewBinding
- **网络**: OkHttp WebSocket
- **序列化**: Gson
- **TTS**: Android TextToSpeech

## 项目结构

```
app/src/main/java/com/yinban/ai/
├── YinBanApplication.kt          # Application 入口
├── ui/
│   ├── MainActivity.kt           # 入口分流 (Splash → Login 或直连)
│   ├── LoginActivity.kt          # 登录/注册 + 角色选择
│   ├── PatientActivity.kt        # 患者端核心逻辑
│   └── GuardianActivity.kt       # 监护人端核心逻辑
├── network/
│   ├── WebSocketManager.kt       # WS 单例管理器 (心跳/重连/分发)
│   └── MessageModels.kt          # 9 种消息类型 + 统一外壳
├── hardware/
│   └── HardwareStreamManager.kt  # 硬件流接口 (视频/音频注入)
├── storage/
│   └── PreferenceManager.kt      # SharedPreferences 持久化
└── utils/
    └── LocationPrivacy.kt        # 位置隐私模糊化
```

## 消息协议 (9 种类型)

| type                    | 方向         | 说明             |
| ----------------------- | ------------ | ---------------- |
| ping / pong             | 双向         | 心跳保活         |
| error                   | 服务器 → 端  | 错误通知         |
| room_status             | 服务器 → 端  | 房间配对状态     |
| stream_start            | 端 → 服务器  | 推流请求         |
| stream_url              | 服务器 → 端  | 推流地址下发     |
| ai_voice_command        | 服务器 → 患者 | AI 语音干预指令  |
| location_update         | 患者 → 服务器 | 位置同步         |
| device_status           | 患者 → 服务器 | 设备状态上报     |
| manual_message          | 双向         | 手动文字消息     |
| device_control_request  | 监护人 → 服务器 | 远程设备控制请求 |

## 核心隐私设计

1. **权限反向确认**: 监护人远程控制 → 患者端弹窗 → 患者主动授权后才执行
2. **位置脱敏**: 隐私模式下经纬度模糊化到街区级 (~1.1km)，SOS 时全精度
3. **拒绝告知**: 患者拒绝分享时自动向监护人发送说明消息

## WebSocket 连接格式

```
ws://<IP>:8000/ws?room=<room>&role=<patient|guardian>
```

## 构建命令

```bash
./gradlew assembleDebug    # 编译 Debug APK
./gradlew assembleRelease  # 编译 Release APK
```
