# ADR 0026：绑定候选的隔离应用镜像构建

2026-09-30 补充决策：真实登记出现 `PropertiesLauncher ClassNotFoundException`，只读基线状态随后确认目标数据库无 Flyway 历史表。经核对原候选 JAR 含该类；隔离构建脚本 `umask 077` 与 `cp` 组合会让新建上下文 JAR 可能仅 root 可读，Docker `COPY` 可保留此模式，而镜像/维护任务均以非 root 身份运行。保持原候选 0600，只对经摘要再校验的独占构建副本执行 `chmod 644`。在 IMAGE_VERIFY 后新增 RUNTIME_SMOKE：固定无效 SHA、无网络/只读/非 root/资源限额，必须得到维护入口的 `IllegalArgumentException` 受控拒绝；否则不签发镜像成功回执。超时/中断按所有者标签尝试清理专用 smoke 容器，仍不 prune、不改生产；网络中断时清理不能绝对保证。真实新镜像待人工验收。

## 状态

2026-09-29 补充：运行397ce071-f708-4f3e-8042-8f3e65dd0017在IMAGE_BUILD下载分层，300秒硬上限导致exit124；非死锁、非材料错误，清理退出码0。将构建/三轮共享预算改为固定900秒（ReleaseImageCommands常量），远程命令1100秒、工具1140秒、含镜像工具的会话至少1200秒（保留更长配置），SSE同预算。审批目标同步900秒，参数仍两项、限额不变。此前300秒记载为历史策略；新预算仅自动化验证，真实重试待用户批准。延长有限预算不等于修复外部镜像源，也不放宽TLS或删除构建能力。

已实现，自动化验证结果见 VERIFICATION；真实模型与真实服务器正向镜像构建通过（运行 4fdc8583-f14d-4052-9ee7-65e85b732065），负向场景主要为自动化证据。未实现生产切换。完整问题复盘见 RELEASE_IMAGE_BUILD_POSTMORTEM。

## 决策

新增业务工具“构建候选应用镜像”（`build_release_candidate_image`），标记 SSH/EXECUTE/HIGH。模型只可传 releaseId 和 manifestSha256，严格限定格式、拒绝额外字段；不允许路径、Docker 命令、镜像标签、构建参数、环境变量、服务名或部署选项。参数与固定 SSH 身份/指纹、部署 Profile、资源限额经现有 SafeExecutionGateway 和一次性 ApprovalRequest 绑定，继续复用 AgentVersion、ToolRegistry、RunStep、AuditEvent，不新增执行 API 或 MCP Server。

候选清单升级格式 2，除 jar 外还绑定 Dockerfile、Compose、Nginx 摘要。旧格式 1 候选保留，但必须重新准备才能构建。构建在同一个固定指纹 SSH 会话中逐级 SFTP 检查候选祖先与固定文件的符号链接、读取有界清单并核对审批指定的摘要；随后固定 Exec 再校验全部材料，只将 app.jar 和 Dockerfile 复制到独占构建尝试目录，再次核对快照。不会执行候选 Compose，也不会读取 .env。Dockerfile 是可信源码的一部分，RUN 内容仍会在构建器内执行，因此本功能不支持运行不可信仓库。

平台生成随机尝试 ID、唯一镜像标签与专用 Buildx docker-container 构建器；不改变默认构建器。固定使用本机 unix:///var/run/docker.sock，Docker 配置也放在尝试目录中，隔离远端环境中自定义 Docker 目标。预检查 Docker/Buildx、timeout/flock、Docker 根可用磁盘至少 2 GiB、主机 MemAvailable 至少 768 MiB，固定文件锁防止平台构建并发。配置并读回构建容器 512 MiB 内存（含 swap）、0.5 CPU 限额；限额覆盖构建器，不保证整个 Docker daemon 的导入/下载资源均受限。

启动构建器最多 120 秒、Docker build 最多 300 秒，Java 通道预算 500 秒、工具 540 秒、含该工具的版本运行预算复用 900 秒。stdout/stderr 合并并限制约 16 KB，保留首尾；成功回执置于尾部并验证 releaseId、manifest 摘要、镜像标签和 sha256 镜像 ID。普通非零构建退出作为可审计结果，摘要错误及超时为工具失败。失败、取消或超时关闭通道并在同一会话尝试有界清理；远端也有 EXIT/HUP/INT/TERM 清理。只移除本次专用 builder 及其缓存状态，不删除候选、生产镜像或生产容器。网络断开/宿主机故障时无法保证清理成功，可能残留尝试目录、镜像或专用构建器，不自动全局清理。

