# ADR 0025：业务级不可变发布候选工作流

## 状态

已实现，自动化通过；2026-09-28 真实模型与真实服务器候选准备正向链路已人工验收（运行 `c37e9338-8fd1-4357-9fa5-734961cbde40`）。独立服务器检查及真实失败场景尚未全部验收，生产部署未实现。

## 决策

发布准备对模型只暴露一个 `prepare_release_candidate` 工具，而不是分别暴露 Maven 测试、打包、查找 jar、计算摘要和上传等技术步骤。

该工具不接受模型参数。平台从远程部署 Profile 读取固定本地源码根、本地 Compose 文件及 SSH 目标，并按以下顺序执行：

1. 校验本地源码根以及 `pom.xml`、`Dockerfile`、本地 Compose 文件、Nginx 配置均为安全普通文件；
2. 执行固定 `mvn -B --no-transfer-progress clean test`；
3. 执行固定 `mvn -B --no-transfer-progress package -DskipTests`；
4. 只接受 `target/app.jar`，不扫描目录、不比较修改时间，也不使用源码根已有的 `app.jar`；
5. 由平台生成 `releaseId`，在 `<remoteDeployRoot>-releases/<releaseId>` 新建不可覆盖目录；
6. 通过同一条经固定主机指纹验证的 SSH 会话上传 `app.jar`、`Dockerfile`、标准化命名的 `compose.yml`、`nginx.conf`、manifest 与摘要清单；
7. 在远端运行固定校验命令，核对文件类型、摘要、制品大小、releaseId 和候选目录。

## 安全边界

- 工具标记为 `SSH / WRITE / HIGH`，继续经过 ToolRegistry、AgentVersion、SafeExecutionGateway、一次性 ApprovalRequest、RunStep 与 AuditEvent。
- 模型不能提供路径、制品名、命令、参数、环境变量、版本号、覆盖或部署选项。
- 本地子进程使用清理后的环境、固定超时、有限输出和进程树终止。
- 远程上传使用 SFTP 独占创建；已有候选目录或文件不会被覆盖。
- 不上传 `.env`、数据库和 uploads，不构建 Docker 镜像，不修改生产目录，不启动或重启服务。
- Maven 非零退出码与远程校验非零退出码作为可审计结果返回；连接、超时、安全校验与回执校验失败作为工具失败。

## 配置决策

本地项目使用 `docker-compose.yml`，生产目录使用 `compose.yml`。V14 将 `localComposeFile` 与原有远程 `composeFile` 分开，避免模型或平台根据文件名猜测。

## 开发锚点

计划外实验前的稳定提交为 `d8526c9b8e76c5dee9eb69c6f6e16ec1b223f975`，标签为 `anchor-remote-ops-before-release-workflow`；实验分支为 `feat/release-candidate-workflow`。
