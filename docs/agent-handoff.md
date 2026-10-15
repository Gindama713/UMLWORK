# Agent 交接记录（2026-10-14，当前可接手）

> 本文件用于中途接手。先读根目录 `AGENTS.md`、`docs/decisions.md`、`docs/api-contract.md` 和两份课程原件。业务语义与接口由这些文件约束；本记录只说明当前工作状态。请勿把未验证事项写成已完成。

## 2026-10-15：课程 UML 图纸规范性修订

- **覆盖 UC-01～UC-10，图 1-1、2-1～2-6、3-3～3-9、4-1～4-11**：按课程模板第 7 节核对图纸。用例图移除未接入的外部识别设备和错误的 `include`，保留十项用例，并移去造成长虚线的说明框；领域模型改用带两端多重性的概念关联，不把跨服务 ID 画成跨库外键；三张 SSD 与设计顺序图标注规约步骤，补生命线激活条，设计图只画真实控制器、服务、仓储/JdbcTemplate 和 Feign 客户端；跨服务活动流程拆成三张泳道图；组件图显式画提供/依赖接口；部署图逐个呈现 Compose 中六个 Java 容器、Nginx、Redis、MySQL 五 schema 和实际端口，默认未启动的 Nacos 不画成运行节点；通信图改成与出场对象级顺序图对应；access/analytics 类图只列真实关键成员以保证报告阅读尺寸；16 号 ER 源按五个 schema 输出五张图。`docs/use-cases.md`、`models/README.md`、`docs/report-outline.md`、根 README 的图纸数和用例参与者已同步。
- **验证**：使用本机 PlantUML 1.2024.3 执行 `java -jar <jar> -checkonly "models/*.puml"` 与 `-charset UTF-8 -tpng -o rendered "models/*.puml"`，两条命令均退出 0；`models/rendered/` 共 28 张 PNG（23 张 UML、5 张 ER）。实际打开总体用例、领域模型、入场/预约/出场设计顺序、三张活动图、组件、部署、access/analytics 类图、通信以及两张 ER 图检查文字和连线。此次只改模型及说明，没有改代码、HTTP 接口、数据库表或状态，也没有重跑后端测试或启动容器；模型的当前运行基线仍是此前 Compose 文件和测试记录，报告图题、首次引用和语义段须按最终排版由负责人核对。
- **下一步优先级**：① 在 Docker Desktop Linux Engine 可用时复测 2026-10-13 充电恢复接口与 2026-10-14 同键并发入场的真实 MySQL/网关路径；本次 `docker info --format '{{.ServerVersion}}'` 因 `dockerDesktopLinuxEngine` 管道不存在而失败，不能把新版容器验收写成已完成。② 负责人按课程模板采集至少八张本人真实运行截图、8～10 分钟带字幕录屏和 Nacos 实例截图。③ 把非功能需求写成可测指标，按同一指标在测试结论中逐项判定；补十天开发日志、报告表格与 AI 使用记录。优先补证据与模型—代码闭环，再考虑新增功能。

## 2026-10-14：入场请求键锁的连接池容量保护

- **UC-02，图 15**：复查前一轮同键行锁发现，每个正在处理的入场需要一条锁连接，再需要另一条连接提交记录。若锁连接占满 Hikari 池，处理中的请求会互相等待直到超时。`AccessRepository` 现在从已启动的 Hikari 池读取最大连接数，将同时持锁的入场数限制为 `poolSize - 1`；池容量小于 2 时明确拒绝启动该操作。已存在的请求键先只读校验，新键才尝试插入，避免重复请求在入队前执行无效更新并锁住同一行。`AccessServiceTest` 将池收窄至 2；修复前同键并发测试因重复更新锁等待超时失败，修复后同键重试返回同一停车记录、只占位一次，改内容复用同键仍返回 `IDEMPOTENCY_CONFLICT`。停车记录仍独立提交，异常补偿不被锁事务回滚。
- **边界**：这是单实例连接池容量保护，跨实例仍依赖数据库行锁。长时间 Feign 调用仍会占用锁连接；需要更高吞吐量时应引入持久化处理权并缩短锁持有时间。本轮无 HTTP 字段、状态、表、费率或依赖变化；接口契约仅澄清请求键行锁与独立提交语义。H2 压力测试不代表真实 MySQL 竞争已验证。
- **验证**：Java 21 `./mvnw.cmd -q test` 的七份 Surefire XML 合计 25 条、0 失败、0 错误；PlantUML 1.2024.3 对图 15 `-checkonly`、渲染退出 0，已打开 `models/rendered/15-design-classes.png` 检查可读。Docker Desktop Linux Engine 和 Ubuntu VM 仍不可达，未进行本轮真实 MySQL/网关重测。

## 2026-10-14：UC-02 同键并发入场加固

- **复现**：新增 `AccessServiceTest.concurrentRetryOfOneEntryOccupiesOnlyOnce`，让第一次请求停在候选车位查询后，第二次以同一请求键、同一内容并发进入。修复前单测退出 1：预期第二次等待，实际立即完成；原有 `access_request_key` 的无操作重复插入没有覆盖整个入场流程。
- **修复**：`AccessRepository.withRequestLock` 在独立连接上锁住已登记的 `access_request_key` 行，同键请求排队；入场记录继续用原有独立提交方式持久化，避免占位成功后进程崩溃导致停车记录随锁事务回滚。锁只用于排队，结束时回滚释放；物理占位仍由 space-service 自有库负责，失败继续按原流程释放/恢复预约。接口契约与图 06、15 已同步；没有新增依赖、表、状态或业务费率。锁在 Feign 调用期间多占用一个连接，这是当前吞吐量上限；扩容时应改为持久化处理权并缩短锁持有时间。
- **验证**：同键并发单测修复前失败、修复后通过；第一个请求尚未完成时，另一连接可读到已提交的 `ENTERING` 记录。另增“占位 503 后补偿成功”断言，确认锁释放后 `ENTRY_FAILED` 仍在库中且 `active_plate` 已清空。Java 21 `./mvnw.cmd -q test` 退出 0，Surefire XML 合计 25 条、0 失败/错误。PlantUML 1.2024.3 对图 06/15 `-checkonly` 与 PNG 渲染退出 0，已打开图片检查可读。Docker Desktop GUI 进程存在，但 Linux Engine 命名管道仍不存在；Ubuntu VM 的 18080/8124 也不可达。本机 3306 可连接，但仓库未提交的 Docker `.env` 账号无法登录该独立 MySQL（1045）。因此同键场景尚未在真实 MySQL/网关复测，也未复测 2026-10-13 的新充电查询接口。

## 2026-10-13：UC-09 活动充电会话恢复

- **发现与修复**：原前端只在内存保留充电会话 ID，页面刷新后无法结束仍为 ACTIVE 的会话，出场冻结会被 space-service 拒绝。space-service 新增只读 `GET /api/v1/spaces/charging-sessions/active?parkingSessionId=...`，从自有 MySQL 查询活动会话，空结果返回 `[]`；充电页可输入停车记录 ID 并点击“恢复进行中的充电”，随后使用原会话 ID 结束。寻车流程不依赖此查询，space-service 暂不可用时仍可单独寻车。接口先写入 `docs/api-contract.md`，设计类图 15 源及 `models/rendered/15-design-classes_002.png` 已同步。未新增依赖、表、状态或费率规则。
- **本机验证**：Java 21 `./mvnw.cmd -q test` 退出 0，Surefire XML 合计 23 条；`space-service` 现有充电测试增加了活动查询在开始后返回原会话、结束后返回空数组及控制器响应断言。`frontend` 的 `npm test -- --run` 为 4/4，`npm run build` 退出 0；PlantUML 1.2024.3 的图 15 `-checkonly`、PNG 渲染退出 0，已打开 space 设计类图确认文字与连线可读。`git diff --check` 退出 0。
- **未验证**：本轮 `docker compose ps` 无法连接 Docker Desktop Linux Engine（命名管道不存在），因此新接口尚未用真实 MySQL/网关/浏览器复测。容器恢复后应重新构建 `space-service` 与 `frontend`，创建充电入场并开始充电，刷新页面，按停车记录 ID 恢复并结束充电，再验证出场结算。课程正式截图仍需负责人本人采集。原有三个未跟踪提示词文件未改；本轮不推送远端。
- **后续审查点（2026-10-14 更新）**：同键并发已用可复现 H2 故障窗口修复，见本文件顶部。真实 MySQL 下的同键竞争与 Feign 故障交错仍待验证；此前的空间占用并发测试只覆盖不同停车记录抢同一车位。

