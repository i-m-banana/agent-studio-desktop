# Agent Studio Desktop

一个单机优先、配置驱动的智能体搭建工具。当前已跑通模型配置、知识库、Agent 草稿、不可变版本发布、检索增强和流式对话主链。

## 当前已具备

- Spring Boot 模块化单体和 React + TypeScript + Vite 本地管理台；
- MySQL 与 PostgreSQL + pgvector 的 Docker Compose 定义；
- Flyway 管理的模型、知识库元数据、Agent 版本、会话和消息表；
- 常见文档上传、Apache Tika 文本解析、切块及 pgvector 存储；
- Agent 版本可选绑定知识库，对话时返回来源文件、chunk 编号和相似度；
- OpenAI Chat Completions 兼容接口及 SSE 流式返回；
- 模型配置、Agent 创建/发布和对话测试页面；
- 密钥仅通过环境变量读取，数据库只保存环境变量名；
- 来源项目、设计边界和验证结果的文档目录。

## 当前边界

当前向量化使用内置的 384 维词法哈希基线，不需要额外的 embedding API，适合先验证完整 RAG 流程；它不是语义向量模型，跨表述召回能力有限。显式 ReAct 工具循环、工具执行、安全审批、SSH 和 Coding 扩展尚未实现，不应描述为已经具备工具型 Agent 能力。

## 本地启动

要求：JDK 21、Maven 3.9+、Node.js 22+、Docker Desktop。

```powershell
# 中间件
docker compose -f docker/compose.yml up -d

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
