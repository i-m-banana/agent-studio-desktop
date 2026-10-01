# 验证记录

## 2026-10-01：完整应用上线正向人工验收通过

提交前复验：后端 `mvn -o test` 20:00:40完成147项，0失败/错误/跳过；前端19项测试通过，TypeScript/Vite生产构建286模块、4.08秒通过。首次受限执行无法读取用户Maven缓存，获准后使用相同离线命令通过；不是代码测试失败，也未下载新依赖。网站本轮只补交接文档，沿用下节已通过的21项验证。

用户报告全链路完成且无明显问题；只读核对持久化运行、审批和审计，完整身份及运行索引见 [CONTROLLED_RELEASE_ACCEPTANCE.md](CONTROLLED_RELEASE_ACCEPTANCE.md)。发布运行2c8c8581-43b4-4c80-9ee6-6e2065272ec2：HIGH一次批准，SafeExecutionGateway一次开始/完成，DEPLOYED、successful=true、exitCode=0、deployed=true、rolledBack=false、manualInterventionRequired=false，51040ms；实际新镜像67303115…与旧镜像05bd35ca…不同。后置健康5958c8bf-ceef-4b67-a6c6-702871063238返回HTTP200。本次迁移数0，没有生产扩展迁移或真实故障回退演练证据，也没有上线后独立RELEASE_STATUS记录。本次仅补文档，不触发服务器任务，不清理任何备份/候选/镜像。下方“生产待验收/本轮未提交”等是此次用户验收前的历史记录，以本节为最新里程碑。

## 2026-10-01：受审应用上线与失败恢复（生产待验收）

本轮开始时Git节点：feat/release-candidate-workflow，HEAD 83314bb；已有基线/镜像/回执/界面未提交改动保留，manual-acceptance未修改或暂存。本地新增publish_remote_release及RELEASE_STATUS，无新执行旁路；同一固定指纹SSH/SFTP会话独占脚本，八项身份、生产组合摘要、新备份、固定app镜像标签及共享锁，兼容迁移与应用切换/健康/失败恢复串联。前端分开准备与真正上线，自动绑定证据并阻止过期/混用；失败、已恢复、未知状态不伪报上线成功。完整操作单CONTROLLED_RELEASE_USER_GUIDE.md、ADR0031、知识包31已同步。

后端最终 `mvn -o test` 于14:55:12通过147项，0失败/错误/跳过：真实POSIX生成脚本+模拟Docker边界覆盖成功/健康失败恢复/恢复失败/迁移失败/校验篡改/备份过期/旧候选维护入口缺失；MINA进程内固定指纹同会话、脚本独占上传、非零恢复回执、超时未知状态、危险参数、线上身份解析；真实Registry/Gateway/Approval/Audit/SSE覆盖新工具批准仅执行一次、拒绝零执行、危险SQL字段审批前拒绝且无模型改写；普通预算/RAG/MCP等全量回归仍通过。初次回归中旧“所有诊断不得包含.env”断言与组合摘要不符，仅RELEASE_STATUS允许在服务器侧计算五文件的组合摘要，不返回.env内容或单独摘要；其余诊断规则保留。

前端最终19项测试通过，TypeScript/Vite生产构建286模块通过（14:50，4.91秒）。上线成功必须deployed=true、rolledBack=false、manualInterventionRequired=false且successful/exitCode正常；恢复或未知不能标绿。新候选身份和旧生产身份不同，备份30分钟，结构/历史/生产身份5分钟有效；失败后要求新的只读证据。尚未进行真实浏览器布局人工验收，不以构建成功替代UI验收。

网站最终 `mvn -o verify -Pmysql-verification` 于14:54:21通过：常规11项+隔离MySQL10项，0失败/错误/跳过，target/app.jar重新打包。新增无迁移校验、兼容扩展字段/普通索引/新表并保留原业务读写、危险DDL及漂移拒绝；保留原基线维护和页面回归。首次pending迁移被Flyway.validate拒绝，修正为前验仅允许pending并逐一审查，迁移后无忽略规则严格validate，不忽略失败/丢失/未来/校验和差异，不省略迁移；候选JAR新维护入口凭据stdin、DDL锁等待5秒、旧契约保留+Hibernate validate，无HTTP入口。测试容器由Testcontainers回收，缓存保留，不全局prune。

用户上一轮的真实成功基线运行8a868b4f-cbc1-4fec-8609-0376398794a2（baselineRegistered=true、版本1、exitCode0），后置状态170bc568-20d5-4b6e-a956-4e993b2852e5返回["1","BASELINE",1]，健康9c586e58-b010-4d45-a413-9a1c34aa67d1返回HTTP200，说明前六步确已完成；不等于新上线工具经过生产验收。本轮没有连接生产SSH执行命令，没有迁移或切换生产，没有删除任何备份/候选/镜像，没有提交Git。真实上线/故障恢复须另行用户HIGH审批验收。MySQL DDL不自动回滚，硬崩溃/SSH失联不能保证恢复，未知状态必须人工核查。

网站实际打包JAR用JDK21启动DatabaseReleaseMain、固定invalid-sha，返回MIGRATION_NOT_CONFIRMED=IllegalArgumentException及exit1，证明启动类/打包可读取且在stdin/数据库访问前受控拒绝。核实本地无运行中任务及项目launcher后重启本地后端/前端（保留平台数据库）；启动脚本确认本地服务就绪。不通过此动作触发生产上线。

## 2026-10-01：只读结构成功回执被误标失败的界面修复

用户提供原始JSON并核对真实运行 `20801dc9-f221-4d56-a92e-2ae8ee55c488`：DATABASE_SCHEMA、successful=true、exitCode=0、schemaComplete=true、outputTruncated=false、databaseModified=false，结构摘要仍为 `fcf08a91fe023fa236335c7c1eb38030bcee837960a4e9b8b05ec5cf7ef8b949`。回执中的baselineRegistered=false表示只读工具未执行登记，不应作为结构核查失败依据。上一轮FixedToolReply对所有含此字段的回执都要求true，导致中文失败卡与真实结果矛盾。

前端现按任务语义判断：结构核查要求执行成功、完整且未截断；只有真实基线登记回执要求baselineRegistered=true。登记回执识别优先于镜像身份，避免其包含imageId时被误命名为构建镜像。成功结构卡明确说明只读操作没有登记基线。新增两项回归覆盖该真实形状、结构不完整/截断/非零，以及基线false/null不能通过。前端16项测试及生产构建通过；未触发服务器任务、未改变基线登记权限或生产数据库、未清理备份、未修改manual-acceptance、未提交Git。

## 2026-10-01：登记前历史表缺失的真实记录核对

只读读取本机运行和审计：运行 `1d54f8dd-ffef-450a-93f3-7e339db271cb` 于北京时间13:23执行 `DATABASE_BASELINE_STATUS`，目标 `root@117.72.84.129:22/root/opt/old-things`，exitCode=1、successful=false，MySQL ERROR1146：`old_things.flyway_schema_history` 不存在。USER_TOOL_REQUEST、审批批准、TOOL_RESULT及执行完成审计均存在，证明查询真实执行；COMPLETED是执行完成状态，不是业务校验通过。此前登记 `eab500af-e29e-4851-b6fa-bc3b88dedb32` 启动类失败，之后没有成功登记记录，因此当前缺表与尚未登记一致；不是SSH/认证故障，不应手动建空表掩盖状态。

进一步核对镜像运行 `01b94bb1-44a5-4f11-9e4b-61bd2968435b`：IMAGE_READY、successful=true、exitCode=0，输出有RUNTIME_SMOKE和BUILDER_CLEANED=true，镜像ID `sha256:f339842d2ca803e981614bf17b3a5350f405cc3deca7734b46df1861471fd036`，生产未修改、服务未重启。备份 `20260930T143429Z-ffba9e8c` 已超过30分钟，不能用于本次登记，但按用户要求继续保留。新增中文六步验收操作单。此轮仅诊断和文档，无代码变更或新测试运行，无远程执行/删除/登记，未修改manual-acceptance、未提交Git；生产基线尚未验收。

## 2026-09-30：六步发布引导与结果辨识（仅自动化）

部署页改为按候选、镜像、近期备份、只读结构、受审基线、只读验收的六步引导。每步显示用途、成功证据和失败可能留下的影响；高级配置/原始技术结果默认折叠，重复的候选、镜像、备份、基线执行入口从高级区移除。平台固定任务回执在操作助手中以 `successful` 和 `exitCode` 显示中文成功/失败，原始 JSON 可展开；模型文字不被当成工具执行证据。运行历史只读恢复最近 50 条的相关工具结果，刷新后不把已执行步骤误显示为未执行；基线按钮还须核对同一目标、镜像自检、新备份时效、完整结构及六项绑定身份。验收须在基线成功后重新读取版本 1 `BASELINE` 成功记录和站点健康，旧诊断不能冒充验收。

在本机浏览器查看部署页，发现中间栏宽度下两列卡片挤压中文，已改为单列；切换到部署 Agent 后确认近期备份显示过期、基线按钮锁定、六步与高级区分离。未点击任何远程任务。前端 `npm test` 14 项、`npm run build` 285 模块通过；本节尚无生产基线正向验收，不宣称整条链路成功。本轮未触发远程任务、未更改生产数据库或网站。用户明确选择保留所有备份，只清理已确认废弃的临时构建产物；当前仅从历史回执识别到若干构建尝试，未通过受控接口复核远端现状与引用，因此未删除任何远端或本地数据，也未执行 Docker 全局 prune。`manual-acceptance/` 未修改，Git 未提交。

## 2026-09-30：基线登记启动类失败后的镜像权限防线

真实受审登记返回 `PropertiesLauncher ClassNotFoundException`，随后用户执行只读 `DATABASE_BASELINE_STATUS` 返回 `old_things.flyway_schema_history` 不存在（exitCode 1）。因此登记未完成；此前工具的 `databaseHistoryMayHaveChanged=true` 是失败时的保守标记，不可推断已登记。源 JAR 确有启动类，隔离构建脚本 `umask 077` 下复制的上下文 JAR 可能为 root 独读，镜像中的非 root 用户无法读取。平台仅将经摘要校验的构建上下文副本改为 0644，原候选仍受保护；新增 `RUNTIME_SMOKE`，在无网络、只读、非 root、限额容器中启动固定维护入口并用无效摘要确认其在读取凭据/连接数据库前受控拒绝。失败或超时不发 IMAGE_READY 回执，并尝试按所有者标签清理本次容器及构建器；启动日志有文件大小限制。不修改网站 Dockerfile、生产数据库/容器、旧候选或旧镜像。最终后端 `mvn -o -q test` 135项通过、0失败/错误/跳过；前端 `npm run build` 284模块通过。真实服务器重构建/重新备份/登记尚待用户审批，不能写成已验收。

## 2026-09-29：固定任务不再依赖模型决定执行（仅自动化）

