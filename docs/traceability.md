# 需求—模型—代码—测试映射（【AI-辅助】）

更新于 2026-10-18。下表“已验证”含自动测试、本机真实 MySQL 顺序联调、Nacos 服务发现、Redis 报表缓存及有记录的并发/断线注入；逐项证据见 [联调记录](code-audit.md)、[故障注入记录](mysql-fault-tests.md)和[交接记录](agent-handoff.md)。当前源码本机通过 37 条后端测试与 8 条前端测试；Windows Docker 新增接口与原网关烟测证据见交接记录，2026-10-13 的充电恢复接口和 2026-10-14 的同键并发入场事务仍未作为专项场景在 Docker 实测。烟测只覆盖其中部分用例，其余沿用表内对应证据。Nacos 控制台截图和课程要求的本人截图尚未完成。报告图号由姜苏豪排版时填写。

| UC | 需求结果 | 权威服务/接口 | 主要表 | 可编辑模型源 | 当前验证 |
|---|---|---|---|---|---|
| 01 | 四类车位可查、新增、停用/恢复 | space：GET/POST /api/v1/spaces，PATCH /api/v1/spaces/{id}/service-status | parking_space、charger | 01、02、12、15、16 | MySQL 网关查询、新增、停用、恢复通过；当前 Docker 栈核对 63 个种子位及 12 个充电桩，H2 测试核对四类容量和充电位配桩 |
| 02 | 唯一停车记录并占位 | access：POST /api/v1/access/entries；space：occupy | parking_session、parking_space | 03、05、06、11、15、16 | MySQL 网关入场成功；重复车牌有自动测试；真实 MySQL 8 路同车位占用仅 1 路成功；H2 同键并发入场在 2 连接池下通过，另有 12 辆不同车位占用/释放测试；网关演示新增 40 条 CLOSED、11 条 PARKED，真实 MySQL 同键场景待专项复测 |
| 03 | 分时阶梯计费、每日封顶 | billing：POST /internal/v1/billing/bills；access：冻结出场意图 | bill、payment、exit_intent、access_request_key | 03、07、08、11、15、16、17 | 5 条定价边界测试；MySQL 2 小时账单 1000 分；billing 断线后保留意图、重试仅一账单 |
| 04 | 模拟支付、发票申请 | billing：payments、invoice-requests | bill、payment、invoice_request | 03、07、08、14、16、17 | MySQL 8 路并发支付仅一笔成功；支付后释放失败保持待释放、恢复不二次收费；发票申请通过 |
| 05 | 指定车位预约与预付；未来时间窗查询 | pass：reservations、pay、cancel、consume、GET reservations/{id}、GET reservation-availability；space：内部只读车位列表 | reservation、reservation_prepayment | 04、09、10、11、15、16、19 | MySQL 8 路同窗预约仅一路成功；预约优惠、预付抵扣、差额退款和重试通过；到期扫描与 USED 不回退有自动测试；新增时间窗 H2 覆盖相邻/取消/超时待预付，Docker 网关脚本验证 14 位清单、重叠 409、相邻成功、取消后空档恢复 |
| 06 | 月卡管理与自动扣费 | pass：monthly-passes、deductions、ledger | monthly_pass、monthly_ledger | 02、08、15、16 | MySQL 月卡支付响应超时后重试，800 分 DEBIT 恰一行、余额 19200 分且付款恰一笔 |
| 07 | 在场车位反向查询 | access：GET /api/v1/access/locate | parking_session | 02、03、15、16 | MySQL 入场后可查；关闭后不可查有自动测试 |
| 08 | 丢卡/超长停车特殊费 | access：exit-requests、GET exceptions；billing：createBill | parking_session、exit_intent、exit_exception、bill | 03、07、08、15、16 | 定价测试覆盖丢卡与超长；MySQL 49 小时丢卡出场产生两条独立异常快照，金额、费率版本与账单相符；同键重试仍为同一账单 |
| 09 | 充电会话与计费 | space：charging-sessions、charging-sessions/active、settlement、charging-fees | charger、charging_session、charging_settlement | 02、08、11、14、15、16 | 5 条 space 测试含冻结/新增竞争、活动会话恢复查询；既有 MySQL 证据含活动充电拒绝出账、冻结后拒绝充电、2 kWh=300 分并入账单；新增恢复接口未在真实网关实测 |
| 10 | 车流量与高峰报表 | analytics：traffic、traffic-preview、traffic-reports/{id} | traffic_report、traffic_bucket；Redis 仅作已保存报表缓存 | 11、12、13、15、16 | 统计/快照/只读不落库/31 天边界测试；本机 Redis 命中及故障回源验证；浏览器某次刷新展示 52 条模拟入场及对应柱图，后续网关脚本预览达到 59 条；来源为正式入场 API |

可选扩展独立编号，不替代 UC-01～UC-10：

| 扩展 | 需求结果 | 权威服务/接口 | 主要表 | 可编辑模型源 | 当前验证 |
|---|---|---|---|---|---|
| EX-01 | 图片给出可编辑车牌候选，人工确认再入场 | access：POST /api/v1/access/plate-recognitions | 无新表；原入场仍写 parking_session | 13、15、18 | 本地假模型测试覆盖候选/空结果/503/非法与超限文件；真实 DeepSeek 通过 Docker 网关从既有演示页面截图识别出苏A92802。真实车辆照片的批量准确率尚未测量 |
| EX-02 | 普通停车费只读预估 | billing：POST /api/v1/billing/parking-estimates | 不写 bill/payment | 15、18 | H2 断言 1000 分且两表记录数不变；Docker 网关同输入返回 1000 分和费率版本；浏览器显示分日明细 |
| EX-03 | 按航站楼、类型推荐；指定车位入场仍安全占位 | space：GET /api/v1/spaces/recommendations；access：POST /api/v1/access/entries(preferredSpaceId) | space 自有 zone_walk_time、parking_space；access 自有 parking_session | 13、15、16、18 | H2 断言筛选与占用排除、推荐竞争不改派；Docker 网关推荐 A-003、精确入场、同键重试同会话、第二辆指定已占位返回 409，原车完成出场；浏览器推荐卡及预填入口可见 |
| EX-04 | 已保存车流报表的 AI 辅助解读，原统计不变 | analytics：POST /api/v1/analytics/traffic-reports/{id}/ai-insight | 无新表；只读 traffic_report、traffic_bucket；Redis 可选读缓存 | 13、15、18 | 假提供方测试验证聚合数据、合法与异常响应；真实 Docker 网关返回 AI_ADVISORY 和两条建议；浏览器在 5 条入场报表上显示建议。未评估建议准确率 |
| EX-05 | 可选机场出行建议，将真实车位候选、试算快照与 AI 文字并列呈现 | access：POST /api/v1/access/travel-advice；space、billing：对应 /internal/v1 只读接口 | 无新表；space 与 billing 各自读取自有数据 | 13、15、18 | access 假提供方测试覆盖成功/非法输入/空车位/依赖与非法模型响应；Docker 网关实测 AI_ADVISORY、3 个候选、7600 分与费率版本，页面同一接口显示 3 个候选和 1000 分（另一次两小时输入）。未验证建议长期正确性或真实航班信息 |

图号对应 models/ 下文件名前缀。测试 XML 位于各模块 target/surefire-reports（构建产物，不提交）。运行截图必须由负责人在本人机器上实际打开系统采集。发现图、字段或数字不一致时，先核对权威服务与业务决策，再同步修订本表。
