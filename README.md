# Agent Studio Desktop

一个单机优先、配置驱动的智能体搭建工具。当前仓库处于阶段 0：建立独立、可启动、可验证的工程基线。

## 当前已具备

- Spring Boot 模块化单体骨架；
- React + TypeScript + Vite 本地管理台骨架；
- MySQL 与 PostgreSQL + pgvector 的 Docker Compose 定义；
- 后端健康状态接口与前端状态展示；
- 来源项目、设计边界和验证结果的文档目录。

## 尚未实现

模型配置、Agent 发布、SSE 流式对话、RAG、ReAct、工具执行、安全审批、SSH 和 Coding 扩展均尚未实现，不应将目录或占位说明视为已完成能力。

## 本地启动

要求：JDK 21、Maven 3.9+、Node.js 22+、Docker Desktop。

```powershell
# 中间件
docker compose -f docker/compose.yml up -d

# 后端（默认端口 8080）
cd backend
mvn spring-boot:run

# 前端（默认端口 5173）
cd ../web
npm install
npm run dev
```

打开 `http://localhost:5173`。前端开发服务器将 `/api` 转发到 `http://localhost:8080`。

为避免与电脑上已有的数据库冲突，容器默认使用以下宿主机端口：

- MySQL：`localhost:13306`（容器内仍为 `3306`）；
- PostgreSQL + pgvector：`localhost:15432`（容器内仍为 `5432`）。

如需改用其他端口，请复制 `.env.example` 为 `.env`，修改 `MYSQL_PORT` 或 `POSTGRES_PORT` 后重新执行 Compose 启动命令。

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
