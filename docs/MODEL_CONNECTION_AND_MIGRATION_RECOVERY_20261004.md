# 模型连接与迁移文件创建补修

## 实际中断

运行 dfbb81d4-8b21-4152-9bda-c82bed3d37f3（会话 cd04eb4e-cabd-4d05-83c8-eab4b8cb75a7）在 2026-10-04 21:25:46 至 21:40:07 执行了 207 步，最终请求下一次模型响应时发生 HTTP connect timed out；不是工具预算或总时限耗尽。

实际成功写入：Item.java、CurrentSchemaVerifier.java、ItemApiController.java，以及 src/test/resources/probe.txt 和 src/main/resources/db/migration/mysql/probe.txt。Java 21 项测试通过发生在这些写入之前，不能当作当前改动验证。MySQL/H2 的 V2__add_content_year.sql 都因平台不支持新建 SQL 而未创建；没有正式迁移成功回执。未自动撤销、删除或重复写入业务文件。

## 平台修复

- 模型工具规划请求只对 HttpConnectTimeoutException、ConnectException 做最多三次连接尝试。响应超时、HTTP 错误、工具失败不自动重跑；停止与总时限继续有效。重连用同一份已有工具结果，不派发旧工具或复用批准。
- 重连事件实时显示在任务反馈与运行记录。三次均失败后保存真实写入清单、Java 测试是否早于后续写入、下一步与审批要求，同时写入原会话；不会把中断写成完成。
- 新建 SQL 仅限 src/main/resources/db/migration/mysql 或 h2 下 V正整数__名称.sql，以及 src/test/resources SQL 夹具。前置校验拒绝其他 SQL 路径，避免弹出无效审批。父目录须已存在，CREATE_NEW 不覆盖，目录身份与项目授权仍在批准后重验，SQL 内容不会由创建工具执行。未开放 D 盘或生产目录。

## 原会话继续方式

刷新页面后发送：继续第一批。先核对已写入文件和探测文件，再补齐正式 MySQL/H2 迁移及对应测试；不重复已有写入，不降低测试要求，不进入第二批，也不上线。所有新写入与验证仍逐项审批。

本次修复不实现网站年份功能、不改生产或网站迁移策略。平台检查使用独立测试夹具，业务改动需要由 Agent Studio 后续实际验证。

## 验证与生效

- 后端相关回归 47 项通过，0 失败、0 错误、0 跳过，包含连接失败重连、工具不重复执行、响应超时不重试、中断进度保存、SQL 路径授权与审批前拒绝。
- 前端 50 项检查通过，TypeScript/Vite 构建通过；重连状态能在任务反馈中显示。
- 2026-10-04 已更新本机 Agent Studio 后端；启动前确认无活动任务或预览，更新后健康状态为 UP。刷新页面即可使用修复，历史失败记录继续保留。
