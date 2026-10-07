# 真实 MySQL 并发与故障注入记录（【AI-辅助】）

日期：2026-09-30。本页只记录本机实际执行的测试。环境为 MySQL 8.4 五个独立 schema、Nacos 3.1.1、六个 Java 进程及 18080 网关。运行前核对 `docs/api-contract.md`；使用新的演示车牌和请求键，保留历史流水。MySQL 密码只经 `BILLING_DB_PASSWORD` / `MYSQL_PWD` 环境变量提供。

## 并发检查：可重复脚本

PowerShell 7 中运行 `./scripts/verify-mysql-concurrency.ps1 -MySqlExe <mysql.exe 的绝对路径>`；先在当前进程设置 `BILLING_DB_PASSWORD`。脚本不读取其他服务数据库，只通过其接口建立演示数据；唯一支付流水用 billing 自有 schema 查询。实际输出保存在忽略的 `target/local-runtime/mysql-concurrency.log`：

| 故障窗口 | 断言及意义 | 2026-09-30 实测 |
|---|---|---|
| 8 个不同停车记录并发占同一新车位 | 恰有 1 个 200，7 个 `409 SPACE_UNAVAILABLE`；能发现条件更新失效导致的双重占位 | PASS，获胜记录随后释放 |
| 8 个不同车牌并发预约同一新车位同一 `[start,end)` | 恰有 1 个 200，7 个 `409 RESERVATION_CONFLICT`；能发现“先查后插”未串行化的冲突 | PASS，获胜预约随后取消 |
| 8 个不同请求键并发支付同一未付账单 | 全部返回同一 `paymentId`，billing 的 `payment` 表恰有 1 行 SUCCESS；能发现重复收费和支付查询旧快照错误 | PASS，停车记录随后关闭 |

首次实测第三项时，MySQL 只写入 1 行成功支付，但 7 个请求返回 HTTP 500：`BillingService.pay` 先普通读建立 REPEATABLE READ 快照，等到账单行锁后看见 `PAID`，再用普通读却看不见新支付行，抛 `NoSuchElementException`。在已付支付查询增加 `FOR UPDATE` 当前读后，**同一个脚本与原断言**全部通过。H2 测试没有覆盖 MySQL 的这个事务快照行为。修复文件：`billing-service/src/main/java/cn/jsuhao/parking/billing/BillingService.java`。

## 断线恢复：本机逐项注入

这些是实际服务停止和 MySQL 行锁注入，不是 H2 mock。每次停止进程前用 `netstat -ano` 核对目标端口 PID，再核对它为本轮启动的 Java 进程；重启时保留原 `NACOS_ENABLED=true`、空 `*_SERVICE_URL` 和环境变量数据库密码。每项均使用相同出场内容及请求键重试。最终数据库复核结果在忽略的 `target/local-runtime/mysql-fault-final-check.log`；该日志分别查询各服务自有库，没有跨库 JOIN。

| 窗口 | 注入与实际结果 | 捕获条件 |
|---|---|---|
| 冻结费用后 billing 下线 | 新建普通在场记录 `a5568025-f8a7-4667-937a-877167cc2a25`，停止 18083 的 billing，`exit-requests` 返回 HTTP 503 `DEPENDENCY_UNAVAILABLE`；`locate` 为 `EXIT_PENDING_PAYMENT`、billId 空，access 库恰有 1 行 `exit_intent`。重启 billing，原键原内容重试两次均返回同一账单 `d6060fb4-6282-4796-b13e-e2a9e8e76686`，最终账单行数 1，支付后 CLOSED。 | 如果冻结意图丢失或状态回退，第一阶段状态/意图断言失败；如果恢复时重算或重复出账，账单 ID/行数断言失败。 |
| 已支付但车位释放失败 | 新建账单 `fefb5001-05fd-470b-b8f6-39fee98b8d4d`，停止 18081 的 space 后调用 `complete-exit(SUCCESS)`，返回 HTTP 200、`PAID_PENDING_RELEASE`、`spaceReleased=false`；billing 成功支付行数为 1。重启 space，原键重试后 `CLOSED` 且仍只有 1 行成功支付。 | 如果过早关闭，第一次状态断言失败；如果重试二次收费，支付行数断言失败。 |
| 月卡扣款后 billing 支付响应超时 | 月卡 `1984a1b0-ae66-40a5-94ae-ef69e01ce7bb`，停车记录 `2098816e-0048-4950-8760-a8ac7c16ecf1`，账单 `3bab8c16-c7b5-49e0-af6b-eb69c99269b8`，应付 800 分。另起 MySQL 事务对这张 bill 执行 `SELECT ... FOR UPDATE`，用 `FOR UPDATE NOWAIT` 返回 3572 证明锁已持有；随后发 `complete-exit(SUCCESS)`。pass 已写入 800 分 DEBIT，access 收到 HTTP 503 并保持 `EXIT_PENDING_PAYMENT`。锁结束后 billing 实际完成一笔 `MONTHLY_BALANCE` 支付；原键重试返回 `CLOSED`。最终月卡 DEBIT 恰有 1 行、余额由 20000 分变为 19200 分，成功支付恰有 1 行。 | 如果超时重试再次扣月卡，DEBIT 行数或余额断言失败；如果重复收费，成功支付行数断言失败；如果未恢复关单，最终状态断言失败。 |

月卡锁注入的 SQL 形状为 `START TRANSACTION; SELECT bill_id FROM bill WHERE bill_id='<本次账单 ID>' FOR UPDATE; SELECT SLEEP(60); ROLLBACK;`，在**独立 MySQL 客户端进程**执行。发出出场完成请求前，另一个连接以 `FOR UPDATE NOWAIT` 验证锁确实在位；测试过程的异步 HTTP 原始 503 输出在 `target/local-runtime/monthly-complete.log`。锁只是临时阻塞，不改账单；60 秒结束后可能是 billing 完成提交但 access 已超时，也可能是 billing 自身回滚，这两种情况下恢复流程都不得重复扣费。本次观察到前一种情况。

本轮没有声称覆盖跨机多实例、网络分区或真实支付网关。服务状态、契约和模型同步见 `docs/api-contract.md`、`models/03-parking-session-state.puml`、`models/08-exit-design-sequence.puml` 与 `models/11-cross-service-activity.puml`。课程要求的真实运行截图仍需姜苏豪本人采集。