## 2026-10-12：UC-01/05/06/09/10 页面与演示数据

- **改动**：`SpaceMap.vue` 与可视化样式移除全局视角切换和蓝色选中框，点击或外部定位时只有一个车位凸起；`PassesPage.vue`、`ChargingPage.vue` 与 `workflows.css` 改成预约/月卡、选桩/充电步骤卡片。`space-service/data.sql` 在 B3 追加 12 个幂等种子位（含 2 个停用位）和 2 个充电桩，现为 31 位、6 桩。新增 `scripts/seed-demo-traffic.ps1`，通过既有网关接口生成可核对的停车与车流演示记录。`README.md`、`frontend/README.md`、`docs/ui-design.md`、`docs/traceability.md` 同步说明。没有新增依赖、接口、状态枚举或表；金额规则和模型图 01/02/04/09/10/12/15/16 不变。
- **验证**：`cd frontend; npm test` 4/4，最终 `npm run build` 成功，主 JS 398.97 kB、CSS 372.90 kB，ECharts 异步包 562.32 kB（原有体积警告）；Java 21 `mvnw.cmd -B -ntp -pl space-service test package` 5/5 且构建成功。Windows Docker 用 `docker compose -f docker-compose.yml -f target/local-runtime/docker-windows.override.yml up -d --build --no-deps space-service frontend` 重建，随后单独重建 `frontend` 部署视觉修正。经网关 GET `/api/v1/spaces` 得 31 位；运行 `pwsh -NoProfile -File scripts/seed-demo-traffic.ps1 -IncludeActive` 退出 0，输出 6 条 `CLOSED`、2 条 `PARKED`，车流预览 `last24h=8; buckets=25`。浏览器 `:18080/` 显示可用 27/占用 2/停用 2、充电桩 6、近 24 小时入场 8；资源页点 A-003 时可见区域只有一个 `is-selected`，其余 10 位平面，全局视角切换控件数 0；充电页修复了 `spaces` ref 被直接当数组导致的空白，6 桩显示并可点选 C-001。390px 宽度下预约与充电页文档宽均为 375px，无横向溢出。
- **接手与边界**：已完成记录的再次运行会继续追加，`-IncludeActive` 只跳过已在场的固定演示车牌；演示数据是通过正式 API 写入真实 MySQL，不是前端假数据。课程正式截图仍需负责人本人采集。本轮未重跑全部 Maven 模块、未测 FPS，未部署到 Ubuntu VM；旧日期章节的 19 位/4 桩是当时的历史实测，不代表现状。未推送远端。

## 2026-10-12：UC-01/UC-10 运营图表改版

- **改动**：`frontend/package.json` 与 lock 增加 ECharts 6.1.0；新增 `EChartView.vue`、`ResourceStatusChart.vue` 和车位状态分组检查，替换 `TrafficChart.vue` 的旧手写柱条；总览增加实际占用率环与当前资源堆叠图，运营报表保留原明细表并强化图形布局。`frontend/README.md`、`README.md`、`docs/ui-design.md` 同步更新。没有改 API、业务状态、数据库表、费率或 UML 模型；图 02/15/16 的资源含义不变。前端只读取 UC-01/UC-10 已有接口，长区间可缩放，零车流保持零显示。未推送。
- **验收**：`cd frontend && npm test` 为 4/4，`npm run build` 成功；主 JS 393.75 kB，ECharts 懒加载包 562.32 kB（gzip 192.92 kB，Vite 体积警告）。`docker compose -f docker-compose.yml -f target/local-runtime/docker-windows.override.yml up -d --build --no-deps frontend` 成功重建。浏览器在 `http://127.0.0.1:18080/` 看到服务 5/5、19 个车位、当前可用 19/占用 0/停用 0；趋势与柱形按钮切换生效，报表页生成了 24 小时、总量 0 的真实快照并显示逐小时明细。最终页面查到 2 个 SVG 图表，读屏摘要分别与真实 0/19 数据一致，1280px 视口下文档宽 1265px，console error/warn 为 0。此次没有新建车辆，因此没有非零车流的浏览器实测；没有做手机视口复测。课程本人截图仍需负责人采集。
- **接手**：前端改动后运行 `npm ci && npm test && npm run build`；Compose 使用预构建 `frontend/dist`，必须再单独重建 `frontend` 容器。对应 UI 依据与数据边界见 `docs/ui-design.md`。本轮未改后端，故未重跑 Maven。

## 2026-10-11：UC-01 演示车位扩充

- `space-service/src/main/resources/data.sql` 从 7 个幂等种子车位扩充到 19 个，新增 6 普通、2 无障碍、2 充电、2 预约位，并给新增的两个充电位各建一桩。只改演示数据，不改接口、状态、表结构、费率、模型图号；图 02/15/16 的类型与关系仍一致。已有车位/占用状态不更新。`README.md` 和 `docs/ui-design.md` 说明现状与历史数据边界。
- Java 21 `mvnw.cmd -B -ntp -pl space-service test package` 成功，5 条测试、0 失败。Docker Desktop 重建 `space-service`，真实网关 GET `/api/v1/spaces` 返回 19 条；逐个核对 12 个新 ID 在列表、2 个新充电位各返回一个充电桩。随后 `scripts/smoke-gateway.ps1` 退出 0，12 项 PASS，日志在 `target/local-runtime/smoke-seeds-20261011.log`。本次在上海时间约 22:50 的 2 小时演示停车跨日夜时段，账单停车 800 分、充电 300 分、应付 1100 分；与前一次 1000/300/1300 分不同来自实际请求时间，不是费率变更。VM 尚未同步本次数据变更。

## 2026-10-11：UC-08 异常快照、UC-05 预约到期与 Nacos 默认启动

