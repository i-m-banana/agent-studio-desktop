# SSH/SFTP 受审远程文本补丁人工验收

适用范围：`feat/coding-workspace` 的远程单文件精确替换子阶段。代码、自动化和真实 Ubuntu/OpenSSH 服务器/模型验收均已完成。

首次真实尝试发现 Ubuntu/OpenSSH 协商 SFTP v3，原实现错误地直接使用只适用于 v5+ 的标准 `Atomic/Overwrite` rename 选项；两次调用均在客户端失败，远程文件保持不变。现已改为优先使用 OpenSSH 公布的 `posix-rename@openssh.com` 原子覆盖扩展，并用强制 v3 自动化复现和验证；重新验收时已重启后端加载修复代码。

## 准备

1. 在已授权远程根下人工创建 `manual-acceptance/ssh-patch-sample.txt`，内容为三行：`alpha`、`remote-before`、`omega`。准备动作不是 Agent Studio 的新建文件能力。
2. 确认“模型配置—SSH 远程工作区”的连接测试通过。
3. 在 Agent Builder 绑定 `read_remote_workspace_text_file` 和 `apply_remote_workspace_text_patch`，发布新 AgentVersion；确认补丁工具显示 `SSH/WRITE/HIGH`。

## 正向与恢复

1. 请求：“先读取 `manual-acceptance/ssh-patch-sample.txt`，再把唯一的 `remote-before` 精确替换为 `remote-after`，需要审批时停下。”
2. 审批前登录服务器确认文件未变化；核对审批卡内路径、`expectedSha256` 和 replacements 后批准。
3. 确认工具返回 `updated=true`、不同的前后摘要、`replacementsApplied=1`，服务器文件只有第二行变化。
4. 用相同方式把 `remote-after` 恢复为 `remote-before`，确认第二次批准也成功。
5. 两次运行详情都应有读取及补丁 TOOL_CALL/TOOL_RESULT；审计应有 TOOL_REQUEST_VALIDATED、APPROVAL_REQUIRED、APPROVAL_DECIDED、TOOL_EXECUTION_STARTED、TOOL_EXECUTION_COMPLETED，目标环境为具体 `SSH:user@host:port/root`。

## 反向

1. 再发起同类补丁，在审批卡点击拒绝；远程文件必须不变，并形成 REJECTED 与 TOOL_EXECUTION_SKIPPED。
2. 让 Agent 读取并生成审批卡，在批准前从服务器人工修改文件，再批准旧请求；应因摘要过期失败且保留人工修改。
3. 可选加强项：对含两个相同 `duplicate` 的文件发起单处替换，应因无法唯一定位而失败；`.env`、`../`、符号链接、二进制和不存在文件同样不得被写入或创建。

## 验收记录

- 验收日期：2026-09-15
- AgentVersion：`3c1643fc-8d44-4e0f-a125-b93e6dc32b52`
- 修复前失败运行：`33853a3f-46cd-4100-a048-769bd3327fb7`。两次批准均因 OpenSSH SFTP v3 不接受标准 rename 选项而失败，远程文件未改变；该记录推动 POSIX rename 兼容修复。
- 正向批准运行：`c02f0f09-ad25-4a29-9717-5933f1830927`。读取摘要 `e6ff0748...`，批准后完成 `remote-before → remote-after`，结果摘要 `55dbaf65...`，耗时 2403 ms。
- 恢复运行：`1349ba57-8f72-467d-a8c4-51f223387094`。读取摘要 `55dbaf65...`，批准后恢复 `remote-after → remote-before`，结果摘要 `e6ff0748...`，耗时 2520 ms。
- 拒绝运行：`3381d12b-4465-4350-b3dd-03750fb909de`。审批状态 REJECTED，AuditEvent 形成 TOOL_EXECUTION_SKIPPED，工具没有执行。
- 陈旧摘要运行：`e139b076-8946-4322-bb97-ea9a18799eae`。审批等待期间人工把文件从摘要 `52ecb02f...` 改为 `292a9533...`，随后又继续修改；两次批准的旧摘要补丁均形成 TOOL_EXECUTION_FAILED，Agent 重新读取后确认 `remote-before` 未被覆盖且人工新增内容仍在。
- 结论：固定指纹、单远程根、密码认证的受审远程单文件文本补丁已通过自动化及真实模型的批准、恢复、拒绝和陈旧摘要验收。不能据此宣称远程 Shell、Git、构建、部署或通用 Coding Agent 已完成。
