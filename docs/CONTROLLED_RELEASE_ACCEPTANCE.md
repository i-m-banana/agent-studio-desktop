# 受审应用上线：真实正向验收（2026-10-01）

用户完成全链路后反馈“感觉没什么问题”。开发者仅只读核对本机持久化 RunStep 与 AuditEvent，没有另行连接生产执行命令或删除任何产物。时间均为 UTC（北京时间 +8 小时）。本次验证的是应用发布正向链路，不是生产故障演练或数据库灾难恢复。

用户进一步确认：这是首次完成全链路前向执行。版本里程碑提交为 `b0f37b2 feat: complete approved application release and recovery workflow`，分支 `feat/release-candidate-workflow`，本地已提交、未推送；较早开发锚点 `83314bb` 保留。网站源码目录不是 Git 仓库，平台提交不包含其源码；用户已有网站副本备份，网站 HANDOFF/VERIFICATION 已同步事实。manual-acceptance 与个人使用问题记录不纳入提交。后续一键上线是待设计/开发事项，不属于本次已验收能力。

## 原始记录索引

| 环节 | 运行 ID | 核对结果 |
|---|---|---|
| 候选准备 | 82ace955-7ea2-4e64-b4b4-0b1097130761 | REMOTE_VERIFY、successful=true、exitCode=0 |
| 镜像构建 | 68a9c386-23c2-4e79-86a1-d90575858f27 | IMAGE_READY、imageBuilt=true、142840ms；RUNTIME_SMOKE 与构建器清理完成 |
| 本次备份 | 07980a13-7e4f-4206-be3a-8b8e2a943c4a | successful=true、exitCode=0；备份创建于11:41:43Z |
| 当前结构 | e515e210-dae1-4326-b12f-14d13c601e4d | successful=true、exitCode=0；结构摘要与上线审批一致 |
| 版本历史 | f7d1a7d7-21b5-4032-8bd2-01429961df86 | successful=true、exitCode=0、["1","BASELINE",1] |
| 上线前身份 | 52b9765b-97a7-48ee-b001-0a86aa0c7da0 | 当前旧镜像及组合文件指纹与上线审批一致，appHealth=healthy |
| 真正上线 | 2c8c8581-43b4-4c80-9ee6-6e2065272ec2 | DEPLOYED、successful=true、exitCode=0、51040ms |
| 上线后站点健康 | 5958c8bf-ceef-4b67-a6c6-702871063238 | 11:45:06Z 发起，successful=true、exitCode=0、HTTP 200 |

上述任务均有一次 APPROVED 步骤。真正上线审计含参数校验、要求审批、批准、一次 TOOL_EXECUTION_STARTED 与一次 TOOL_EXECUTION_COMPLETED，没有失败事件。批准时间11:44:04.541826Z，完成11:44:55.604560Z；不能把会话 COMPLETED 单独当作成功证据。

## 本次八项批准身份

```json
{
  "releaseId": "20261001T102858Z-61f00ca1",
  "manifestSha256": "73c17ed1b5e5c8af5b9f8718a8f2e08b9533a26d8be26d6219938f17a4b93faf",
  "imageId": "sha256:6730311554459e34085f799a788098d45508eadf9a367c25e52e1272847b4918",
  "schemaSha256": "f4436725739c1881c3dbdda056ae2191b03355613f002941b60e6a1c7dbbb8df",
  "backupId": "20261001T114143Z-1612e900",
  "backupManifestSha256": "9d457cac5be977f882f34e59ddcf9091b19b1fe0139eac60163a1bd158378500",
  "previousImageId": "sha256:05bd35ca94d8dbc8785db55e781d9a8189acb65facab033c0cca4b4607b11b35",
  "productionSha256": "bebf41539f20be82c8fb87bbb519a8e658f5afd90f15e1a43fd368021aca4e89"
}
```

目标：`root@117.72.84.129:22/root/opt/old-things`。AgentVersion：`d23e15ed-0305-4369-9cfe-a34952626ef8`；审批参数摘要：`998e02cabc20345861e36676ef2efed41e288024dbb10ed44dc27fe37f8182be`。

上线回执：deployed=true、rolledBack=false、manualInterventionRequired=false、outputTruncated=false；currentImageId 等于批准的新 imageId，且与旧镜像不同。本次 migrationsExecuted=0、databaseMayHaveChanged=false：检查了版本与兼容性，没有待执行迁移，不应宣称本次生产验证了扩展迁移。productionSha256 是批准的**上线前**指纹，不能误解为上线后文件摘要。

远程尝试目录：`/root/opt/old-things-releases/20261001T102858Z-61f00ca1/publish-2a981e6ef7db4e8290e3d9dbb3572f3d`。按固定脚本成功条件，DEPLOYED 在镜像/容器健康、首页与 /browse、制品同步及再次健康验证后发出。另有独立后置 SITE_HEALTH 的 HTTP200；记录中尚无上线后的独立 RELEASE_STATUS，不虚构其运行或上线后组合指纹。

## 结论与仍未覆盖的范围

正向“准备候选 → 构建 → 近期备份 → 身份/结构/历史核查 → 审批上线 → 健康验收”已真实通过。用户反馈未发现问题；具体登录、上传等逐项业务行为未提供独立记录，不标记为逐项验收通过。

健康失败回退、回退失败、迁移失败等有自动化证据，尚未在真实生产故意注入故障；MySQL DDL 不随应用回退撤销，硬崩溃/SSH失联仍需人工核查。所有备份保留，不自动清理历史候选/镜像/尝试记录。本次没有执行任何清理。

本轮代码自动化结果见 VERIFICATION：后端147项、前端19项及生产构建、网站21项（含隔离MySQL）通过。此处仅补记真实运行证据，不重复触发上线。