原始a612cb2b-0e62-44c3-8cd3-550c5b15a7a8与4dd68bf1-9df1-4d66-a206-4713fc372a29只有MODEL_CALL，分别虚构基线/认证错误，不是服务器执行证据。现有chat/stream增加可选requestedTool（固定七工具、当前版本绑定、有界对象），仍经同一SafeExecutionGateway、Schema、一次性参数/目标审批、RunStep和AuditEvent。固定任务直接输出原始工具回执，不请求模型重写；拒绝/失败不伪造成功。自然语言明确执行请求没有结果时显示EXECUTION_EVIDENCE_CHECK/任务未执行，预算耗尽且全部拒绝也拦截；解释如何使用工具不误拦截。旧三字段API兼容，普通ReAct/RAG/MCP不移除。

最终后端 `mvn -o test` 23:01:10通过132项，0失败/错误/跳过。新增7项集成用真实Registry/Gateway/Approval/Audit/SSE及模拟SSH适配器：批准仅执行一次/不调用模型、原始非零结果、拒绝零执行、危险SQL字段网关拒绝、未绑定400、无证据自由聊天拦截、四轮拒绝后不伪造finalization；另1项解释问题/明确调用判别。前端9项测试及TypeScript/Vite生产构建284模块通过（2.85秒）。最初测试类访问包内方法与未声明checked exception已修正，未放宽生产接口。ADR0030、README、基线人工说明、知识包30同步更新；新触发机制真实服务器待用户审批验收，不执行生产任务，不修改manual-acceptance，不提交Git。

## 2026-09-29：候选镜像慢速下载预算修复（仅自动化）

核对原始运行397ce071-f708-4f3e-8042-8f3e65dd0017：PRECHECK/BOOTSTRAP/资源核查通过，基础镜像16.97MB层260.4秒推进到15.66MB，300秒终止exit124，清理退出0；没有生产切换。固定共享构建预算900秒、命令1100秒、工具1140秒、镜像版本会话与SSE至少1200秒，保留更长配置；其余会话不变。审批目标绑定900秒，三轮共享、不无限重试，原限额/摘要/TLS/输出/清理机制不变。

后端 `mvn -o test` 22:31:39通过124项，0失败/错误/跳过，含真实POSIX生成脚本和模拟Docker下载重试/耗尽/超时/清理、SSH通道超时、900秒命令与审批策略、镜像会话1200秒及更长配置、普通会话预算回归。前端 `npm run build` 284模块通过（3.49秒）。新预算没有真实服务器成功证据；不能把本次自动化标为已人工验收。ADR0026/人工说明/问题复盘/知识包28同步更新；未操作生产、未修改manual-acceptance、未提交Git。

## 2026-09-29：受审数据库版本1基线登记（仅自动化）

只读结构真实正向运行35dff08a-dbf9-4e1e-ac92-a5ebf65a5f90已核对原始RunStep和审计：成功0、668ms、完整未截断，old_things/MySQL8.0.46、8表44列及4组外键，摘要fcf08a91fe023fa236335c7c1eb38030bcee837960a4e9b8b05ec5cf7ef8b949。审批/开始/完成完整，未登记历史；非正向仍主要为自动化证据。

新增adopt_remote_database_baseline（登记受审数据库基线），SSH/WRITE/HIGH210秒，六项身份绑定新候选、镜像、结构和30分钟内备份。SFTP/固定命令复核摘要、备份完整性/时效/目标、镜像ID/标签和实时结构；隔离候选维护入口短READ锁下校验契约，仅Flyway baseline1，不migrate/repair/clean、不改业务行或重启网站。失败登记状态不可假定，增加DATABASE_BASELINE_STATUS只读诊断。凭据为0700目录内0600临时文件，经stdin传入，正常EXIT回收；清理只针对匹配owner标签容器，失败不伪造成功。ADR0029/验收说明记录残留、短写等待与外部DDL风险。

网站最终 `mvn -o verify -Pmysql-verification` 22:09:11通过：默认10+独立MySQL7（原业务4+新增维护3），无失败/错误/跳过，target/app.jar重新打包；含缺少唯一约束即使摘要正确也拒绝登记的补充断言。首次DATE/DATETIME解析及44列计数不匹配已修正，不移除契约校验；READ锁内禁用START TRANSACTION/COMMIT以免隐式解锁。维护PropertiesLauncher用构建JDK验证可启动且无效参数返回受控拒绝；普通shell默认Java8不适配，候选用Java17。没有连接生产。

平台最终 `mvn -o test` 124项通过（22:07:52），0失败/错误/跳过；包括HIGH/参数、同会话、备份摘要/过期、结构漂移、非零未知状态、超时关闭、真实POSIX脚本成功/失败退出码、owner限定容器清理、清理失败不伪装成功及凭据文件清理。POSIX测试Docker和flock均为模拟边界；首次固定Git Bash路径不存在、Git Bash无flock已改为既有解析方式/明确模拟，不更改生产锁。网站MySQL锁/历史测试为真实隔离数据库。SQL副本规范化比较一致。

前端9项测试及生产构建通过（284模块），当前链路身份在运行步骤清空时保留、切目标/版本时清空，避免六项信息被下一个任务冲掉；不开发历史会话或历史候选列表。git diff --check通过；manual-acceptance/未修改。未提交，HEAD保持83314bb。真实基线登记/生产切换/故障回滚仍待独立审批验收。

本地应用已重启，工具目录确认登记受审数据库基线SSH/WRITE/HIGH210秒；健康UP、版本1.0.0。没有触发服务器任务或登记基线。

## 2026-09-29：受审生产数据库结构只读核查（仅自动化通过）

在既有 inspect_remote_deployment 增加 DATABASE_SCHEMA，不新增通用 SQL/执行接口。复用固定 SSH 指纹、同会话 SFTP 路径校验、SSH/EXECUTE/HIGH、一次性参数绑定审批和 RunStep/AuditEvent。固定 mysql 容器以只读事务查询 information_schema，容器凭据不离开容器，不读取业务行/列默认值，不登记基线、不启动新候选、不修改生产。输出完整协议才计算 schemaSha256；非零/截断不发摘要，错误协议失败。单查询/远端/SSH/工具预算分别 15/25(+2 秒 kill)/30/45 秒，结构输出 48000 字节。未截断 UTF-8 缓存跨边界解码修复有专门回归，不缩减索引或审计能力。

最终后端 `mvn -o test` 117 项通过（21:29:01），0 失败/错误/跳过；独立 `mvn -o '-Dtest=RemoteDatabaseSchemaMySqlIT' test` 1 项通过（21:30:07），真实本地 MySQL 8.0 查询验证索引/外键规则、数据变更不影响摘要、DDL 改变摘要、密码/默认值/行值不泄露、未创建历史表且原行保留。测试使用本次 UUID 容器，无生产连接或公开端口，finally 仅回收该容器及匿名卷；缓存镜像保留，不全局 prune。该 IT 必须显式运行，不算在默认 117 项中。

第一次真实 MySQL 测试暴露 INDEX JSON 行宽度误写成 10（实际 9），修正并补回归后通过，未跳过协议或索引校验。首次沙箱 Maven 依赖解析被权限阻止，正常本机缓存环境重跑成功；最终全量离线运行，不改变全局 Maven 配置。

前端 6 项 Vitest 与 TypeScript/Vite 生产构建通过（283 模块），部署页增加中文核查入口及摘要/未完成警示。git diff --check 通过。启动本地应用后健康 UP、版本 1.0.0，未新增数据库迁移。当前分支 feat/release-candidate-workflow，HEAD 保持 83314bb，本轮未提交；manual-acceptance 和网站真实数据未改。

真实生产结构采集尚待用户 HIGH 审批，不能写已人工验收；结构采集也不代表契约兼容。真实输出核对后再实现受审基线登记（重新核查漂移、新备份、候选绑定）、受控 app 切换和失败回滚，完整发版链路尚未完成。验收说明见 DATABASE_SCHEMA_ACCEPTANCE，边界见 ADR 0028。

## 2026-09-28：本地网站版本化数据库迁移基础通过

用户明确授权 D:/idea_work/shiguangxv 本地任意修改并已备份。按该目录 AGENTS/HANDOFF 保持业务和真实数据，加入 Flyway 9.22.3、MySQL 支持及 Hibernate validate，关闭自动 baseline/clean；源码派生 V1 仅供空库，现有生产结构未核实。旧快照与当前实体不一致，不导入旧备份建立假基线。只读核对辅助类及演进/兼容性流程见 ADR 0027 和网站 docs/DATABASE_MIGRATION.md。

网站 `mvn clean verify -Pmysql-verification` 于 23:37:43 通过：默认 10 项（原功能 3 + 迁移边界 7），独立 MySQL 8.0 4 项（原功能 3 + 兼容扩展 1），全部 0 失败/错误/跳过，生产 JAR 打包成功。测试专用 V2 不进入生产包；MySQL/Ryuk 测试容器自动回收，原本地数据库仍健康，镜像缓存保留。本轮未更改 Agent Studio 运行代码，沿用上一阶段后端 110 项及前端 6 项/生产构建证据；不声称本轮重跑了这些测试。

H2 枚举类型差异、旧 Testcontainers 与 Docker 29 不兼容、子类重复测试配置覆盖认证测试值已修复，不以降级或禁测解决。网站没有 Git 仓库，未初始化/提交；生产数据库/服务器未修改，manual-acceptance 未修改。新候选必须重新准备；现有构建成功的旧镜像不包含本地改造。受审生产结构核查/基线登记、app 切换、健康检查及失败回滚仍未完成，不能宣称整条发布链路结束。

## 2026-09-28：候选镜像正向真实验收与发布前置核查

用户提供成功截图后读取原始运行 4fdc8583-f14d-4052-9ee7-65e85b732065：successful=true，IMAGE_READY，exitCode=0，83372 ms，镜像 ID sha256:2849cb4ada5a090933f75cfcabfb2e7bd8738d3f4c4309f1d2bdf019cbe25a7c，输出未截断，第一轮成功，构建器清理回执为 true，生产未修改、服务未重启。原始审计完整包含 APPROVED、TOOL_EXECUTION_STARTED、TOOL_EXECUTION_COMPLETED。此为真实服务器与模型正向人工验收，不替代负向人工演练或生产发布验收。完整根因、修复和未解决风险见 RELEASE_IMAGE_BUILD_POSTMORTEM。

继续开发前只读核查 D:/idea_work/shiguangxv/src/main/resources/application.yml:11，仍有 ddl-auto: update，pom 未发现 Flyway/Liquibase 依赖。计划第 8 节要求数据库回滚前置条件；当前不能承诺仅切回镜像可恢复业务。按用户要求不采用永久禁止结构变化的功能缩减，记录需要网站仓库基线/迁移/兼容性改造授权，未擅自修改网站、数据库或执行生产切换。历史会话需求保持延期，manual-acceptance 未修改。

## 2026-09-28：审计错误摘要超长修复

真实运行 7d1f5f80-f87d-4273-b1d4-f8a1384a462c 已审批，14:52:39Z 写入 TOOL_EXECUTION_STARTED，随后 RunStep 返回 audit_event.details 的 Data too long，而不是原始构建错误。审计中没有完成或失败事件；不能以模型总结认定“构建未执行”或确认镜像/构建器最终状态，已丢失的原始错误不可凭空恢复。

