# 验证记录

## 阶段 0：工程基线（2026-09-02）

验证结果：

- 后端 `mvn test`：通过，2 个测试、0 失败、0 错误；
- 后端真实启动：通过，Spring Boot 3.4.3 使用 Java 21 启动于 8080；
- `GET /api/system/status`：通过，返回 `application=agent-studio-backend`、`status=UP`；
- 前端 `npm run build`：通过，TypeScript 检查与 Vite 生产构建完成；
- `docker compose -f docker/compose.yml config`：通过；
- MySQL 与 pgvector 镜像拉取、网络、数据卷和容器创建：用户环境已验证；
- 容器实启：pgvector 在 `15432` 已健康；MySQL 先后在 `3306`、`13306` 遇到宿主机端口占用，当前默认端口已调整为 `23306`，等待复验。

环境事实：

- Maven 3.9.11 使用 JDK 21.0.4；
- 系统 `java` 命令当前指向 Java 8，但 Maven 使用已配置的 Java 21；
- Node.js 22.13.1，npm 10.9.2；
- Docker CLI 29.2.0、Compose 5.0.2；当前开发会话无权访问 Docker Desktop 引擎，因此容器运行状态待在可访问引擎的环境验证。

真实异常与修复：

- 初次后端构建因依赖尚未下载且沙箱禁止联网而失败；授权下载后测试与启动均通过；
- 初次前端构建因 `tsconfig.node.json` 的 `allowImportingTsExtensions` 缺少 `noEmit` 而失败；补充 `noEmit: true` 后构建通过。
- Compose 实启先后发现本机已有服务占用 `3306` 和 `13306`；MySQL 默认映射调整为 `23306`，pgvector 保持 `15432`，并允许通过 `.env` 覆盖。

本阶段所有业务骨架均为本次新增，未迁移来源项目业务代码。

## 阶段 1：Agent Builder 最小主链（2026-09-02）

已实现：

- Flyway V1：模型配置、Agent 草稿、不可变版本、会话和消息表；
- 模型配置创建、更新与列表 API；
- Agent 草稿创建、更新、发布与版本列表 API；
- OpenAI Chat Completions 兼容流式调用；
- `run`、`delta`、`done`、`error` SSE 协议；
- 模型配置、Agent Builder 和对话测试台页面。

验证结果：

- `mvn test`：4 个测试通过，0 失败、0 错误；
- 版本隔离测试：模型配置和草稿更新后，v1 快照保持不变，v2 使用新配置；
- SSE 集成测试：验证 `run → delta → done` 事件及增量内容；
- `npm run build`：TypeScript 与 Vite 生产构建通过；
- Compose 配置解析：MySQL 默认宿主机端口为 `23306`，pgvector 为 `15432`。

真实异常与修复：

- SSE 集成测试最初受 Windows 测试响应字符集影响，中文断言显示为问号；事件结构无误，改用与字符集无关的 ASCII 增量断言后全部通过。

后续已经完成真实 MySQL、DeepSeek 和 RAG 验证，见阶段 2、3。工具调用、ReAct、运行步骤、审批和审计仍未实现。

## 阶段 2：真实模型与数据库（2026-09-02）

- 用户确认 MySQL `23306` 与 pgvector `15432` 两个容器均为 healthy；
- Spring Boot 真实连接 MySQL 8.4，Flyway V1、V2 校验及迁移通过；
- 使用用户配置的 DeepSeek OpenAI 兼容端点完成真实 SSE 调用，收到中文 `delta` 并以 `done` 正常结束；
- 模型密钥仍仅从启动后端的 `DEEPSEEK_API_KEY` 环境变量读取，未写入数据库。

## 阶段 3：知识库与检索增强（2026-09-02）

已验证：

- 文档上传、Tika 解析、切块和 pgvector 写入完成，示例 Markdown 状态为 `READY`；
- Agent 发布版本正确快照知识库标识，草稿 API 的 `status` 字段正常返回；
- SSE 顺序实测为 `run → sources → error`：`sources` 返回文件名、chunk 编号、内容和相似度；独立 8081 验收进程未设置 DeepSeek 密钥，因此模型阶段按设计返回明确 `error`；
- 删除文档后 MySQL 文档列表为空、本地文件不存在，重复检索不再返回 `sources`，随后重新上传恢复为 `READY`；
- 后端 `mvn test`：6 个测试通过，0 失败、0 错误；
- 前端 `npm run build`：TypeScript 检查与 Vite 生产构建通过。
- 浏览器冒烟检查：四个导航入口及新增知识库创建、文档上传控件均正常渲染；由于 5173 当时仍连接旧的 8080 后端进程，完整新接口交互改由独立 8081 实例完成。重启日常后端后前端即可使用新接口。

真实异常与修复：

- 引入 pgvector 数据源后，Flyway 和业务 JDBC 曾自动选择 PostgreSQL，导致 MySQL 表查询落到错误数据库；现已显式声明 primary MySQL DataSource/JdbcTemplate，并给四个业务仓储加限定绑定，向量仓储只使用 PostgreSQL。

当前限制：

- 内置 384 维向量为词法哈希基线，不是语义 embedding；
- 暂无跨存储分布式事务和失败补偿任务；
- 尚未实现工具调用、ReAct、审批、SSH 和 Coding 扩展。
