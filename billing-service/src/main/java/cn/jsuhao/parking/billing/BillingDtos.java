package cn.jsuhao.parking.billing;

import java.time.OffsetDateTime;
import java.util.List;

record BillRequest(String parkingSessionId, OffsetDateTime entryTime, OffsetDateTime exitTime,
        String benefitType, Long prepaidCents, Long chargingCents, String exceptionType) {}

record FeeItem(String name, long amountCents, String detail) {}

record BillView(String billId, String parkingSessionId, String status, long parkingBaseCents,
        long discountCents, long prepaidCents, long prepaidRefundCents, long parkingDueCents,
        long chargingCents, long exceptionCents, long amountDueCents, String rateVersion,
        List<FeeItem> feeItems) {}

record PaymentRequest(String simulatedResult, String paymentSource) {}

record PaymentView(String paymentId, String billId, String billStatus, String paymentStatus,
        String paymentSource, long amountCents) {}

record InvoiceRequest(String invoiceTitle, String taxNumber) {}

record InvoiceView(String invoiceRequestId, String billId, String status) {}
