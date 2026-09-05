# Agent Studio Desktop

一个单机优先、配置驱动的智能体搭建工具。当前已跑通模型配置、知识库、Agent 草稿、不可变版本发布、检索增强和流式对话主链。

## 当前已具备

- Spring Boot 模块化单体和 React + TypeScript + Vite 本地管理台；
- MySQL 与 PostgreSQL + pgvector 的 Docker Compose 定义；
- Flyway 管理的模型、知识库元数据、Agent 版本、会话和消息表；
- 常见文档上传、Apache Tika 文本解析、切块及 pgvector 存储；
- Agent 版本可选绑定知识库，对话时返回来源文件、chunk 编号和相似度；
- Agent 草稿可绑定内置工具，发布时把工具集合快照到不可变版本；
- 显式 ReAct 工具循环、AgentRun/RunStep 持久化和前端运行步骤展示；
- 高风险工具审批：展示原始参数、参数摘要、过期时间，并支持批准一次或拒绝；
- MCP Streamable HTTP 与 stdio：工具、Resources、Prompts 发现与调用，Resource 导入知识库，Server 管理、同步历史、配置导入导出、Agent 绑定与安全审计；
- 统一安全执行网关：通用参数 Schema 校验、风险判断、审批、限时执行和结构化审计；
- AgentRun 主动取消、120 秒总时限、遗留运行关闭和运行历史详情；
- OpenAI Chat Completions 兼容接口及 SSE 流式返回；
- 模型配置、Agent 创建/发布和对话测试页面；
- 密钥仅通过环境变量读取，数据库只保存环境变量名；
- 来源项目、设计边界和验证结果的文档目录。

## 当前边界

当前默认通过本机 Ollama 的 `qwen3-embedding:0.6b` 生成 1024 维语义向量，并保留 384 维词法哈希作为可配置回退。工具能力已包含 LOW/READ 的 `current_time`、HIGH/WRITE 的 `write_workspace_note` 和动态 MCP 工具；所有工具统一经过 Schema 校验、安全执行网关和 AuditEvent，HIGH 调用还必须经过参数绑定、限时、一次性审批。运行支持主动取消和总超时，重启会关闭遗留状态但不会断点续跑。MCP 支持 `2025-06-18` Streamable HTTP 与本机 stdio 的 tools、resources 和 prompts 核心闭环，以及 Server 编辑、启停、受保护删除、同步历史、配置导入导出与单工具信任策略；OAuth、sampling、elicitation、roots、resource templates、订阅通知、SSH、Coding、身份体系、OS 级进程沙箱和真正的崩溃续跑尚未实现，无答案拒答仍是已知限制。

## 本地启动

要求：JDK 21、Maven 3.9+、Node.js 22+、Docker Desktop、Ollama。

```powershell
# 中间件
docker compose -f docker/compose.yml up -d

# 默认 RAG embedding 模型（首次需要下载）
ollama pull qwen3-embedding:0.6b

# 在启动后端的 PowerShell 会话设置模型密钥（名称需与管理台配置一致）
$env:OPENAI_API_KEY="your-key"
# DeepSeek 示例：模型 API 地址填 https://api.deepseek.com
$env:DEEPSEEK_API_KEY="your-deepseek-key"

# 后端（默认端口 8080）
cd backend
mvn spring-boot:run

# 前端（默认端口 5173）
cd ../web
npm install
npm run dev
```

打开 `http://localhost:5173`。前端开发服务器将 `/api` 转发到 `http://localhost:8080`。

进入“知识库”创建资料库并上传文档，再在 Agent Builder 中绑定该知识库并发布版本。知识库文件保存在本机 `data/knowledge`，元数据位于 MySQL，检索向量位于 PostgreSQL + pgvector。删除文档会同步清理这三处数据。

测试工具调用时，在 Agent Builder 新建或编辑草稿，勾选“获取当前时间”，然后发布新版本；已有发布版本不会自动获得后来新增的工具。对该新版本询问“现在上海时间几点”，对话页会展示模型调用、工具调用和工具结果步骤。工具版本当前采用非流式 Chat Completions 完成每一轮决策，最终答案以一个 `delta` 事件返回；未绑定工具的普通对话仍保持 token 流式输出。

测试审批时可绑定“写入受控工作区笔记”并发布新版本。模型请求写入后，对话页会展示文件名、内容、参数 SHA-256 摘要和过期时间；拒绝或超时不会写文件，批准只能按所展示参数消费一次，且只允许在 `data/tool-workspace` 新建 `.md/.txt`，不会覆盖已有文件。

运行开始后，对话输入区会出现“停止运行”。默认总时限为 120 秒，可用 `AGENT_RUN_TIMEOUT` 调整。运行结束后可在“运行记录”查看最终状态、错误和完整步骤；应用重启会把上一进程遗留的运行标为 `INTERRUPTED`，不会自动续跑。

工具运行完成后，在“运行记录”选择对应运行可以查看“安全审计”。审计展示参数校验、审批决定、执行开始、完成、跳过或失败，但只保存参数摘要和结果概况，不复制原始参数及完整输出。

## MCP 本机验收

HTTP 模式可另开终端运行 `node examples/mcp-fixture-server/server.mjs`，然后在“03 MCP 连接”新增 `http://127.0.0.1:3001/mcp`。stdio 模式无需常驻进程，选择“本机 stdio”，启动程序填 `node`，参数填 `examples/mcp-fixture-server/stdio-server.mjs` 的绝对路径。连接成功后，工具会出现在 Agent Builder，Resource 和 Prompt 会出现在 MCP 目录；还可查看同步历史、导入/导出无秘密配置。完整集中验收步骤见 `examples/mcp-fixture-server/README.md`。

已有知识库从旧 384 维索引升级时，在知识库页面点击“使用当前模型重建索引”。新索引使用 `qwen3-0.6b-v1` 版本，与旧表隔离。需要离线回退时，可在启动后端前设置 `$env:EMBEDDING_PROVIDER="local-hash"`，并重新构建对应知识库索引。

如果上传文档时提示无法连接 Ollama，先用 `ollama list` 确认服务和模型。若 `OLLAMA_MODELS` 指向了空目录，可在单独的 PowerShell 中用已有模型目录启动：

```powershell
$env:OLLAMA_MODELS="$env:USERPROFILE\.ollama\models"
ollama serve
```

保持该窗口运行，然后在知识库页面点击重建索引；原始文件无需重新上传。

为避免与电脑上已有的数据库冲突，容器默认使用以下宿主机端口：

- MySQL：`localhost:23306`（容器内仍为 `3306`）；
- PostgreSQL + pgvector：`localhost:15432`（容器内仍为 `5432`）。

如需改用其他端口，请复制 `.env.example` 为 `.env`，修改 `MYSQL_PORT` 或 `POSTGRES_PORT` 后重新执行 Compose 启动命令。后端默认连接 `23306`；自定义端口时也要在启动后端的环境中设置同名 `MYSQL_PORT`。

如果之前因 `3306` 冲突而创建过容器，修改后的配置可直接修复并重建：

```powershell
docker compose -f docker/compose.yml up -d
docker compose -f docker/compose.yml ps
```

## 验证

```powershell
cd backend
mvn test

cd ../web
npm run build
```

架构和来源边界见 [`docs`](docs/README.md)。

用于手工上传和 RAG/面试复盘的完整项目知识包见 [`examples/project-knowledge-pack`](examples/project-knowledge-pack/00-入库说明与事实口径.md)。建议先上传 00–10，保留第 11 份题库在知识库外做盲测。
