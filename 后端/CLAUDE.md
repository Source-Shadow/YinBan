# 影伴 (YinBan / ShadowPartner) — 后端开发文档

## 项目概述

本目录包含两个后端系统：

| 系统 | 状态 | 技术 | 端口 | 入口 |
|------|------|------|------|------|
| AI影伴 WebSocket 中转服务器 | **已实现 v2.0** | Python FastAPI | 8000 | `main.py` |
| ShadowPartner 业务后端 | **设计阶段** | Spring Boot + FastAPI AI | 8080 / 8000 | 待搭建 |

---

## 一、当前系统：WebSocket 中转服务器

### 技术栈

- **语言**: Python 3.11+
- **框架**: FastAPI + Uvicorn
- **协议**: WebSocket (ws://)
- **依赖**: `fastapi`, `uvicorn`（无需额外依赖）

### 启动命令

```bash
cd 后端
python main.py
```

或指定推流地址：

```powershell
$env:MJPEG_STREAM_URL = "http://192.168.1.100:5000/video_feed"
python main.py
```

### 项目文件

```
后端/
├── main.py              # 服务器主程序（WebSocket + 房间管理）
├── test/
│   ├── test_client.py   # WebSocket 测试客户端（模拟患者/监护人）
│   └── main.py          # 测试入口
├── 接口对接单.md          # 前后端接口协议（唯一标准）
├── README.md            # 快速入门文档
└── 合作同学的AI的理解/     # ShadowPartner 设计文档（见第二节）
```

### 架构核心

- **房间管理**: 内存字典 `rooms: dict[str, dict[str, WebSocket]]`
- **连接方式**: `ws://<IP>:8000/ws?room=<房间号>&role=<patient|guardian>`
- **配对逻辑**: 第一个连入 → waiting；第二个连入 → paired；断开 → waiting；全断 → 删除房间
- **消息格式**: `{"msg_id": "UUID", "type": "类型", "data": {...}, "timestamp": "ISO8601"}`
- **时区**: 中国标准时间 CST (UTC+8)

### 13 种消息类型

| 类别 | type | 方向 | 处理方式 |
|------|------|------|----------|
| 心跳 | `ping` / `pong` | 双向 | 服务器回复 pong |
| 房间 | `room_status` | Server→App | 服务器生成 |
| 推流 | `stream_start` | 患者→Server | 服务器生成 `stream_url` 发给监护人 |
| 推流 | `stream_url` | Server→监护人 | 服务器生成 |
| 转发⚡ | `location_update` | 患者→监护人 | 原封不动转发 |
| 转发⚡ | `device_status` | 患者→监护人 | 原封不动转发 |
| 转发⚡ | `manual_message` | 双向 | 原封不动转发 |
| 转发⚡ | `device_control_request` | 双向 | 原封不动转发 |
| 转发⚡ | `call_request` | 双向 | 原封不动转发 |
| 转发⚡ | `call_response` | 双向 | 原封不动转发 |
| SOS | `sos_alert` | 患者→监护人 | 补充 `stream_url` 后转发 |
| AI⚡ | `ai_voice_command` | Server→患者 | 原封不动转发（基础版测试用） |
| AI⚡ | `danger_detected` | Server→患者 | 原封不动转发（基础版测试用） |

> **编码规范**: 直接转发类消息在 `main.py` 第 105-111 行的元组中集中管理，新增转发类型只需加入该元组。

### 关键函数

| 函数 | 用途 |
|------|------|
| `make_msg(type, data)` | 生成统一格式消息（自动生成 UUID 和时间戳） |
| `forward_to_other(room, from_role, msg)` | 转发给房间内另一个人 |
| `get_other_role(role)` | 获取对方角色 |

### 测试

```bash
# 终端1：启动服务器
python main.py

# 终端2：模拟患者
python test/test_client.py patient

# 终端3：模拟监护人
python test/test_client.py guardian
```

---

## 二、规划系统：ShadowPartner 业务后端

### 技术栈

- **语言**: Java 17+
- **框架**: Spring Boot (单体架构)
- **构建**: Maven 3.8+
- **数据库**: MySQL 8.x
- **AI 服务**: Python FastAPI（内部调用）
- **前端**: Vue3 + Vite（端口 5173）

### 端口规划

| 服务 | 端口 |
|------|------|
| Vue3 前端 | 5173 |
| Spring Boot 后端 | 8080 |
| FastAPI AI 服务 | 8000 |

### 目标目录结构

```
backend/
  src/main/java/com/shadowpartner/
    ShadowPartnerApplication.java
    common/
      config/        # Security、CORS、异常处理配置
      exception/     # 全局异常处理
      response/      # 统一返回格式 {success, code, message, data, timestamp}
      security/      # JWT 认证、权限拦截
      util/          # 工具类
    auth/            # 登录认证模块
    user/            # 用户管理模块
    supporter/       # 支持者关系模块
    scenario/        # 情境分析模块（核心）
    ai/              # AI 调用模块（HTTP 调 FastAPI）
      client/        # Feign/RestTemplate 客户端
      dto/           # AI 请求/响应 DTO
      fallback/      # AI 失败兜底
    risk/            # 风险判断模块（规则复核）
    event/           # 事件记录模块
    feedback/        # 用户反馈模块
  src/main/resources/
    application.yml
```

### 模块依赖关系

```
Auth → User → Supporter
Scenario → AI Call → Risk
Scenario → Event
Risk → Event
Supporter → Event
```

### 统一返回格式

所有 API 返回：
```json
{
  "success": true,
  "code": "OK",
  "message": "ok",
  "data": {},
  "timestamp": "2026-07-09T10:00:00"
}
```

### P0 接口清单（Demo 必须实现，共 10 个）

```text
# 认证
POST /api/v1/auth/login
GET  /api/v1/auth/me

# 用户
GET  /api/v1/users/me

# 核心业务
POST /api/v1/scenarios/analyze     ← 一次完成分析+回复+风险判断

# 分享闭环
POST /api/v1/shares
GET  /api/v1/supporter/shares
GET  /api/v1/supporter/shares/{shareId}

# 反馈
POST /api/v1/feedbacks

# Demo
GET  /api/v1/demo/scenarios
POST /api/v1/demo/scenarios/{id}/run
```

### AI 兜底原则

AI 服务不可用时，Spring Boot 必须：
1. 返回 `fallbackUsed: true`
2. 风险等级默认不低于 `MEDIUM`
3. 提示用户暂停操作、不转账、不泄露隐私、联系可信任的人
4. **绝不返回空白结果**，保证 Demo 流程不中断

### 错误码规范

| code | HTTP | 说明 |
|------|------|------|
| `BAD_REQUEST` | 400 | 参数错误 |
| `UNAUTHORIZED` | 401 | 未登录 |
| `FORBIDDEN` | 403 | 权限不足 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `AI_SERVICE_ERROR` | 502 | AI 服务异常 |
| `AI_MODEL_TIMEOUT` | 504 | AI 超时 |
| `DATABASE_ERROR` | 500 | 数据库异常 |
| `INTERNAL_ERROR` | 500 | 未知错误 |

---

## 三、编码规范

### Python（当前 WebSocket 服务器）

- 类型注解：所有函数参数和返回值使用 type hints
- 异步：WebSocket 处理使用 `async/await`
- 命名：函数用 `snake_case`，常量用 `UPPER_SNAKE_CASE`
- 消息处理：新增消息类型请在 `main.py` 第 105-111 行转发元组中注册

### Java（规划 Spring Boot 后端）

- 分层：Controller → Service → Repository，不跨层调用
- DTO：Controller 层只接收和返回 DTO，不暴露 Entity
- 异常：统一使用全局异常处理器，不手动 try-catch 返回错误
- 命名：类名 `PascalCase`，方法 `camelCase`，包名全小写
- 日志：敏感信息（手机号、地址、验证码、银行卡）必须脱敏
- AI 调用：必须有超时设置（8-10秒）和失败兜底

---

## 四、相关文档索引

| 文档 | 路径 | 用途 |
|------|------|------|
| 接口对接单 | `../接口对接单.md` | 前后端协议唯一标准 |
| 架构设计 | `合作同学的AI的理解/architecture.md` | 系统架构全貌 |
| API 设计 | `合作同学的AI的理解/api-design.md` | 接口详细定义 |
| 数据库设计 | `合作同学的AI的理解/database-design.md` | 表结构与索引 |
| 开发指南 | `合作同学的AI的理解/development-guide.md` | 环境搭建与启动 |
| PRD | `合作同学的AI的理解/PRD.md` | 产品需求 |
| 环境验证 | `合作同学的AI的理解/environment-verification-report.md` | 开发环境验证结果 |
| 前端 CLAUDE.md | `../前端/YinBan_v1.0_完整工程/CLAUDE.md` | 前端架构参考 |
