# 监管AI助手 · Java 后端（Spring Boot + MyBatis）

与 Node 演示版同一套设计：相同的建表 SQL、相同的接口契约、相同的业务逻辑与 prompt。
Node 版（`/mnt/agents/output/app`）继续作为预研演示环境；本工程为正式产品后端骨架。

## 快速启动

```bash
# 1. 准备数据库（MySQL 8 / MariaDB）
mysql -e "CREATE DATABASE ai_assistant DEFAULT CHARSET utf8mb4;"
mysql ai_assistant < db/schema.sql

# 2. 修改 src/main/resources/application.yml 中的数据源账号密码（默认 root/root@localhost:3306）

# 3. 启动（首次启动自动写入演示平台凭证 + 6条规范条文，幂等）
mvn spring-boot:run
# 或打包后运行：mvn package -DskipTests && java -jar target/ai-assistant-1.0.0.jar
```

服务端口 `8080`。冒烟测试：

```bash
curl -X POST http://localhost:8080/api/integration/demoSign \
  -H 'Content-Type: application/json' \
  -d '{"appKey":"demo-platform","userName":"张工"}'
# 拿到 token 后即可调用 /api/assistant/* 四个场景接口
```

## 工程结构（只需要理解 3 层）

```
controller/   IntegrationController（免登）· AssistantController（四场景）· StatsController（看板/知识库）
    │
service/      AssistantService —— 唯一需要精读的业务文件（4个方法 = 4个场景，prompt 在这里）
    │
provider/     MeteredGateway（供应商选择 + 调用计量，不用改）
              ├── MockProvider  —— 默认，返回演示数据，全流程可跑
              └── HttpProvider  —— 真实API（OpenAI兼容协议），配 yml 即启用
    │
mapper/       注解式 MyBatis，SQL 即文档
```

## 常见任务对照

| 要做什么 | 改哪里 |
|---|---|
| 切真实模型API | `application.yml`：`model.mode: http` + `base-url` + `api-key`，零代码 |
| 调某场景效果 | `AssistantService` 对应方法里的 system prompt（中文，一看就懂） |
| 接讯飞/阿里语音 | `HttpProvider.asr / tts` 按厂商协议扩展（已留注释位置） |
| 加规范条文 | `StartupSeeder.KNOWLEDGE_SEED` 数组加一行 |
| 新接入一个平台 | `platform_apps` 表插一行（appKey/appSecret/平台名） |
| 允许某平台iframe嵌入 | `application.yml` 的 `app.frame-ancestors` 配平台域名 |

## 平台免登对接（生产路径）

平台后端用 appSecret 计算签名：

```
signature = HMAC-SHA256(appKey + "\n" + timestamp + "\n" + userName, appSecret)  # hex
POST /api/integration/issueAccessToken { appKey, signature, timestamp, userName, orgName }
→ { token, expiresAt }
```

前端弹层 iframe 以 `/assistant?token=xxx&embed=1` 打开助手（见 Node 版 PlatformPage 演示）。
`demoSign` 仅 is_demo 平台可用，生产平台调用返回 403。

## 前端接入改造（React 版只需换调用层）

现有 React 前端（`/mnt/agents/output/app`）四个场景面板保持不变，把 tRPC 调用换成 fetch 即可：

```typescript
// 例如 QaPanel 中：
// 原来：trpc.assistant.ask.useMutation()
// 改为：
const res = await fetch("/api/assistant/ask", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ token, question }),
});
const result = await res.json(); // 返回结构与原来完全一致
```

接口路径与返回结构对照（前后一致，无缝替换）：

| 功能 | REST 路径 | 方法 |
|---|---|---|
| 免登换Token | `/api/integration/issueAccessToken` | POST |
| 演示签名 | `/api/integration/demoSign` | POST |
| 会话信息 | `/api/integration/sessionInfo?token=` | GET |
| 规范问答 | `/api/assistant/ask` | POST |
| 隐患描述 | `/api/assistant/describeHazard` | POST |
| 材料预审 | `/api/assistant/reviewMaterial` | POST |
| 文书比对 | `/api/assistant/compareRectification` | POST |
| 语音合成 | `/api/assistant/synthesize` | POST |
| 用量汇总 | `/api/stats/usageSummary` | GET |
| 每日用量 | `/api/stats/usageDaily` | GET |
| 知识库 | `/api/knowledge/list` `/api/knowledge/search?keyword=` | GET |

## 数据库

- 建表：`db/schema.sql`（8 张表，与 Node 版完全相同，两端可共库）
- `call_logs`：每次模型调用的用量/耗时/成败——第四阶段 API 年费预算修正的数据源

## 验证状态

- Maven 编译通过（Java 17）
- 已实测：启动自动建种子 → demoSign 免登 → 四场景调用 → call_logs 计量 → 看板统计，全链路通过（Mock 模式）
