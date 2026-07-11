# AI 影伴 — 前端（Android 原生）

患者端 & 监护人端双端 App，通过房间号配对，WebSocket 实时通信 + MJPEG 视频流。

## 技术栈

| | |
|---|---|
| 语言 | Kotlin |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 34 (Android 14) |
| UI | Material Design 3 + ViewBinding |
| 网络 | OkHttp WebSocket |
| 视频 | WebView 加载 MJPEG 流 |

## 快速开始

### 1. 用 Android Studio 打开

```
前端\YinBan_v1.0_完整工程
```

### 2. 编译安装

```bash
# Debug APK（可直接安装到手机）
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

> Debug 版本显示服务器 IP 输入框；Release 版本使用内置地址。当前阶段用 Debug 即可。

---

## 连接服务器（局域网）

服务器（Python FastAPI）运行在电脑上，手机通过 WiFi 连接。

### 方式一：手机连电脑热点（推荐，无需路由器）

1. **电脑开热点**：设置 → 网络和 Internet → 移动热点 → 开启
2. **手机连热点**：手机 WiFi 连接电脑的热点
3. **查看电脑 IP**：
   ```powershell
   # PowerShell 中运行：
   ipconfig
   ```
   找到"移动热点"或"无线局域网适配器"下 `IPv4 地址`，通常是 `192.168.137.1`

4. **在 App 登录页填写这个 IP**（步骤 2 的服务器 IP 输入框）

### 方式二：电脑和手机连同一个 WiFi（校园网貌似无法实现，有AP隔离）

1. 电脑和手机连接同一个路由器 WiFi
2. **查看电脑 IP**：
   ```powershell
   ipconfig
   ```
   找到 WiFi 适配器的 `IPv4 地址`，例如 `192.168.1.100`

3. **在 App 登录页填写这个 IP**

> ⚠️ 每次电脑重启或重新连网，IP 可能会变。如果连不上，检查一下 IP 是否还是之前的。

---

## 使用流程

### 前提

1. 电脑已启动后端服务器（见 `后端/README.md`）
2. 电脑已启动硬件同学 Flask MJPEG 服务（如有）
3. 手机已连上电脑热点或同一 WiFi
4. 防火墙已放行 8000 端口（首次启动后端时 Windows 会弹窗询问）

### 患者端操作

1. 打开 App → 选择「患者端」
2. 输入账号密码 + 服务器 IP（电脑的 IP）
3. 输入配对码（如 `TEST01`），点击「配对并进入」
4. 进入后可开启摄像头推流、查看 AI 提示、发送 SOS

### 监护人端操作

1. 打开 App → 选择「监护人端」
2. 输入**和患者端相同的**账号密码 + 服务器 IP
3. 输入**和患者端相同的**配对码
4. 配对成功后可查看实时画面、患者位置、接收预警

---

## 项目结构

```
app/src/main/java/com/yinban/ai/
├── YinBanApplication.kt          # Application 入口
├── ui/
│   ├── MainActivity.kt           # 入口分流
│   ├── LoginActivity.kt          # 三步登录（角色→账号→配对码）
│   ├── PatientActivity.kt        # 患者端
│   ├── GuardianActivity.kt       # 监护人端
│   ├── ChatActivity.kt           # 聊天页
│   └── VideoCallActivity.kt      # 通话页
├── network/
│   ├── WebSocketManager.kt       # WS 单例管理器
│   └── MessageModels.kt          # 消息类型定义
├── hardware/
│   └── HardwareStreamManager.kt  # 硬件流接口
├── storage/
│   └── PreferenceManager.kt      # SharedPreferences 持久化
└── utils/
    └── LocationPrivacy.kt        # 位置隐私模糊化
```

## WebSocket 连接格式

```
ws://<服务器IP>:8000/ws?room=<配对码>&role=<patient|guardian>
```

例：`ws://192.168.137.1:8000/ws?room=TEST01&role=patient`

## 注意事项

- 患者端和监护人端必须使用**相同的配对码**（如 `TEST01`）才能互通
- 一台电脑可以同时跑后端 + 硬件 Flask 服务
- 如果 WebView 加载视频失败，检查：防火墙是否放行 Flask 端口（5000）、URL 中的 IP 是否正确
- 首次使用需要先添加 Collaborator：把另一台手机的账号加入协作名单