根因是 AuditRepository 对超长错误先 substring(0,1000) 再追加省略号，向 VARCHAR(1000) 传入 1001 字符。修复为最多 999 字符加省略号，避免切断 UTF-16 代理对；不放宽数据库校验、不跳过审计、不改变 Gateway、审批或审计身份。工具失败 RunStep 使用与成功工具相同的有界长记录策略，保留首尾原始错误，审计详情继续作为短摘要而非全文日志。历史会话开发仍延期。

新增 999/1000/1001/16000 长度、中文、emoji 边界，以及实际传给 JDBC 的参数摘要/状态/详情长度回归。后端全量 110 项通过，0 失败、0 错误、0 跳过（23:01:55），git diff --check 通过；本轮前端未修改，沿用上一轮 6 项测试与生产构建证据。真实构建成功仍待独立验收。manual-acceptance 未修改，未通过旁路执行服务器任务。

本地应用已重启，23:03:07 健康 UP、版本 1.0.0；未新增数据库迁移，本次严格适配已有 VARCHAR(1000)。

## 2026-09-28：基础镜像下载 EOF 有界重试与原始记录修复

真实运行 163df3a5-d08b-431f-acda-ca03a13f436e 的工具结果为 successful=false、IMAGE_BUILD、exitCode=1、139988 ms；RunStep 证明三个固定镜像源配置已加载、构建器正常启动。用户截图显示 eclipse-temurin 基础镜像下载 short read（期望 3843 字节，实际 0）及 unexpected EOF，未改生产、未重启服务。该运行的持久化工具 JSON 在 4000 字符处被截断，无法从既有数据库记录恢复末尾或断言具体失败镜像源；不得宣称网络已连通或镜像已通过人工验收。

本次实现下载传输故障最多三轮重试，固定 5 秒退避，共用原 300 秒构建预算、同一独立构建器/标签；超时、编译/权限及非传输错误不重试，重试策略写入审批目标。每轮诊断日志限制 2048 个 shell 文件大小单位（目标 shell 通常为 512 或 1024 字节，即最多约 2 MiB），输出仍使用 16000 字符首尾有界流。未增加不可信源，未放宽 TLS，未修改 Dockerfile、全局 Docker 或生产服务。重试仅改善瞬时故障，持久外部网络故障仍会安全失败。

工具 RunStep 改为保留有界完整结果而非 4000 字符切坏 JSON，超 128000 字符保留首尾并标记省略；新增 V15 将 output_text 扩大为 MEDIUMTEXT，不改已有记录身份或审批。模型上下文仍限 16000 字符。历史会话与候选选择需求已记录到 REMOTE_DEPLOYMENT_OPERATIONS_PLAN，按用户要求在发版链路完成后开发，本次没有实现这些功能。

专项测试 13 项通过，随后新增构建超时不重试回归；最终后端全量 108 项通过，0 失败、0 错误、0 跳过（22:50:51），含 V15 迁移；前端 6 项测试与生产构建（283 模块）通过。第一次全量运行有一项旧命令文本断言仍期待单次固定 timeout，更新为共享 deadline/remaining 断言后全量通过。真实服务器成功构建仍待用户重新审批验收。manual-acceptance 未修改。

本地应用重启完成，22:51:25 MySQL 成功应用 V15；22:51:42 系统健康为 UP、版本 1.0.0。未通过旁路触发服务器构建，下一次构建仍要求用户界面 HIGH 一次性审批。

## 2026-09-28：临时 BuildKit 显式接入批准的镜像源

真实运行 `acfd5a76-c617-4cb8-b844-4536e4456403` 已通过固定材料预检并启动构建器，但 IMAGE_BUILD 解析 `eclipse-temurin:17-jre-alpine` 元数据时直连 registry-1.docker.io 超时，普通退出码 1，耗时 118,637 ms。日志确认本次构建器 removed、清理退出码 0；不代表此前其它尝试都已清理，镜像构建仍未人工验收成功。

用户提供宿主 Docker 三项镜像源配置并批准临时构建器接入：`https://docker.1ms.run/`、`https://docker.1panel.live/`、`https://docker.ketches.cn/`。固定策略生成独占尝试目录内 0600 `buildkitd.toml`，以 SHA-256 校验，再通过 --buildkitd-config 传给本次专用构建器。配置不进入 jar/Dockerfile 两文件构建上下文；不改 Docker 全局配置或候选 Dockerfile、不重启网站、不启用 HTTP/insecure。审批目标、结果、成功回执绑定镜像源列表与配置摘要；模型参数仍只有候选 ID 和清单摘要，拒绝自定义 URL。

后端全量 103 项通过，0 失败、0 错误、0 跳过（22:28:47 完成），包括实际 POSIX 模拟 Docker 流程读取镜像源配置、配置摘要、两文件上下文、危险额外 registryMirrors 拒绝及无全局 Docker 修改命令。前端 Vitest 6 项和生产构建通过（283 模块）。首次专项测试捕获脚本格式化参数顺序错误，修复后全量通过；真实服务器镜像源连通性和成功镜像仍待用户审批重试，不能宣称问题已在生产环境解决。构建器镜像拉取仍由宿主 Docker 配置处理；镜像源均失败时 BuildKit 仍可能回退官方 registry。

## 2026-09-28：镜像失败日志保留与幂等清理修复

真实请求 `4c669f76-f3b8-4e25-b2b4-df0b6658bb80` 因 90 秒审批超时未执行；随后 `66169d98-913d-4717-a8cf-0ef3b9224c48` 于 17:00:33 批准并经 SafeExecutionGateway 开始，17:02:38 失败，约 125 秒。错误只保留“构建失败且清理未确认”，丢弃了远端原始退出码与日志；不能由此认定构建器残留，也无法确认实际是否为 120 秒 bootstrap 超时。该次真实镜像构建未验收通过。

修复远端 EXIT trap 保留原始退出码，输出构建阶段与独立清理退出码；成功清理写 builder.cleaned，后续补偿清理幂等成功。后端保留 stage、原始构建 exitCode/输出/截断标志、builder、attempt 和独立清理错误；超时与取消也保留已有输出，不再让清理错误掩盖构建原因。原有资源、超时、一次性审批和生产不切换边界保持不变，未延长超时、未自动重试远端构建、未清理历史尝试或生产对象。

新增回归覆盖重复清理、bootstrap 退出 124 同时清理退出 18、原始失败输出保留及清理成功后超时诊断。最终后端全量 102 项通过，0 失败、0 错误、0 跳过（17:15:31 完成）；前端 6 项测试和生产构建通过（283 模块）。真实重试待用户审批操作，本次只证明修复自动化通过；manual-acceptance 未修改、未提交。

## 2026-09-28：隔离候选应用镜像构建（自动化）

新增“构建候选应用镜像” `build_release_candidate_image`（SSH/EXECUTE/HIGH），仅接受严格格式的 releaseId、manifestSha256，沿用现有参数绑定一次性审批、固定 SSH 指纹、Profile 与审计链。候选清单升级格式 2，绑定 jar/Dockerfile/Compose/Nginx 全部固定摘要；旧候选保留但拒绝镜像构建。单 SSH 会话逐级 SFTP 安全检查、有界清单读取、Exec 固定摘要复核与仅 jar/Dockerfile 的独占快照。

固定 Docker/Buildx、内存与磁盘预检查、平台构建互斥锁、独立 Docker 配置和本机 socket；专用 docker-container 构建器配置并核对 512 MiB/0.5 CPU 限额，启动 120 秒、实际构建 300 秒、通道 500 秒、工具 540 秒，相关 AgentVersion 继续使用 900 秒外层预算。输出合并并有界首尾保留；验证尾部成功回执的候选、清单、标签与镜像 ID。普通非零退出为结果，摘要失败和超时为工具失败。正常、失败、取消时清理本次专用 builder，不操作生产切换、重启、prune 或历史镜像。网络故障下只能确认尝试清理，不承诺绝无残留。

最终后端全量 `mvn test`：98 项通过，0 失败、0 错误、0 跳过（16:50:38 完成）。包括 MINA 本地 SSH 会话复用、成功/非零/超时/取消清理、16 KB 截断仍保留回执、危险参数、候选清单篡改/旧格式、候选祖先与每项固定文件符号链接拒绝；4 项 POSIX 实际脚本测试用模拟 Docker 边界验证成功/失败/篡改/低磁盘的检查和清理顺序。新增取消测试首次断言仅给 4 秒退出时间，不足以覆盖 MINA 5 秒连接预算，调整为覆盖连接及清理预算的 15 秒后专项及全量通过。

前端 Vitest 6 项通过，TypeScript/Vite 生产构建通过（283 模块）。新增候选身份/摘要输入、自动填入当前成功候选与中文镜像请求/结果卡；未新增执行旁路。已重启本地前后端，系统状态 UP/1.0.0，工具目录确认新工具 SSH/EXECUTE/HIGH、540 秒已注册。真实服务器 Docker 构建与真实模型验收待用户操作，未自动执行真实构建或生产操作。详见 RELEASE_IMAGE_ACCEPTANCE 和 ADR 0026。`manual-acceptance/` 未修改、未提交。

## 2026-09-28：不可变发布候选正向人工验收

用户确认成功，核对真实运行 `c37e9338-8fd1-4357-9fa5-734961cbde40` 为 COMPLETED。工具请求参数为 `{}`，经过 HIGH 参数绑定一次性批准与 SafeExecutionGateway，审计完整覆盖参数校验、审批、执行开始和执行完成；工具耗时 100,397 ms，退出码 0，阶段 REMOTE_VERIFY。

候选 ID 为 `20260928T081518Z-080d3c7e`，位于 `/root/opt/old-things-releases/20260928T081518Z-080d3c7e`。本地测试 3 项全部通过，制品 `target/app.jar` 为 49,246,340 字节；制品 SHA-256 为 `19a2c0b3f1eb547fecbcdb0cddc50c0486df65804da83c783306f3cf2322cd37`，manifest SHA-256 为 `08f2143a7214771bbfd75afdd424f2cb19dbf7dae87b406d25b9a865f9d5e443`。工具返回 productionModified=false，用户截图与运行记录一致。

准确口径为“真实模型与真实服务器候选准备正向链路已人工验收”。尚无用户独立执行服务器权限/摘要检查及准备前后生产指纹对比的证据；危险参数、失败和取消等场景仍以自动化证据为准。没有执行镜像构建、生产切换或回滚，不能宣称完整发布闭环验收。自动化基线沿用 9 月 26 日后端 85 项和 9 月 22 日前端 4 项及生产构建，本次仅补证据文档，未重新执行测试。

## 2026-09-26：候选发布总时限修复

修复后后端全量 85 项通过，0 失败、0 错误、0 跳过。新增测试覆盖候选版本独立预算、普通版本预算不变及保留更长的用户配置。已重启本地前后端并通过启动健康检查；未重新触发真实远程候选任务。

真实运行 `6f224b5c-12e6-4194-b740-3547d3124eab` 于 20:11:55 开始，20:11:58 批准并进入工具，20:13:55 被 120 秒 Agent 总时限中断。工具预算为 540 秒，存在外层时限短于内部工作流的问题。本地 jar 的修改时间为 20:12:37，随后日志已出现 SSH 连接；旧日志不足以证明上传是否阻塞。

