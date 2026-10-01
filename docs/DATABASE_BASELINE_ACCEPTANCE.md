# 受审数据库基线登记验收

2026-10-01 最新结果：真实登记8a868b4f-cbc1-4fec-8609-0376398794a2已成功，baselineRegistered=true、版本1、exitCode0；后续多次只读状态均返回["1","BASELINE",1]，包括上线前f7d1a7d7-21b5-4032-8bd2-01429961df86。完整应用上线亦通过，见CONTROLLED_RELEASE_ACCEPTANCE.md。不要重复登记；下方历史表缺失是较早失败记录，不是当前状态。

面向用户的中文逐步验收表、失败处理和产物说明见 [六步数据库基线准备操作单](BASELINE_WORKFLOW_USER_GUIDE.md)。2026-10-01 的真实状态查询仍返回目标历史表不存在；查询执行完成并不代表基线已登记。

2026-09-30 实际失败补充：一次已审批登记返回 `ClassNotFoundException: org.springframework.boot.loader.launch.PropertiesLauncher`；随后只读 `DATABASE_BASELINE_STATUS` 返回 `old_things.flyway_schema_history` 不存在。此结果表示基线登记未成功，但不替代完整数据库审计。平台现修复候选镜像构建上下文 JAR 权限，并在镜像成功回执前增加非 root、无网络启动自检。下一次请先重新构建同一候选取得新 imageId，再创建30分钟内的新备份、复查结构摘要与状态，然后把新镜像、新备份等六项真实值绑定审批；旧 imageId/已过期 backupId 不可继续使用。生产登记操作须由用户确认并批准，开发者不代为重试。

2026-09-29固定任务触发修复：点击诊断/备份/候选/镜像/基线按钮，通过现有会话提交明确工具与参数，不再依赖模型选择是否调用。预期运行步骤先有USER_TOOL_REQUEST，再TOOL_CALL和审批；批准后出现真实TOOL_RESULT及原始工具回执。拒绝显示“工具未执行”，失败原样展示，不由模型改写。自然语言明确请求工具但模型没有执行时，显示EXECUTION_EVIDENCE_CHECK/任务未执行。历史模型声称agentstudio已有基线或deploy认证失败的两个无工具运行不是验收证据，不应修改数据库凭据。仍需绑定当前Agent版本及一次性审批；不能由自动化测试代替真实登记验收。

当前仅自动化通过，真实生产登记尚未验收。这次会创建Flyway历史表和版本1记录，短暂阻止业务表写入；不是只读任务，也不是发版。

## 操作顺序

1. Agent Builder 勾选并发布：准备不可变发布候选（prepare_release_candidate）、构建候选应用镜像（build_release_candidate_image）、创建受控远程发布前备份（prepare_remote_deployment_backup）、检查受控远程部署（inspect_remote_deployment）、登记受审数据库基线（adopt_remote_database_baseline）。不需要移除其他工具。
2. 在远程工作台“部署”页重新请求**准备候选**，然后**构建镜像**。旧成功候选20260928T085656Z-31986997不包含维护入口，不能用；新候选须来自当前D:/idea_work/shiguangxv源码。每次分别HIGH审批，留在当前工作台上下文。
3. 镜像成功后请求**发布前备份**，必须30分钟内，部署目标保持不变；保留旧备份。
4. 请求**数据库结构核查（DATABASE_SCHEMA）**。此前已验收摘要fcf08a91fe023fa236335c7c1eb38030bcee837960a4e9b8b05ec5cf7ef8b949；若改变先调查，不能假定永不变。登记时还会重查。
5. **登记受审数据库基线**卡自动从当前页面的镜像/备份/结构结果填六项JSON，可从原始结果人工补齐，不让模型猜测。核对候选、镜像、结构、备份均属当前目标，确认无其他DDL，点击**请求登记基线**，核对HIGH审批卡后批准一次。
6. 预期successful=true、exitCode=0、baselineRegistered=true、baselineVersion=1、servicesRestarted=false。业务表和内容保持，新增历史表。不要删历史，不执行V1建表，也不要重复登记。
7. 请求**数据库基线状态（DATABASE_BASELINE_STATUS）**，预期仅 `["1","BASELINE",1]`。再请求**站点健康（SITE_HEALTH）**，在浏览器检查首页、浏览、登录等原业务。
8. 提供运行ID或告知成功，由开发者核对RunStep/AuditEvent并记录真实验收。此时尚未切换应用，整个发版/回滚链路仍未完成。

## 失败与产物

非零、超时、断线、清理未确认、缺少回执，均不代表什么都没做。先只读检查基线状态并提供运行ID，不立即重试；若已有历史则停止登记，不自动删除或restore数据库。

生产新增候选目录下baseline-UUID尝试目录、空Docker配置目录/容器启动标记；正常回收临时凭据和容器。候选、镜像、备份仍保留，不prune、不通配符清理。异常残留须按具体尝试名核对；数据库正常只新增历史表。桌面保留审批、RunStep、AuditEvent。

网站隔离MySQL套件包含正向登记/保留行、漂移/默认值/重复拒绝、锁释放及原业务回归。平台包含HIGH/六项绑定、同SSH会话、备份摘要/过期/结构漂移、非零未知状态、超时关闭；真实POSIX+模拟Docker/文件锁测试验证材料、退出码和凭据清理，不据此宣称真实服务器已登记。
