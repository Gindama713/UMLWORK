package cn.jsuhao.parking.access;

import static cn.jsuhao.parking.access.ApiModels.*;

import cn.jsuhao.parking.access.AccessRepository.SessionRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class AccessService {
    private static final Logger log = LoggerFactory.getLogger(AccessService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REMOTE_BUSINESS_ERRORS = Set.of(
            "INVALID_ARGUMENT", "NOT_FOUND", "SPACE_UNAVAILABLE", "ACTIVE_SESSION_EXISTS",
            "RESERVATION_CONFLICT", "STATE_CONFLICT", "PASS_NOT_ELIGIBLE",
            "PASS_BALANCE_INSUFFICIENT", "BILL_ALREADY_PAID", "IDEMPOTENCY_CONFLICT");
    private static final Set<String> SPACE_TYPES =
            Set.of("NORMAL", "ACCESSIBLE", "CHARGING", "RESERVATION");
    private final AccessRepository repo;
    private final SpaceClient spaces;
    private final BillingClient billing;
    private final PassClient passes;

    AccessService(AccessRepository repo, SpaceClient spaces, BillingClient billing, PassClient passes) {
        this.repo = repo;
        this.spaces = spaces;
        this.billing = billing;
        this.passes = passes;
    }

    SessionView enter(EntryRequest input, String key) {
        requireKey(key);
        if (input == null) throw invalid("缺少入场内容");
        String plate = plate(input.plateNumber());
        String type = input.spaceType() == null ? "NORMAL" : input.spaceType().toUpperCase(Locale.ROOT);
        if (!SPACE_TYPES.contains(type)) throw invalid("未知车位类型");
        String reservationId = blankToNull(input.reservationId());
        if (type.equals("RESERVATION") != (reservationId != null))
            throw invalid("预约位必须提供预约 ID，其他车位不能提供预约 ID");
        OffsetDateTime entry = input.entryTime() == null
                ? OffsetDateTime.now(ZoneOffset.UTC) : input.entryTime();
        String payload = plate + "|" + type + "|" + reservationId + "|" +
                (input.entryTime() == null ? "AUTO" : input.entryTime().toInstant());
        repo.registerRequest(key, "ENTRY", payload);
        SessionRow session = repo.byRequestKey(key).orElse(null);
        if (session == null) {
            String id = UUID.randomUUID().toString();
            try {
                repo.insert(id, plate, type, reservationId, entry, key, payload);
                session = get(id);
            } catch (DuplicateKeyException duplicate) {
                session = repo.byRequestKey(key).orElse(null);
                if (session == null) throw failure("ACTIVE_SESSION_EXISTS", HttpStatus.CONFLICT,
                        "该车牌已有在场或处理中的停车记录");
            }
        }
        if (!session.requestPayload().equals(payload))
            throw failure("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT, "请求键已用于其他入场内容");
        if (session.status().equals("PARKED")) return session.view();
        if (!session.status().equals("ENTERING"))
            throw failure("STATE_CONFLICT", HttpStatus.CONFLICT, "本次入场已结束，失败后请使用新请求键");

        String chosenId = session.spaceId();
        try {
            if (reservationId != null) {
                ReservationView reservation = required(
                        passes.consume(reservationId, key + ":consume",
                                new ReservationUse(plate, session.entry(), session.id())));
                chosenId = reservation.spaceId();
                if (chosenId == null) throw invalid("预约未绑定车位");
                String reservationSpaceId = chosenId;
                SpaceView choice = session.spaceId() == null
                        ? available(type).stream().filter(s -> s.spaceId().equals(reservationSpaceId))
                            .findFirst().orElseThrow(() -> failure("SPACE_UNAVAILABLE",
                                    HttpStatus.CONFLICT, "预约车位当前不可占用"))
                        : new SpaceView(chosenId, type, session.floor(), session.zone(),
                                session.number(), "OCCUPIED", session.id());
                repo.chooseSpace(session.id(), choice);
                SpaceView occupied = required(spaces.occupy(chosenId, key + ":occupy",
                        new SessionRef(session.id())));
                repo.chooseSpace(session.id(), occupied);
            } else {
                List<SpaceView> candidates = session.spaceId() == null
                        ? available(type)
                        : List.of(new SpaceView(session.spaceId(), type, session.floor(),
                                session.zone(), session.number(), "OCCUPIED", session.id()));
                boolean occupied = false;
                for (SpaceView candidate : candidates) {
                    chosenId = candidate.spaceId();
                    repo.chooseSpace(session.id(), candidate);
                    try {
                        SpaceView result = required(spaces.occupy(chosenId, key + ":occupy:" + chosenId,
                                new SessionRef(session.id())));
                        repo.chooseSpace(session.id(), result);
                        occupied = true;
                        break;
                    } catch (FeignException.Conflict race) {
                        if (session.spaceId() != null) throw race;
                    }
                }
                if (!occupied) throw failure("SPACE_UNAVAILABLE", HttpStatus.CONFLICT,
                        "该类型暂无可用车位");
            }
            repo.parked(session.id());
            return get(session.id()).view();
        } catch (RuntimeException error) {
            // A timed-out occupy may have succeeded remotely. Release by the same session ID.
            boolean released = chosenId == null || releaseAfterFailedEntry(chosenId, session.id(), key);
            boolean reservationRestored = reservationId == null ||
                    restoreReservation(reservationId, session.id(), key);
            if (released && reservationRestored) repo.entryFailed(session.id());
            throw dependencyOrOriginal(error);
        }
    }

    SessionView locate(String rawPlate) {
        SessionRow session = repo.activePlate(plate(rawPlate))
                .filter(s -> Set.of("PARKED", "EXIT_PENDING_PAYMENT", "PAID_PENDING_RELEASE")
                        .contains(s.status()))
                .orElseThrow(() -> failure("NOT_FOUND", HttpStatus.NOT_FOUND, "未找到在场车辆"));
        var intent = repo.exitIntent(session.id()).orElse(null);
        return intent == null ? session.view() : new SessionView(session.id(), session.plate(),
                session.spaceId(), session.floor(), session.zone(), session.number(), session.entry(),
                session.status(), intent.exit(), session.billId(), intent.exceptionType(), intent.operator());
    }

    ExitView requestExit(String id, ExitRequest input, String key) {
        requireKey(key);
        SessionRow session = get(id);
        if (!session.status().equals("PARKED") && session.billId() == null)
            throw failure("STATE_CONFLICT", HttpStatus.CONFLICT, "停车记录不能出账");
        if (input == null || input.exitTime() == null) throw invalid("出场时间必填");
        OffsetDateTime exit = input.exitTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!exit.toInstant().isAfter(session.entry().toInstant()))
            throw invalid("出场时间必须晚于入场时间");
        String exception = input.exceptionType() == null ? "NONE" : input.exceptionType();
        if (!Set.of("NONE", "LOST_CARD").contains(exception)) throw invalid("未知异常类型");
        if (exception.equals("LOST_CARD") && blankToNull(input.operator()) == null)
            throw invalid("丢卡处理人必填");
        String operator = blankToNull(input.operator());
        if (operator != null && operator.length() > 60) throw invalid("处理人最长 60 字符");
        repo.registerRequest(key, "EXIT_REQUEST", id + "|" + exit.toInstant() + "|" + exception + "|" + operator);
        if (session.billId() != null) {
            checkExitInput(exit, exception, operator, session.exit(), session.exceptionType(), session.operator());
            return exitView(session);
        }
        try {
            var intent = repo.exitIntent(id).orElse(null);
            ChargingFees charging;
            if (intent == null) {
                BenefitView benefit = required(passes.eligibility(session.plate(),
                        session.entry().toString(), exit.toString(), session.spaceType(), session.id()));
                if (benefit.benefitType() == null || !Set.of("NONE", "RESERVATION", "MONTHLY").contains(benefit.benefitType()))
                    throw failure("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "权益服务返回未知类型");
                charging = required(spaces.settle(session.spaceId(), id + ":settlement", new SessionRef(id)));
                intent = repo.reserveExit(id, new AccessRepository.ExitIntent(exit, exception, operator,
                        benefit.benefitType(), benefit.prepaidCents(), benefit.monthlyPassId()));
            } else charging = required(spaces.settle(session.spaceId(), id + ":settlement", new SessionRef(id)));
            checkExitInput(exit, exception, operator, intent.exit(), intent.exceptionType(), intent.operator());
            long chargingCents = charging.chargingCents();
            BillView bill = required(billing.createBill(id,
                    new BillRequest(id, session.entry(), intent.exit(), intent.benefitType(),
                            intent.prepaidCents(), chargingCents, intent.exceptionType())));
            repo.exitRequested(id, intent.exit(), bill.billId(), intent.exceptionType(), intent.operator(),
                    intent.monthlyPassId());
            return exitView(get(id));
        } catch (RuntimeException error) {
            throw dependencyOrOriginal(error);
        }
    }

    CompleteView completeExit(String id, CompleteRequest input, String key) {
        requireKey(key);
        if (input == null || input.simulatedResult() == null || !Set.of("SUCCESS", "FAILURE").contains(input.simulatedResult()))
            throw invalid("simulatedResult 必须为 SUCCESS 或 FAILURE");
        repo.registerRequest(key, "COMPLETE_EXIT", id + "|" + input.simulatedResult());
        SessionRow session = get(id);
        if (session.billId() == null || session.status().equals("PARKED"))
            throw failure("STATE_CONFLICT", HttpStatus.CONFLICT, "请先申请出场并生成账单");
        if (session.status().equals("CLOSED"))
            return new CompleteView(id, session.billId(), null, "CLOSED", true);
        String paymentId = null;
        if (session.status().equals("EXIT_PENDING_PAYMENT")) {
            try {
                BillView bill = required(billing.bill(session.billId()));
                if (!"PAID".equals(bill.status())) {
                    String source = "SIMULATED";
                    if (input.simulatedResult().equals("SUCCESS") && session.monthlyPassId() != null
                            && bill.amountDueCents() > 0) {
                        try {
                            required(passes.deduct(session.monthlyPassId(), id + ":monthly-deduction",
                                    new DeductionRequest(id, bill.amountDueCents())));
                            source = "MONTHLY_BALANCE";
                        } catch (FeignException.Conflict insufficient) {
                            if (!insufficient.contentUTF8().contains("PASS_BALANCE_INSUFFICIENT"))
                                throw insufficient;
                        }
                    }
                    PaymentView payment = required(billing.pay(session.billId(), id + ":payment:" +
                            input.simulatedResult() + ":" + source,
                            new PaymentRequest(input.simulatedResult(), source)));
                    paymentId = payment.paymentId();
                    if (!"PAID".equals(payment.billStatus()))
                        return new CompleteView(id, session.billId(), paymentId,
                                "EXIT_PENDING_PAYMENT", false);
                }
                repo.paid(id);
            } catch (RuntimeException error) {
                throw dependencyOrOriginal(error);
            }
        }
        try {
            if (session.reservationId() != null) {
                BillView bill = required(billing.bill(session.billId()));
                if (bill.prepaidRefundCents() > 0)
                    required(passes.refundRemainder(session.reservationId(), id + ":refund",
                            new Refund(id, bill.prepaidRefundCents())));
            }
            required(spaces.release(session.spaceId(), id + ":release", new SessionRef(id)));
            repo.closed(id);
            return new CompleteView(id, session.billId(), paymentId, "CLOSED", true);
        } catch (RuntimeException error) {
            // Keep PAID_PENDING_RELEASE. Retrying this endpoint skips payment.
            log.warn("Paid session {} could not finish refund or space release; retry is safe", id, error);
            return new CompleteView(id, session.billId(), paymentId, "PAID_PENDING_RELEASE", false);
        }
    }

    List<TrafficSession> traffic(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.toInstant().isAfter(from.toInstant()))
            throw invalid("统计范围无效");
        return repo.traffic(from, to);
    }

    private static void checkExitInput(OffsetDateTime exit, String exception, String operator,
            OffsetDateTime savedExit, String savedException, String savedOperator) {
        if (!exit.toInstant().equals(savedExit.toInstant()) || !exception.equals(savedException)
                || !java.util.Objects.equals(operator, savedOperator))
            throw failure("STATE_CONFLICT", HttpStatus.CONFLICT, "本次出场内容已冻结，请按原时间和异常信息重试");
    }

    private ExitView exitView(SessionRow session) {
        try {
            BillView bill = required(billing.bill(session.billId()));
            return new ExitView(session.id(), bill.billId(), session.status(), bill.amountDueCents());
        } catch (RuntimeException error) {
            throw dependencyOrOriginal(error);
        }
    }

    private List<SpaceView> available(String type) {
        List<SpaceView> list = required(spaces.available(type));
        return list;
    }

    private boolean releaseAfterFailedEntry(String spaceId, String id, String key) {
        try {
            required(spaces.release(spaceId, key + ":compensate", new SessionRef(id)));
            return true;
        } catch (FeignException.Conflict | FeignException.NotFound notOurs) {
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean restoreReservation(String reservationId, String id, String key) {
        try {
            required(passes.releaseConsumption(reservationId, key + ":restore",
                    new SessionRef(id)));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private SessionRow get(String id) {
        return repo.byId(id).orElseThrow(() -> failure("NOT_FOUND", HttpStatus.NOT_FOUND,
                "停车记录不存在"));
    }

    private static <T> T required(Envelope<T> response) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw failure("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "下游服务响应异常");
        return response.data();
    }

    private static RuntimeException dependencyOrOriginal(RuntimeException error) {
        if (error instanceof ApiFailure) return error;
        if (error instanceof FeignException remote) {
            if (remote.status() == 400 || remote.status() == 404 || remote.status() == 409) {
                try {
                    JsonNode body = JSON.readTree(remote.contentUTF8());
                    if (body != null && REMOTE_BUSINESS_ERRORS.contains(body.path("code").asText()))
                        return failure(body.path("code").asText(), HttpStatus.valueOf(remote.status()),
                                body.path("message").asText("下游业务请求被拒绝"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // Non-contract responses are treated as unavailable dependencies.
                }
            }
            return failure("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "依赖服务不可用或响应异常");
        }
        return error;
    }

    private static String plate(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[\\p{IsHan}A-Z0-9]{5,12}"))
            throw invalid("车牌应为 5 至 12 位汉字、字母或数字");
        return value;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw invalid("Idempotency-Key 必填且最长 100 字符");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiFailure invalid(String message) {
        return failure("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST, message);
    }

    private static ApiFailure failure(String code, HttpStatus status, String message) {
        return new ApiFailure(code, status, message);
    }
}
