# ADR 0001：采用模块化单体与独立前端

- 状态：已接受
- 日期：2026-09-02

## 决定

后端使用单个 Spring Boot Maven 工程，通过 Java package 隔离 Agent、Runtime、Model、Knowledge、Tool、Execution、Conversation 和 Adapter；前端使用独立 React + TypeScript + Vite 工程。

用户配置产生的 Agent Runtime 是由固定工厂创建和缓存的普通对象，不动态注册为全局 Spring Bean。

## 原因

新产品面向个人电脑上的配置、运行和调试场景。模块化单体能保留清晰边界，同时减少多 Maven 模块、动态 Bean 生命周期和跨层策略树带来的理解及调试成本。

## 约束

- 第一阶段不引入微服务、Redis、MQ、工作流画布或多智能体；
- 所有未来工具执行必须统一进入安全执行网关；
- 已发布 AgentVersion 不可变，一次运行固定绑定版本；
- RAG 数据不得在应用启动时自动清空。