- **UC-08，图 08/15/16**：`access-service` 新增自有表 `exit_exception` 和 `GET /api/v1/access/{sessionId}/exceptions`。出账成功后，同一本地事务保存账单 ID 与异常快照：丢卡保留人工处理人，超长停车以 billing 返回的 `LONG_STAY` 明细判定并记 `SYSTEM`；同一会话可有两条，均保留原入出场时刻、整数分金额和费率版本。业务金额仍由 billing 计算，access 不复制费率。前端账单页显示处理记录。变更文件：`access-service` 的 `ApiModels`、`AccessService`、`AccessRepository`、`AccessController`、`schema.sql` 与测试；前端 `useParkingWorkspace.js`、`BillingPage.vue`、`workflows.css`；`docs/api-contract.md`、模型 08/15/16。停车状态、既有支付协议与其他服务数据库均未改。
- **UC-05，图 04/15**：`pass-service` 增加按配置间隔扫描到期预约，默认 60 秒；待支付预约超过付款窗口或开始时刻、已确认但未入场预约超过结束时刻后持久化为 `EXPIRED`，`USED` 保持不变。新预约的冲突判断前也会立即清理已开始但未付款的旧预约，避免扫描间隔内的假冲突。新增 `GET /api/v1/passes/reservations/{id}`，前端预约凭证可主动刷新。变更文件：`PassService`、`PassController`、`PassServiceApplication`、`application.yml`、测试、`config/nacos/pass-service.properties`、前端 `useParkingWorkspace.js` 与 `PassesPage.vue`、`docs/decisions.md`、接口契约、模型 04/15。无新表、无新状态枚举；预约冲突仍按 `[start,end)` 和 pass 自有库判断。
- **Nacos 启动模式**：六个模块的配置导入移到 `application-nacos-config.yml`，默认 profile 关闭 Nacos 配置客户端；`NACOS_ENABLED` 继续单独控制注册发现。需要配置中心时加 `--spring.profiles.active=nacos-config`，网关同时需要 `lb://` 路由时用 `nacos,nacos-config`。本地配置仍兜底。同步 `README.md`、`docker/README.md`、`docker-compose.yml`、`docs/api-contract.md`。无业务接口、状态或表变化。现有 `docs/grasp-audit.md` 已标注历史审计快照中的过时文件位置；`docs/traceability.md`、`docs/report-outline.md`、`models/README.md` 已更新。
- **实际验证**：Java 21 `mvnw.cmd -B -ntp test` 七模块 `BUILD SUCCESS`，7 份 Surefire XML 合计 **23 条、0 失败、0 错误**；完整输出为本机忽略文件 `target/local-runtime/verify-final-20261011.log`，其中 Nacos 日志目录/连接错误匹配数 0。`npm test` 为 3/3，`npm run build` 成功（主 JS 391.59 kB）。PlantUML 1.2024.3 对 `models/*.puml` 执行 `-checkonly` 与 PNG 渲染均退出 0，已打开图 04、08、15 的 access/pass 图、16 核对布局。`mvnw.cmd -B -ntp -pl space-service '-Dspring.profiles.active=nacos-config' test` 在 8848 不可达时仍为 5/5，证明该单服务的可选导入能回退本地参数；输出在 `target/local-runtime/verify-nacos-config-offline.log`，会有 Nacos 客户端的连接错误日志。
- **真实容器与网关**：以新 JAR `docker compose -f docker-compose.yml -f target/local-runtime/docker-windows.override.yml up -d --build --no-deps access-service pass-service` 重建，两容器及 MySQL、其他服务均 healthy。经 `127.0.0.1:18080` 创建 49 小时 NORMAL 入场，丢卡出账、查询异常、同键重试、成功支付出场；会话 `91f41722-1cf3-49e6-8a6b-232430d899a5` 的账单 `0e5c265e-db27-4df6-9284-ce2b454e33c2` 产生 `LONG_STAY=2600`、`LOST_CARD=2000` 分两条快照，费率版本同为 `v1-demo-20260925`，重试账单 ID 不变，最终 `CLOSED`。另创建预约 `5beb957b-98e5-4d23-a84e-2b845c8f940b`，约 75 秒后经新 GET 读到 `EXPIRED`。`pwsh -NoProfile -File scripts/smoke-gateway.ps1` 退出 0，12 项 PASS，输出在 `target/local-runtime/smoke-final-20261011.log`。前端容器已用新 `dist` 重建；网关 `/`、新 JS 静态资源均 HTTP 200，脚本内容含“异常处理记录”和“刷新预约状态”。
- **尚未验证**：`nacos-config` 新 profile 尚未在正在运行的 Nacos 上复测热更新；此前 2026-10-07 的证据是旧默认导入配置。此次未在 Ubuntu VM 重新部署；前端生产构建通过，但本轮未逐按钮做浏览器回归。课程本人截图和报告仍由负责人完成。未推送远端，原有三个未跟踪提示词文件未改。

## 2026-10-11：报告表 5-1 核查草稿

- 新增 `docs/model-code-consistency-draft.md`，按 access、space、billing、pass、analytics 五个权威服务逐行列出 UML 图、源码、独立 schema、测试与核查结论；同步 `docs/report-outline.md` 的 5.2 和缺口清单。静态定位与现有 20 条测试、Docker 链路证据支撑所列语义；不是报告正文或本人独立核查，负责人需复核后使用。无接口、状态、表和模型变化。

## 2026-10-11：预约服务只读接口分层修正

- **UC-05，图 10/15**：pass-service 的 `SpaceClient` 改走新增的 `GET /internal/v1/spaces?type=RESERVATION`，space-service 在 `SpaceController` 复用原公开列表逻辑，返回同一字段与全部当前状态。未来时间窗冲突仍由 pass 自有数据库判断；不能改用仅返回 AVAILABLE 的接口。无业务状态、表、费率和公开接口变化；新增内部 GET 已先写入 `docs/api-contract.md`。设计顺序图 10、space 类图 15 源码与 PNG 已同步，PlantUML 1.2024.3 `-checkonly`、渲染通过并实际打开图片核对。
- **测试**：space 新增“内部列表仍返回 OCCUPIED 预约位”，pass 新增“OCCUPIED 可订未来时段、OUT_OF_SERVICE 拒绝”；`mvnw.cmd -pl space-service,pass-service -am test` 两模块各 5 条、0 失败，打包成功。随后全模块 `mvnw.cmd test` 为 20 条、0 失败，原始输出在忽略的 `target/local-runtime/verify-full-20261011.log`。Windows Docker Desktop 只重建 space/pass，两容器均 healthy；从 pass 容器 GET 新内部路径得到 `code=OK`、2 个预约位；经 gateway 创建预约得到 `PENDING_PAYMENT`，随即取消得到 `CANCELLED`（预约 ID `e36ed292-fca8-4320-9d92-4d5929926ab5`）。`scripts/smoke-gateway.ps1` 12 项 PASS，账单停车 1000 分 + 充电 300 分 = 应付 1300 分，原始输出在 `target/local-runtime/smoke-internal-spaces.log`。原有三个未跟踪提示词文件未改。

## 2026-10-11：提交验证流程

- 新增 `.github/workflows/verify.yml`：推送、PR 或手动触发时，用 Java 21 跑六模块 Maven 测试，用 Node 22 跑前端 API 测试与 Vite 构建。只赋予仓库内容读取权限，不注入业务密码；不启动 MySQL、Redis、Nacos 或 Docker。没有业务接口、状态、表及模型变化；对应 UC-01～UC-10 的回归入口，不能把它当成真实跨服务联调证据。
- 本机执行 `mvnw.cmd -B -ntp test`：七模块 Reactor `BUILD SUCCESS`，18 条后端测试；`npm test --prefix frontend`：3 条通过；`npm run build --prefix frontend`：构建成功，主 JS 390.53 kB。首次运行因当前终端 `JAVA_HOME` 指向不存在的 JDK、沙箱阻止 esbuild 启动而未开始/未构建；改用已安装的 Corretto 21 路径，并在获准的环境重跑通过。GitHub Actions **尚未远端运行验证**，提交和推送后须查看该 workflow 的首次结果。
- 本次没有使用用户提供的 DeepSeek 密钥。UC-02 仍为契约规定的人工输入/本地模拟车牌识别，不能在报告中写成真实图像识别；密钥不得进入仓库或测试日志。

## 2026-10-10：Windows Docker、网关首页与可读性整理

