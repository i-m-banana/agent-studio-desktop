# Agent Studio Desktop

一个单机优先、配置驱动的智能体搭建工具。当前版本为 `1.0.0`，已于 2026-09-13 完成首个单机正式版验收。

当前远程运维实验分支新增业务级“准备不可变发布候选”：从固定本地 Maven 源码测试并生成 `target/app.jar`，通过受控 SSH/SFTP 暂存到不可覆盖的远程候选目录并校验摘要，但不修改生产服务。2026-09-28 候选准备正向链路已通过真实模型与服务器人工验收；不代表生产部署或回滚已完成。完整验收步骤见 `docs/RELEASE_CANDIDATE_ACCEPTANCE.md`。

## 当前已具备

2026-10-01 新增“受审上线并验证恢复（publish_remote_release）”：绑定八项身份，核查近期备份和生产指纹，校验兼容扩展迁移，只重建 app、刷新 Nginx、验证实际镜像/容器/首页/浏览页，成功后同步生产 app.jar/Dockerfile；失败尝试恢复旧应用，不覆盖数据库或 uploads。代码/自动化验收不等于生产已经上线，真实发版待逐次审批。完整中文操作单：[CONTROLLED_RELEASE_USER_GUIDE.md](docs/CONTROLLED_RELEASE_USER_GUIDE.md)。

2026-10-01 完整应用上线正向人工验收通过：新候选镜像已切换，上线回执 DEPLOYED，后置健康 HTTP200；完整运行、审批与审计证据见 [CONTROLLED_RELEASE_ACCEPTANCE.md](docs/CONTROLLED_RELEASE_ACCEPTANCE.md)。本次没有待执行迁移；失败恢复有自动化覆盖，未做真实生产故障注入。上方“待逐次审批”指后续每次发版仍必须审批，不表示本次尚未上线。

实验分支增加“构建候选应用镜像（build_release_candidate_image）”：按候选 ID 与清单摘要构建独立限额镜像，不切换生产；新非 root 启动自检及真实构建已经验收，步骤见 `docs/RELEASE_IMAGE_ACCEPTANCE.md`。候选清单升级为格式 2，旧候选需要重新准备。

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
- Coding 工作区：已真实验收目录浏览、轻量文件路径搜索、UTF-8 文本分段读取，以及摘要绑定、HIGH 审批的精确文本补丁；
- Coding 白名单验证：固定 Maven Test、npm Test 和 npm Build，统一进入 HIGH 审批并限制环境、输出和运行时间；自动化及真实 Maven/npm 批准/拒绝链均已验收；
- SSH/SFTP 远程工作区：固定主机指纹、密码安全解析、单远程根的目录浏览/路径搜索/文本读取，以及摘要绑定、HIGH 审批的远程单文件精确补丁均已通过自动化和真实 Ubuntu/OpenSSH 服务器验收；
- 受控 SSH Exec：只允许在授权远程项目目录运行 Git 状态/差异摘要和固定 Maven/npm 测试构建任务，统一进入 `SSH/EXECUTE/HIGH` 审批；自动化及真实 Ubuntu/OpenSSH 上的五种固定任务正向链均已验收，并验证了非零退出、超时关闭和错误项目标记；
- 受控远程工作台：人工文件浏览不调用模型，三栏分别内部滚动，固定任务、审批和输出保持在同一操作上下文；真实界面与 SSH 任务已人工确认；
- 远程部署只读诊断：独立 V12 生产 Profile，原五项 HIGH 及 DATABASE_SCHEMA（数据库结构核查）正向链已真实验收；新增只读 DATABASE_BASELINE_STATUS 检查历史状态，不读取业务行；
- 受审数据库基线登记：六项身份绑定新候选/镜像/结构及新鲜备份，短读锁下仅登记Flyway版本1，不迁移或重启网站；仅自动化通过，待真实验收，见 docs/DATABASE_BASELINE_ACCEPTANCE.md；
- 远程发布前备份：无模型参数的 `SSH/WRITE/HIGH` 工具只创建全新备份目录，固定保存数据库、uploads、部署文件、受保护 `.env`、镜像/服务清单和摘要 manifest；自动化及两次真实生产创建验收通过，恢复能力仍待独立演练；
- 远程备份隔离恢复演练：无模型参数的 `SSH/WRITE/HIGH` 工具固定选择最新合格备份，只在 `restore-drills` 新目录校验并展开恢复材料；不导入数据库、不修改生产目录或容器，自动化及真实生产材料化、后置服务状态与站点健康验收通过；
- AgentRun 主动取消、120 秒总时限、遗留运行关闭和运行历史详情；
- OpenAI Chat Completions 兼容接口及 SSE 流式返回；
- 模型配置、Agent 创建/发布和对话测试页面；
- 密钥可在前端写入 Windows DPAPI 安全存储，环境变量仍可高优先级覆盖；数据库和接口只保存/返回凭据名称，不返回密钥；
- Agent 历史版本支持归档与恢复；对话默认只展示最新版本，零引用的误发版本可在归档后永久删除；
- 系统诊断逐项检查 MySQL、pgvector、Embedding、模型密钥、数据目录和 MCP；
- 无检索证据时确定性拒答，有候选片段时执行严格的答案证据契约；
- 一键启动/停止、发布检查、MySQL/pgvector/data 备份与受确认保护的恢复脚本；
- 来源项目、设计边界和验证结果的文档目录。

