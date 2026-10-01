# 候选应用镜像构建人工验收

2026-10-01 更新：新候选20261001T102858Z-61f00ca1的真实构建68a9c386-23c2-4e79-86a1-d90575858f27通过IMAGE_READY、RUNTIME_SMOKE及构建器清理，142840ms；随后同一镜像真实上线通过。详细证据见CONTROLLED_RELEASE_ACCEPTANCE.md。下方“待验收”保留为修复前历史描述。

2026-09-30 补充：基线登记曾因候选镜像内非 root 用户无法读取 JAR 而报 `PropertiesLauncher ClassNotFoundException`。修复后，新构建会在返回成功前额外执行 `RUNTIME_SMOKE`：无网络、只读、非 root 启动固定维护入口，以固定无效摘要得到受控拒绝；这不是连接数据库或登记基线。旧镜像即使曾显示构建成功，也不能据此通过新自检。请使用相同候选 ID 与清单摘要重新构建，获取**新的 imageId**；不必重做候选准备，也不要删除旧镜像或运行全局清理。真实新构建仍需人工审批与核验。

2026-09-28 真实服务器与真实模型正向镜像构建通过，原始证据见 RELEASE_IMAGE_BUILD_POSTMORTEM；拒绝、篡改、超时和重试耗尽等负向场景主要为自动化证据。上一阶段候选准备及本阶段构建正向验收均不代替生产发布验收。

## 影响范围

本工具不改生产文件、不执行 Compose 切换、不重启网站；但下载、构建与镜像导入会占用 CPU、内存、磁盘、网络。构建器限额为 512 MiB、0.5 CPU，Docker daemon 的导入/下载不保证受同样限额约束。建议低流量时测试；保留至少 2 GiB Docker 磁盘空间、768 MiB 主机可用内存。不自动安装依赖、不 prune、不删除旧镜像或候选。

## 操作步骤（中文工具对照）

2026-09-29 慢速分层下载修复：审批目标改为 LIMIT:512MiB,0.5CPU,900s 和 REGISTRY_RETRY:3_MAX,SHARED_900s,5s_BACKOFF,TRANSPORT_ONLY。最多三轮共享15分钟构建预算，不是每轮15分钟；源码/权限失败和阶段超时不重试。远程命令1100秒、工具1140秒、包含此工具的会话至少1200秒，SSE与运行一致；其他会话预算不变。仍保留有界输出和专用构建器清理。该新预算仅自动化通过后可重试，不能宣称真实下载已通过。

本次失败候选可直接复用：releaseId=20260929T141749Z-2d71736c，manifestSha256=4af52f7d5158cd73bec36457226e16ccdeda18c330fcb51e13b80e79dd1ba388。刷新界面后重新请求构建并审批一次，不用重新准备jar。审批应显示新900秒策略；失败先查原始记录，网络持续不可用仍可能失败，不得无限重试。

镜像源修复后的重试可直接复用已经准备成功的格式 2 候选，无需重新构建 jar。临时 BuildKit 现显式配置用户批准的 https://docker.1ms.run/、https://docker.1panel.live/、https://docker.ketches.cn/，审批目标应包含 REGISTRY_MIRRORS 和 BUILDKIT_CONFIG_SHA256；工具参数仍仅 ID 和清单摘要，不可传镜像源。输出应包含 REGISTRY_CONFIG 阶段及镜像源列表、配置摘要。真实连通性和成功镜像尚待人工验收，镜像源失败时仍可能回退 Docker Hub。

1. Agent Builder 勾选“准备不可变发布候选（prepare_release_candidate）”和“构建候选应用镜像（build_release_candidate_image）”，发布新版本。
2. “远程工作台 → 部署”选择该版本，固定目标必须 READY。先运行“发布指纹”“服务状态”“站点健康”，记录生产基线。
3. 重新点“请求准备候选”并批准一次。旧候选格式 1 没有绑定 Dockerfile，不能用于镜像构建；新候选格式 2 会绑定全部固定材料。
4. 成功后镜像卡自动填入候选 ID 与完整清单 SHA-256；若切换页面或使用旧会话，从本次成功结果复制这两项。不要填写服务器路径，也不要填制品 SHA-256。
5. 点“请求构建镜像”。审批卡工具应为 build_release_candidate_image，参数仅 releaseId、manifestSha256 两项，目标标记 IMAGE:BUILD_ONLY，显示资源限额；确认 ID/摘要后批准一次。
6. 预期 successful=true、exitCode=0，返回独立 imageTag、sha256 imageId、manifestSha256、buildAttemptPath、productionModified=false、servicesRestarted=false；输出末尾有 BUILDER_CLEANED=true。保留运行 ID 与镜像结果，后续发布须绑定镜像 ID，不凭“最新标签”猜测。
7. 再运行三项生产基线诊断，确认生产指纹未变、服务仍运行、健康仍 HTTP 200。本阶段网站展示不应发生版本变化。

服务器可独立只读核查（将标签换成结果的准确 imageTag）：

```sh
docker image inspect '<返回的 imageTag>' --format '{{.Id}} {{json .Config.Labels}}'
# 工具使用隔离 Docker 配置，默认 buildx ls 不足以证明专用 builder 已清理。
docker --config '<返回的 buildAttemptPath>/docker-config' buildx ls
docker ps -a --filter 'name=buildx_buildkit_<返回的 builderName>0'
docker ps --format '{{.Names}} {{.Status}}'
```

专用 builder 名称以 agentstudio- 开头，只核查本次返回的 builderName。成功后该 builder 应已清理；候选原始文件和生产容器保留。不要手动执行 docker prune 或删除生产镜像。

## 拒绝与失败

- 拒绝审批：不得连接 SSH 或开始构建。
- 错误清单摘要、旧格式、越界候选 ID、任意额外 command/tag/env：不得构建。
- 磁盘/内存不足、缺少 Buildx、registry 不可达：保留诊断结果，不安装依赖或退回无限额模式。
- Docker 普通非零退出：返回失败执行结果；候选与构建尝试保留供排查。
- 构建失败同时清理失败：错误必须分别展示原始 stage、构建 exitCode、原始输出、builder/attempt 和清理退出码/输出，不能用清理错误覆盖构建原因。退出码 124 的阶段超时仍作为工具失败，但保留下载/启动日志；已成功清理的构建器重复清理应直接成功。
- 超时/取消：通道关闭并请求清理专用构建器；网络中断时清理可能未确认，需独立核查本次 builderName。不能据取消消息宣称远程所有活动均已停止。

## 后续链路

目前完成候选准备和镜像构建正向验收。下一阶段开发“确定镜像 → 新备份 → 应用切换 → 健康检查 → 失败恢复”，但正式发布前必须解决网站 ddl-auto: update 的数据库兼容回滚前置条件。整条链路完成并验收后，会提供面向用户的完整中文操作说明与预期效果；现在不能宣称完整发布闭环。
