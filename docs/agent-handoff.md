# Agent 交接记录（2026-10-06，当前可接手）

> 本文件用于中途接手。先读根目录 `AGENTS.md`、`docs/decisions.md`、`docs/api-contract.md` 和两份课程原件。业务语义与接口由这些文件约束；本记录只说明当前工作状态。请勿把未验证事项写成已完成。

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
