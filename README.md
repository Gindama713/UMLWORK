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

服务只能通过接口交换 ID/DTO，不能连接其他服务的库。业务金额使用整数分；费率由各权威服务读取 Nacos 配置或本地 application.yml 兜底值，可通过环境变量覆盖。SQL 建表由各服务 resources/schema.sql 执行；space-service 的 data.sql 插入幂等演示车位。

## 技术版本

Java 21、Spring Boot 4.0.6、Spring Cloud 2025.1.1、Spring Cloud Alibaba 2025.1.0.0、MySQL 8.x、Vue 3、Vite 6、Element Plus 2；本机 Redis 3.2.100 仅用于已保存报表缓存。Maven 版本由项目包装器确定；具体依赖以根 pom.xml 为准。版本组合参考 [Spring Cloud Alibaba 官方对应表](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/)；本机已用 Nacos 3.1.1 做服务发现联调，证据和边界见 [交接记录](docs/agent-handoff.md)。

## 本地 MySQL 启动

1. 在 MySQL 客户端或 Workbench 执行 [sql/00-create-databases.sql](sql/00-create-databases.sql)。建议为每个服务创建只可访问自有 schema 的账号；不要把真实密码提交到仓库。
2. 在五个服务各自的终端设置对应 DB URL、USER、PASSWORD 环境变量：SPACE_DB_*、ACCESS_DB_*、BILLING_DB_*、PASS_DB_*、ANALYTICS_DB_*。URL 需要连接到本机或实际 MySQL，并统一 UTC 会话时区。各 application.yml 给出了变量名与示例默认值。
3. 设置 JAVA_HOME 指向 Java 21，在项目根目录运行 `.\mvnw.cmd clean package`。
4. 分别启动五个服务的 target/*.jar，最后启动 gateway/target/gateway-0.1.0-SNAPSHOT.jar。默认网关通过固定本地服务地址访问，便于先联调。
5. 在 frontend/ 运行 `npm install`、`npm run dev`，打开终端显示的地址。页面的“检查服务”应显示五项已连接。

如果当前命令行找不到 mysql.exe，Windows MySQL 8.4 常见位置为 C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe。不要在命令行参数或 Git 文件中写数据库密码。

## Nacos 服务发现与配置中心

五个服务与网关设置 `NACOS_ENABLED=true`、`NACOS_SERVER_ADDR=实际地址`；调用方把 `SPACE_SERVICE_URL`、`BILLING_SERVICE_URL`、`PASS_SERVICE_URL`、`ACCESS_SERVICE_URL` 设置为空值。网关用 `--spring.profiles.active=nacos` 启动，路由切换为 `lb://服务名`。本机 Nacos 3.1.1 standalone 的客户端端口为 8848，控制台端口为 8080。2026-09-30 实测六实例注册与网关发现，证据和控制台截图状态见 [交接记录](docs/agent-handoff.md)。

六个模块通过 `spring.config.import=optional:nacos:<spring.application.name>.properties` 导入同名 Data ID（`DEFAULT_GROUP`），配置中心地址使用 `NACOS_SERVER_ADDR`。将 [config/nacos](config/nacos) 内三个示例文件分别以原文件名发布到 Nacos；它们只含 billing 的 16 项费率、space 的 2 项充电参数、pass 的 4 项预约/月卡参数，不含数据库密码。gateway、access、analytics 没有业务费率，允许对应 Data ID 暂不存在。本地 application.yml 保留相同前缀和默认值，Nacos 不可用时仍可启动。新建账单按当前配置生成费率版本与金额的同一快照，旧账单不重算；space/pass 的 `@Value` 参数由 `@RefreshScope` 在配置变更后重新注入。2026-10-07 的实际热更新和离线启动结果见交接记录。配置中心运行与服务发现可分别使用：`NACOS_ENABLED=false` 只关闭注册发现，不关闭可选配置导入。

在项目根目录运行 `./scripts/verify-nacos-pricing-refresh.ps1` 可复核费率热更新；脚本要求 Nacos 中已经发布 v1 演示基线，测试结束会恢复原配置。它只创建新的模拟账单，不修改旧账单。

## Redis 报表缓存（UC-10）

`analytics-service` 对 `GET /api/v1/analytics/traffic-reports/{id}` 使用 Redis 读穿缓存，默认地址 `127.0.0.1:6379`，键前缀 `parking:analytics:report:v1:`，默认 TTL 为 1 小时。首次查看从 `parking_analytics` 读取并写入缓存；后续命中返回同一份不可变报表。实时 `traffic-preview` 不缓存。Redis 不可用时会回源 MySQL；若两者均不可用，仍按既有契约返回 `503 DEPENDENCY_UNAVAILABLE`。Redis 不参与车位、账单、支付或出场状态判断。

启动 analytics 前在本机环境变量中设置 `REDIS_PASSWORD`，不要将真实密码写入仓库。可按需设置 `REDIS_HOST`、`REDIS_PORT`、`REDIS_CONNECT_TIMEOUT`、`REDIS_TIMEOUT` 和 `ANALYTICS_REDIS_TTL`；默认值见 [analytics 配置](analytics-service/src/main/resources/application.yml)。Redis 是可选缓存，其健康项不决定服务整体健康状态，故障会在日志记录。`analytics-service` 新增 BOM 管理的 Redis starter 与 Jackson Java Time 模块：前者提供连接与模板，后者保持报表 `OffsetDateTime` 的时区语义。

Windows 本机没有 Docker/WSL；Ubuntu 虚拟机已运行 Compose 部署，Windows 经网关烟测通过。Sentinel 曾在真实 MySQL 并发预约回归中引发 Feign 解码错误，已回退，当前不宣称启用熔断。

## Docker Compose（Ubuntu 虚拟机）

[`docker-compose.yml`](docker-compose.yml) 默认编排 MySQL 8.4、Redis、五个业务服务、gateway 与 Nginx 前端。VM 部署采用固定服务地址；Nacos 是单独的可选 profile，**本次 VM 未部署和验证该 profile**。Java 镜像直接复制六个已编译 JAR，前端镜像复制 `frontend/dist`；容器构建不下载 Maven/npm 依赖。因此首次运行前须先准备构建产物：

```powershell
# Windows 项目根目录；只清理六个模块，不删除根目录 target/local-runtime/
$env:JAVA_HOME = 'C:\Program Files\Amazon Corretto\jdk21.0.11_10' # 换成实际 Java 21 路径
.\mvnw.cmd -pl gateway,space-service,access-service,billing-service,pass-service,analytics-service -DskipTests clean package
cd frontend
npm ci
npm run build
```

将六个 `模块/target/模块-0.1.0-SNAPSHOT.jar` 和 `frontend/dist/` 保持相同相对目录传入虚拟机项目目录。随后在 Ubuntu 项目根目录执行：

```bash
cp .env.example .env
# 编辑 .env：设置 MySQL root、五个服务专用账号和 Redis 的密码；不得提交 .env
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:18080/actuator/health
curl -fsS http://127.0.0.1:8080/
```

容器内部使用 `mysql:3306`、`redis:6379` 和 Compose DNS 服务名；浏览器访问 `http://<虚拟机地址>:8080/`，Nginx 将 `/api/` 转给 gateway。MySQL 数据卷首次创建时执行五库建库脚本和服务专用账号脚本；已有旧数据卷不会重复执行这两份初始化脚本。服务自己的 `schema.sql` 按 `spring.sql.init.mode=always` 在每次启动时运行。业务参数仍由本地配置兜底。

Windows 已对 VM `192.168.125.129` 运行 `pwsh -NoProfile -File .\scripts\smoke-gateway.ps1 -GatewayUrl http://192.168.125.129:18080`，脚本退出码 0，11 个断言全部 PASS；前端首页 HTTP 200，前端 `/api/v1/spaces/status` 返回 `code=OK`。VM 侧容器启动与健康状态由 VM agent 报告；Windows 本机没有 Docker CLI，未独立读取 `docker compose ps`。完整边界见 [交接记录](docs/agent-handoff.md) 与 [容器说明](docker/README.md)。

## 验证与演示

- `.\mvnw.cmd clean package` 运行所有现有测试；测试使用 H2 的 MySQL 兼容模式检查服务各自的数据约束和主要分支，不替代 MySQL 联调。
- `npm run build` 检查前端。
- 18 条后端测试均通过（原有 16 条 + Redis 缓存 2 条）；真实服务已启动时，可用 PowerShell 7 执行 `./scripts/smoke-gateway.ps1` 检查充电→冻结费用→出账→支付→释放→发票。脚本保留演示记录；网关地址可用 `-GatewayUrl` 覆盖。
- 已验证的真实 MySQL 网关链路：普通停车 08:00–10:00 为 1000 分；预约九折减预付 500 分后应付 400 分；月卡停车费八折后余额自动扣 800 分；2 kWh 充电费 300 分并入账单；异常费用及日报表可复核。详见 [联调记录](docs/code-audit.md)。
- 真实 MySQL 并发与断线恢复：`scripts/verify-mysql-concurrency.ps1` 覆盖并发占位、预约和支付；billing/space 下线与月卡支付超时的人工注入步骤、断言和实测结果见 [故障注入记录](docs/mysql-fault-tests.md)。
- [模型源文件](models/README.md)是 18 份可编辑 PlantUML 草案，22 张渲染图位于 `models/rendered/`；新增 17 号服务级出场进程顺序图，15 号源文件分别渲染五个服务的类图。报告里的图、代码、截图应持续同步。课程所需至少 8 张本人截图和带字幕录屏仍需完成。

## 协作约定

多 agent 先读 AGENTS.md 和两份课程原件，独占自己的服务目录。跨服务字段、状态或费率变更先更新 docs/api-contract.md / docs/decisions.md，再改模型、代码与测试。报告指定的本人原创反思章节由姜苏豪亲自撰写并真实申报 AI 使用。
