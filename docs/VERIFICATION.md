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

## 阶段 4：Qwen 语义向量生产升级（2026-09-02）

已实现：

- 新增 `EmbeddingGateway`，默认实现为 Ollama `qwen3-embedding:0.6b`，1024 维；LocalHash 作为环境变量可切换的回退；
- 新增版本化 `knowledge_chunk_v2`，保存模型和 `qwen3-0.6b-v1` 索引版本，旧 384 维表未删除；
- 新增知识库重建 API 和前端“使用当前模型重建索引”入口；
- Qwen 索引采用按知识库和版本过滤的精确 cosine 搜索，暂不使用 HNSW；
- 删除文档会同时清理旧、新两套向量表，重建失败只清理当前索引版本。

验证结果：

- 后端 `mvn test`：8 个测试通过，包括 Ollama 批量文档 embedding、查询前缀隔离和维度校验；
- 前端 `npm run build`：通过；
- 最终冻结语料（11 份文档、23 个 chunk，SHA-256 `9de7e241c68f44161bd9c20dae69df79134c5aa2120d6fc0f1795fa818a8a3d0`）：Qwen Recall@5 100%、MRR 0.8000、改写题 MRR 0.7250、p95 358.656 ms；同语料 LocalHash 分别为 83.33%、0.6424、0.5486 和 0.234 ms；
- 真实 MySQL、pgvector 和 Ollama：知识库重建成功，`knowledge_chunk_v2` 实际保存 `qwen3-embedding:0.6b`、1024 维、`qwen3-0.6b-v1`；
- 真实 SSE：改写问题命中正确来源，最终无查询前缀配置下 score 约 0.40；独立 8081 进程未设置 DeepSeek 密钥，随后按预期返回模型环境变量错误。

消融与限制：

- 在知识包扩充前的同语料消融中，加入统一中文 query instruction 后 MRR 从 0.8160 降到 0.6917，因此默认关闭、保留可配置；
- 知识包内容变化会改变 chunk 数和指标，扩充后已同时重跑 LocalHash 与 Qwen，生产报告不混用历史语料结果；
- Qwen 的 Recall 和延迟达到约定目标；MRR 达到 0.8000 边界但未严格超过 `> 0.80`，因此保留 rerank 优化项；
- 2026-09-04 复现并修复 Ollama 进程继承空 `D:\ollama-models` 导致的索引失败：用已有模型目录启动后，`/api/embed` 返回 1024 维向量，失败文档通过重建 API 恢复为 READY；后端新增可操作的连接错误和完整异常日志；
- 无答案误召回率仍为 100%，固定阈值无法在当前题集上同时维持高召回和可靠拒答；
- 尚未进行结构感知切块、混合检索或 rerank，这些留作独立评测，不与 embedding 收益混在同一次改造中。
