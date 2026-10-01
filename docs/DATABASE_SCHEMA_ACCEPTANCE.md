# 数据库结构核查验收

2026-09-29 正向真实验收通过：运行35dff08a-dbf9-4e1e-ac92-a5ebf65a5f90，exitCode=0、668ms、完整未截断，审批/开始/完成审计完整。采集old_things/MySQL8.0.46的8表、44列、主键/唯一/4组外键，无历史表。结构SHA为fcf08a91fe023fa236335c7c1eb38030bcee837960a4e9b8b05ec5cf7ef8b949，未登记基线。下述负向场景仍主要是自动化证据；受审基线登记仅自动化通过，生产发布未开放。

## 操作

1. 在远程工作台选择已发布且包含 **检查受控远程部署 / inspect_remote_deployment** 的 AgentVersion；无需新增工具或取消其他能力。
2. 部署 Profile 保持生产目标，在“部署”页点击 **数据库结构核查 / DATABASE_SCHEMA** 的“请求诊断”。也可发送：

   请只使用 inspect_remote_deployment 工具，参数严格为 {"task":"DATABASE_SCHEMA"}。只采集固定生产数据库结构，不得提供 SQL、数据库名、路径、命令、环境变量或登记基线选项。

3. 在一次性 HIGH 审批卡核对 SSH 指纹、生产根与固定 task 后批准；拒绝时应不执行。
4. 运行记录工具结果预期 successful=true、exitCode=0、schemaComplete=true、outputTruncated=false、schemaFormat=schema-metadata-v1、64 位 schemaSha256、databaseModified=false、baselineRegistered=false。这些布尔值说明固定任务没有数据库写入流程，不是对第三方并发变更的监控证明。
5. output 是一行一条 JSON 元数据。当前网站预期业务表为 users/categories/items/item_tags/comments/favorites/likes/events。请不要删除不一致的表/字段，也不要执行本地 V1；把运行 ID 发给开发者继续核对。若出现额外表、旧字段或已存在 flyway_schema_history，需先调查，不会自动接管。
6. 结果不应出现密码、.env 内容、用户/评论等业务数据；错误时读取运行记录及审计，不根据模型总结断言数据库已变更。超时/非零/截断/不完整结果不能用于下一阶段基线登记。

该任务不会写业务表、创建 Flyway 历史、启动新候选或重启网站；查询会占用少量数据库资源，不能称为完全零影响。验收期间避免主动进行 DDL。完成后只需告诉我运行成功或给出运行 ID，不用手工复制长摘要。

## 自动化

默认后端全量包含固定映射、HIGH 注册、同一 SSH 会话、成功摘要、非零、超时关闭、截断无摘要、无效协议、额外 SQL 拒绝及 UTF-8 缓存边界。

独立本地 MySQL 8.0（无生产连接）：在 backend 运行 `mvn '-Dtest=RemoteDatabaseSchemaMySqlIT' test`。创建本次 UUID 命名、无端口暴露的临时容器；只查询固定 fixture。验证索引/外键规则真实查询成功、密码/行值/默认值不泄露、数据变更摘要不变、DDL 变更摘要变化、无历史表创建且行保留。finally 只删除本次测试容器及其匿名卷，镜像缓存保留，不全局 prune。该套件必须显式执行，普通 mvn test 不会自动运行 IT，Docker 不可用时不假装通过。
