# Coding 受审文本补丁人工验收

适用范围：`feat/coding-workspace` 的“受审补丁”子阶段。代码、自动化与真实模型的批准/拒绝主链已经完成；不同证据强度在文末分别记录。

## 准备

1. 确认后端的 `AGENT_STUDIO_CODING_WORKSPACE` 指向专门用于验收、且不含真实秘密的代码目录，然后重启后端。
2. 在工作区新建 `manual-acceptance/patch-sample.txt`，写入三行：`alpha`、`target-before`、`omega`。该准备动作由人完成，不是本工具的新建文件能力。
3. 在 Agent Builder 创建或更新测试 Agent，同时绑定 `read_workspace_text_file` 与 `apply_workspace_text_patch`，发布一个新版本。确认补丁工具显示为 `WRITE/HIGH`。

## 正向主链

1. 对新版本发出：“先读取 `manual-acceptance/patch-sample.txt`，再把唯一的 `target-before` 精确替换为 `target-after`；需要审批时停下来。”
2. 第一次读取结果应出现 `sha256`；随后页面应展示 `apply_workspace_text_patch` 的审批卡，包含相对路径、`expectedSha256` 和 replacements，不应在批准前修改文件。
3. 核对参数准确后点击批准。文件应只把第二行改成 `target-after`，其他内容不变；最终工具结果应包含 `updated=true`、不同的前后摘要和 `replacementsApplied=1`。
4. 打开运行详情，确认存在读取和补丁的 TOOL_CALL/TOOL_RESULT；安全审计应包含补丁的 TOOL_REQUEST_VALIDATED、APPROVAL_REQUIRED、APPROVAL_DECIDED、TOOL_EXECUTION_STARTED、TOOL_EXECUTION_COMPLETED。AuditEvent 不应出现文件正文或相对路径明文。

## 拒绝与过期摘要

1. 把文件恢复成准备内容，再发起相同请求；审批卡出现后点击拒绝。文件必须保持不变，运行与审计应显示 REJECTED 和 TOOL_EXECUTION_SKIPPED。
2. 让 Agent 先读取文件并生成补丁请求，但在批准前由人工修改该文件，然后批准旧请求。工具应因 `expectedSha256` 与当前文件不一致而失败，不能覆盖人工新内容。
3. 准备一个包含两处完全相同 `duplicate` 的文件，请求只把 `duplicate` 替换一次。工具应提示原文本出现多次并拒绝写入。
4. 请求修改 `.env`、工作区外路径、二进制文件或不存在的文件，均应失败；不得创建新文件。

## 验收记录

- 验收日期：2026-09-15
- AgentVersion：`e8f3cc22-3710-4709-bb23-e84d214faa1e`
- 正向批准运行：`3cc5b2f0-e54f-424a-804c-6cc801fa112f`。读取摘要 `23a75e2e...`，批准后单次替换为 `target-after`，结果摘要 `f8323880...`；RunStep 和 AuditEvent 均完整。
- 恢复样例运行：`10c216d9-3c49-433f-9cf9-f5b3cbdaace9`。批准后把 `target-after` 恢复为 `target-before`，用于确认反向精确替换同样成功。
- 拒绝运行：`814047b0-54f8-43af-ac9d-9b53622079fc`。审批状态 REJECTED，AuditEvent 记录 TOOL_EXECUTION_SKIPPED，工具未写入。
- 陈旧摘要：用户确认人工测试通过，但运行库中没有一条可独立识别为该场景的失败记录，因此不填写虚构运行 ID；自动化对陈旧摘要及失败不改文件有确定证据。
- 结论：真实模型的摘要读取、HIGH 审批、批准执行、拒绝跳过和结果审计主链通过；陈旧摘要由用户人工确认与自动化共同覆盖，但缺少独立运行 ID。准确口径是“Coding 受审文本补丁子阶段已验收”，仍不能宣称通用 Coding、任意写入或命令执行完成。