包含 `prepare_release_candidate` 的 AgentVersion 使用独立 900 秒运行预算（`AGENT_RELEASE_CANDIDATE_TIMEOUT`），SSE 使用相同预算加 10 秒；其他 Agent 保持原有 120 秒。工具 540 秒与 Maven 每阶段 240 秒限制保持有效。补充 Maven 阶段耗时、上传字节进度及取消检查，关闭 Maven 标准输入以避免交互等待。修复后真实服务器验收待用户重试。

## 2026-09-22：业务级不可变发布候选（自动化）

- 新增 `prepare_release_candidate`（SSH / WRITE / HIGH），无模型参数，继续复用现有审批、运行步骤和审计链路。
- 固定本地 Maven 测试与打包，仅接受 `target/app.jar`；拆分本地 `docker-compose.yml` 与远程 `compose.yml` 配置。
- 使用单 SSH 会话和 SFTP 独占创建候选目录，上传六项固定文件并执行远程摘要回执校验。
- 自动化覆盖：工具注册、危险参数拒绝、固定 Maven 生命周期、只选 `target/app.jar`、本地失败不连接 SSH、单会话上传、固定文件集合、摘要回执和生产未修改标记。后端全量 83 项通过，0 失败、0 错误、0 跳过；前端 Vitest 4 项通过，TypeScript/Vite 生产构建通过，282 个模块。
- 真实模型与真实服务器验收：待执行，见 `docs/RELEASE_CANDIDATE_ACCEPTANCE.md`。

## 2026-09-21：隔离远程备份恢复材料演练

新增 `verify_remote_deployment_backup_restore`，固定为 `SSH/WRITE/HIGH` 且 Schema 是无字段对象。工具只选择固定备份根中最新的合格备份，在 `restore-drills` 下创建独占随机目录；先检查目录/文件非符号链接、无 `FAILED`、两份 SHA-256 清单和 gzip，再复制数据库与固定部署文件、以 0600 复制 `.env`、隔离展开 uploads 并拒绝展开后的符号链接。命令不包含 Docker、Compose、MySQL 导入、生产目录写入、删除或清理。

进程内 Apache MINA SSHD 专项由 9 项扩充到 13 项，覆盖工具注册、空参数 Schema、固定最新备份选择、隔离路径、成功元数据、生产零修改声明、非零退出、超时通道关闭和危险参数拒绝，13 项全部通过。后端全量 79 项通过，0 失败、0 错误、0 跳过；前端 Vitest 4 项通过，TypeScript/Vite 生产构建通过，282 个模块。

真实恢复演练运行 `0ff426e4-2fd1-429a-b860-6b0ae62e501c` 通过空参数校验、HIGH 一次性审批和完整审计链，选择最新备份 `20260921T143308Z-5b8b3432`，创建隔离目录 `restore-20260921T145940Z-c5bb4e7f`；材料化数据库 28,056 字节、uploads 377,940,481 字节、105 个文件，生产目录未修改且数据库未导入。随后真实 `COMPOSE_VALIDATE` 与 `COMPOSE_STATUS` 均成功，四个服务 running；第一次健康请求 `5aa32a9e-3b71-496f-9238-803f37f9c408` 只有 MODEL_CALL、没有工具步骤，已排除为模型无证据描述。重新运行 `149b12cc-fe0e-4cb6-a150-ab6a77582c45` 后形成完整 `SITE_HEALTH` 工具/审批/审计链并返回 `HTTP 200`。因此隔离材料化演练正向人工验收通过，但数据库导入和生产恢复仍未验证。

## 2026-09-21：只新增的远程发布前备份

新增 `prepare_remote_deployment_backup`（`SSH/WRITE/HIGH`），输入 Schema 是不接受任何字段的空对象。服务器生成 UTC 时间戳加随机后缀的 backupId，并在固定 backupRoot 下独占创建新目录；没有覆盖、删除、清理、恢复或模型自定义路径/名称/命令/参数/环境变量分支。工具复用部署 Profile、固定 SSH 身份/指纹和 SafeExecutionGateway 批准后目标复核。

固定流程在同一 SSH 会话完成 MySQL `--single-transaction` 逻辑导出、uploads 压缩、app.jar/Dockerfile/Compose/Nginx 配置复制、`.env` 0600 安全复制、镜像清单和四个固定服务状态记录。`.env` 和数据库凭据不返回；九个负载文件生成 SHA256SUMS，manifest 另有摘要。数据库及 uploads 必须非空，成功回执的 backupId、备份路径、文件数、大小和 manifest SHA-256 还会在客户端严格校验。失败目录保留 `FAILED` 标记，不自动删除现场。

进程内 Apache MINA SSHD 专项测试覆盖 `SSH/WRITE/HIGH` 注册、空参数 Schema、同会话校验、固定创建命令、无删除命令、成功 manifest 元数据、非零退出、超时关闭和危险参数拒绝。后端全量 `mvn test` 为 75 个测试通过、0 失败、0 错误、0 跳过；前端 Vitest 4 项通过，TypeScript/Vite 生产构建通过，282 个模块。

第一次真实备份运行 `925e6062-e1a4-432f-8c29-fc456967952b` 成功创建 `20260921T142020Z-207e3ede`：数据库 28,056 字节，uploads 压缩包 353,700,874 字节，退出码 0且输出未截断。用户在服务器只读核对目录 700、`.env` 600、SHA256SUMS 九项及 manifest 摘要全部 OK，数据库/uploads 非空、gzip 有效且没有 `FAILED` 标记。随后固定命令增加成功返回前的 `sha256sum -c` 自校验。

第二次真实备份运行 `c19eb39f-a2b9-406c-94d4-2d10e9d194d9` 成功创建不同的 `20260921T143308Z-5b8b3432`，数据库 28,056 字节、uploads 353,700,874 字节、九个固定负载，manifest SHA-256 为 `1564e27eacbeb98a6dc393ee68def5fbfd87993939e7d3808dc7ffdd4197b4c9`。运行经过空参数校验、HIGH 一次性批准、SafeExecutionGateway 和完整 AuditEvent 链，工具内部摘要自校验通过后才返回成功。创建型备份正向人工验收完成；不能据此声称恢复能力已经验证。

## 2026-09-21：LLM 消息安全 Markdown 渲染

“对话测试台”和“远程工作台 → 操作助手”的 Agent 消息改用 `react-markdown + remark-gfm` 渲染标题、段落、列表、强调、引用、行内代码、代码块、链接和表格；流式生成中的未闭合 Markdown 可继续按普通文本显示。用户消息、ApprovalRequest 参数、RunStep 工具输入/输出及原始日志不进入 Markdown 渲染器。

渲染器未启用原始 HTML；链接只允许锚点、HTTP、HTTPS 和 mailto，外部链接附带 `noopener/noreferrer` 并在新窗口打开。Markdown 远程图片降级为文字占位，不向模型指定的地址发起请求。代码块与表格限制在消息卡内部横向滚动，保留工作台固定高度和内部滚动边界。Vitest 4 项测试覆盖 GFM 表格/代码、原始 HTML、危险链接、远程图片、外部链接隔离和流式未闭合文本；前端生产构建通过，282 个模块。用户通过真实 `COMPOSE_STATUS` 运行 `9bafe710-5d27-456c-92e5-9bad2066aec7` 确认项目列表、行内代码和表格渲染符合预期，批次 2.5 已人工验收。

## 2026-09-21：部署诊断长审批目标兼容修复

真实模型首次逐项请求五种部署诊断时，五项都在创建 `ApprovalRequest` 阶段失败，数据库报错为 `Data too long for column 'target_environment'`。审计只到 `TOOL_REQUEST_VALIDATED`，没有 `APPROVAL_REQUIRED` 或 `TOOL_EXECUTION_STARTED`，因此本次失败没有连接 SSH、没有运行 Docker/Nginx/HTTP 命令，也没有影响网站。原因是 V5 将审批目标定义为 `VARCHAR(160)`，而部署诊断会把 SSH 身份、主机指纹、部署根、Compose 项目/文件和健康地址共同绑定到审批快照，合法目标可能超过 160 字符。

Flyway V13 将 `approval_request.target_environment` 前向扩展为 `VARCHAR(4096)`，不修改既有迁移。会话集成回归现在实际写入并核对超过 160 字符的审批目标。后端全量 `mvn test` 为 71 个测试通过、0 失败、0 错误、0 跳过，Flyway 空库 V1–V13 通过；前端 TypeScript/Vite 生产构建通过，29 个模块。

重启后端并完成 V13 真实 MySQL 迁移后，用户逐项请求并批准五项真实生产只读诊断，均完成 `TOOL_REQUEST_VALIDATED → APPROVAL_REQUIRED → APPROVAL_DECIDED → TOOL_EXECUTION_STARTED → TOOL_EXECUTION_COMPLETED` 审计链：`COMPOSE_VALIDATE` 运行 `8c0d0ba0-9427-44c7-8ee8-454406ef5a94`（720 ms），`COMPOSE_STATUS` 运行 `aea5d640-8a0c-4649-b814-5721094d911e`（318 ms），`NGINX_VALIDATE` 运行 `4d486b06-6735-4f85-a5d3-1f2c7035d1e0`（303 ms），`SITE_HEALTH` 运行 `ec8f2e73-3698-4c9c-875a-d7b6908f134b`（HTTP 200，117 ms），`RELEASE_FINGERPRINT` 运行 `2c7f32d1-88a6-4c08-bc7b-39911969cfb2`（214 ms）。五项均 `successful=true`、退出码 0、输出未截断；用户确认结果符合预期。准确口径更新为“远程部署五项只读诊断正向链已人工验收”，仍不能声称已经执行部署、备份或回滚。

## 2026-09-19：独立生产 Profile 与只读部署诊断

Flyway V12 新增单一部署 Profile，保存本地源码根、远程部署/备份根、Compose 文件/项目、Nginx 配置相对路径和固定回环健康地址。SSH 主机、端口、用户、密码凭据和固定 SHA-256 主机指纹继续引用既有 SSH 工作区；普通 `remoteRoot` 没有放宽。工作台“部署”标签现在可保存并只读检查 Profile，显示四个固定服务、五张诊断卡和有界结果。

新增 `inspect_remote_deployment`（`SSH/EXECUTE/HIGH`），Schema 只有五种枚举 task：`COMPOSE_VALIDATE`、`COMPOSE_STATUS`、`NGINX_VALIDATE`、`SITE_HEALTH`、`RELEASE_FINGERPRINT`。工具不接受 path、命令、服务名、URL、参数或环境变量。每次调用在同一 SSH 会话先用 SFTP 逐级拒绝符号链接并检查固定清单，再运行静态映射的 Exec Channel；`.env` 仅对固定路径执行 `lstat`，不读取内容。内部时限 30 秒，输出约 16 KB 首尾保留，stdout/stderr 合并，超时或中断关闭通道；非零退出码作为工具结果返回。

`RemoteDeploymentToolTests` 覆盖工具注册、同会话、五种命令映射、`.env` 不进入命令/输出、成功、非零退出、超时关闭、输出截断、未知任务和危险额外参数拒绝；控制器测试覆盖 V12 Profile 保存/读取和非回环健康地址拒绝。全量后端 `mvn test` 为 71 个测试通过、0 失败、0 错误、0 跳过，Flyway 空库 V1–V12 通过；前端 TypeScript/Vite 生产构建通过，29 个模块。真实生产服务器与真实模型尚未验收，当前不能声称部署、备份或回滚完成。