- **范围**：保持 UC-01～UC-10 的业务语义、HTTP 业务接口、状态、表和金额单位不变。网关默认/Nacos 两份路由配置增加可配置的 `FRONTEND_SERVICE_URI` 页面资源路由，Docker 中指向 Nginx；浏览器访问 `:18080/` 不再进入 Whitelabel 404。`analytics-service` 将 `TrafficController.java` 中原有控制器、Feign 客户端、统计服务、DTO/异常和异常处理拆为五个同包文件；类名、方法和调用顺序未变。前端由单个 App 大文件拆成应用外壳、七个页面、工作台状态和 API 客户端；样式按四份职责文件整理，增加短暂入场/悬停动效并尊重 `prefers-reduced-motion`。文件边界见 `frontend/README.md`；部署图 13 已同步网关至前端的静态路由并重渲染。
- **本机验证**：Windows Docker Desktop 29.8.2 / Compose 5.5.1 的 `docker compose -f docker-compose.yml -f target/local-runtime/docker-windows.override.yml up -d --build` 成功。`docker compose ps` 中 MySQL、Redis、五个业务服务、gateway 均 healthy；Nginx 前端 running。宿主 MySQL 占用 3306，忽略的本地 override 只把 Docker MySQL 宿主端口改为 13306，容器内仍用 3306。网关 `/` 和 JS 静态资源均 HTTP 200，`/api/v1/spaces/status` 为 `OK`。最终容器上的 `pwsh -NoProfile -File scripts/smoke-gateway.ps1 -GatewayUrl http://127.0.0.1:18080` 退出码 0、12 项 PASS，账单 `a82b11ae-3de7-43a5-b616-58d028230d29` 为停车 1000 分 + 充电 300 分 = 1300 分。
- **测试和浏览器**：`./mvnw.cmd test` 退出码 0，七份 Surefire XML 合计 18 条、0 失败、0 错误；拆分后 `analytics-service` 单模块测试及 gateway/analytics 打包通过。`frontend` 的 `npm test` 为 3 条、0 失败，验证 503 和非法成功包重试沿用幂等键、非 JSON 404 返回可理解错误。页面按需注册实际使用的十个 Element Plus 组件后 `npm run build` 通过，主 JS 从约 995 kB 降至 390.53 kB（gzip 135.10 kB），不再有 Vite 大包警告。真实浏览器从 `:18080/` 打开七个页面，均显示对应标题、当前页表单控件可见且控制台 error/warn 为 0；390px 视口 `scrollWidth=375 <= innerWidth=390`。PlantUML 1.2024.3 对图 13 `-checkonly` 与 PNG 渲染退出码均为 0，已打开 PNG 检查。未测量动画 FPS，不声称达到固定帧率。
- **边界**：本轮网关烟测覆盖入场/充电/计费/支付失败恢复/释放/发票/报表；预约、月卡和异常出场的先前 MySQL 联调证据仍见 `docs/code-audit.md`、`docs/mysql-fault-tests.md`，本轮没有重新逐按钮做这三条浏览器流程。Nacos profile 的新增页面路由已编译，未在真实 Nacos 模式复测。课程要求的本人截图、录屏、原创报告章节仍由负责人完成。未推送远端；原有三个未跟踪的 agent prompt 文档没有修改。

## 2026-10-09：GRASP 职责分配静态审计

- **范围与交付**：逐类核对六模块 36 个主 Java 文件，以 7 个测试文件交叉检查；新增 `docs/grasp-audit.md`，含九项原则、六问、八个疑点和报告表 4-1 草稿。源代码、API、状态、数据库表和依赖均未更改；对照 `models/15-design-classes.puml`。重点发现 pass→space 使用公共 `/api/v1/spaces` 读接口，与服务间 `/internal/v1` 约定不一致，不能简单改用当前 `available` 接口，否则预约资源判断会改变；Jackson 2/3 混用是技术一致性风险，尚无功能故障证据。未为了凑模式重构。
- **验证**：审计前后分别执行 `./mvnw.cmd test`（设置本机 Java 21 `JAVA_HOME`），均 `BUILD SUCCESS`，7 份 Surefire XML 各次均为 18 条、0 失败、0 错误。结束时扫描外服务 Java import 为 0。原始第二次 Maven 输出为临时文件 `UMLWORK-grasp-final-test.log`；审计文档给出源码行号与验证边界。对应报告表 4-1，涉及 UC-01～UC-10 的职责映射；模型 15 没有改动。负责人仍需决定内部车位只读接口与 Jackson 迁移的时机。

## 2026-10-09：Ubuntu VM Compose 与 Windows 网关烟测

- **范围与来源**：VM agent 已在 `192.168.125.129` 部署 MySQL 8.4、Redis、五个业务服务、gateway 与 Nginx 前端；默认使用固定地址路由，Nacos 容器没有部署。其编排文件通过只读 HTTP 包取回，包的 SHA-256 独立核对为 `b1869fb596c8d15494daaaf0f296fe9edb34a0824c896a8afae06fc431ee77b6`。仓库原先未在 VM 验证的 `compose.yaml` 被 VM 当前编排的 `docker-compose.yml` 取代；前端宿主端口是 8080。新增每服务独立数据库账号的初始化脚本；同步图 13。对应 UC-01～UC-10 的容器部署；业务 HTTP 接口、状态、表、费率和服务所有权不变。
- **Windows 独立实测**：`pwsh -NoProfile -File .\scripts\smoke-gateway.ps1 -GatewayUrl http://192.168.125.129:18080` 退出码 0，11 个断言均 PASS，包括活动充电阻止出场、冻结费用出账、失败支付保持在场、同键重试、支付后释放、重复完成不再扣费、发票与流量预览。会话 `78ca40cc-f354-4067-b4b5-da63c5cb836e`，账单 `d7b6945e-ff72-4445-ac8d-b7fa4f7199a2`，停车 1000 分、充电 300 分、应付 1300 分。原始输出保存在本机临时文件 `UMLWORK-vm-smoke-20261009.log`。另查网关 `/actuator/health` 为 `UP`、前端首页 HTTP 200、前端 `/api/v1/spaces/status` 为 `code=OK`。
- **边界与后续**：VM agent 报告六个 Java 服务健康、MySQL 初始化成功；Windows 本机没有 Docker CLI，未独立获取 `docker compose ps` 或容器日志。取回的编排文件与 VM 运行版本对齐后，本仓库又做了文档、构建上下文白名单及初始化脚本输入校验，**这些新编辑尚未在 VM 重建测试**。需在 VM 同步仓库后执行 `docker compose config --quiet && docker compose up -d --build && docker compose ps`，再跑同一烟测。Nacos profile 与 Sentinel 仍未在 VM 验证；课程截图必须由负责人本人采集。不要删除 `target/local-runtime/` 或 `.workbuddy-ai/`。

## 2026-10-08：Docker Compose 初稿（已被 2026-10-09 的 VM 版本取代）

- **当时的初稿范围**：共享 Java 运行镜像 `Dockerfile`、`compose.yaml`、Nginx 前端镜像与反代配置、`.dockerignore` 和 `.env.example`。初稿计划预编译六个 Spring Boot JAR 与 `frontend/dist`，并默认用 Nacos 注册发现；该版本从未在 VM 验证，且已被上方 2026-10-09 的固定地址编排取代。Redis 仅供 analytics 报表缓存；HTTP 接口、状态、表、业务费率和服务所有权未变化。
- **本机证据**：Windows 以 Java 21 对六模块执行 `-DskipTests clean package`，六模块 Reactor 均 `SUCCESS`；`frontend` 执行 `npm ci` 和 `npm run build` 成功。六个可执行 JAR 包与 `dist` 已通过 HTTP 传到 Ubuntu 虚拟机接收目录，接收端 size/SHA-256 与本机一致；这只证明传输和构建，不证明容器能启动。
- **待验证**：Windows 本机没有 Docker CLI/daemon；须在 Ubuntu 上执行 `docker compose config --quiet`、`docker compose up -d --build`，记录 `docker compose ps` 的健康状态，并从 Windows 对虚拟机 gateway 跑 `scripts/smoke-gateway.ps1 -GatewayUrl http://<虚拟机地址>:18080`。失败时按容器日志修 Compose，不得写成已部署成功。虚拟机上的 Compose 文件须与本仓库版本同步；不要在两端并行改同一文件。原有 `target/local-runtime/` 未清理，`.workbuddy-ai/` 未触碰。

