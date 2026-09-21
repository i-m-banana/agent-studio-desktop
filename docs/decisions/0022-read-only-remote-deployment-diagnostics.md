# ADR 0022：独立生产目标与只读部署诊断

## 状态

已接受；自动化验证及真实生产服务器五项正向诊断已通过。负向边界继续由自动化覆盖。

## 背景

受控远程工作台已经能浏览授权代码根并运行五种项目验证任务，但生产目录位于独立路径，不能通过把普通 `remoteRoot` 放宽到 `/root` 来获得访问。直接开放 Docker、Nginx 或 Shell 命令会破坏现有固定任务与一次性审批边界。

## 决策

Flyway V12 保存单一部署 Profile：本地源码根、远程部署根、远程备份根、Compose 文件和项目名、Nginx 配置相对路径及固定回环健康地址。SSH 主机、端口、用户名、密码凭据和固定 SHA-256 主机指纹只引用现有 SSH 工作区配置，不重复保存秘密。

完整审批目标可能超过 V5 原有的 160 字符限制。Flyway V13 将 `approval_request.target_environment` 扩展为 `VARCHAR(4096)`，使 SSH 身份、固定指纹、部署根、Compose 项目/文件和健康地址仍能完整绑定，而不是截断或减少审批信息。

新增 `inspect_remote_deployment`，标记为 `SSH/EXECUTE/HIGH`。模型参数只有 `COMPOSE_VALIDATE`、`COMPOSE_STATUS`、`NGINX_VALIDATE`、`SITE_HEALTH`、`RELEASE_FINGERPRINT` 五种枚举 task；不接受路径、命令、服务名、URL、参数或环境变量。批准目标绑定 SSH 身份/指纹、部署根、Compose 项目/文件和健康地址，批准后仍由 SafeExecutionGateway 二次检查当前目标。

每次诊断在同一短生命周期 SSH 会话中先通过 SFTP 逐级检查部署根、备份根、Compose、Dockerfile、app.jar、Nginx 配置、`data/uploads` 和 `.env` 类型，再建立 Exec Channel。`.env` 只对固定路径执行 `lstat`，不打开、不读取、不返回。命令由平台静态映射；30 秒内部时限，约 16 KB 首尾输出，stdout/stderr 合并，超时或中断关闭通道。非零退出码作为结果返回，连接、路径、标记、超时和通道错误才使工具失败。

## 后果

- 工作台“部署”页可保存并测试独立 Profile，显示固定服务、诊断卡和有界结果；
- 当前阶段不会执行 `up`、`restart`、`down`、`build`、reload、备份、发布或回滚；
- Docker/Nginx 命令仍只能由固定映射触发，不存在终端或任意参数入口；
- 固定任务会调用远程 Docker/HTTP 工具，因此必须在真实服务器人工验证兼容性后才能写“已人工验收”；
- 自动化通过不代表网站部署完成。
