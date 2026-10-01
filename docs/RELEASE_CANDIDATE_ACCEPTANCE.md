# 不可变发布候选人工验收

当前事实口径：自动化测试通过；2026-09-28 真实模型与真实 SSH 服务器候选准备正向链路已人工验收。真实运行 `c37e9338-8fd1-4357-9fa5-734961cbde40` 经空参数一次性审批后成功，候选为 `20260928T081518Z-080d3c7e`，远程摘要校验通过，工具耗时 100,397 ms。用户独立服务器权限/摘要检查、生产前后对比及真实失败/拒绝场景尚未提供证据；以下保留完整验收步骤，不代表每项都已完成。生产部署和回滚不属于本工具。

## 前置条件

1. 本地源码根指向可信 Maven 项目，并包含 `pom.xml`、`Dockerfile`、`docker-compose.yml`、`nginx.conf`。
2. `pom.xml` 固定生成 `target/app.jar`。
   镜像构建要求新生成的格式 2 清单，它额外绑定 Dockerfile、Compose 和 Nginx 摘要；旧格式 1 的已验收候选保留，但需要重新准备候选后才能进入镜像构建。
3. SSH Profile、密码凭据、远程根和固定 SHA-256 主机指纹已通过检查。
4. 部署 Profile 中“本地 Compose 文件”为 `docker-compose.yml`，“远程 Compose 文件”为 `compose.yml`。
5. 发布一个包含“准备不可变发布候选”工具的新 AgentVersion。

## 正向验收

1. 打开“远程工作台 → 部署”，确认固定生产目标为 READY。
2. 选择包含该工具的已发布 AgentVersion。
3. 点击“请求准备候选”，检查审批卡：工具应为 `prepare_release_candidate`，参数必须为 `{}`，目标应同时包含本地源码根、固定 SSH 目标及 `RELEASE:PREPARE_ONLY`。
4. 批准一次。等待本地 Maven 测试、打包、SFTP 上传和远程摘要校验完成。
5. 结果应包含：releaseId、`target/app.jar`、远程候选目录、artifact SHA-256、manifest SHA-256、制品大小、`successful=true`、`exitCode=0`、`productionModified=false`、`imageBuilt=false`。
6. 在服务器只读核对：

```sh
candidate=/root/opt/old-things-releases/<界面返回的 releaseId>
stat -c '%F %a %U:%G %n' "$candidate"
find "$candidate" -maxdepth 1 -type f -printf '%f | %s bytes\n' | sort
cd "$candidate" && sha256sum -c SHA256SUMS
```

固定文件应为 `app.jar`、`Dockerfile`、`compose.yml`、`nginx.conf`、`manifest.properties`、`SHA256SUMS`。不应存在 `.env`、数据库或 uploads。

## 生产无影响核对

候选准备前后各运行一次已有 `RELEASE_FINGERPRINT`、`COMPOSE_STATUS` 与 `SITE_HEALTH`。生产 `app.jar` 摘要、服务状态和健康结果应保持不变。该工具不会部署候选版本。

## 失败与拒绝验收

- 向模型要求传入 jar、路径、命令、环境变量或 releaseId，工具应拒绝参数。
- 临时制造一个会失败的本地测试，结果应停在 `MAVEN_TEST`，返回非零退出码，服务器不得出现新候选目录。
- 重复 releaseId 的覆盖行为由独占创建拒绝；模型没有指定 releaseId 的入口。
- 中断或超时后，本地进程树/远程命令通道应关闭。若上传中途失败，可保留不完整候选目录供审计，但它没有成功回执，不能进入部署阶段。
