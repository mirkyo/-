# 知微 · 智能体对话平台

一个支持**知识库检索增强（RAG）** 与 **工具调用（ReAct）** 的智能体对话平台，提供类 ChatGPT 的交互体验：流式输出、思考过程可见、工具调用可追溯、回答带引用来源。

基于 [`spring-ai-agentx`](https://github.com/bigchuidw3/spring-ai-agentx) 提供的 `spring-ai-agentx-core` + `spring-ai-agentx-rag` 两个库构建（**只依赖 jar，不修改框架**）。

---

## 核心能力

| 模块 | 说明 |
|---|---|
| **对话** | 流式输出（SSE）、思考过程折叠展示、工具调用过程可见、中断与继续生成、断点续传 |
| **引用溯源** | 检索命中片段带编号回传，回答用 `[1][2]` 标注，来源卡片可展开查看原文与图片 |
| **知识库** | 建库 / 文档上传 → 选择解析器与分块策略 → 分块预览 → 确认入库（两阶段提交） |
| **RAG 检索** | Agentic RAG，知识库封装为 `kbSearch` 工具，由模型自主决策调用；支持父子分块、重排序 |
| **图片理解** | MinerU 抽取文档内图片，可选用多模态模型生成图片描述；图片通过 HTTP 资产接口回写前端 |
| **技能体系** | 文件系统 `SKILL.md` ↔ `agentx_skill` 表双向同步；zip 上传、启停、删除；已启用技能自动挂载 |
| **多用户** | `SUPER_ADMIN · ADMIN · USER` 三级角色，知识库与会话按用户严格隔离 |
| **管理后台** | 用户管理、技能管理 |

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21 |
| Web 框架 | Spring Boot 3.5.6 |
| 智能体引擎 | Spring AI 1.1.0 + spring-ai-agentx 1.0.1-M2 |
| 关系库 | MySQL 8（业务数据）+ MyBatis-Plus |
| 向量库 | Qdrant（默认）/ PgVector（可选） |
| 文档解析 | Apache Tika + MinerU（可选） |
| 认证 | JWT（Spring Security） |
| 前端 | 原生 HTML / CSS / JavaScript（无构建步骤） |

## 项目结构

```
.
├── src/main/java/com/ziwei/zw/
│   ├── common/      统一响应、全局异常、健康检查
│   ├── config/      配置属性、智能体装配
│   ├── security/    Spring Security、JWT
│   ├── user/        认证、用户管理
│   ├── stream/      对话编排、SSE 事件、中断恢复
│   ├── rag/         知识库、文档解析、向量检索、图片资产
│   ├── skill/       技能管理
│   └── session/     会话管理
├── src/main/resources/
│   ├── db/          建表脚本
│   └── static/      前端页面（html / js / css）
├── deploy/          Dockerfile、部署脚本、部署说明
└── docs/report-2/   设计报告、接口契约
```

## 快速开始

### 1. 前置依赖

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | 必需 |
| Maven | 3.6+ | 必需 |
| MySQL | 8.x | 必需 |
| Qdrant | 1.x | 必需（向量库） |
| LLM API | OpenAI 兼容 | 必需 |

MinerU 为可选项，仅在使用 MinerU 解析器或图片理解功能时需要。

### 2. 安装 AgentX 依赖

`spring-ai-agentx-rag` 未发布到公共仓库，需先将其安装到本地 Maven 仓库：

```bash
git clone https://github.com/bigchuidw3/spring-ai-agentx.git
cd spring-ai-agentx
mvn -DskipTests install
```

### 3. 初始化数据库

```bash
mysql -u root -p < src/main/resources/db/schema-mysql.sql
```

### 4. 配置

复制环境变量模板并填入真实值：

```bash
cp .env.example .env
```

必填项：

| 变量 | 说明 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL 密码 |
| `QDRANT_API_KEY` | Qdrant 访问密钥 |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | LLM 服务地址、密钥、模型名 |
| `JWT_SECRET` | JWT 签名密钥（≥32 字节，可用 `openssl rand -base64 32` 生成） |
| `INIT_ADMIN_PASSWORD` | 首次启动创建的管理员密码，登录后请立即修改 |

### 5. 启动

```bash
# 启动 Qdrant（默认 6333 / 6334）
qdrant

# 启动应用
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

访问 `http://localhost:8000`，默认管理员账号 `admin`，密码为 `INIT_ADMIN_PASSWORD` 的值。

### Docker 方式（可选）

```bash
docker compose up -d
```

## 接口

完整接口契约见 [`docs/report-2/openapi.yaml`](docs/report-2/openapi.yaml)。

主要分组：

| 分组 | 前缀 | 说明 |
|---|---|---|
| 认证 | `/api/auth` | 登录、注册、登出、当前用户 |
| 对话 | `/api/chat` | 流式对话、附件、中断、续传、回放 |
| 知识库 | `/api/rag` | 知识库 CRUD、文档预览与入库 |
| 会话 | `/api/sessions` | 列表、明细、删除 |
| 技能 | `/api/skills` | 上传、启停、删除 |
| 用户管理 | `/api/admin/user` | 增删改查（管理员） |
| 资产 | `/api/assets` | 文档内图片 |
| 健康 | `/api/health` | 探针 |

### 流式事件

`/api/chat/stream` 返回 `text/event-stream`，事件类型：

| 事件 | 说明 |
|---|---|
| `Thinking` | 模型思考增量 |
| `ToolStart` / `ToolEnd` | 工具调用开始 / 结束 |
| `Text` | 正文增量 |
| `Citations` | 引用来源列表 |
| `Error` / `Stopped` | 错误 / 用户中断 |
| `Complete` | 流结束 |

## 设计文档

| 文档 | 说明 |
|---|---|
| [`docs/report-2/报告.md`](docs/report-2/报告.md) | 系统设计报告（含架构图、数据模型图、核心流程） |
| [`docs/report-2/openapi.yaml`](docs/report-2/openapi.yaml) | 接口契约 |
| [`deploy/部署方案-Windows.md`](deploy/部署方案-Windows.md) | Windows 部署说明 |

## 配置说明

关键配置项（`application.yml`）：

| 配置键 | 说明 |
|---|---|
| `spring.datasource.*` | MySQL 连接 |
| `zw.rag.qdrant.host` / `api-key` | Qdrant 地址与密钥 |
| `zw.model.chat.base-url` / `api-key` / `model` | LLM 接口 |
| `zw.agent.skills-dir` | 技能目录（默认 `./data/skills`） |
| `zw.rag.mineru.token` | MinerU token（可选） |
| `zw.rag.mineru.asset-dir` | 图片资产目录（默认 `./data/assets`） |

## 安全说明

- 所有密钥通过环境变量注入，仓库中不含任何真实凭据
- `.env`、`application-local.yml`、`config-local/` 已在 `.gitignore` 中排除
- 密码使用 bcrypt 存储
- 登录失败次数超限后自动锁定账号
- 知识库、会话、向量检索均按用户隔离

## License

本项目为课程作业作品，仅供学习交流使用。