## 2026-09-18：受控远程工作台与审批目标绑定

新增“远程工作台”一级页面，采用远程文件区、文件/变更/任务/部署/输出标签区和 Agent/审批/步骤区三栏布局。初版人工验收反馈表明，让文件树和文本预览也等待模型会造成明显延迟；因此人工浏览改为专用只读 API，直接复用既有目录/文本工具及其固定指纹、受限根、逐级符号链接和受保护路径校验，不创建 AgentRun、会话或模型调用。该 API 不提供写入、补丁、搜索或命令能力。Agent 自主读取、远程补丁和五种固定 SSH 任务仍继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、ApprovalRequest、RunStep 与 AuditEvent，没有新增旁路执行接口或任意终端。

工作台使用视口固定高度和三栏内部滚动；右侧操作助手只展示当前 RunStep 和最近六条消息，更早消息收起并保留在运行记录，连续操作不再撑高整页。任务区只允许选择五个固定 task 和相对项目路径；本批次完成时部署区仅为无执行能力的占位说明，后续只读诊断见上一节。

SafeExecutionGateway 现在会在 HIGH 审批通过后、工具启动前重新解析目标。目标与 ApprovalRequest 快照不一致时记录 `TOOL_TARGET_CHANGED/REJECTED` 并阻止执行；SSH 目标快照同时包含主机、端口、用户名、远程根和固定 SHA-256 主机指纹。新增会话集成测试在审批等待期间改变 SSH 配置，确认旧审批不能启动新目标工具。专项浏览控制器与会话测试为 15 个测试通过；该批次全量后端 `mvn test` 为 65 个测试通过，Flyway 从空库验证 V1–V11；前端生产构建通过。2026-09-19 用户确认多项固定任务、无模型文件浏览、父目录导航和操作助手内部滚动符合预期，工作台阶段已人工验收；未提供具体运行 ID，完整证据仍以运行库为准。

## 锚点后阶段 6：受控 SSH Exec（2026-09-16）

新增 `run_remote_workspace_task`，标记为 `SSH/EXECUTE/HIGH`，只接受远程根内相对 `path` 和 `GIT_STATUS`、`GIT_DIFF_SUMMARY`、`MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD` 固定枚举。模型不能提供命令、参数、环境变量或 Shell 文本。每次工具调用在一个短生命周期已认证 SSH 会话中复用 SFTP 与 Exec Channel：先逐级 `lstat` 校验目录、符号链接和项目标记，再执行固定映射；工作目录另受保守字符集约束。

`RemoteSshExecToolTests` 的进程内 Apache MINA SSHD 服务器同时配置 SFTP Subsystem 与 CommandFactory，覆盖同会话复用、五种固定命令映射、工具注册与具体目标、成功、非零退出、stdout/stderr 合并、75 秒内部超时关闭、约 16000 字节首尾截断、越界、符号链接、缺失项目标记和不安全工作目录。会话集成回归验证 AgentVersion 绑定、SafeExecutionGateway 的参数绑定一次性 HIGH 审批、拒绝后不连接服务器，以及 `command` 类额外参数在审批前由 Schema 拒绝并形成安全审计。

本阶段沿用 AgentVersion、ToolRegistry、ApprovalRequest、RunStep 与 AuditEvent，没有新增表、旁路执行接口或 MCP Server。全量后端 `mvn test` 为 63 个测试通过、0 失败、0 错误、0 跳过；H2 仍从空库校验 Flyway V1–V11，既有 RAG、MCP、运行控制、Coding 与 SSH/SFTP 回归未破坏。前端 TypeScript 与 Vite 生产构建通过，29 个模块完成打包。2026-09-17 至 2026-09-18，真实 DeepSeek 模型在真实 Ubuntu/OpenSSH 目标上完成 Git 状态、Git 差异摘要、Maven test、npm test 和 npm build 五种固定任务的正向运行；真实链路还验证退出码 127/1 作为完成结果返回、75 秒超时关闭远程通道，以及错误项目标记安全失败。审批拒绝、危险额外参数、输出截断、越界和符号链接绕过由自动化覆盖，本轮没有在真实服务器重复制造这些副作用。运行 ID 和输出见 `SSH_EXEC_ACCEPTANCE.md`。仍不支持 sudo、PTY、交互式/后台/任意 Shell、Git 修改、自定义参数或环境变量、依赖安装、Docker/Nginx、部署与回滚。

## 锚点后阶段 5：SSH/SFTP 受审远程文本补丁（2026-09-15）

新增 `apply_remote_workspace_text_patch`，标记为 `SSH/WRITE/HIGH`。它只修改授权远程根内既有 UTF-8 普通文件，要求读取时 SHA-256 一致、每段旧文本唯一匹配；批准后先写同目录独占临时文件、保留原权限、再次核对摘要，再要求 SFTP Server 原子覆盖。服务器不支持原子替换时失败关闭，不退化为直接覆盖。

`RemoteSftpWorkspaceTests` 使用进程内真实 SSH/SFTP Server 验证工具注册及具体 SSH 目标、摘要绑定成功补丁、权限保留、临时文件清理，以及陈旧摘要、缺失/重复文本、越界、`.env`、二进制和不存在文件失败不改。首次真实 Ubuntu/OpenSSH 验收暴露：OpenSSH 固定协商 SFTP v3，而标准 rename 的 `Atomic/Overwrite` 选项要求 v5+，客户端在发送请求前即抛出 `UnsupportedOperationException`。修复后优先检测并调用 OpenSSH `posix-rename@openssh.com` 扩展，高版本才使用标准选项；v3 又没有该扩展时继续失败关闭。专项测试现强制协商 v3，并覆盖扩展成功与扩展缺失拒绝两条分支。

HIGH 审批、一次性参数绑定、拒绝跳过与审计由既有 SafeExecutionGateway/ApprovalRequest 集成回归共同覆盖。修复后全量后端为 54 个测试通过、0 失败、0 错误、0 跳过；Flyway V1–V11、RAG、MCP、运行控制与既有 Coding 回归均未破坏。前端 TypeScript 与 Vite 生产构建通过，29 个模块完成打包。

真实 Ubuntu/OpenSSH 服务器与真实模型已经完成批准修改、批准恢复、审批拒绝和陈旧摘要四条分支：运行 `c02f0f09...` 将 `remote-before` 改为 `remote-after`，运行 `1349ba57...` 恢复原文，两次都返回 `updated=true` 且形成完整 HIGH 审批及完成审计；运行 `3381d12b...` 被拒绝并形成 TOOL_EXECUTION_SKIPPED；运行 `e139b076...` 在审批等待期间由人工连续修改文件，两次旧摘要执行均形成 TOOL_EXECUTION_FAILED，人工新内容未被覆盖。修复前失败运行 `33853a3f...` 作为 SFTP v3 兼容问题的发现证据保留。准确结论是远程受审单文件文本补丁已验收；远程 Shell、Git、构建测试、文件新建/删除、Docker/Nginx 和部署仍未实现。

## 锚点后阶段 4：SSH/SFTP 远程只读工作区（2026-09-15）

新增 `list_remote_workspace_directory`、`search_remote_workspace_files`、`read_remote_workspace_text_file` 三个 `SSH/READ/LOW` 工具。连接要求应用外核验的 SHA-256 主机指纹，密码由环境变量或 DPAPI 安全凭据提供；远程路径限制在单一授权根，逐级拒绝符号链接并保护密钥路径。工具继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、RunStep 与 AuditEvent。

`RemoteSftpWorkspaceTests` 使用进程内真实 SSH/SFTP Server 验证握手、密码认证、目录浏览、剪枝搜索、UTF-8 分段读取、SHA-256、具体远程目标，以及错误主机指纹、越界和 `.env` 拒绝。指纹探测会先触发不携带身份的 SSH 密钥交换，再读取主机公钥，避免只建立 TCP 会话时误报“服务器未提供主机公钥”。V11 和 `SshWorkspaceControllerTests` 进一步验证页面连接配置持久化、密码不进入配置表、根目录拒绝和工具动态读取已保存配置。加入本阶段后全量后端为 51 个测试通过、0 失败、0 错误、0 跳过，前端 TypeScript 与 Vite 生产构建通过。

真实 Ubuntu 服务器和模型主链已完成三次正向、两次反向验收：目录浏览 `b8edbcb9...`、搜索 `60767fb6...`、读取 `44a6c0d4...` 均成功；越界请求 `6dc7b118...` 由模型在工具调用前拒绝；错误指纹 `e6ac26e2...` 真实形成 FAILED ToolResult 和 TOOL_EXECUTION_FAILED 审计。完整证据见 `SSH_SFTP_READONLY_ACCEPTANCE.md`。远程 Shell、写入、Git、构建、日志命令和部署仍不在本阶段。

## 锚点后阶段 3：Coding 白名单本地验证（2026-09-15）

已实现 `EXECUTE/HIGH` 的 `run_workspace_verification`，只接受工作区相对目录以及 `MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD` 固定任务。程序路径解析、项目标记、受限环境、75 秒进程预算、子进程树终止、约 16000 字符输出和非零退出码结果均在工具内部收口；调用继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、ApprovalRequest、RunStep 与 AuditEvent。

专项测试在 Windows 实际启动固定测试脚本，覆盖成功结果、非零退出、大输出首尾截断、超时终止、标记缺失、非法任务和工作区启动程序拒绝。会话集成测试确认该工具必须进入 HIGH 审批，拒绝后只形成 TOOL_EXECUTION_SKIPPED，不启动验证进程。最终全量后端 `mvn test` 为 46 个测试通过、0 失败、0 错误、0 跳过；H2 从空库校验 V1–V10，既有 RAG、MCP、审批、运行控制与 Coding 回归均通过。

真实 Maven/npm 与浏览器审批链已经通过。运行 `c86216ab-02fe-40b7-90af-0f19b3b665eb` 在 `backend` 执行 `MAVEN_TEST`，批准后 22085 ms、退出码 0；运行 `94773e95-f1fd-4490-87e0-ea247ea2a8e1` 在 `web` 执行 `NPM_BUILD`，批准后 4054 ms、退出码 0；运行 `f87a5e91-04d2-467d-adc0-5c92cd706378` 被拒绝后仅记录 TOOL_EXECUTION_SKIPPED。固定入口仍会执行项目自带代码，当前没有 OS 级文件系统或网络沙箱，仅适用于信任的本地仓库；不能宣称支持任意 Shell 或安全执行不可信代码。

## 锚点后阶段 2：Coding 受审文本补丁（2026-09-15）

已实现 `WRITE/HIGH` 的 `apply_workspace_text_patch`。读取工具返回完整原始文件的 SHA-256；补丁把该摘要、相对路径和精确 replacements 绑定到既有一次性 ApprovalRequest，继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、RunStep 与 AuditEvent，没有新增数据库迁移或旁路写接口。

单元测试覆盖摘要输出、工具风险注册、成功原子替换、陈旧摘要、重复匹配和越界拒绝，并确认失败时原文件保持不变。会话集成测试用可控模型发起补丁，真实经过审批请求、批准、执行、文件变更、运行步骤和审计完成事件。全量后端 `mvn test` 为 41 个测试通过、0 失败、0 错误、0 跳过；H2 从空库校验 V1–V10，既有 AgentVersion、审批、RAG、MCP、运行控制和安全凭据测试均通过。