## 2026-10-08：Redis 已保存报表缓存（最新）

- **范围与边界**：UC-10 的 `analytics-service` 增加 `SavedReportCache`，仅对 `GET /api/v1/analytics/traffic-reports/{id}` 做 Redis 读穿缓存；`traffic-preview` 不缓存。`traffic_report` / `traffic_bucket` 的 MySQL 仍为权威存储，缓存写入发生在成功读库后；Redis 出错只记日志并回源，不改变 HTTP 契约、状态或表。键前缀 `parking:analytics:report:v1:`、TTL 默认 1 小时，地址/超时/密码均从环境变量配置，仓库不含真实密码。新增 `spring-boot-starter-data-redis` 提供 Lettuce 与 `StringRedisTemplate`；新增 BOM 管理的 `jackson-datatype-jsr310` 保证 `OffsetDateTime` JSON 往返保持偏移量。模型同步到图 12、13、15（analytics 那张）。
- **真实 Redis 与 MySQL 证据**：本机 Redis `127.0.0.1:6379`、版本 3.2.100，使用环境变量密码认证后 `PING=PONG`。隔离端口 19085 的新版 analytics 查询已有报表 `f3a483da-1e1c-471e-84b1-236292c0d099` 两次，均返回 `OK`、`totalEntries=10`；Redis `EXISTS parking:analytics:report:v1:<id>` 返回 1，`TTL` 返回 3582 秒。隔离端口 19086 指向不可用 Redis 端口 6399，`/actuator/health=UP`，同报表仍返回 `OK`/10，日志有读写缓存失败并回源的记录。隔离端口 19087 将 MySQL 指向不可用 3307 且关闭 SQL 初始化，已缓存报表仍返回 `OK`/10；未缓存 ID 返回 HTTP 503、`DEPENDENCY_UNAVAILABLE`，证明没有假成功。三只临时 Java 进程测试后已停止；原始日志在忽略目录 `target/local-runtime/analytics-redis*.log`、`analytics-mysql-down-cache-hit.*.log`。
- **测试与运行**：`./mvnw.cmd -pl analytics-service -am test` 为 3 条、0 失败；`./mvnw.cmd test` 全模块 18 条、0 失败，输出 `target/local-runtime/redis-analytics-tests.log` 与 `redis-full-mvn-test.log`。`SavedReportCacheTest` 覆盖带时区的 JSON 往返和 Redis 故障视为未命中；`TrafficServiceTest` 覆盖命中缓存后无需 MySQL 行。启动 analytics 前设置 `REDIS_PASSWORD`，必要时设置 `REDIS_HOST`、`REDIS_PORT`、`ANALYTICS_REDIS_TTL`，其余默认见 application.yml。本轮未推送远端。
- **给 Compose agent**：B1 仍未在本仓库验收。若在虚拟机编排 Redis，应把 analytics 的 `REDIS_HOST` 指向 Compose 服务名，并从未提交的 `.env` 注入 `REDIS_PASSWORD`；不要把本机 `127.0.0.1` 当成容器内 Redis 地址。此处仅是对接说明，不能据此宣称 Compose 已通过。

## 2026-10-07：UML 规范修正与进程视图

- **A1/A2/A3**：`models/15-design-classes.puml` 按各服务 Java 源码补真实字段类型、方法参数/返回类型和可见性（包内访问用 `~`），引用关系标多重性；`ParkingPricing.BenefitPolicy` 标为私有嵌套接口，真实 `NONE` 匿名类用 `..|>` 实现关系表示，两个 lambda 只在 note 中说明，没有虚构具名策略类；补 `analytics-service` 的 Controller、Service、Feign 客户端、records 和异常类，注明只读调用 access、自有 JDBC 只写 analytics 库。覆盖 UC-03/04/10 等；没有改代码、接口、状态、金额单位或数据库表。A1/A2/A3 分别提交 `99421df`、`d8647bf`、`d4db3d0`，最终匿名实现连线另见本轮渲染同步提交。
- **A4**：`models/17-process-sequence.puml` 新增报告图 3-2，命线为客户端、网关及四个参与出场的业务服务；依次表现权益核验、充电费用冻结、`exit_intent` 与 `EXIT_PENDING_PAYMENT` 持久化、出账、支付、`PAID_PENDING_RELEASE`、释放与 `CLOSED`。billing 停机分支返回 503 并保留原意图，同键重试读取旧快照；释放失败分支跳过已付账单的再次支付。与代码 `AccessService.requestExit/completeExit` 和 `AccessRepository.reserveExit` 核对；未画 Sentinel 分支，因为 B2 已回退。`docs/report-outline.md` 的图号与缺口清单已更新，提交 `9cc6d26`。
- **模型验收**：本机 VS Code 扩展内 PlantUML 1.2024.3 对 18 个 `.puml` 运行 `-checkonly`，退出码 0；同一工具渲染出 22 张 PNG（15 号一份源生成五张服务类图）。类图可见性行计数 91、多重性行计数 16、实现关系行计数 1，均非零；实际打开 `models/rendered/15-design-classes.png`、`15-design-classes_001.png`～`_004.png` 与 `17-process-sequence.png` 检查，未见文字截断或连线重叠。可复现命令：`$files=Get-ChildItem models -Filter '*.puml' | % FullName; java -jar <本机 plantuml.jar> -checkonly $files; java -jar <本机 plantuml.jar> -charset UTF-8 -tpng -o rendered $files`。报告图4-1～4-5 应分别引用这五张类图。
- **边界**：本轮只修改模型和说明，后端验证沿用 B3 的 16 条自动测试、MySQL 并发脚本及网关烟测原始输出；未把模型更新说成新的运行联调。`frontend` 中 `npm run build` 在受限沙箱首次因 esbuild `spawn EPERM` 未能启动；同一命令在允许子进程的环境下通过（Vite 6.3.5，主 JS 991.65 kB，只有体积警告）。B1/B2 仍未通过，课程截图、录屏与原创章节仍由负责人完成。未推送远端。

## 2026-10-07：加分项核验与 Nacos 配置中心

