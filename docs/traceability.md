# 需求—模型—代码—测试映射（【AI-辅助】）

更新于 2026-09-28。下表“已验证”含自动测试和本机真实 MySQL 顺序联调；真实 Nacos、并发故障注入、本人截图另列，不与下表混淆。报告图号由姜苏豪排版时填写。具体证据见 [联调记录](code-audit.md)。

| UC | 需求结果 | 权威服务/接口 | 主要表 | 可编辑模型源 | 当前验证 |
|---|---|---|---|---|---|
| 01 | 四类车位可查、新增、停用/恢复 | space：GET/POST /api/v1/spaces，PATCH /api/v1/spaces/{id}/service-status | parking_space | 01、02、12、15、16 | MySQL 网关查询、新增、停用、恢复通过 |
| 02 | 唯一停车记录并占位 | access：POST /api/v1/access/entries；space：occupy | parking_session、parking_space | 03、05、06、11、15、16 | MySQL 网关入场成功；重复车牌有自动测试 |
| 03 | 分时阶梯计费、每日封顶 | billing：POST /internal/v1/billing/bills；access：冻结出场意图 | bill、payment、exit_intent、access_request_key | 07、08、15、16 | 5 条定价边界测试；MySQL 2 小时账单 1000 分；请求键冲突/出账失败恢复有自动测试 |
| 04 | 模拟支付、发票申请 | billing：payments、invoice-requests | bill、payment、invoice_request | 07、08、14、16 | 支付失败重试有自动测试；MySQL 网关成功支付及发票申请通过 |
| 05 | 指定车位预约与预付 | pass：reservations、pay、cancel、consume | reservation、reservation_prepayment | 04、09、10、11、16 | 冲突、并发自动测试；MySQL 预约优惠、预付抵扣、差额退款和重试通过 |
| 06 | 月卡管理与自动扣费 | pass：monthly-passes、deductions、ledger | monthly_pass、monthly_ledger | 02、08、15、16 | 幂等余额扣费测试；MySQL 月卡账单、余额和流水通过 |
| 07 | 在场车位反向查询 | access：GET /api/v1/access/locate | parking_session | 02、03、15、16 | MySQL 入场后可查；关闭后不可查有自动测试 |
| 08 | 丢卡/超长停车特殊费 | access：exit-requests；billing：createBill | parking_session、bill | 03、07、08、15、16 | 定价测试覆盖丢卡与超长；MySQL 49 小时且丢卡完整出场通过 |
| 09 | 充电会话与计费 | space：charging-sessions、settlement、charging-fees | charger、charging_session、charging_settlement | 02、08、11、14、15、16 | 4 条 space 测试含冻结/新增竞争；MySQL 活动充电拒绝出账、冻结后拒绝充电；2 kWh=300 分并入账单 |
| 10 | 车流量与高峰报表 | analytics：traffic、traffic-preview | traffic_report、traffic_bucket | 11、12、13、16 | 统计/快照/只读不落库/31 天边界测试；浏览器真实柱图与报表通过 |

图号对应 models/ 下文件名前缀。测试 XML 位于各模块 target/surefire-reports（构建产物，不提交）。运行截图必须由负责人在本人机器上实际打开系统采集。发现图、字段或数字不一致时，先核对权威服务与业务决策，再同步修订本表。
