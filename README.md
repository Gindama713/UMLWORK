# 机场智慧停车与出行服务平台

软件建模技术大作业第 14 题。负责人：姜苏豪（软件工程 24201718）。

> 【AI-辅助】当前为可运行的业务实现与建模草案。五个独立 Spring 服务、网关与真实 MySQL 五库已完成主要业务联调；自动测试使用 H2 的 MySQL 兼容模式。真实 Nacos API 六实例与网关服务发现已验证，控制台截图、本人截图和报告仍待完成；详见 [交接记录](docs/agent-handoff.md)。

先读 [项目统一契约](AGENTS.md)、[业务规则](docs/decisions.md)、[API v1](docs/api-contract.md) 和 [用例规约](docs/use-cases.md)。

下一位 agent 从 [交接记录](docs/agent-handoff.md) 开始。[界面设计记录](docs/ui-design.md) 包含机场导视配色、真实车位立体/平面示意、车辆位置卡、票据式账单、结算进度与动效取舍。快捷入口用于导航，不是权限控制。

## 模块与所有权

| 模块 | 职责 | 默认端口 | 独立 schema |
|---|---|---:|---|
| gateway | 对外 /api/v1 路由 | 18080 | 无 |
| space-service | 车位、充电桩、充电会话 | 18081 | parking_space |
| access-service | 入场、寻车、出场协调与停车记录 | 18082 | parking_access |
| billing-service | 分时计费、账单、模拟支付、发票 | 18083 | parking_billing |
| pass-service | 预约预付、月卡与扣费 | 18084 | parking_pass |
| analytics-service | 车流量和报表快照 | 18085 | parking_analytics |
| frontend | Vue 3 + Element Plus 演示页 | Vite 输出 | 无 |

服务只能通过接口交换 ID/DTO，不能连接其他服务的库。业务金额使用整数分，费率放在各权威服务的 application.yml，可通过环境变量覆盖。SQL 建表由各服务 resources/schema.sql 执行；space-service 的 data.sql 插入幂等演示车位。

## 技术版本

Java 21、Spring Boot 4.0.6、Spring Cloud 2025.1.1、Spring Cloud Alibaba 2025.1.0.0、MySQL 8.x、Vue 3、Vite 6、Element Plus 2。Maven 版本由项目包装器确定；具体依赖以根 pom.xml 为准。版本组合参考 [Spring Cloud Alibaba 官方对应表](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/)；本机已用 Nacos 3.1.1 做服务发现联调，证据和边界见 [交接记录](docs/agent-handoff.md)。

## 本地 MySQL 启动

1. 在 MySQL 客户端或 Workbench 执行 [sql/00-create-databases.sql](sql/00-create-databases.sql)。建议为每个服务创建只可访问自有 schema 的账号；不要把真实密码提交到仓库。
2. 在五个服务各自的终端设置对应 DB URL、USER、PASSWORD 环境变量：SPACE_DB_*、ACCESS_DB_*、BILLING_DB_*、PASS_DB_*、ANALYTICS_DB_*。URL 需要连接到本机或实际 MySQL，并统一 UTC 会话时区。各 application.yml 给出了变量名与示例默认值。
3. 设置 JAVA_HOME 指向 Java 21，在项目根目录运行 `.\mvnw.cmd clean package`。
4. 分别启动五个服务的 target/*.jar，最后启动 gateway/target/gateway-0.1.0-SNAPSHOT.jar。默认网关通过固定本地服务地址访问，便于先联调。
5. 在 frontend/ 运行 `npm install`、`npm run dev`，打开终端显示的地址。页面的“检查服务”应显示五项已连接。

如果当前命令行找不到 mysql.exe，Windows MySQL 8.4 常见位置为 C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe。不要在命令行参数或 Git 文件中写数据库密码。

## Nacos 发现模式

五个服务与网关设置 `NACOS_ENABLED=true`、`NACOS_SERVER_ADDR=实际地址`；调用方把 `SPACE_SERVICE_URL`、`BILLING_SERVICE_URL`、`PASS_SERVICE_URL`、`ACCESS_SERVICE_URL` 设置为空值。网关用 `--spring.profiles.active=nacos` 启动，路由切换为 `lb://服务名`。本机 Nacos 3.1.1 standalone 的客户端端口为 8848，控制台端口为 8080。2026-09-30 实测 Nacos API 返回六个健康实例，`./scripts/smoke-gateway.ps1` 全部通过，且经网关创建和取消预约成功（验证 pass 对 space 的 Feign 发现）；复现命令及控制台截图状态见 [交接记录](docs/agent-handoff.md)。未使用 Nacos 配置中心。

## 验证与演示

- `.\mvnw.cmd clean package` 运行所有现有测试；测试使用 H2 的 MySQL 兼容模式检查服务各自的数据约束和主要分支，不替代 MySQL 联调。
- `npm run build` 检查前端。
- 16 条现有后端测试均通过；真实服务已启动时，可用 PowerShell 7 执行 `./scripts/smoke-gateway.ps1` 检查充电→冻结费用→出账→支付→释放→发票。脚本保留演示记录；网关地址可用 `-GatewayUrl` 覆盖。
- 已验证的真实 MySQL 网关链路：普通停车 08:00–10:00 为 1000 分；预约九折减预付 500 分后应付 400 分；月卡停车费八折后余额自动扣 800 分；2 kWh 充电费 300 分并入账单；异常费用及日报表可复核。详见 [联调记录](docs/code-audit.md)。
- 真实 MySQL 并发与断线恢复：`scripts/verify-mysql-concurrency.ps1` 覆盖并发占位、预约和支付；billing/space 下线与月卡支付超时的人工注入步骤、断言和实测结果见 [故障注入记录](docs/mysql-fault-tests.md)。
- [模型源文件](models/README.md)是可编辑 PlantUML 草案，17 张渲染图位于 `models/rendered/`；报告里的图、代码、截图应持续同步。课程所需至少 8 张本人截图和带字幕录屏仍需完成。

## 协作约定

多 agent 先读 AGENTS.md 和两份课程原件，独占自己的服务目录。跨服务字段、状态或费率变更先更新 docs/api-contract.md / docs/decisions.md，再改模型、代码与测试。报告指定的本人原创反思章节由姜苏豪亲自撰写并真实申报 AI 使用。