## 有意不包含

2026-09-28 补充失败诊断：远端阶段标记区分 PRECHECK、BUILDER_CREATE、BUILDER_BOOTSTRAP、RESOURCE_LIMIT_VERIFY、IMAGE_BUILD、IMAGE_VERIFY、BUILDER_CLEANUP。EXIT 清理必须保留进入清理前的原始退出码；清理成功写入本次尝试的 builder.cleaned，后端补偿清理据此幂等返回，不把“已经移除”报为失败。真正清理失败时同时保留原始构建输出/退出码与独立清理诊断，不丢弃任何一层原因。网络故障下仍不保证清理完成。

- 不切换 Compose、不重启 app/Nginx/MySQL、不修改生产配置、不迁移数据库。
- 不运行站点发布/回滚，不推送 registry，不 prune，不清理历史镜像或候选。
- 不安装 Docker/Buildx，缺少依赖则失败，不自动退回无限额构建。
- 不宣称完全离线：RUN 使用 --network none，但构建器及基础镜像可能从 registry 下载；基础镜像标签尚未固定 digest，不能宣称可重复构建。

## 2026-09-28：显式镜像源策略

用户确认服务器 Docker 已配置 https://docker.1ms.run/、https://docker.1panel.live/、https://docker.ketches.cn/，并批准临时构建器接入这三个地址。工具不读取并盲信远端任意 mirror 配置，也不接受模型提供 URL；本阶段将批准的三项作为固定策略。每次在独占尝试目录以不可覆盖方式生成 0600 buildkitd.toml，核对 SHA-256，再以 --buildkitd-config 传给本次专用构建器；文件不进入 app.jar/Dockerfile 两文件构建上下文。docker.io 的镜像源及三个目标均不启用 HTTP 或 insecure。

审批目标包含完整 HTTPS 镜像源列表和配置 SHA-256；结果与成功回执也携带摘要并严格校验。配置仅影响本次 BuildKit，不改 /etc/docker/daemon.json、不重启 Docker、不切换默认 builder、不改候选 Dockerfile。构建器自身的 moby/buildkit 镜像仍由宿主 Docker daemon 拉取，沿用服务器现有配置。BuildKit 可能在镜像源失败后回退官方 registry，因此不能承诺完全不访问 Docker Hub，也不能据本次接入宣称第三方镜像源可靠或受信任程度等同官方来源；本阶段基础镜像标签仍未固定 digest。

## 官方依据

2026-09-28 审计摘要边界：audit_event.details 保持 VARCHAR(1000)，截断标志必须计入 1000 字符限制，且不切断代理对。成功与失败工具 RunStep 都保留有界长输出；短审计摘要不替代原始故障证据。数据库写入错误不能证明远端任务没有执行，也不能依赖模型叙述判断镜像和清理状态。修复不允许绕过 SafeExecutionGateway 或跳过审计。

2026-09-28 下载中断处理：只有日志同时包含 registry/下载阶段标记和 unexpected EOF、i/o timeout、TLS handshake timeout、connection reset 或临时 DNS 故障时，才允许在同一审批、同一专用构建器和标签内最多三次 build。三次共用原 300 秒墙钟预算，间隔固定 5 秒；124/137 不重试，普通编译/权限/材料错误不重试，不新增镜像源、不降低 TLS、不改 Dockerfile、不启用 --pull/--no-cache。每轮独立日志受 ulimit -f 2048 限制（单位由目标 shell 决定，通常 512 或 1024 字节，最多约 2 MiB），远端输出仍首尾有界 16000 字符。成功仍须镜像、标签、清单和清理回执全部通过校验。重试只改善瞬时故障，不证明外部镜像源可用。

运行步骤不再使用通用 4000 字符截断切坏工具 JSON。工具记录保留最多 128000 字符，超限保留首尾并明确省略标志；V15 仅把 run_step.output_text 扩为 MEDIUMTEXT，以容纳 UTF-8 和转义后的有界输出。模型上下文仍保持独立 16000 字符限制，不扩大模型权限或上下文预算。已截断的历史记录无法凭空恢复。

- [Docker container driver：内存、CPU、专用构建器](https://docs.docker.com/build/builders/drivers/docker-container/)
- [BuildKit 镜像源配置](https://docs.docker.com/build/buildkit/configure/)
- [Buildx build：load、network、label](https://docs.docker.com/reference/cli/docker/buildx/build/)
- [Buildx rm：只清理指定 builder](https://docs.docker.com/reference/cli/docker/buildx/rm/)
