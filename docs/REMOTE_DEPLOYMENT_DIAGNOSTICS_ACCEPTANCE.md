# 远程部署只读诊断人工验收

## 当前口径

V12、部署 Profile、工作台部署页及五项固定诊断已完成自动化验证。首次真实模型运行暴露审批目标超过 V5 的 160 字符字段上限，五项均在 SSH 执行前失败；V13 修复后，真实生产服务器五项正向链已由用户逐项批准并确认符合预期。可以表述为“远程部署五项只读诊断正向链已人工验收”，不得写成“已完成部署”。负向边界仍以自动化证据为主。

## 前置检查

1. 备份当前 Agent Studio 数据，并重启后端确认 Flyway V13 成功；
2. 确认普通 SSH 工作区仍为受限代码根，不要改成 `/root`；
3. 在“远程工作台 → 部署”填写真实生产目录、备份目录、Compose 文件/项目、Nginx 配置相对路径和服务器回环健康地址；
4. 确认部署根内的 Compose、Dockerfile、`app.jar`、Nginx 配置和 `.env` 是普通文件，`data/uploads` 是目录，备份根存在；
5. 发布包含 `inspect_remote_deployment` 的 AgentVersion。

## Profile 与安全边界

1. 保存 Profile 后点击“只读检查目标”，确认状态变为 READY；
2. 将部署根、备份根或 Nginx 路径临时改成符号链接/不存在路径，确认检查失败；恢复正确配置；
3. 确认页面和运行输出没有 `.env` 内容、密码、Token 或数据库凭据；
4. 确认普通远程文件树仍不能浏览生产根和 `.env`；
5. 确认工具 Schema 只有 task，没有命令、path、URL、服务名、参数或环境变量。

## 五项只读诊断

逐项请求并批准一次：

1. `COMPOSE_VALIDATE`：Compose 配置语法通过，输出不包含展开后的环境配置；
2. `COMPOSE_STATUS`：只显示固定的 nginx、app、mysql、phpmyadmin；
3. `NGINX_VALIDATE`：返回 Nginx 语法检查结果，不 reload；
4. `SITE_HEALTH`：只访问 Profile 中固定的 `127.0.0.1` 地址；
5. `RELEASE_FINGERPRINT`：只返回 app.jar、Dockerfile、Compose 和 Nginx 配置的摘要、大小及修改时间。

每次确认审批卡目标包含 SSH 身份/指纹、部署根、Compose 项目/文件和健康地址；运行结果包含 task、target、deploymentRoot、composeProject、successful、exitCode、durationMs、output、outputTruncated。

## 负向验收

1. 拒绝一次审批，确认未启动 Exec Channel；
2. 审批等待期间修改部署 Profile，再批准旧请求，确认记录 `TOOL_TARGET_CHANGED/REJECTED` 且没有 `TOOL_EXECUTION_STARTED`；
3. 让固定诊断产生非零退出码，确认它作为 completed 工具结果返回；
4. 尝试让模型提供 `docker compose down`、自定义服务、URL 或参数，确认 Schema/工具拒绝；
5. 确认界面不存在 `up/restart/down/build/reload`、备份、发布、回滚和任意 Shell 入口。

## 自动化证据

- 后端全量：71 个测试通过，0 失败、0 错误、0 跳过；
- Flyway：空库 V1–V13 迁移通过，长审批目标（超过 160 字符）可写入；
- 前端：TypeScript 与 Vite 生产构建通过，29 个模块；
- 真实生产服务器/模型：首次五项运行均在审批落库阶段失败，失败时未建立 SSH/Exec Channel；V13 修复后的正向复测全部通过。

## 真实正向证据（2026-09-21）

- Compose 校验：`8c0d0ba0-9427-44c7-8ee8-454406ef5a94`，退出码 0，720 ms；
- 固定服务状态：`aea5d640-8a0c-4649-b814-5721094d911e`，退出码 0，318 ms，app/mysql/nginx/phpmyadmin 均在运行；
- Nginx 校验：`4d486b06-6735-4f85-a5d3-1f2c7035d1e0`，退出码 0，303 ms，配置语法检查通过；
- 站点健康：`ec8f2e73-3698-4c9c-875a-d7b6908f134b`，退出码 0，117 ms，返回 HTTP 200；
- 发布指纹：`2c7f32d1-88a6-4c08-bc7b-39911969cfb2`，退出码 0，214 ms，返回 app.jar、Dockerfile、compose.yml 和 nginx.conf 的 SHA-256、大小及修改时间；
- 五次结果均为 `successful=true`、`outputTruncated=false`，并具备完整的一次性 HIGH 审批和执行审计链。