真实模型与浏览器验收已确认批准写入与拒绝跳过：运行 `3cc5b2f0-e54f-424a-804c-6cc801fa112f` 完成 `target-before → target-after`，运行 `10c216d9-3c49-433f-9cf9-f5b3cbdaace9` 完成反向恢复，运行 `814047b0-54f8-43af-ac9d-9b53622079fc` 在审批拒绝后记录 TOOL_EXECUTION_SKIPPED。陈旧摘要由用户确认通过并有自动化证据，但运行库没有可独立识别的对应失败记录。准确结论是受审文本补丁子阶段已验收；统一 diff、预览页、撤销、文件新建/删除、命令、构建测试和 SSH/SFTP 仍未实现。

## 锚点后阶段 1：Coding 只读工作区（2026-09-14）

已实现三个 `READ/LOW` 工具：`list_workspace_directory`、`search_workspace_files`、`read_workspace_text_file`。它们由 AgentTool 自动注册进入原 ToolRegistry，并继续通过 SafeExecutionGateway；没有新建数据库表或绕过 AgentVersion、RunStep、AuditEvent、审批、RAG、MCP 的既有代码路径。

路径安全自动化覆盖相对路径约束、`..` 和绝对路径拒绝、Windows 数据流语法拒绝、受保护目录/`.env`/常见密钥文件拒绝、二进制拒绝、严格 UTF-8、文件大小/行数/结果数量边界、目录浏览、文件路径搜索和分段读取。集成测试让可控模型调用目录浏览工具，验证 AgentVersion 绑定、RunStep 的 TOOL_RESULT，以及 AuditEvent 的校验/开始/完成事件；审计响应不包含文件名或文件内容。

2026-09-15 搜索性能修复后全量后端执行结果：38 个测试全部通过，0 失败、0 错误、0 跳过。Windows 路径测试在普通符号链接不可用时创建目录联接，验证指向工作区外的目标仍被拒绝；新增测试验证生成目录默认剪枝、显式指定剪枝目录仍可搜索，以及达到结果上限后提前停止。前端 `npm run build` 通过，29 个模块完成生产打包。

人工验收已确认用户日常目录中的真实模型目录浏览、文本读取和优化后的根目录搜索通过，浏览器 RunStep 与安全审计一致。负向路径和 Windows 目录联接由自动化验证，本轮没有重复全部负向浏览器操作。证据见 `CODING_WORKSPACE_ACCEPTANCE.md`。本阶段不包含内容搜索、补丁、写入、命令、构建测试、SSH/SFTP 或部署，不能据此宣称通用 Coding 已完成。

首轮真实验收中，运行 `b70a18b6-f47e-48b2-9d59-a710a50d7532` 的两次根目录 `search_workspace_files` 均在 10 秒超时；模型第三次把 `path` 缩小到 `backend` 后扫描 647 项、1995 ms 完成。该证据推动了轻量剪枝和重复路径解析优化。修复后运行 `e435a565-737a-45ab-ab42-64c2f544c11e` 不指定 `path`，首次调用扫描 333 项、跳过 7 个目录、98 ms 完成，返回两个源码文件且未包含 `target` 生成物。优化前失败记录继续保留，不能从项目历史中抹去。

## 阶段 12：1.0.0-rc1 正式版前加固（2026-09-06）

- 版本统一为 `1.0.0-rc1`，新增系统诊断 API 与前端第 07 页，检查 MySQL、pgvector、Embedding 实际调用、模型密钥变量、data 写权限和 MCP；
- RAG 空召回且无工具时固定拒答并跳过模型，有候选片段时注入严格证据契约；
- 后端测试由 24 个增至 31 个，新增并发会话隔离、知识库 chunk/删除隔离、MCP 配置与 Resource HTTP 入口、空召回拒答、系统诊断和 Windows DPAPI 覆盖；最终 31 个通过、0 失败、0 错误；
- 前端 `npm run build` 通过，TypeScript 和 Vite 生产构建成功；
- Tavily 真实多轮检索暴露的审批不可见与工具轮数收尾问题已修复：审批卡固定显示、标题提醒和秒级倒计时；第四轮工具返回后以无工具模型调用完成 `MODEL_FINALIZATION`，不再直接把已有成功结果标为失败；
- PowerShell 运维脚本通过 Windows PowerShell 兼容解析；预检在本机确认 Maven 使用 Java 21.0.4、Docker、Node、npm、Ollama 和 data 目录可用；
- 发布检查最初因运行中的 Vite 锁住 esbuild 而使原地 `npm ci` 失败；改为经过路径前缀校验的随机临时副本后，干净安装和生产构建通过，不干扰开发服务器；
- 首次用户执行一键启动时，前述中断的原地 `npm ci` 已把日常 node_modules 留在不完整状态，前端日志明确显示找不到 vite；重新 `npm install` 后恢复。启动脚本因此增加本地 Vite 完整性检查和自动补装，并改用隐藏 cmd 子进程，避免隐藏 PowerShell Host 在 Maven 设置控制台标题时抛出异常；复测还发现 Windows 可能把 `localhost` 解析到 IPv6，造成 Vite 已启动而 IPv4 健康检查误报超时，现已把 Vite 明确绑定到 `127.0.0.1`，并让停止脚本容忍清理瞬间的进程退出竞态。最终实测启动脚本约 14 秒成功返回，后端状态为 `UP`、版本为 `1.0.0-rc1`，前端返回 HTTP 200，停止脚本随后完整关闭两个进程树；
- Docker 真实备份演练成功，生成非空 MySQL SQL、pgvector SQL、data.zip 和带 `1.0.0-rc1` 的 manifest；恢复脚本的强制确认保护已验证，未在用户当前数据库执行破坏性恢复；
- 备份演练先后发现并修复旧版 PowerShell 解析差异、MySQL 应用账号缺少 PROCESS 权限、管道覆盖原始退出码三个问题。最终使用 `--no-tablespaces` 并在写文件前固定保存退出码，避免半份备份误报成功。

尚待用户集中人工验收：重启日常后端后查看“系统诊断”，完成真实 DeepSeek 普通/无答案问答、一次并发双会话、HIGH 工具批准与拒绝，以及 HTTP/stdio MCP 集中链路。通过后才从 rc1 升为 `1.0.0`。

## 阶段 13：真实网络检索后的运行收口（2026-09-07）

- Tavily Streamable HTTP 使用 Bearer 环境变量成功同步并执行。运行 `ddcba638` 中首次 search 已完成并产生额度，后续 extract 审批未在视口内被发现，90 秒过期后又触发重试，最终碰到 120 秒总时限。
- 运行 `4b7783e1` 的四个工具轮次全部获批并完成，包含多次 search 与 extract；最后已取得 MCP 官方规范和 GitHub Release 证据，却因循环结束后没有最终模型调用而失败。
- 修复后审批卡在消息滚动区内 sticky 显示，浏览器标题提示待审批，页面显示实时剩余秒数，过期后按钮禁用。
- ReAct 在正常轮次中加入“最少必要调用”约束；工具预算耗尽后移除全部工具并追加一次强制总结，记录为 `MODEL_FINALIZATION/COMPLETED`。模型若仍返回工具请求或空答案才安全失败。
- 运行记录继续只保留 4000 字符工具输出，模型观察上下文单独提高到 16000 字符，避免 Tavily 多来源结果因展示截断而迫使模型反复搜索。
- 回归测试把原“达到轮数即失败”用例改为“第五次无工具总结并完成”；目标测试 6 个通过，全量后端 30 个测试通过、0 失败、0 错误，前端 TypeScript 与 Vite 生产构建通过。

## 阶段 14：安全凭据与 Agent 版本生命周期（2026-09-07）

- 新增统一 SecretResolver，模型调用、HTTP MCP、stdio MCP 和系统诊断都按“进程环境变量优先、Windows DPAPI 次之”解析；
- 前端仅提交密钥新值并展示 ENVIRONMENT/SECURE_STORE/NONE 状态，后端 API、MySQL、AgentVersion 和 MCP 配置均不返回或持久化明文；
- SecretStore 专项测试真实执行 DPAPI 写入、解密与删除，并确认磁盘文件不含原始字符串；
- Flyway V10 为 AgentVersion 增加 archived_at；对话默认只显示每个 Agent 最新版本，历史版本可显式展开，归档版本不能通过 API 创建新会话；
- 永久删除要求“非最新版 + 已归档 + Conversation/AgentRun 零引用”，避免破坏历史运行；Agent 集成测试覆盖归档隐藏、管理查询、零引用删除和最新版拒绝归档；
- H2 从空库顺序执行 V1–V10，最终全量后端 31 个测试通过、0 失败、0 错误；前端 TypeScript 与 Vite 生产构建通过；`git diff --check` 无空白错误。

尚待用户本机人工验收：启动后由 MySQL 执行 V10，在页面保存 DeepSeek/Tavily 凭据并重启验证持久可用；发布两个测试版本，确认默认下拉、历史开关、归档/恢复和有引用版本禁止删除均符合提示。

## 阶段 0：工程基线（2026-09-02）

验证结果：

- 后端 `mvn test`：通过，2 个测试、0 失败、0 错误；
- 后端真实启动：通过，Spring Boot 3.4.3 使用 Java 21 启动于 8080；
- `GET /api/system/status`：通过，返回 `application=agent-studio-backend`、`status=UP`；
- 前端 `npm run build`：通过，TypeScript 检查与 Vite 生产构建完成；
- `docker compose -f docker/compose.yml config`：通过；
- MySQL 与 pgvector 镜像拉取、网络、数据卷和容器创建：用户环境已验证；
- 容器实启：pgvector 在 `15432` 已健康；MySQL 先后在 `3306`、`13306` 遇到宿主机端口占用，当前默认端口已调整为 `23306`，等待复验。

环境事实：

- Maven 3.9.11 使用 JDK 21.0.4；
- 系统 `java` 命令当前指向 Java 8，但 Maven 使用已配置的 Java 21；
- Node.js 22.13.1，npm 10.9.2；
- Docker CLI 29.2.0、Compose 5.0.2；当前开发会话无权访问 Docker Desktop 引擎，因此容器运行状态待在可访问引擎的环境验证。

真实异常与修复：

- 初次后端构建因依赖尚未下载且沙箱禁止联网而失败；授权下载后测试与启动均通过；
- 初次前端构建因 `tsconfig.node.json` 的 `allowImportingTsExtensions` 缺少 `noEmit` 而失败；补充 `noEmit: true` 后构建通过。
- Compose 实启先后发现本机已有服务占用 `3306` 和 `13306`；MySQL 默认映射调整为 `23306`，pgvector 保持 `15432`，并允许通过 `.env` 覆盖。

本阶段所有业务骨架均为本次新增，未迁移来源项目业务代码。

## 阶段 1：Agent Builder 最小主链（2026-09-02）

已实现：