- **B1 Compose 未实施**：本机 `docker` / `docker compose` 均不存在，Docker Desktop 安装目录也不存在；`wsl --status` 提示尚未安装 WSL。无法执行必需的 `docker compose up -d` 和六服务容器健康验收，故未提交 Docker 文件，不把它写成已完成。没有触碰 `.workbuddy-ai/` 或既有 `target/local-runtime/` 文件。
- **B2 Sentinel 已回退**：用 BOM 管理的 `spring-cloud-starter-alibaba-sentinel`、Feign fallback 与可配置熔断规则做过真实 MySQL 试验。billing 停机时三次出场请求均为 503，`EXIT_PENDING_PAYMENT` 与唯一 `exit_intent` 保留；恢复后同键得到一张账单、一笔成功支付，原始输出 `target/local-runtime/sentinel-breaker-result.log`。但 `scripts/verify-mysql-concurrency.ps1` 的 8 路预约回归反复出现 1 个 200、其余多个 503，原断言要求 7 个 `409 RESERVATION_CONFLICT`；`target/local-runtime/pass-service-sentinel-trace.out.log` 指向 `feign.codec.DecodeException: 'messageConverters' must not be empty`（SpringDecoder）。按本任务“不得破坏一致性”的取舍，已撤销全部 B2 源码、依赖和测试改动，未提交 Sentinel；不要在报告写“已启用熔断”。诊断日志保留供后续定位版本兼容问题。
- **B3 配置范围**：六个模块增加 BOM 管理的 Nacos Config 依赖，通过 `optional:nacos:<应用名>.properties` 导入 `DEFAULT_GROUP`；`config/nacos/` 提交 billing 16 项、space 2 项、pass 4 项演示基线，没有密码。原 application.yml 前缀、环境变量与本地默认值仍在。space/pass 的构造器 `@Value` 由 `@RefreshScope` 刷新；billing 每次出账从当前 Environment 绑定一份 `PricingProperties`，金额和 `rateVersion` 用同一快照写账单，已付历史不重算。对应 UC-03/04/05/06/09，部署图 13；接口路径/DTO/错误码、状态、表均未变化。
- **B3 实测**：在 Nacos 8848 未运行时启动六个新 JAR，18080～18085 的 `/actuator/health` 全部 `UP`，`./scripts/smoke-gateway.ps1` 全部通过，输出在 `target/local-runtime/nacos-config-offline-smoke.log`。之后启动本机 Nacos 3.1.1，经其配置 API 发布三个示例 Data ID，服务日志出现 `Refresh keys changed`。`./scripts/verify-nacos-pricing-refresh.ps1` 将 `day-first-cents` 由 600 临时改为 650、版本改为 `v2-refresh-check`，不重启 billing，新账单由 1000 变 1050 分且版本同步变更，旧账单仍为 1000 分和 v1；脚本 `finally` 已恢复远端配置，输出在 `target/local-runtime/nacos-pricing-script-final.log`。space 充电单价 150→200 分/kWh 后，新 2 kWh 会话为 300→400 分，版本同步变化；pass 预约预付 500→700 分后，新预约金额变化，均未重启且已恢复演示基线；输出在 `target/local-runtime/nacos-value-refresh-result.log`。本轮 `./mvnw.cmd test` 为 16 条、0 失败（`target/local-runtime/nacos-config-mvn-test.log`）；原并发脚本五项检查恢复全绿，输出在 `target/local-runtime/nacos-config-concurrency.log`。
- **仍需关注**：本轮配置中心通过 Nacos HTTP API 与实际服务验证，未采集控制台完整窗口截图；课程截图仍由姜苏豪本人采集。Nacos 客户端在受限沙箱中尝试将快照写到用户目录时有 `FileNotFoundException` 日志，但实际配置通知和业务热更新通过；普通本地环境可按日志核查快照目录权限。B1/B2 没有验收，不得在部署图画为已运行。

## 2026-10-06：车位立体示意与轻量动效

- **范围与文件**：为 UC-01、UC-07 的车位展示修改 `frontend/src/components/SpaceMap.vue`、`frontend/src/style.css`；近 24 小时车流柱图只增加短时入场动画。先在 `docs/ui-design.md` 记录 ECharts GL、数据过渡、动态排序柱图的官方资料和取舍。没有新增依赖、接口、状态或数据库表；模型中的资源含义不变，对应车位资源模型与 UC-01/07 映射见 `docs/traceability.md`。
- **行为**：页面新增“立体/平面”视角按钮，按服务返回的真实楼层、区域、车位、状态绘制同一批车位；立体效果是 CSS 透视与层高示意，不代表机场真实建筑。选中、筛楼层、点击详情在两种视角下保持一致。`prefers-reduced-motion` 仍关闭所有非必要动效。
- **验证与运行**：`cd frontend && npm run build` 成功，主 JS 991.65 kB（原有体积警告）。本机 MySQL + 五服务 + gateway 固定地址模式启动，Vite `http://127.0.0.1:5173/` 页面显示 5/5、15 个实际车位；浏览器切换视角、B2 筛选、点击 C-001 均通过，390px 宽无横向溢出，控制台无 error/warn。服务 PID 位于忽略目录 `target/local-runtime/ui-processes.json`，Vite PID 位于 `ui-vite.pid`，接手时先核实进程存活。没有帧率量测，不能写成达到某个 FPS。未推送远端；课程正式截图仍须负责人本人采集。

## 2026-09-30：任务 C 浏览器业务回归

- **预约（UC-05、图 08/11）**：在 Vite 页面经网关创建苏AUI3091 对 D-001 的预约（18:00～19:00），模拟预付失败后重试成功，18:15 凭证入场，19:15 出场；页面显示停车费 600 分、预约优惠 60 分、预付抵扣 500 分、应付 40 分。模拟结算支付失败时保留待支付，重试后显示已支付、`CLOSED` 和车位释放。另创建苏AUI3092 对 D-002 的待预付预约并在页面取消，显示 `CANCELLED`。这些是演示输入时刻，不代表生产时钟规则。
- **月卡（UC-06、图 08/11）**：页面创建苏AUI3095 月卡，初始余额 20000 分及 `INITIAL_CREDIT` 流水；普通车位入场，账单停车费 1200 分、月卡优惠 240 分，完成出场后刷新显示余额 19040 分、唯一一条 960 分 `DEBIT` 流水，关联停车记录 `a53952c8-a30f-436d-9497-d69035bec563`。月卡 ID 为 `00112e1e-e8ec-480e-9634-0d32675f3dd4`。
- **充电（UC-09、图 08/11）**：苏AUI3094 入 C-001 充电位，14:30 开始、15:00 结束，输入 2.000 kWh，页面显示 300 分；16:00 出场账单分列停车 1000 分、充电 300 分、应付 1300 分，支付后 `CLOSED` 且车位释放。充电会话 ID 为 `3be788bf-6325-404d-8e40-2d8323c51639`。
- **浏览器发现并修复**：从预约车位切到普通车位时，隐藏的旧 `reservationId` 原仍被提交，后端正确拒绝。`frontend/src/App.vue` 仅在 `spaceType === 'RESERVATION'` 时提交此字段；复现旧值后切普通位、入场成功，`frontend` 的 `npm run build` 通过。接口、状态、表和 UML 未变化；复用现有入场接口，未增加依赖。首次沙箱构建因 esbuild `spawn EPERM` 未启动，获准沙箱外构建成功。
- **证据边界与运行**：上述为本机真实 MySQL + 网关 + Vite 的浏览器操作，非课程提交截图；负责人仍须在本人机器采集完整窗口截图与录屏。当前服务重启后的 PID 记录为忽略文件 `target/local-runtime/nacos-processes-c.json`，下次接手须核实 PID 和端口，勿把旧运行记录当作仍在线。Nacos 控制台实例页截图仍待负责人本人初始化管理员密码；API 六实例和网关调用证据见任务 A。任务 B 的独立故障窗口见 `docs/mysql-fault-tests.md`。

## 2026-09-30：任务 A 真实 Nacos 服务发现联调

