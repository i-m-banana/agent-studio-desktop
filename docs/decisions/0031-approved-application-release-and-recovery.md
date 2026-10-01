# ADR 0031：受审应用上线与保守恢复

2026-10-01，状态：本地自动化与真实生产正向上线验收通过；真实故障恢复尚未演练。开发锚点：feat/release-candidate-workflow / 83314bb。验收运行、身份与审批审计见 CONTROLLED_RELEASE_ACCEPTANCE.md。

六步基线准备不发布网站。增加单一业务工具 publish_remote_release，八项身份绑定候选、镜像、结构、新备份、当前生产镜像及组合文件指纹。沿用 ToolRegistry、SafeExecutionGateway、不可变 AgentVersion、ApprovalRequest、RunStep、AuditEvent；固定任务入口仅解析展示/持久化真实结果，不让模型伪造执行或选择命令。

在同一固定指纹SSH/SFTP会话独占新建发布尝试目录和脚本。服务器使用与基线相同锁，校验材料与全备份、标签、时效、生产指纹及健康；仅允许应用发布，不隐式迁移Compose网络/卷或代理配置。镜像ID明确，固定本地app标签须映射被批准的前一运行镜像；禁止pull/build/down。只重建app，固定Nginx校验和reload以重新解析app地址，验证容器实际imageId、health、固定首页及/browse。之后原子替换生产app.jar和Dockerfile；失败恢复原标签、原材料、原app，并再次核对健康及指纹。

数据库维护入口位于批准的网站候选JAR，凭据为有界stdin，不启动业务服务。先验证显式版本1基线及已执行迁移校验和，再检查全部pending版本化SQL，限于扩展式语法，执行Flyway迁移、严格后验校验、旧结构集合保留及候选Hibernate validate。仅预校验允许pending；不忽略failed/missing/future/checksum差异，不自动baseline/repair/clean，不跳过被拒绝的迁移。危险收缩、数据改写及非SQL迁移仍需独立流程，不宣称它们可自动回滚。

MySQL DDL不与应用切换组成ACID事务。失败保守返回数据库可能改变；应用回退不撤销兼容扩展。迁移失败不切换应用，输出要求人工检查。SSH失联、缺少回执、外层timeout等不声称已恢复；远端trap有独立限时恢复预算，最后强杀仅作为上界。进程/主机硬崩溃不能保证trap完成，恢复必须依据实际线上状态和持久化回执，不能重放旧审批。此方案不是通用数据库灾难恢复或无停机蓝绿发布。

官方依据：[Compose up](https://docs.docker.com/reference/cli/docker/compose/up/) 的 no-deps/no-build/pull控制；[Flyway validate](https://documentation.red-gate.com/flyway/reference/commands/validate) 与 [callbacks](https://documentation.red-gate.com/flyway/flyway-concepts/callbacks)。这些文档只支持工具语义；本项目恢复成功必须以实际镜像、文件和健康证据确认。
