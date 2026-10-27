# Vision Assistant（VA）

包装视觉能力的**统一对外网关与编排层**：前端控制台 + Java 服务端，对内调度 ocr-agent
（PaddleOCR，:48763）与 packvision ip_check（向量化/IP 一致性，:8001）两个底层能力服务。

> 架构决策：前后端分离（antd 前端 + Java Spring Boot 服务端）｜存量 Python 服务接口零改动、线上直连保留｜同机部署。
> Java 网关落地前曾用 Python 原型验证过全链路契约，原型保留在 `python-prototype/`（含异步任务/回调模型的参考实现）。

## 项目结构

```
vision-assistant/
├── va-server/          # Java 17 + Spring Boot 3.4 后端（:9000）
│   └── src/main/java/com/bz/va/
│       ├── config/       VaProperties 配置 + RestClient（长超时/UTF-8 表单/健康短超时）
│       ├── security/     ApiKeyFilter（X-API-Key 白名单，空=开发模式）
│       ├── client/       OcrClient / IpCheckClient（下游适配：错误归一化/OCR 并发闸门/大图202轮询）
│       ├── service/      OcrTextsAdapter（ocr_texts 适配契约）+ SummaryBuilder + IpConsistencyService（编排）
│       └── web/          控制器 + 统一错误体 {"error":{code,message}}
├── va-web/             # React 18 + antd 5 + Vite 前端
│   └── src/pages/       检测工作台（OCR/全检+画布标注）/ 标准库管理 / 设置
└── python-prototype/   # Python FastAPI 原型（任务持久化/webhook 重试的参考实现，不再演进）
```

```
业务系统(B端, X-API-Key)      C端用户
        │                        │
        ▼                        ▼
┌────────────────────────────────────────┐
│ va-server :9000（对外API/鉴权/编排/托管前端）│
│ va-web（antd 控制台）                    │
└─────┬───────────────────┬──────────────┘
      ▼                   ▼
 ocr-agent :48763    packvision ip_check :8001
```

## 快速开始

```bash
# 后端（需 JDK 17 + Maven）
cd va-server && mvn spring-boot:run          # 或 java -jar target/va-server-0.2.0.jar

# 前端（需 Node 18+）
cd va-web && npm install && npm run dev      # 开发模式 :5173，/api 代理到 :9000
cd va-web && npm run build                   # 生产构建 → dist 由 va-server 直接托管
```

浏览器打开 `http://127.0.0.1:9000/`。后端 Swagger：`/swagger-ui/index.html`。
单测：`cd va-server && mvn test`（9 个用例，覆盖 ocr_texts 适配与结论汇总）。

### 配置（va-server，环境变量）

| 变量 | 说明 |
|---|---|
| `VA_API_KEYS` | 逗号分隔 API Key；空 = 关闭鉴权（仅开发） |
| `VA_OCR_BASE_URL` / `VA_IPCHECK_BASE_URL` | 下游地址（默认本机 48763 / 8001） |
| `VA_PORT` | 端口，默认 9000 |

## 对外 API（/api/v1，除 health 外需 X-API-Key）

| 端点 | 说明 |
|---|---|
| `GET /health` | VA + 下游能力健康（免鉴权） |
| `POST /ocr/analyze` | OCR 识别（同步；`image` 文件或 `url` 二选一；大图自动等待下游异步任务） |
| `POST /ip/identify` | **IP 一致性全检**：`bizno` + 图片 → VA 编排 OCR → 适配 ocr_texts → identify/auto → 汇总报告。`async=true` 返回 `202 {task_id}`；`callbackUrl` 完成后回调（重试 + 可选 HMAC 签名） |
| `GET /tasks/{task_id}` | 任务状态/步骤/结果（SQLite 持久化，重启后已完成结果仍可查） |
| `GET /tasks?limit=50` | 最近任务列表 |
| `GET /ip/library?bizno=` | 标准库角色列表 |
| `POST /ip/library?bizno=` | 建角色/追加标准图（`name` + `files[]`，可选 `model`） |
| `DELETE /ip/library/{name}?bizno=` | 删除角色 |
| `GET /ip/library/images/{id}?bizno=` | 读取标准图 |

全检结果判读：`summary.overall ∈ pass / review / fail`；`verdicts` 为形象↔标准库三档判定
（match/mismatch/low_confidence）计数；`name_inconsistent` 列出图文错位区域。

```bash
# 异步全检（推荐）
curl -X POST 'http://127.0.0.1:9000/api/v1/ip/identify?async=true' \
  -H "X-API-Key: <KEY>" -F "image=@package.png" -F "bizno=customer-a" \
  -F "callbackUrl=http://biz.example.com/va/callback"
# → 202 {"task_id": "...", "task_url": "/api/v1/tasks/..."}
curl -H "X-API-Key: <KEY>" http://127.0.0.1:9000/api/v1/tasks/<task_id>
```

## 设计要点

- **可靠性收口**：OCR 下游任务表在内存、回调不可靠 → va-server 以闸门（Semaphore=1）排队保护串行推理、
  大图 202 自动轮询兜底、下游 404 任务转明确错误。
- **异步任务层**：SQLite 持久化（重启后已完成结果可查、在途任务标记 INTERRUPTED）、步骤实时落库、
  webhook 指数退避重试 + `X-VA-Signature`（HMAC-SHA256，设 `VA_WEBHOOK_SECRET` 时启用）。
- **`ocr_texts` 适配契约**（packvision docs/ip-check-api.md §6.1）：类型过滤（只留 text/art_text）、
  裁白边 crop 偏移、像素坐标直传——`OcrTextsAdapter` 单测锁定。
- **错误归一化**：下游 4xx 透传语义、5xx 归 502，统一 `{"error":{code,message}}`。
- **前端同源托管**：生产模式 va-server 直接服务 va-web/dist，无需独立前端部署；标准图经后端代理加载
  （带鉴权、无跨域问题）。

**E2E 已验证**（真实 ocr-agent + packvision）：建库 → 异步全检（三步骤 ok）→ 结论汇总 → webhook 回调真实送达。

## 路线图

- [x] 异步任务模型（v0.3.0：SQLite 持久化 + 步骤跟踪 + webhook 重试）
- [ ] LLM Agent 编排（把下游能力暴露为 tool schema，面向交互式检测）
- [ ] 接入 packvision 分割服务（:8000）
- [ ] 更多原子能力透传：/verify 文案校验、/panels 拆面
- [ ] 生产部署脚本（Windows NSSM，同 ocr-agent 模式）

## 已知边界（v0.3）

- 同步模式的全检仍保留（`async` 缺省 false），大图建议用异步任务。
- 上传的图片字节不落盘（任务只存元信息），任务在进程内执行；单进程部署（任务并发默认 2，OCR 推理仍由闸门串行）。
- 标准库管理为薄代理，鉴权在 va-server；两个 Python 服务的直连端口仍开放（存量线上在用）。
