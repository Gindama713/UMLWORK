# 服务接口契约 v1（【AI-辅助】）

2026-09-25 实施基线。业务规则见 [decisions.md](decisions.md)。本文件约束服务之间交换的字段和语义；一个服务不能直接读取另一个服务的表。增减字段前由集成负责人修改本文件，并同步用例、模型和调用方。

## 通用格式

- UTF-8 JSON。外部路径以 /api/v1 开头，服务间路径以 /internal/v1 开头。状态接口仍保留。
- 网关的 `/`、`/assets/**` 等为前端页面/静态资源路由，不是业务 REST 接口；地址由 `FRONTEND_SERVICE_URI` 配置。业务响应格式只约束 `/api/v1` 和 `/internal/v1`。
- 所有响应为 {code:string,message:string,data:object|array|null,requestId:string}；成功 code=OK。非 2xx 也遵守此格式。
- ID 为 UUID 字符串；车牌去除两端空白、规范化大写，统一接受 5～12 位汉字/字母/数字；金额用 long 整数分；电量用十进制数 kWh；时间为 ISO 8601 带偏移量字符串，数据库时刻精度为微秒，计费自然日按 Asia/Shanghai。
- 创建、支付、占用、释放均携带 Idempotency-Key。相同键和请求重试返回原业务结果；同键不同请求返回 IDEMPOTENCY_CONFLICT。access 的出场重试返回当前恢复状态（例如已付待释放恢复为 CLOSED），不会再次扣费。停车记录 ID 和账单的 parkingSessionId 唯一约束作为第二道防线。
- 服务内部也返回响应包，不传 JPA/JDBC 实体。各服务只声明自己消费的 DTO，不建跨服务数据库实体共享包。
- HTTP 400 INVALID_ARGUMENT，404 NOT_FOUND，409 ACTIVE_SESSION_EXISTS / SPACE_UNAVAILABLE / RESERVATION_CONFLICT / STATE_CONFLICT / BILL_ALREADY_PAID / IDEMPOTENCY_CONFLICT，503 DEPENDENCY_UNAVAILABLE。错误 data=null。

## UC-01 / UC-02 / UC-07：车位与入场

| 方法与路径 | 输入 | data | 要点 |
|---|---|---|---|
| GET /api/v1/spaces?type=NORMAL | type 可选：NORMAL、ACCESSIBLE、CHARGING、RESERVATION | [{spaceId,type,floor,zone,number,status}] | 只读，不替代预约冲突检查 |
| POST /api/v1/spaces | {type,floor,zone,number} | {spaceId,type,floor,zone,number,status} | 管理员演示创建物理车位，初始 AVAILABLE；编号唯一；CHARGING 同一事务创建配套充电桩 |
| PATCH /api/v1/spaces/{spaceId}/service-status | {status} | 同上 | status 仅 AVAILABLE/OUT_OF_SERVICE；OCCUPIED 不可停用，保留历史而不物理删除 |
| GET /internal/v1/spaces/available?type=NORMAL | 必填 type | 同上，仅 AVAILABLE | 普通入场不得取 RESERVATION 位 |
| POST /internal/v1/spaces/{spaceId}/occupy | {parkingSessionId} | {spaceId,type,floor,zone,number,status,parkingSessionId} | 条件更新 AVAILABLE→OCCUPIED；同 session 重试返回相同结果 |
| POST /internal/v1/spaces/{spaceId}/release | {parkingSessionId} | 同上，status=AVAILABLE | 只有相同 session 可释放；重复释放幂等 |
| POST /internal/v1/spaces/{spaceId}/settlement | {parkingSessionId} | {parkingSessionId,chargingCents} | 出账前冻结本停车记录充电费用；必须仍占用此车位且无 ACTIVE 充电；有 ACTIVE 返回 409 STATE_CONFLICT；冻结后禁止新增充电，同 session 重试返回原快照 |
| POST /api/v1/access/entries | {plateNumber,spaceType,reservationId?,entryTime?} | {parkingSessionId,plateNumber,spaceId,floor,zone,spaceNumber,entryTime,status,exitTime?,billId?,exceptionType?,operator?} | entryTime 可指定带偏移量的演示时刻，省略则取服务器当前时刻；预约位必须提供有效预约；出场附加字段用于页面重开后恢复待结算工作台 |
| GET /api/v1/access/locate?plateNumber=... | plateNumber 必填 | 同上 | 仅返回仍在场车辆；带回已冻结出场意图；billing 故障时 `EXIT_PENDING_PAYMENT` 的 billId 可暂为空，重试出账后再支付/释放 |
| GET /internal/v1/access/sessions?from=...&to=... | 带偏移量的 from、to，左闭右开 | [{parkingSessionId,entryTime,exitTime?,status}] | 只给 analytics 统计已完成入场的记录（PARKED/EXIT_PENDING_PAYMENT/PAID_PENDING_RELEASE/CLOSED），排除 ENTERING/ENTRY_FAILED |

