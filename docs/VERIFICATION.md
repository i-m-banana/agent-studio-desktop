# 验证记录

## 阶段 0：工程基线（2026-09-02）

验证结果：

- 后端 `mvn test`：通过，2 个测试、0 失败、0 错误；
- 后端真实启动：通过，Spring Boot 3.4.3 使用 Java 21 启动于 8080；
- `GET /api/system/status`：通过，返回 `application=agent-studio-backend`、`status=UP`；
- 前端 `npm run build`：通过，TypeScript 检查与 Vite 生产构建完成；
- `docker compose -f docker/compose.yml config`：通过；
- MySQL 与 pgvector 容器实启：未验证，原因见环境事实。

环境事实：

- Maven 3.9.11 使用 JDK 21.0.4；
- 系统 `java` 命令当前指向 Java 8，但 Maven 使用已配置的 Java 21；
- Node.js 22.13.1，npm 10.9.2；
- Docker CLI 29.2.0、Compose 5.0.2；当前开发会话无权访问 Docker Desktop 引擎，因此容器运行状态待在可访问引擎的环境验证。

真实异常与修复：

- 初次后端构建因依赖尚未下载且沙箱禁止联网而失败；授权下载后测试与启动均通过；
- 初次前端构建因 `tsconfig.node.json` 的 `allowImportingTsExtensions` 缺少 `noEmit` 而失败；补充 `noEmit: true` 后构建通过。

本阶段所有业务骨架均为本次新增，未迁移来源项目业务代码。
