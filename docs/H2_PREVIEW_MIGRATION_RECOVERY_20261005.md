# H2 预览迁移补修

## 核实结果

第三批运行 c602ba41-5151-47e0-9dc9-3c235ac3925c 的 MySQL 合成验证 da72dc54-0c2c-42a8-a03d-ebb2f1fa38d9 为 SUCCEEDED，successful=true、cleanupConfirmed=true；三组执行 23/3/3 项，均无失败、错误或跳过。源码摘要为 19652d5a7ab80333b45ecc2f4cac3dfd6e142dc51f7814a0f4bafbda0a4d6329。

两次失败预览 e11672bd-5420-4274-be5c-04c419eaa266、1cba921f-8e0a-468d-aa74-75f5c31bfa2e 均已自动停止并清理，不存在需要助手再次停止的残留预览。

原平台预览已有外部 H2 驱动和独立配置，启动时以 spring.config.location 指定平台配置，覆盖网站 MySQL 默认配置。缺陷是关闭 Flyway 后只执行写死的 H2 V1 初始化脚本，新增 V2 的 content_year 字段及索引未进入预览，最新实体无法在旧预览结构上就绪。无需修改网站 pom.xml、放开 application*.yml 或放宽生产迁移策略。

## 修改

- 平台专用 H2 配置开启 Flyway，执行 classpath:db/migration/h2 中的当前版本迁移；不自动 baseline、不 clean、不 out-of-order，继续 Hibernate validate。
- 平台合成数据改为固定只读 /preview/migration/afterMigrate.sql 回调，在迁移之后填入一次性 H2 数据。该文件来自平台资源，不改写业务迁移目录，也不用于生产发布维护。
- 预览就绪失败时取得有界容器状态及日志，在回收本次资源后保存 FAILED 记录及原因。只遮盖平台预览凭据，诊断不提供生产凭据或读取真实数据。
- 项目预览菜单可查看最近失败原因；原有停止并清理按钮保留。启动仍经过原平台逐次审批，不添加绕过审批的启动接口。

## 验证

LocalPreviewTests 8 项、PreviewApprovalTests 5 项、真实 WebsitePreviewTests 1 项全部通过，共 14 项，0 失败、0 错误、0 跳过。真实测试使用当前授权源码的隔离副本：

- H2 预览 READY，浏览及空结果页面正常，断网、只读根文件系统、无真实数据挂载等隔离断言通过。
- 合成账号注册、登录 Cookie 和收藏页面通过。
- 创建 contentYear=1995 的合成条目，真实 HTTP 返回正确年份；候选栏目 era=1990s 显示该条目。
- 当前项目源码摘要未变；测试完成后实例、卷及临时源码副本全部清理。
- TypeScript/Vite 构建通过。

测试实例已停止，其临时地址不可用于人工验收。更新本机服务后，请从原会话的“项目：时光序”菜单请求启动预览并批准一次；使用新返回的 URL 及有效期。源码仍为上述摘要时，已有 MySQL 29 项回执继续有效；H2 预览不替代 MySQL 验证。

本次未修改网站源码、构建网站远程镜像、备份生产或上线。