- Flyway V1：模型配置、Agent 草稿、不可变版本、会话和消息表；
- 模型配置创建、更新与列表 API；
- Agent 草稿创建、更新、发布与版本列表 API；
- OpenAI Chat Completions 兼容流式调用；
- `run`、`delta`、`done`、`error` SSE 协议；
- 模型配置、Agent Builder 和对话测试台页面。

验证结果：

- `mvn test`：4 个测试通过，0 失败、0 错误；
- 版本隔离测试：模型配置和草稿更新后，v1 快照保持不变，v2 使用新配置；
- SSE 集成测试：验证 `run → delta → done` 事件及增量内容；
- `npm run build`：TypeScript 与 Vite 生产构建通过；
- Compose 配置解析：MySQL 默认宿主机端口为 `23306`，pgvector 为 `15432`。

真实异常与修复：

- SSE 集成测试最初受 Windows 测试响应字符集影响，中文断言显示为问号；事件结构无误，改用与字符集无关的 ASCII 增量断言后全部通过。

后续已经完成真实 MySQL、DeepSeek 和 RAG 验证，见阶段 2、3；工具运行时第一阶段见阶段 5。阶段 1 当时尚未实现工具、运行步骤、审批和审计。

## 阶段 2：真实模型与数据库（2026-09-02）

- 用户确认 MySQL `23306` 与 pgvector `15432` 两个容器均为 healthy；
- Spring Boot 真实连接 MySQL 8.4，Flyway V1、V2 校验及迁移通过；
- 使用用户配置的 DeepSeek OpenAI 兼容端点完成真实 SSE 调用，收到中文 `delta` 并以 `done` 正常结束；
- 模型密钥仍仅从启动后端的 `DEEPSEEK_API_KEY` 环境变量读取，未写入数据库。

## 阶段 3：知识库与检索增强（2026-09-02）

已验证：

- 文档上传、Tika 解析、切块和 pgvector 写入完成，示例 Markdown 状态为 `READY`；
- Agent 发布版本正确快照知识库标识，草稿 API 的 `status` 字段正常返回；
- SSE 顺序实测为 `run → sources → error`：`sources` 返回文件名、chunk 编号、内容和相似度；独立 8081 验收进程未设置 DeepSeek 密钥，因此模型阶段按设计返回明确 `error`；
- 删除文档后 MySQL 文档列表为空、本地文件不存在，重复检索不再返回 `sources`，随后重新上传恢复为 `READY`；
- 后端 `mvn test`：6 个测试通过，0 失败、0 错误；
- 前端 `npm run build`：TypeScript 检查与 Vite 生产构建通过。
- 浏览器冒烟检查：四个导航入口及新增知识库创建、文档上传控件均正常渲染；由于 5173 当时仍连接旧的 8080 后端进程，完整新接口交互改由独立 8081 实例完成。重启日常后端后前端即可使用新接口。

真实异常与修复：

- 引入 pgvector 数据源后，Flyway 和业务 JDBC 曾自动选择 PostgreSQL，导致 MySQL 表查询落到错误数据库；现已显式声明 primary MySQL DataSource/JdbcTemplate，并给四个业务仓储加限定绑定，向量仓储只使用 PostgreSQL。

当前限制：

- 内置 384 维向量为词法哈希基线，不是语义 embedding；
- 暂无跨存储分布式事务和失败补偿任务；
- 本阶段当时尚未实现工具调用、ReAct、审批、SSH 和 Coding 扩展；低风险工具与 ReAct 后续已在阶段 5 落地。

## 阶段 4：Qwen 语义向量生产升级（2026-09-02）

已实现：

- 新增 `EmbeddingGateway`，默认实现为 Ollama `qwen3-embedding:0.6b`，1024 维；LocalHash 作为环境变量可切换的回退；
- 新增版本化 `knowledge_chunk_v2`，保存模型和 `qwen3-0.6b-v1` 索引版本，旧 384 维表未删除；
- 新增知识库重建 API 和前端“使用当前模型重建索引”入口；
- Qwen 索引采用按知识库和版本过滤的精确 cosine 搜索，暂不使用 HNSW；
- 删除文档会同时清理旧、新两套向量表，重建失败只清理当前索引版本。

验证结果：

- 后端 `mvn test`：8 个测试通过，包括 Ollama 批量文档 embedding、查询前缀隔离和维度校验；
- 前端 `npm run build`：通过；
- 阶段 4 当时冻结语料（11 份文档、23 个 chunk）：Qwen Recall@5 100%、MRR 0.8000、改写题 MRR 0.7250、p95 358.656 ms；知识包后续扩充后的最新同语料结果见阶段 5；
- 真实 MySQL、pgvector 和 Ollama：知识库重建成功，`knowledge_chunk_v2` 实际保存 `qwen3-embedding:0.6b`、1024 维、`qwen3-0.6b-v1`；
- 真实 SSE：改写问题命中正确来源，最终无查询前缀配置下 score 约 0.40；独立 8081 进程未设置 DeepSeek 密钥，随后按预期返回模型环境变量错误。

消融与限制：

- 在知识包扩充前的同语料消融中，加入统一中文 query instruction 后 MRR 从 0.8160 降到 0.6917，因此默认关闭、保留可配置；
- 知识包内容变化会改变 chunk 数和指标，扩充后已同时重跑 LocalHash 与 Qwen，生产报告不混用历史语料结果；
- 阶段 4 的 Qwen Recall 和延迟达到约定目标，MRR 为 0.8000；扩充语料后已升至 0.8472，但仍保留 rerank 优化项；
- 2026-09-04 复现并修复 Ollama 进程继承空 `D:\ollama-models` 导致的索引失败：用已有模型目录启动后，`/api/embed` 返回 1024 维向量，失败文档通过重建 API 恢复为 READY；后端新增可操作的连接错误和完整异常日志；
- 无答案误召回率仍为 100%，固定阈值无法在当前题集上同时维持高召回和可靠拒答；
- 尚未进行结构感知切块、混合检索或 rerank，这些留作独立评测，不与 embedding 收益混在同一次改造中。

## 阶段 5：低风险工具与显式 ReAct（2026-09-04）

已实现：

- 内置 LOW/READ 工具 `current_time`，支持可选 IANA 时区并拒绝未知参数；
- Agent 草稿工具绑定和 AgentVersion 不可变工具快照；
- 显式“模型 tool call → 平台执行 → Observation → 模型续答”循环；
- 默认最大 4 轮、单轮最多 4 次工具调用和单工具超时；
- `AgentRun`、`RunStep` 持久化，以及 `run`/`step` SSE 与前端步骤面板；
- 工具失败会作为 Observation 反馈模型，越权调用和达到上限会安全终止。

验证结果：

- 后端 `mvn test`：12 个测试通过，0 失败、0 错误；
- 自动化覆盖普通流式路径、工具版本快照、成功工具循环、运行步骤查询、重复工具调用上限和工具超时；
- 前端 `npm run build`：通过；
- 真实 MySQL 8.4：Flyway V3 从版本 2 升至 3，创建工具绑定、运行与步骤表；
- 真实 API：`GET /api/tools` 返回 `current_time`，历史 Agent 正常读取且工具集合为空。

尚未验证与限制：

- 独立验收进程没有继承用户日常 8080 进程中的 `DEEPSEEK_API_KEY`，因此本阶段的真实 DeepSeek tool-calling 兼容性尚待用户重启后端后验证；自动化测试使用可控模型网关验证了完整循环；
- 工具版最终答案当前以单个 `delta` 返回，不是 token 级流式；
- 高风险审批、MCP、SSH、Coding、运行取消、总运行超时和崩溃恢复不在本阶段范围。

本阶段当时的知识包同步回归（已由阶段 6 新结果替代）：

- 更新后的 00–10 为 11 份文档、26 个 chunk，SHA-256 `67b0b2e5394d2687e663b913bce8d53d1bf908a5e616bc6ba10ccdded1503325`；
- Qwen Recall@5 100%、MRR 0.8333、改写题 MRR 0.8333、p95 234.978 ms；
- 同语料 LocalHash Recall@5 83.33%、MRR 0.6021、改写题 MRR 0.4958；
- 两者无答案误召回率仍为 100%，新增工具文档没有解决既有拒答问题。

## 阶段 5.1：Agent 草稿编辑与可选外键修复（2026-09-05）

- 真实运行状态检查：Flyway V3、MySQL、pgvector、模型、知识库和 `current_time` 均正常；数据库已有“科研知识助手”和“chat-test”；
- 首次判断曾误以为是同名创建；用户截图显示名称为全新的“查时间”，该判断被纠正；
- 实际根因：前端用空字符串表示“不使用知识库”，后端跳过校验后仍将空字符串写入可选外键，触发 MySQL 外键约束；
- 修复：前端提交 null，后端把 null、空字符串和纯空格统一归一化为 null；测试覆盖空字符串创建并断言知识库标识为 null；
- 修复：Agent 卡片新增编辑草稿，表单回填现有配置，保存调用 PUT，取消编辑可恢复空表单；
- 服务层主动检查 Agent 名称，重复时返回明确的“Agent 名称已存在，请换一个名称或编辑已有 Agent”；
- 回归：后端 12 个测试通过，前端生产构建通过；重名断言加入现有版本隔离测试。
- 真实 MySQL 验收：临时 8081 新代码对已有“科研知识助手”发起同名 POST，返回 HTTP 409 和精确提示；请求前后 Agent 数量均为 2，没有产生脏数据；验收进程随后关闭。

## 阶段 6：高风险工具审批最小闭环（2026-09-05）

- 真实 DeepSeek 工具调用已通过数据库步骤复核：上海和伦敦请求均形成 `MODEL_CALL → TOOL_CALL → TOOL_RESULT → MODEL_CALL`；伦敦参数为 `Europe/London`，结果正确包含夏令时 UTC+1；
- 新增 Flyway V4 `approval_request`，保存运行、工具调用、原参数、SHA-256、状态、理由和有效期；
- HIGH 工具执行前进入 `WAITING_APPROVAL`，通过 SSE 展示参数；批准只能按原摘要消费一次，拒绝/过期不执行；
- 新增受控样例 `write_workspace_note`：只在 `data/tool-workspace` 新建 `.md/.txt`，禁止覆盖、路径穿越、空内容和超长内容；
- 前端提供批准一次与拒绝操作，并显示审批步骤；默认审批有效期 90 秒；
- 后端 15 个测试通过，覆盖批准摘要消费、拒绝端到端循环、受控写入和路径穿越；前端生产构建通过。
- 临时 8081 真实启动验证：MySQL 8.4 从 V3 成功迁移到 V4，`GET /api/tools` 同时返回 `current_time` 与 HIGH/WRITE 的 `write_workspace_note`，健康状态为 UP；验收进程随后关闭。

当前限制：等待中的运行不能跨应用重启恢复，尚无登录/RBAC、审批人身份、主动取消、SSH、MCP 或通用命令执行。

用户人工验收补充：拒绝 `approval-reject-test.md` 后未执行写入；批准 `approval-accept-test.md` 后文件创建成功，真实审批双分支通过。

知识包最终同步回归：

- 更新后的 00–10 为 11 份文档、27 个 chunk，SHA-256 `e3338b860bd32c6bd6891c2ff981a81f3f1cba0446b22af153a0ea4c7a479ccb`；
- Qwen Recall@5 100%、MRR 0.8854、改写题 MRR 0.8958、p95 245.068 ms；
- 同语料 LocalHash Recall@5 87.50%、MRR 0.6632、改写题 MRR 0.5903；
- 两者无答案误召回率仍为 100%，审批材料扩充没有改变既有拒答短板。