## UC-03 / UC-04 / UC-08：结算

| 方法与路径 | 输入 | data | 要点 |
|---|---|---|---|
| POST /api/v1/access/{sessionId}/exit-requests | {exitTime,exceptionType?,operator?} | {parkingSessionId,billId,status,amountDueCents} | exceptionType: NONE/LOST_CARD；丢卡时 operator 必填；超长由 billing 自动识别；冻结后在 access 保存出场意图（时间、异常、权益快照）并转 `EXIT_PENDING_PAYMENT`，再请求账单；若 billing 不可用则返回 DEPENDENCY_UNAVAILABLE、状态保留且账单 ID 暂为空；重试按原意图恢复，改变时间/异常报 STATE_CONFLICT |
| POST /api/v1/access/{sessionId}/complete-exit | {simulatedResult} | {parkingSessionId,billId,paymentId?,status,spaceReleased} | simulatedResult: SUCCESS/FAILURE；成功后释放，释放失败为 PAID_PENDING_RELEASE，重试不得二次扣费 |
| POST /internal/v1/billing/bills | {parkingSessionId,entryTime,exitTime,benefitType,prepaidCents,chargingCents,exceptionType} | BillView | benefitType: NONE/RESERVATION/MONTHLY；同 session 只建一账单 |
| GET /internal/v1/billing/bills/{billId} | — | BillView | 供失败后恢复 |
| GET /api/v1/billing/bills/{billId} | — | BillView | 页面查看账单明细，不重算 |
| POST /internal/v1/billing/bills/{billId}/payments | {simulatedResult,paymentSource?} | {paymentId,billId,billStatus,paymentStatus,paymentSource,amountCents} | SUCCESS/FAILURE；paymentSource: SIMULATED/MONTHLY_BALANCE；已成功支付重试返回原成功记录 |
| POST /api/v1/billing/bills/{billId}/payments | 同上 | 同上 | 供独立演示/前端；出场仍通过 access 协调 |
| POST /api/v1/billing/bills/{billId}/invoice-requests | {invoiceTitle,taxNumber?} | {invoiceRequestId,billId,status} | 仅已付账单，模拟申请 |

BillView = {billId,parkingSessionId,status,parkingBaseCents,discountCents,prepaidCents,prepaidRefundCents,parkingDueCents,chargingCents,exceptionCents,amountDueCents,rateVersion,feeItems:[{name,amountCents,detail}]}。prepaidCents 是本单实际抵扣额；prepaidRefundCents 是待退差额。金额不为负；优惠、预付先作用于停车基础费，充电和异常费不参与抵扣。月卡余额可支付整张折后账单；余额不足则整笔改为模拟支付。账单 status: UNPAID/PAID。账单创建时保存费率版本和明细快照。

停车记录状态：ENTERING/PARKED/EXIT_PENDING_PAYMENT/PAID_PENDING_RELEASE/CLOSED/ENTRY_FAILED。已付待释放需重试完成出场，不能新建账单。只有 CLOSED 才不再被寻车查询。

## UC-05 / UC-06：预约与月卡

| 方法与路径 | 输入 | data | 要点 |
|---|---|---|---|
| POST /api/v1/passes/reservations | {plateNumber,spaceId,startTime,endTime} | {reservationId,spaceId,status,prepaidCents} | 仅 RESERVATION 车位；时段冲突在 pass 自有库中串行化检查 |
| POST /api/v1/passes/reservations/{id}/pay | {simulatedResult} | {reservationId,status,paymentId} | SUCCESS 后 CONFIRMED；失败仍 PENDING_PAYMENT |
| POST /api/v1/passes/reservations/{id}/cancel | {} | {reservationId,status,refundCents} | 开始前取消可退；幂等 |
| POST /internal/v1/passes/reservations/{id}/consume | {plateNumber,entryTime,parkingSessionId} | {reservationId,spaceId,status,prepaidCents} | 仅 CONFIRMED 且匹配车牌/时间；重复同 session 幂等 |
| POST /internal/v1/passes/reservations/{id}/release-consumption | {parkingSessionId} | {reservationId,status} | 仅占位失败时由 access 补偿本 session 的核销；相同请求幂等 |
| POST /internal/v1/passes/reservations/{id}/refund-remainder | {parkingSessionId,amountCents} | {reservationId,parkingSessionId,refundCents,refundId} | 账单已付后由 access 请求退差额；同 session 重试幂等、金额变化报 IDEMPOTENCY_CONFLICT |
| GET /internal/v1/passes/eligibility?plateNumber=...&entryTime=...&exitTime=...&spaceType=...&parkingSessionId=... | 五项必填 | {benefitType,prepaidCents,monthlyPassId?} | 预约按核销的停车记录 ID 精确核验，月卡按车牌和时段核验；无权益为 NONE |
| POST /api/v1/passes/monthly-passes | {plateNumber,startTime} | {monthlyPassId,plateNumber,endTime,balanceCents,status} | 有效 30 天；初始余额来自配置及模拟充值记录 |
| GET /api/v1/passes/monthly-passes/{id} | 月卡 ID | 同上 | 查询当前余额和有效状态 |
| GET /api/v1/passes/monthly-passes/{id}/ledger | 月卡 ID | [{entryId,parkingSessionId?,kind,amountCents,balanceAfterCents,createdAt}] | 核对初始入金及自动扣费 |
| POST /internal/v1/passes/monthly-passes/{id}/deductions | {parkingSessionId,amountCents} | {deductionId,balanceCents} | 同 session 幂等；余额不足报 PASS_BALANCE_INSUFFICIENT |

