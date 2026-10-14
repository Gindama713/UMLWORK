# 需求—模型—代码—测试映射（【AI-辅助】）

更新于 2026-10-14。下表“已验证”含自动测试、本机真实 MySQL 顺序联调、Nacos 服务发现、Redis 报表缓存及有记录的并发/断线注入；逐项证据见 [联调记录](code-audit.md)、[故障注入记录](mysql-fault-tests.md)和[交接记录](agent-handoff.md)。当前源码本机通过 25 条后端测试与 4 条前端测试；既有 Windows Docker 网关烟测证据见交接记录，2026-10-13 的充电恢复接口和 2026-10-14 的同键并发入场事务尚未在 Docker 实测。烟测只覆盖其中部分用例，其余沿用表内对应证据。Nacos 控制台截图和课程要求的本人截图尚未完成。报告图号由姜苏豪排版时填写。

| UC | 需求结果 | 权威服务/接口 | 主要表 | 可编辑模型源 | 当前验证 |
|---|---|---|---|---|---|
| 01 | 四类车位可查、新增、停用/恢复 | space：GET/POST /api/v1/spaces，PATCH /api/v1/spaces/{id}/service-status | parking_space、charger | 01、02、12、15、16 | MySQL 网关查询、新增、停用、恢复通过；当前 Docker 栈核对 31 个种子位及 6 个充电桩 |
| 02 | 唯一停车记录并占位 | access：POST /api/v1/access/entries；space：occupy | parking_session、parking_space | 03、05、06、11、15、16 | MySQL 网关入场成功；重复车牌有自动测试；真实 MySQL 8 路同车位占用仅 1 路成功；H2 同键并发入场在 2 连接池下通过，含重复请求键内容冲突和失败补偿，真实 MySQL 同键场景待复测 |
| 03 | 分时阶梯计费、每日封顶 | billing：POST /internal/v1/billing/bills；access：冻结出场意图 | bill、payment、exit_intent、access_request_key | 03、07、08、11、15、16、17 | 5 条定价边界测试；MySQL 2 小时账单 1000 分；billing 断线后保留意图、重试仅一账单 |
| 04 | 模拟支付、发票申请 | billing：payments、invoice-requests | bill、payment、invoice_request | 03、07、08、14、16、17 | MySQL 8 路并发支付仅一笔成功；支付后释放失败保持待释放、恢复不二次收费；发票申请通过 |
| 05 | 指定车位预约与预付 | pass：reservations、pay、cancel、consume、GET reservations/{id}；space：内部只读车位列表 | reservation、reservation_prepayment | 04、09、10、11、15、16 | MySQL 8 路同窗预约仅一路成功；预约优惠、预付抵扣、差额退款和重试通过；到期扫描与 USED 不回退有自动测试；内部 GET 与创建/取消预约在 Docker 栈验证 |
| 06 | 月卡管理与自动扣费 | pass：monthly-passes、deductions、ledger | monthly_pass、monthly_ledger | 02、08、15、16 | MySQL 月卡支付响应超时后重试，800 分 DEBIT 恰一行、余额 19200 分且付款恰一笔 |
| 07 | 在场车位反向查询 | access：GET /api/v1/access/locate | parking_session | 02、03、15、16 | MySQL 入场后可查；关闭后不可查有自动测试 |
| 08 | 丢卡/超长停车特殊费 | access：exit-requests、GET exceptions；billing：createBill | parking_session、exit_intent、exit_exception、bill | 03、07、08、15、16 | 定价测试覆盖丢卡与超长；MySQL 49 小时丢卡出场产生两条独立异常快照，金额、费率版本与账单相符；同键重试仍为同一账单 |
| 09 | 充电会话与计费 | space：charging-sessions、charging-sessions/active、settlement、charging-fees | charger、charging_session、charging_settlement | 02、08、11、14、15、16 | 5 条 space 测试含冻结/新增竞争、活动会话恢复查询；既有 MySQL 证据含活动充电拒绝出账、冻结后拒绝充电、2 kWh=300 分并入账单；新增恢复接口未在真实网关实测 |
| 10 | 车流量与高峰报表 | analytics：traffic、traffic-preview、traffic-reports/{id} | traffic_report、traffic_bucket；Redis 仅作已保存报表缓存 | 11、12、13、15、16 | 统计/快照/只读不落库/31 天边界测试；本机 Redis 命中及故障回源验证；浏览器真实柱图与报表通过 |

图号对应 models/ 下文件名前缀。测试 XML 位于各模块 target/surefire-reports（构建产物，不提交）。运行截图必须由负责人在本人机器上实际打开系统采集。发现图、字段或数字不一致时，先核对权威服务与业务决策，再同步修订本表。