- **已实测**：官方 Nacos 3.1.1 standalone 从忽略的 `target/local-runtime/nacos-3.1.1/` 启动，8848 客户端端口和 8080 控制台端口可达。六个 Java 进程设置 `NACOS_ENABLED=true`、`NACOS_SERVER_ADDR=127.0.0.1:8848`；四个调用方 `*_SERVICE_URL` 均置空；gateway 使用 `--spring.profiles.active=nacos`。`Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=100'` 返回 gateway、space、access、billing、pass、analytics 共六个服务；逐个 `.../nacos/v1/ns/instance/list?serviceName=名称` 返回一个 `healthy=True enabled=True` 实例，端口分别为 18080～18085。原始摘要在忽略文件 `target/local-runtime/nacos-instances.log`，可随时用上述命令重查。
- **经网关实测**：`./scripts/smoke-gateway.ps1` 12 项检查全部 PASS，停车记录 `acc7e1e4-3993-441a-9cf6-1b5f35e73783`，账单 `cf9fc60c-18b3-45e9-b359-c4b1ebf59518`（停车 1000 分、充电 300 分、应付 1300 分），输出在 `target/local-runtime/nacos-smoke-gateway.log`。另经网关 `POST /api/v1/passes/reservations` 返回 200/PENDING_PAYMENT，随后取消返回 200/CANCELLED；这一步验证 pass 的 Feign `space-service` 服务名发现。六个 `/actuator/health` 均为 UP。
- **发现并修复**：空 `SPACE_SERVICE_URL` 时 pass 启动失败，明确报 `No Feign Client for loadBalancing defined`。负责人已同意补官方依赖；只在 `pass-service/pom.xml` 加入 BOM 管理的 `spring-cloud-starter-loadbalancer`。`./mvnw.cmd -pl pass-service -am package` 通过，pass 自身 4 个测试 0 失败。网关、access、analytics 原本已含该依赖，无需增加。接口路径/DTO/状态/数据库表未变；部署约定同步到 `docs/api-contract.md`，已更新 `models/13-deployment.puml` 和渲染 PNG（图 13），以及 README。
- **待补控制台 UI 证据**：Nacos 控制台首次访问停在“初始化管理员密码”。凭据必须由负责人亲自设置，尚未取得控制台实例页截图；不要把上述 API 证据写成“控制台截图已完成”。负责人设置后进入服务管理实例列表，确认六项，再由其采集课程所需本人完整窗口截图。当前六个 Java 进程 PID 位于 `target/local-runtime/nacos-processes-c.json`，重启前须按端口和命令核实；Nacos 包、认证密钥和日志均留在忽略的本地运行目录，未写入 Git。
- **覆盖与后续**：本项联调覆盖 UC-02、UC-03、UC-04、UC-05、UC-07、UC-09、UC-10 的相关真实接口。任务 B、C 的结果见相应章节。课程报告中的 Nacos 注册截图仍待负责人本人采集，Nacos 配置中心没有启用。

## 2026-09-30：任务 B 真实 MySQL 并发与故障注入

- **执行结果**：可重复运行 `scripts/verify-mysql-concurrency.ps1 -MySqlExe <mysql.exe 路径>`（`BILLING_DB_PASSWORD` 只通过环境提供），8 路并发占位仅 1 成功/7 个 SPACE_UNAVAILABLE，8 路同窗预约仅 1 成功/7 个 RESERVATION_CONFLICT，8 路并发成功支付返回同一 paymentId 且 billing 自有库只有 1 条 SUCCESS 流水；脚本最后正常关闭记录。实际输出在忽略的 `target/local-runtime/mysql-concurrency.log`。真实 billing 停机后出账重试、space 停机后支付/释放恢复、月卡扣款后 billing 支付响应超时均已逐项注入并复核；步骤、具体 ID、故障窗口和断言见 `docs/mysql-fault-tests.md`，最终数据库摘要在 `target/local-runtime/mysql-fault-final-check.log`。
- **修正的真实缺陷**：MySQL 并发支付原先 7/8 请求返回 500，原因是 REPEATABLE READ 事务旧快照看不见另一事务刚提交的支付流水。`billing-service/.../BillingService.java` 的已付流水查询改为 `FOR UPDATE` 当前读；原脚本不放宽断言重跑通过。billing 停机时，access 原先将出场意图保存但仍显示 `PARKED`，与本轮故障验收及状态语义不符。`access-service/.../AccessRepository.java` 在本地事务中原子保存出场意图并转 `EXIT_PENDING_PAYMENT`，`AccessService.java` 允许无账单的原意图重试；原有 access 测试加入停机后的状态与空 billId 断言。实际 MySQL 验证先返回 503，恢复后保留 1 行意图与 1 张账单。
- **契约与模型**：HTTP 路径/DTO/金额单位/数据库表未改；`docs/api-contract.md` 明确待出账时 billId 可空。同步更新 PlantUML 03 状态图、08 出场设计顺序图、11 活动图及其 PNG；17 张源文件均已 `-checkonly` 通过。对应 UC-02、UC-03、UC-04、UC-05、UC-06；图号 03、08、11（任务 A 为图 13）。`README.md` 和 `docs/traceability.md` 已链接新证据。
- **构建与剩余边界**：`./mvnw.cmd test` 在上述修正后重新运行，16 条测试、0 失败；access 与 billing 的 `package` 分别通过。故障注入使用真实 MySQL 单机与单实例服务，未证明跨机多实例、网络分区或真实支付网关。任务 C 的结果见本文件顶部；任务 A 的控制台实例截图仍等负责人初始化本机管理员密码。

## 用户当前目标

全面核对接口与代码、修正业务逻辑与硬编码问题，并把前端做成更接近真实机场停车平台的可视化界面。负责人已再次授权“来吧写代码吧”。本轮由集成 agent 顺序实施，持续更新本记录，不推送远端。

### 界面 v2 已实施并验收（2026-09-28）

负责人认为上一版界面设计不够好，本轮先写 `docs/ui-design.md` 再改 frontend。新版统一深蓝/浅柠檬绿、SVG 图标与机场导视；总览围绕真实车位图和常用操作，寻车突出车牌/位置，结算采用票据与状态进度。未增加依赖或后端接口。修改入口是 App.vue、style.css、components/AppIcon.vue 和 SpaceMap.vue；其他 agent 可按下方记录接手，勿继续套用旧 CSS 类名。

## 本轮实施顺序与文件所有权

1. 集成负责人先更新接口和模型：space 提供结算冻结接口，在本地事务中检查无 ACTIVE 充电、保存费用快照；access 在出账前调用。冻结后禁止本停车记录新增充电，重试读取原快照，避免不可变账单漏费。
2. 修改 space/access 及对应测试，验证上轮未构建改动；新增充电车位同时创建一个配套充电桩。
   出场补齐请求键内容登记；费用冻结后在 access 自有库保存出场意图与权益快照再远程出账，防止断线重试用不同出场时间覆盖既有账单语义。
3. 保留前端原有业务动作，新增运营总览、车位分区可视图、实际车流图和账单分项，修正出场后界面状态刷新。使用现有 Vue/Element Plus 与 CSS，不添加图表依赖。
4. 构建、真实 MySQL/网关联调、浏览器核对；更新审查与追溯记录，并在本文件记录通过项和剩余项。

本轮集成负责人持有 space/access/analytics 的相关文件、前端和公共契约/模型；没有向历史子 agent 分派并发写入。接手前先查看此文件底部的最新验证结果，未验证的改动不能写进报告“已完成”。

补充审查：集成负责人同时修正 pass 的车牌校验，使预约/月卡和入场均接受同一格式；access 统计查询排除尚未完成入场的 ENTERING 记录。契约已先更新，两项均附现有测试断言。

### 2026-09-28 16:55 当前检查点

- 原有未验证改动已构建；space 的冻结、配套充电桩和冻结/新增充电竞争测试通过；access 业务错误透传、缺失数据拒绝、断线重试意图固定、请求键冲突断言通过。后端合计 16 条测试通过（分次 package），前端新版两次 build 通过。
- 已写入 `charging_settlement`（space）、`access_request_key` 和 `exit_intent`（access），均为 CREATE IF NOT EXISTS 增量建表，不删除已有数据。新增费用冻结规则和出场意图已同步到契约与模型源。
- 前端加入可复用 SpaceMap/TrafficChart、七项导航、总览指标、收费明细、发票结果与出场状态同步；前端只做分转元/图表比例，业务金额仍来自后端。浏览器验收正在进行。
- 五个服务与网关由本轮 Start-Process 隐藏启动，PID 及日志在被忽略的 `target/local-runtime/`；Vite exec 会话 17032，地址 http://127.0.0.1:5173/。构建前若需重启，只停止此进程记录中的本项目 Java 进程，勿停止 IDE 的 Java。