预约状态 PENDING_PAYMENT/CONFIRMED/USED/CANCELLED/EXPIRED。未来预约时间窗只存在 pass-service；space-service 的状态只表示当前占用。月卡预存款扣除与账单支付须在 access 协调下可恢复，不得在失败后重复扣余额。

## UC-09 / UC-10：充电与报表

| 方法与路径 | 输入 | data | 要点 |
|---|---|---|---|
| GET /api/v1/spaces/chargers?spaceId=... | spaceId 可选 | [{chargerId,spaceId,status}] | 给演示页选择充电桩 |
| POST /api/v1/spaces/charging-sessions | {chargerId,parkingSessionId,startTime} | {chargingSessionId,status} | 一个充电桩只能有一条 ACTIVE 会话；停车记录已冻结结算则 409 STATE_CONFLICT |
| POST /api/v1/spaces/charging-sessions/{id}/finish | {endTime,energyKwh} | {chargingSessionId,status,energyKwh,chargingCents,rateVersion} | 金额只由服务配置计算 |
| GET /internal/v1/spaces/charging-fees?parkingSessionId=... | 必填 parkingSessionId | {parkingSessionId,chargingCents} | 只读查看已结束费用，不保证结算一致性；access 出账必须调用 settlement 冻结接口 |
| GET /api/v1/analytics/traffic?from=...&to=...&granularity=HOUR | 带偏移量时间，粒度 HOUR/DAY | {reportId,from,to,granularity,buckets:[{start,count}],peakBucket,totalEntries} | 入场时刻计数，左闭右开；生成的报表快照存 analytics 自有库 |
| GET /api/v1/analytics/traffic-preview?from=...&to=...&granularity=HOUR | 同上 | 同上，reportId=null | 只读预览，不持久化，供总览图表刷新 |
| GET /api/v1/analytics/traffic-reports/{id} | 报表 ID | 同上 | 重看已保存的快照，不重新访问 access |

## 服务依赖及开发范围

2026-10-08 实现补充：`analytics-service` 的已保存报表查询可从 Redis 读取不可变快照，未命中或 Redis 不可用时回源本服务 MySQL；实时 `traffic-preview` 不缓存。HTTP 路径、DTO、状态和错误码不变，Redis 不承担权威存储。

space 与 billing 不调用其他业务服务；pass 只读查询 space；access 调 space、pass、billing；analytics 只读调 access。网关只路由。第一条垂直链先完成无预约普通车位的入场、寻车、出账、模拟支付和释放，再接入其余功能；尚未实现的接口不能写成已完成。

2026-09-30 部署补充：接口路径、DTO、状态及错误语义不变。服务发现模式下，六个 Java 进程均以 `NACOS_ENABLED=true` 注册到同一 Nacos 3.1.1；四个 `*_SERVICE_URL` 置空后，access、pass、analytics 的 Feign 调用按服务名解析，网关 `nacos` profile 的 `lb://` 路由也按服务名解析。pass-service 必须含 Spring Cloud LoadBalancer（版本由根 BOM 管理），否则空 URL 的 Feign 客户端在启动时失败。该模式与默认固定本地地址模式使用同一组 HTTP 契约。

2026-10-07 配置补充：六个应用导入同名 Nacos `.properties` Data ID（`DEFAULT_GROUP`，`optional:nacos:`），本地 application.yml 保留兜底值；只同步业务配置，不把数据库密码、状态枚举或接口 DTO 放入 Nacos。计费、充电与预约的新记录可读取刷新后的参数，已生成账单与充电会话保留原规则版本快照。配置中心不改变上述 HTTP 错误语义。

2026-09-28 补充：出场顺序为核验权益 → space 冻结充电费用 → billing 创建不可变账单。冻结记录存 space 自有库；冻结与开始充电使用同一车位行锁串行化，无消息队列或分布式事务。冻结后下游失败，可重试出账，不能再新增充电；释放后保留快照供审计。