## 当前边界

当前默认通过本机 Ollama 的 `qwen3-embedding:0.6b` 生成 1024 维语义向量，并保留 384 维词法哈希作为可配置回退。工具统一经过 Schema、安全执行网关、参数绑定的一次性 HIGH 审批及 AuditEvent。除固定候选构建、基线登记和受审应用发布外，SSH仍不提供任意/交互式Shell、自定义命令参数、远程Git修改、依赖安装或通用Docker操作；不支持密钥认证、多目标、备份覆盖/删除/清理、生产数据库恢复/导入、基础设施变更发布。应用回退不撤销数据库DDL，也不是无停机发布。远程补丁不支持新建/删除或跨文件事务；MCP OAuth、身份体系、OS级沙箱和崩溃续跑尚未实现。重启只关闭平台遗留运行状态，不能证明远程副作用已恢复。

## 本地启动

要求：JDK 21、Maven 3.9+、Node.js 22+、Docker Desktop、Ollama。

推荐直接使用一键启动，再到“01 模型与凭据”保存密钥；保存一次后，同一 Windows 用户后续启动无需重复设置：

```powershell
.\scripts\start.ps1
```

脚本会先检查 Maven 实际使用的 Java 版本、Docker、Node、Ollama 和数据目录，然后启动数据库、后端和前端。首次启动后到“01 模型与凭据”输入 DeepSeek/Tavily 密钥，再到“07 系统诊断”查看业务就绪状态。密钥以当前 Windows 用户绑定的 DPAPI 密文保存在 `data/secrets`，不会写入 MySQL 或返回前端；若同时设置环境变量，则环境变量优先。停止应用使用 `.\scripts\stop.ps1`；增加 `-StopDatabases` 才会同时停止数据库容器。

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

发布多个 Agent 版本后，对话测试台默认只列出每个 Agent 的最新版本，可用“显示历史版本”临时展开仍在使用的旧版本。Agent Builder 的“管理历史版本”可以归档旧版本；归档不会改写既有会话和运行。只有已归档、没有任何会话或运行引用、且不是最新版的误发版本才能永久删除。

进入“知识库”创建资料库并上传文档，再在 Agent Builder 中绑定该知识库并发布版本。知识库文件保存在本机 `data/knowledge`，元数据位于 MySQL，检索向量位于 PostgreSQL + pgvector。删除文档会同步清理这三处数据。

测试工具调用时，在 Agent Builder 新建或编辑草稿，勾选“获取当前时间”，然后发布新版本；已有发布版本不会自动获得后来新增的工具。对该新版本询问“现在上海时间几点”，对话页会展示模型调用、工具调用和工具结果步骤。工具版本当前采用非流式 Chat Completions 完成每一轮决策，最终答案以一个 `delta` 事件返回；未绑定工具的普通对话仍保持 token 流式输出。

测试审批时可绑定“写入受控工作区笔记”并发布新版本。模型请求写入后，对话页会展示文件名、内容、参数 SHA-256 摘要和过期时间；拒绝或超时不会写文件，批准只能按所展示参数消费一次，且只允许在 `data/tool-workspace` 新建 `.md/.txt`，不会覆盖已有文件。

运行开始后，对话输入区会出现“停止运行”。默认总时限为 120 秒，可用 `AGENT_RUN_TIMEOUT` 调整。高风险审批会固定显示并倒计时；工具轮数预算耗尽后，平台会关闭工具并让模型依据已有结果完成一次最终回答，而不是丢弃已取得的结果。运行结束后可在“运行记录”查看最终状态、错误和完整步骤；应用重启会把上一进程遗留的运行标为 `INTERRUPTED`，不会自动续跑。

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
.\scripts\release-check.ps1
```

数据备份运行 `.\scripts\backup.ps1`。恢复会覆盖当前数据，必须显式传入 backups 下的目录和 `-ConfirmRestore`；完整正式版清单见 [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)。

架构和来源边界见 [`docs`](docs/README.md)。

远程工作台固定任务按钮提交显式工具请求，仍经当前AgentVersion、SafeExecutionGateway和一次性审批，不依赖模型决定是否执行或改写回执。没有执行证据时显示“任务未执行”，聊天文字不等于诊断完成；详见ADR0030。自然语言助手/RAG/MCP保持原有能力。

用于手工上传和 RAG/面试复盘的完整项目知识包见 [`examples/project-knowledge-pack`](examples/project-knowledge-pack/00-入库说明与事实口径.md)。建议上传除 11 号盲测题库和 12 号系统提示词外的其余文档。

当前成熟开发锚点见 [`16-成熟版本开发锚点与面试全景.md`](examples/project-knowledge-pack/16-成熟版本开发锚点与面试全景.md)，锚点后的分阶段路线见 [`17-锚点后的未来开发计划.md`](examples/project-knowledge-pack/17-锚点后的未来开发计划.md)。