## 已验证的实施基线（本轮开始前）

- 根 Maven 多模块含 gateway 与五个独立服务；五个 MySQL schema 为 `parking_space`、`parking_access`、`parking_billing`、`parking_pass`、`parking_analytics`。费率/预付/月卡参数在各权威服务配置中，密码不在仓库。
- 上次完整 `mvnw.cmd clean package` 为 14 条测试、0 失败；`frontend` 的 `npm run build` 通过。六个 Java 服务和前端曾在本机启动，详情见 `docs/code-audit.md`。
- 当时真实 MySQL 顺序联调已覆盖普通停车、预约预付、月卡、充电、异常出场、发票与日报；17 个 PlantUML 源文件已通过语法检查并生成 PNG。当时真实 Nacos 与浏览器逐按钮验收尚未完成，其后续结果见本文件顶部；课程截图、录屏和报告仍由负责人完成。

## 9 月 26 日中断时的遗留改动（9 月 28 日已继续验证）

| 文件 | 改动 | 最新验证 |
|---|---|---|
| `access-service/.../AccessService.java` | 透传下游业务错误，拒绝 null 成功数据；新增出场意图恢复及请求键内容登记。 | 自动测试验证 409 STATE_CONFLICT、503、null 数据、冻结后出账失败重试及同键内容冲突；真实网关联调 409 通过。 |
| `analytics-service/.../TrafficController.java` | 新增只读 preview，复用统计计算；范围严格不超过 31 天。 | 自动测试检查 preview 不落库、原报表可保存读取、31 天加 1 秒拒绝；浏览器生成报表通过。 |
| `analytics-service/.../TrafficServiceTest.java` | 增加 preview 不落库断言。 | Maven 测试通过。 |
| `space-service/.../SpaceOperations.java` | 结束充电加行锁；新增结算冻结；新增充电车位配套充电桩；时间统一微秒。 | 4 条测试含占位竞争、冻结/新增充电竞争、精度重试；MySQL 冻结完整链路通过。MySQL 并发结束压力测试尚未单独做。 |
| `frontend/vite.config.js` | 代理目标可由环境覆盖。 | build 与浏览器实际网关代理通过。 |
| `docs/api-contract.md` | 补充 preview、settlement、寻车恢复字段、微秒和统一车牌规则。 | 与调用 DTO、SQL、模型和 smoke-gateway 联调核对。 |

9 月 26 日前端曾临时删除后从 Git 对象恢复，9 月 28 日已经在恢复版基础上完成可视化改版；当前 App.vue 不再等于旧恢复对象。业务动作在 App.vue，SpaceMap/TrafficChart 仅负责呈现。原有未提交变更及 `.workbuddy-ai/` 属于共享工作区，勿随意覆盖或清理。

## 代码审查发现，下一位 agent 应优先处理

1. **Nacos 实际注册发现（2026-09-30 更新）。** 服务端、六实例和网关链路已实测，详见本文件顶部。控制台实例列表截图仍待负责人初始化管理员密码后采集。
2. **实际故障注入（2026-09-30 更新）。** billing/space 停机恢复及月卡扣款后支付超时已在真实 MySQL 复核，详见 `docs/mysql-fault-tests.md`；跨机网络分区和多实例故障尚未验证。
3. **浏览器全流程验收（2026-09-30 更新）。** v2 已走普通入场→寻车→出账→失败支付→重新寻车恢复同账单→成功出场→发票及报表；预约/月卡/充电的新增逐项操作见本文件顶部。快捷入口只是导航，不是权限控制。
4. **课程交付。** 负责人核对 17 个模型，补本人完整窗口截图、8～10 分钟带字幕录屏、Word 报告与 AI 附录。`docs/screenshots/qa-overview.png` 是 agent 在本人机器的浏览器 QA 实拍，不能代替课程规定的本人完整窗口截图。

## 最新运行和验证入口

- 后端 `mvnw.cmd test`：16 条测试，0 failure/error；最后的 access、space 改动各自重新 package 验证；其他模块 package 已通过。运行中的 JAR 会被 Windows 锁定，重打包前按 `target/local-runtime/processes.json` 核实并停止对应服务进程。
- 前端 `npm run build` 通过；唯一构建提示为 Element Plus 全量包约 991 kB，当前没有加载故障。不新增依赖来制作图表。
- 真实 MySQL 联调可在 PowerShell 7 执行 `./scripts/smoke-gateway.ps1`，支持 `-GatewayUrl` 覆盖地址。脚本创建一条充电停车演示并保留流水，不删除数据；验证活动充电拒绝出账、冻结后拒绝充电、金额一致、请求键冲突、待支付恢复、支付释放及发票和 preview。失败时按输出停车记录 ID 检查，不手动篡改账单。
- 最新 PID/日志在 `target/local-runtime/`，不含密码；Vite 会话 17032。前端地址 http://127.0.0.1:5173/，网关 18080，五个业务服务 18081～18085；Java 路径为本机 Amazon Corretto JDK 21。密码只通过环境传入，仓库没有保存。
- 首页保留七项业务导航，入场/寻车/预约入口与真实资源指标；车位图按后端楼层区域生成，道路为示意。结算按车牌检索，UUID/演示时间/失败支付收入详情。390px 宽度逐页检查七项业务，无页面横向溢出；导航可横向滚动。
- 17:17 最终重启后再次运行 smoke-gateway 全部通过，六项健康检查均 UP；非法月卡车牌实际 HTTP 400。最后一次真实联调输出保存在 `target/local-runtime/smoke-gateway.log`。浏览器总览已刷新并保留打开，最终 QA 图片已更新。

### 界面 v2 浏览器证据

- 苏A92802 实际入场于 17:54:03，车位 B1/A/A-001；演示出场时间 19:54:03，账单 1000 分。支付失败保留待支付；热更新后重新寻车恢复同一张账单/出场时刻，成功后 CLOSED、车位释放，模拟发票 REQUESTED。演示未来出场时刻已记录，不作生产时间规则。
- 修正公共操作提示：支付失败和待释放显示警告；普通寻车不会误报支付失败。成功只保留短提示和具体结果，关闭出场后付款按钮不可再点。切换停车记录先清空旧账单，防止下游读取失败时混用另一辆车的费用。
- 报表生成成功，当前近 24 小时真实入场合计 4 辆；浏览器控制台本轮无 error/warn。车位详情点击、楼层切换、入口导航、七页手机布局均已核对。没有重跑后端全套测试：此轮未修改后端，使用真实前端链路验证。
- 本机 QA 实拍：`docs/screenshots/qa-overview-v2.png`、`qa-entry-v2.png`、`qa-billing-v2.png`、`qa-mobile-v2.png`。保留 v1 图片作为历史证据；这些页面截图不能替代课程要求的本人完整窗口截图。设计/验收细节见 `docs/ui-design.md`。

## 仓库与课程边界

工作区有大量此前的未提交实现，当前尚未新建提交或推送 GitHub。按 `AGENTS.md`，不自行推送远端或提交最终作业；课程指定的本人原创章节和真实运行截图由姜苏豪完成。任何新接口先更新 `docs/api-contract.md`，再同步代码、模型和测试。
