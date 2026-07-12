# 🤖 小影火 AI — API Key 配置指南

> 小影火依赖 DeepSeek API 提供智能对话。**没有配置 API Key 时，AI 功能将不可用。**

---

## 🎯 三种配置方式（任选一种）

### 方式一：App 内设置（推荐 ✅）

> 最简单，无需改代码，每个用户可用自己的 Key。

1. 打开 App，登录进入患者端
2. 点击底部 **"我的"** 标签页
3. 找到 **🤖 AI 设置 → DeepSeek API Key**
4. 点击后填入你的 API Key（格式：`sk-xxxxxxxx`）
5. 点"保存"

> 设置后立即生效，存于本地，下次打开 App 无需重新输入。

---

### 方式二：修改代码默认值

> 适合开发者/测试，改了之后所有用户共用同一个 Key。

打开 `app/src/main/java/com/yinban/ai/network/DeepSeekClient.kt`，把第 21 行改成：

```kotlin
const val DEFAULT_API_KEY = "sk-你的APIKey"
```

改完重新编译安装 App 即可。

---

### 方式三：后端环境变量

> 如果你启动了后端服务器，且走 WebSocket 路径调用 AI。

```powershell
# Windows PowerShell
$env:DEEPSEEK_API_KEY = "sk-你的APIKey"
python 后端/main.py
```

```bash
# macOS / Linux
DEEPSEEK_API_KEY="sk-你的APIKey" python 后端/main.py
```

---

## 🔑 如何获取 API Key

1. 打开 [platform.deepseek.com](https://platform.deepseek.com)
2. 注册/登录
3. 进入 **API Keys** 页面
4. 点击 **创建 API Key**
5. 复制生成的 key（格式 `sk-xxxxxxxxxxxxxxxx`）

> **费用**：DeepSeek API 按量计费，充值 10 元能用很久。当前项目默认使用 `deepseek-chat` 模型。

---

## 🔄 AI 调用路径说明

```
┌──────────────┐     WebSocket      ┌────────────┐     HTTP      ┌──────────────┐
│   App 前端    │ ◄──────────────►  │  后端 main.py │ ◄──────────► │ DeepSeek API │
│  (患者端)     │   chat_ai_request  │  (FastAPI)   │              │  (云端)       │
└──────────────┘                    └────────────┘              └──────────────┘
                                           │
      ┌────────────────────────────────────┘
      │  后端在线 → 走上面这条路径（需要后端配置 API Key）
      │
      │  后端离线 → App 直连 DeepSeek HTTP（需要 App 配置 API Key）
      ▼
┌──────────────┐                    ┌──────────────┐
│   App 前端    │ ──── HTTP ──────► │ DeepSeek API │
│  (兜底模式)   │                    │  (云端)       │
└──────────────┘                    └──────────────┘
```

- **后端在线 + 后端有 Key** → 通过 WebSocket 走服务器调用，App 不需要 Key
- **后端离线** → App 自动兜底，直连 DeepSeek HTTP（需 App 内配置 Key）

---

## ❓ 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 小影火不回复 | 没有配置 API Key | 在"我的"页面设置 Key |
| "抱歉，我暂时无法回复（401）" | API Key 无效或过期 | 检查 Key 是否正确 |
| "抱歉，我暂时无法回复（429）" | API 额度用完了 | 去 DeepSeek 平台充值 |
| 网络错误 | 手机没网 | 检查 WiFi / 移动数据 |

---

> ⚠️ **注意**：GitHub 仓库里不会保存 API Key（安全策略禁止）。每个开发者/用户需要自己获取和配置。
