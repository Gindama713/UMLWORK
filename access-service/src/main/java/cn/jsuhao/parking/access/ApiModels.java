package cn.jsuhao.parking.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class ApiModels {
    private ApiModels() {}

    record Envelope<T>(String code, String message, T data, String requestId) {
        static <T> Envelope<T> ok(T data) {
            return new Envelope<>("OK", "ok", data, UUID.randomUUID().toString());
        }
    }

    record EntryRequest(String plateNumber, String spaceType, String reservationId, OffsetDateTime entryTime) {}
    record ExitRequest(OffsetDateTime exitTime, String exceptionType, String operator) {}
    record CompleteRequest(String simulatedResult) {}
    record SessionRef(String parkingSessionId) {}
    record ReservationUse(String plateNumber, OffsetDateTime entryTime, String parkingSessionId) {}
    record Refund(String parkingSessionId, long amountCents) {}
    record SpaceView(String spaceId, String type, String floor, String zone, String number,
                     String status, String parkingSessionId) {}
    record ReservationView(String reservationId, String spaceId, String status, long prepaidCents) {}
    record BenefitView(String benefitType, long prepaidCents, String monthlyPassId) {}
    record ChargingFees(String parkingSessionId, long chargingCents) {}
    record BillRequest(String parkingSessionId, OffsetDateTime entryTime, OffsetDateTime exitTime,
                       String benefitType, long prepaidCents, long chargingCents, String exceptionType) {}
    record BillView(String billId, String parkingSessionId, String status, long amountDueCents,
                    long prepaidRefundCents) {}
    record PaymentRequest(String simulatedResult, String paymentSource) {}
    record PaymentView(String paymentId, String billId, String billStatus, String paymentStatus,
                       String paymentSource, long amountCents) {}
    record DeductionRequest(String parkingSessionId, long amountCents) {}
    record SessionView(String parkingSessionId, String plateNumber, String spaceId, String floor,
                       String zone, String spaceNumber, OffsetDateTime entryTime, String status,
                       OffsetDateTime exitTime, String billId, String exceptionType, String operator) {}
    record ExitView(String parkingSessionId, String billId, String status, long amountDueCents) {}
    record CompleteView(String parkingSessionId, String billId, String paymentId, String status,
                        boolean spaceReleased) {}
    record TrafficSession(String parkingSessionId, OffsetDateTime entryTime,
                          OffsetDateTime exitTime, String status) {}

    static final class ApiFailure extends RuntimeException {
        private final String code;
        private final HttpStatus status;

        ApiFailure(String code, HttpStatus status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        String code() { return code; }
        HttpStatus status() { return status; }
    }
}