## 阶段 7：运行取消、总时限与历史详情（2026-09-05）

- 新增 RunControlService，以 runId 绑定执行线程和默认 120 秒截止时间；配置项为 `AGENT_RUN_TIMEOUT`；
- `POST /api/runs/{id}/cancel` 先持久化 CANCEL_REQUESTED，再中断执行线程；最终区分 CANCELLED 与 TIMED_OUT；
- 模型流、ReAct、审批等待和工具失败边界均检查终止信号，终止写入 RUN_TERMINATION 并发送 `terminated` SSE；
- `GET /api/runs` 返回最近运行摘要，`GET /api/runs/{id}` 返回完整步骤；前端新增停止按钮和运行记录页面；
- 应用启动时把旧进程遗留的非终态运行关闭为 INTERRUPTED、待决审批改为 EXPIRED；这是状态收口，不是断点续跑；
- 后端 17 个测试通过，其中阻塞模型测试覆盖主动取消、300 ms 总超时、历史摘要和终止步骤；前端生产构建通过。
- 提交前并发复核补上最终状态的条件更新，防止 `COMPLETED`/`FAILED` 在取消竞态中覆盖 `CANCEL_REQUESTED`；完整 17 测试与前端构建再次通过。
- 用户在真实 DeepSeek、MySQL 和浏览器环境完成运行治理验收：运行 `86918b87` 主动停止后为 `CANCELLED`、记录 4 步并显示“用户主动取消运行”；运行 `f72e72ab` 不干预直至默认 120 秒截止后为 `TIMED_OUT`、记录 7 步并显示“运行超过总时限 120 秒”。

当前限制：Java 中断是协作式取消；第三方工具仍需提供自己的取消能力。系统按单实例设计，多实例共享数据库前需要实例租约。客户端直接断开还不会自动提交取消，应用重启也不会从 RunStep 继续。

## 阶段 9：MCP Streamable HTTP 核心闭环（2026-09-05）

- 新增 Flyway V6，持久化 MCP Server、协议协商结果、同步错误和带描述指纹的工具目录；
- 实现 MCP `2025-06-18` initialize/initialized、tools/list 分页和 tools/call，支持 application/json 与 POST 返回的 text/event-stream，并传递 session id 和协议版本头；
- 工具生成平台稳定名称，避免跨 Server 重名；描述或 Schema 改变产生新修订，旧记录不覆盖；
- 动态 MCP 工具进入原 ToolRegistry，并继续经过 Schema 校验、HIGH 一次性审批、目标绑定、单工具超时、运行取消和 AuditEvent；
- MCP 凭据只保存环境变量名。HTTP 仅允许回环地址，远程 Endpoint 强制 HTTPS；
- 前端新增 MCP 连接页，支持保存并连接、失败原因、重新同步和发现工具展示；
- 随项目新增零依赖 Node.js fixture Server，可人工验证完整链路。

自动化证据：H2 成功应用 V1 至 V6；协议测试覆盖有状态初始化、协议版本头、工具发现、tools/call 和 JSON-RPC 错误；集成测试覆盖动态工具进入统一注册表、稳定命名、HIGH 风险、目标环境和执行委托。全量后端共 23 个测试通过，前端生产构建通过。

阶段 9 当时尚待人工验证用户本机 MySQL 的 V6 迁移，以及 DeepSeek 对动态 MCP 工具的真实 tool_call、批准和拒绝分支；当时也不支持 stdio。后续用户已提供 HTTP MCP 批准调用成功的运行记录，stdio 与管理能力则在阶段 10 补齐。

知识包加入 MCP 材料后再次执行冻结 30 题回归：00–10 为 11 份文档、35 个 chunk，语料 SHA-256 `a1970fceb87a09ad2044ef027b1de0cd3086b510842f3d8817e0ab79bac46259`。LocalHash Recall@5 87.50%、MRR 0.6854；Qwen Recall@5 100%、MRR 0.8278、改写题 MRR 0.8361、p95 233.375 ms。两者无答案误召回率仍为 100%，MCP 文档扩充没有解决证据充分性判断。结果 JSON 保存每份文档哈希；后续文字修订应以新快照复测，不能把本段哈希当作始终不变的目录标识。

## 阶段 10：MCP stdio 与日常管理（2026-09-05）

- Flyway V7 增加 transport、stdio 启动参数/工作目录/环境变量引用和单工具 enabled 策略；原 HTTP Server 自动保持 `STREAMABLE_HTTP`；
- 新增 stdio 客户端，覆盖进程启动、初始化、换行 JSON-RPC、tools/list、tools/call、stderr 诊断、取消通知和分级进程清理；
- 启动参数不经过 shell，拒绝直接配置命令行解释器；密钥只按宿主环境变量名注入，不写入数据库；
- MCP 页面支持 HTTP/stdio 条件配置、编辑、启停、受保护删除、同步差异，以及单工具启用、风险和超时策略；
- 被 Agent 草稿或历史版本引用的 Server 不能物理删除，保证运行记录与版本引用仍可解释；
- 零依赖 stdio Node fixture 可用于人工全链路验收。

自动化证据：H2 成功应用 V1 至 V7；真实子进程测试完成 stdio 发现和调用；HTTP 协议与统一注册表回归继续通过；管理集成测试覆盖同步差异、工具停用、Server 停用和未引用删除。全量后端 24 个测试通过，前端 TypeScript 与 Vite 生产构建通过。

尚待人工验证：用户本机 MySQL 应在后端重启时从 V6 升至 V7，并在页面使用 stdio fixture 完成真实 DeepSeek tool_call。当前未实现 OS 沙箱、OAuth、resources、prompts、sampling、roots、elicitation、list_changed 订阅和 session 池。

知识包完成 stdio 与 Server 管理材料后，重新运行同一冻结 30 题：00–10 仍为 11 份文档，切分后为 38 个 chunk，语料 SHA-256 `686a21f66cf2d931e298d5a77e25d5d0658909e7bd5201708d79273befd098cd`。LocalHash Recall@5 87.50%、MRR 0.6542、改写题 MRR 0.5306、p95 0.272 ms；Qwen Recall@5 100%、MRR 0.8958、改写题 MRR 0.8333、p95 260.366 ms。两者无答案误召回率仍为 100%。结果保存在 `local-hash-after-mcp-stdio.json` 与 `qwen3-embedding-0.6b-after-mcp-stdio.json`。

知识包同步回归：

- 00–10 扩充为 11 份文档、32 个 chunk，SHA-256 `954f15a497955490510433e99c0878eb21df9975dba56644a506648f6aabb482`；
- Qwen Recall@5 100%、MRR 0.8542、改写题 MRR 0.8056、p95 244.784 ms；
- LocalHash Recall@5 79.17%、MRR 0.6333；两者无答案误召回率仍为 100%；
- 语料增长后 Qwen MRR 低于上一版 0.8854，已如实记录为结构化切块与 rerank 的后续输入，没有用旧语料指标覆盖。

## 阶段 8：统一安全执行网关与结构化审计（2026-09-05）

- Flyway V5 新增 `audit_event`，审批记录补充会话、AgentVersion、能力、风险和目标环境；H2 从空库顺序执行 V1–V5 通过；
- ChatService 把工具执行委托给 SafeExecutionGateway，固定执行 Schema 校验、风险审批、取消检查、限时执行和审计；
- AuditEvent 只保存参数 SHA-256 和输出长度/耗时等摘要，不复制原始参数或完整工具输出；
- `GET /api/audit-events?runId=...` 返回单次运行审计，运行详情页新增安全审计区；
- 自动化覆盖 LOW 工具的校验与执行审计、HIGH 工具的审批与跳过审计，以及缺失字段、额外字段和错误类型三类 Schema 拒绝；
- 后端 20 个测试通过，0 失败、0 错误；前端 TypeScript 与 Vite 生产构建通过。

尚待真实环境验证：用户重启后端后由 MySQL 执行 V5，并分别运行一次 current_time 和 write_workspace_note，确认历史详情出现安全审计。当前审计没有用户身份、审批人、签名防篡改、租户隔离和保留策略，只能称为本地结构化审计。

## 阶段 11：MCP Resources、Prompts 与集中收口（2026-09-05）

- Flyway V8 增加 Resource 与 Prompt 目录，分别保存 URI、媒体类型、大小、参数描述、active 状态和稳定标识；V9 增加 MCP 同步历史；
- Streamable HTTP 与 stdio 都按协商 capability 分页发现 tools、resources 和 prompts，不要求 Server 同时支持三者；
- Resource 支持按需读取、预览及导入当前知识库。导入复用 20 MB 限制、Tika、切块、EmbeddingGateway 和 pgvector；多段文本合并，混合或多段二进制拒绝；
- Prompt 支持必填参数校验和 `prompts/get`，保留 Server 返回的角色与内容；它只由用户主动使用，不会自动替换 Agent 系统提示词；
- 每次同步保存 READY/FAILED、协议版本、三类能力数量、工具差异与错误；管理台可查看最近记录；
- Server 配置支持 JSON 导入导出，只包含 Bearer Token 环境变量名或 stdio 环境变量名称映射，不包含密钥值；
- HTTP 与 stdio 的零依赖 fixture 均提供工具、Resource 和 Prompt，集中人工验收不依赖第三方 MCP 服务。

自动化证据：H2 从空库成功执行 V1–V9；HTTP 协议测试在独立短 session 中完成三类目录发现、resources/read、prompts/get 和 tools/call；stdio 测试真实启动 Node 子进程完成同一组能力；服务集成测试验证目录持久化、读取委托、Prompt 参数、同步历史和安全配置导出。全量后端 24 个测试全部通过，前端 TypeScript 与 Vite 生产构建通过。

尚待集中人工验收：用户本机 MySQL 从 V7 迁移到 V9，页面分别连接 HTTP/stdio fixture，完成 Resource 预览与知识库导入、Prompt 参数生成、工具批准/拒绝、同步历史和配置导入导出。当前仍不支持 OAuth 授权码/PKCE 与安全令牌存储、sampling、elicitation、roots、resource templates、subscriptions/list_changed、旧版 HTTP+SSE 或长 session 池；stdio 仍无 OS 级沙箱。以上是明确的扩展边界，不能描述为“兼容全部 MCP Server”。

知识包同步回归：冻结的 00–10 为 11 份文档、42 个 chunk，语料 SHA-256 `35639a984b8a9607645ea4b7447d4643dd63161431b7a66e0c2026ebdae5e3a8`。LocalHash Recall@5 87.50%、MRR 0.6507、改写题 MRR 0.5903、p95 0.296 ms；Qwen Recall@5 100%、MRR 0.8542、原题 MRR 0.9167、改写题 MRR 0.7917、p95 275.724 ms。两者无答案误召回率仍为 100%。结果保存在 `local-hash-after-mcp-primitives.json` 和 `qwen3-embedding-0.6b-after-mcp-primitives.json`；知识扩充后 Qwen 仍保持全召回，但无答案判断依旧是明确短板。
