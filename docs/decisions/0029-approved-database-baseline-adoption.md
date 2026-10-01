# ADR 0029：受审数据库基线登记

## 状态

已实现，自动化结果见 VERIFICATION。真实基线登记尚未验收，不宣称发布链路完成。

## 决策

“登记受审数据库基线 / adopt_remote_database_baseline” 为 SSH/WRITE/HIGH、210秒业务工具，六项参数绑定 releaseId、manifestSha256、imageId、schemaSha256、backupId、backupManifestSha256。固定版本1，不允许SQL、版本、路径、凭据或命令。复用Gateway、参数绑定一次性审批、AgentVersion、RunStep、AuditEvent，不增执行API/MCP。审批目标含备份根和短读锁影响。

同固定指纹SSH会话执行SFTP候选/备份祖先及文件校验、绑定清单摘要、备份身份和30分钟时效、数据库结构摘要复核。固定远端脚本再次校验候选四份材料、备份九项SHA（只允许固定九个文件名、拒绝重复项）、gzip有效性、时效与当前生产五份配置/制品。只用已有sha256镜像ID，校验候选/清单标签；禁止拉取。完整性不等于数据库导入恢复演练成功。

维护入口包含在新网站候选中，Boot PropertiesLauncher启动专用main，而非网站HTTP启动流程。一个独占、所有者标记的临时容器接入固定Compose internal网络，不开放端口、不挂业务卷或Docker socket。非root、只读根、cap-drop/no-new-privileges、512MiB/0.5CPU、60秒。凭据从既有mysql容器取出，保存在0700尝试目录中的0600临时文件，经NUL分隔stdin传入，不进入命令行、模型、新容器环境或输出。Compose自身可能按正常规则加载宿主.env，平台不打开/返回其内容。

网站维护代码获得八张业务表READ锁，登记期间写入可能短暂等待。锁等待5秒；再次采集同一元数据SQL与摘要，检查44字段类型/可空性/自增、字符集、表类型、主键、唯一和四组外键及规则；拒绝非空默认值、生成列、表达式/前缀索引、视图、触发器、例程等未批准结构，补充Hibernate validate。约束名字和列物理顺序可不同但仍进入摘要。只调用Flyway baseline()，不migrate/repair/clean，不执行V1或写业务数据。

MySQL START TRANSACTION会隐式释放LOCK TABLES，锁内采集明确省略开始/提交事务，锁连接维持到Flyway独立连接完成历史登记，再finally释放。外部DDL不遵循平台文件锁，新表等命名空间操作仍须人工避免；不在其他管理员进行DDL时登记。异常不自动删除历史或重试。

远端EXIT/信号trap删除本次凭据，并仅清理匹配owner标签的临时容器；清理失败不伪装成功，原失败码保留。SSH超时/中断关闭通道，远端timeout为第二边界；主机/网络故障仍可能残留受保护文件/容器，不能保证任何故障下均回收。不删候选、备份、生产容器或卷。

成功回执确认版本1登记，失败结果baselineRegistered=null、databaseHistoryMayHaveChanged=true；超时/断线/无回执也不能认定未写入。新增只读DATABASE_BASELINE_STATUS只返回历史版本、类型、成功状态，异常先查、不盲重试。登记仅新增历史表/记录，不重启或切换网站；后续发布仍需新备份和单独验收。
