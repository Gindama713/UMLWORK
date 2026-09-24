# 服务接口契约草案 v0.1

> 【AI-辅助】**未冻结、尚未实现。**本文件是多 agent 开发前的讨论稿。姜苏豪确认 `docs/decisions.md` 后，接口负责人再填写精确路径、DTO 约束、错误码、幂等行为与示例，并升为 v1。当前服务骨架只实现第 1 节的状态接口。

## 1. 已实现的骨架状态接口

| 经网关访问的接口 | 提供服务 | 本地直连端口 |
|---|---|---|
| `GET /api/v1/spaces/status` | space-service | 18081 |
| `GET /api/v1/access/status` | access-service | 18082 |
| `GET /api/v1/billing/status` | billing-service | 18083 |
| `GET /api/v1/passes/status` | pass-service | 18084 |
| `GET /api/v1/analytics/status` | analytics-service | 18085 |

成功响应示例：

```json
{
  "code": "OK",
  "message": "service ready",
  "data": {"service": "space-service", "phase": "SCAFFOLD"},
  "requestId": "b67828f9-4b62-423e-9ec0-b1891574650a"
}
```

这些接口是连通性演示，不代表业务功能可用。网关默认端口 18080；`GET /actuator/health` 是各进程自己的健康检查，不经过业务路由。

## 2. 通用数据格式

- JSON UTF-8；外部接口前缀 `/api/v1`，内部调用前缀 `/internal/v1`。
- ID 为字符串；车牌统一大写规范化；金额为非负整数，单位“分”；电量为十进制定点数，单位 kWh。
- 时间为带偏移量的 ISO 8601 字符串，例如 `2026-10-01T08:30:00+08:00`。数据库存 UTC，计费自然日用 `Asia/Shanghai`。
- 响应统一为 `code`、`message`、`data`、`requestId`。写操作接收 `Idempotency-Key` 请求头；同一键加同一请求内容重试应返回同一业务结果，键相同而内容不同须报冲突。
- 跨服务 DTO 不携带 ORM 实体，不传其他服务的数据库表结构。失败响应不返回成功样式的 `data`。

## 3. 拟定接口和数据所有权

| 提供服务 | 候选路径与方法 | 请求关键字段 | 响应关键字段 | 主要错误 |
|---|---|---|---|---|
| space-service | `GET /internal/v1/spaces/available` | type、start、end | spaceId、floor、zone、number | INVALID_ARGUMENT |
| space-service | `POST /internal/v1/spaces/{spaceId}/occupy` | parkingSessionId、reservationId? | spaceId、state、version | SPACE_UNAVAILABLE |
| space-service | `POST /internal/v1/spaces/{spaceId}/release` | parkingSessionId | spaceId、state | NOT_FOUND、STATE_CONFLICT |
| access-service | `POST /api/v1/access/entries` | plateNumber、spaceType、reservationId? | parkingSessionId、space、entryTime | ACTIVE_SESSION_EXISTS、SPACE_UNAVAILABLE |
| access-service | `GET /api/v1/access/locate` | plateNumber | parkingSessionId、floor、zone、spaceNumber | NOT_FOUND |
| access-service | `POST /api/v1/access/{sessionId}/exit-requests` | exitTime、exceptionType? | billId、amountDueCents、status | STATE_CONFLICT、DEPENDENCY_UNAVAILABLE |
| pass-service | `POST /api/v1/passes/reservations` | plateNumber、start、end、spaceType | reservationId、status、prepayCents | RESERVATION_CONFLICT |
| pass-service | `POST /internal/v1/passes/eligibility` | plateNumber、entryTime、exitTime、spaceType | reservationBenefit、monthlyPassBenefit | PASS_NOT_ELIGIBLE |
| billing-service | `POST /internal/v1/billing/quotes` | entryTime、exitTime、benefits、chargingCents | feeItems、amountDueCents、rateVersion | INVALID_ARGUMENT |
| billing-service | `POST /internal/v1/billing/bills` | parkingSessionId、quote、exitTime | billId、status、amountDueCents | STATE_CONFLICT |
| billing-service | `POST /api/v1/billing/bills/{billId}/payments` | simulatedResult | paymentId、billStatus | BILL_ALREADY_PAID |
| billing-service | `POST /api/v1/billing/bills/{billId}/invoice-requests` | invoiceTitle、taxNumber? | invoiceRequestId、status | BILL_NOT_PAID |
| space-service | `POST /api/v1/spaces/charging-sessions` | chargerId、parkingSessionId、startTime | chargingSessionId、status | SPACE_UNAVAILABLE |
| space-service | `POST /api/v1/spaces/charging-sessions/{id}/finish` | endTime、energyKwh | chargingCents、status | STATE_CONFLICT |
| analytics-service | `GET /api/v1/analytics/traffic` | from、to、granularity | countByBucket、peakBucket | INVALID_ARGUMENT |

字段 `reservationBenefit`、`monthlyPassBenefit` 的金额和互斥规则、`simulatedResult` 的合法值，均等第 8 节业务决策后冻结。前端不得自行计算应付款；`quotes` 仅为试算，最终金额以 `bills` 快照为准。

## 4. HTTP 与错误语义

| HTTP | 典型业务码 | 说明 |
|---|---|---|
| 200/201 | OK | 查询、创建或幂等重放成功 |
| 400 | INVALID_ARGUMENT | 字段缺失、格式错误、时间区间不合法 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | SPACE_UNAVAILABLE / ACTIVE_SESSION_EXISTS / RESERVATION_CONFLICT / STATE_CONFLICT / BILL_ALREADY_PAID | 当前状态不允许操作 |
| 503 | DEPENDENCY_UNAVAILABLE | 依赖服务不可用；不得伪称业务成功 |

具体 400/409 场景和错误信息需由实现者补齐测试。所有 agent 变更字段或状态时，先提出契约变更，经接口负责人更新本文件后再改代码。
