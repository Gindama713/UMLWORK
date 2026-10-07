package cn.jsuhao.parking.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
final class ParkingPricing {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final Supplier<PricingProperties> currentRates;

    ParkingPricing(PricingProperties rates) {
        this.currentRates = () -> rates;
    }

    @Autowired
    ParkingPricing(Environment environment) {
        this.currentRates = () -> Binder.get(environment).bind("parking.pricing", PricingProperties.class)
                .orElseThrow(() -> new IllegalStateException("Missing parking.pricing configuration"));
    }

    PriceBreakdown calculate(BillRequest request) {
        PricingProperties rates = currentRates.get();
        if (request == null || request.entryTime() == null || request.exitTime() == null) {
            throw new IllegalArgumentException("entryTime and exitTime are required");
        }
        Instant entry = request.entryTime().toInstant();
        Instant exit = request.exitTime().toInstant();
        if (!exit.isAfter(entry)) {
            throw new IllegalArgumentException("exitTime must be after entryTime");
        }
        long prepaid = nonnegative(request.prepaidCents(), "prepaidCents");
        long charging = nonnegative(request.chargingCents(), "chargingCents");
        String benefit = request.benefitType() == null ? "NONE" : request.benefitType();
        String exception = request.exceptionType() == null ? "NONE" : request.exceptionType();
        BenefitPolicy policy = switch (benefit) {
            case "NONE" -> new BenefitPolicy() { public long discount(long base) { return 0; } };
            case "RESERVATION" -> base -> basisPoints(base, rates.reservationDiscountBps());
            case "MONTHLY" -> base -> basisPoints(base, rates.monthlyDiscountBps());
            default -> throw new IllegalArgumentException("Unknown benefitType");
        };
        if (!exception.equals("NONE") && !exception.equals("LOST_CARD")) {
            throw new IllegalArgumentException("Unknown exceptionType");
        }
        if (prepaid > 0 && !benefit.equals("RESERVATION")) {
            throw new IllegalArgumentException("Prepayment requires reservation benefit");
        }

        List<FeeItem> items = new ArrayList<>();
        long base = parkingBase(entry, exit, items, rates);
        long discount = policy.discount(base);
        long discountedParking = Math.subtractExact(base, discount);
        long appliedPrepaid = Math.min(prepaid, discountedParking);
        long refund = Math.subtractExact(prepaid, appliedPrepaid);
        long parkingDue = Math.subtractExact(discountedParking, appliedPrepaid);
        long exceptional = exception.equals("LOST_CARD") ? rates.lostCardCents() : 0;
        if (exceptional > 0) items.add(new FeeItem("LOST_CARD", exceptional, "丢卡附加费"));
        if (Duration.between(entry, exit).compareTo(Duration.ofHours(rates.longStayHours())) > 0) {
            long surcharge = basisPoints(base, rates.longStaySurchargeBps());
            exceptional = Math.addExact(exceptional, surcharge);
            items.add(new FeeItem("LONG_STAY", surcharge, "超长停车附加费，基于封顶后停车基础费"));
        }
        if (discount > 0) items.add(new FeeItem("DISCOUNT", discount, benefit + " 停车优惠"));
        if (appliedPrepaid > 0) items.add(new FeeItem("PREPAID_APPLIED", appliedPrepaid, "预约预付抵扣"));
        if (refund > 0) items.add(new FeeItem("PREPAID_REFUND", refund, "应退未使用的预约预付款"));
        if (charging > 0) items.add(new FeeItem("CHARGING", charging, "充电费用"));
        long due = Math.addExact(Math.addExact(parkingDue, charging), exceptional);
        return new PriceBreakdown(base, discount, appliedPrepaid, refund, parkingDue, charging,
                exceptional, due, List.copyOf(items), rates.version());
    }

    private long parkingBase(Instant entry, Instant exit, List<FeeItem> items, PricingProperties rates) {
        Instant cursor = entry.plus(Duration.ofMinutes(rates.freeMinutes()));
        if (!cursor.isBefore(exit)) return 0;
        long total = 0;
        while (cursor.isBefore(exit)) {
            LocalDate date = cursor.atZone(SHANGHAI).toLocalDate();
            ZonedDateTime midnight = date.atStartOfDay(SHANGHAI);
            Instant dayEnd = date.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
            Instant segmentEnd = min(exit, dayEnd);
            Instant dayStart = midnight.with(rates.daytimeStart()).toInstant();
            Instant daytimeEnd = midnight.with(rates.daytimeEnd()).toInstant();
            long earlyNight = segmentMinutes(cursor, segmentEnd, midnight.toInstant(), dayStart);
            long day = segmentMinutes(cursor, segmentEnd, dayStart, daytimeEnd);
            long lateNight = segmentMinutes(cursor, segmentEnd, daytimeEnd, dayEnd);
            long dayFee = day == 0 ? 0 : Math.addExact(rates.dayFirstCents(),
                    Math.multiplyExact(ceilUnits(Math.max(0, day - rates.dayFirstMinutes()),
                            rates.dayStepMinutes()), rates.dayStepCents()));
            long nightFee = Math.multiplyExact(
                    Math.addExact(ceilUnits(earlyNight, rates.nightStepMinutes()),
                            ceilUnits(lateNight, rates.nightStepMinutes())), rates.nightStepCents());
            long raw = Math.addExact(dayFee, nightFee);
            long capped = Math.min(raw, rates.dailyCapCents());
            if (capped > 0) items.add(new FeeItem("PARKING_DAY", capped,
                    date + " 日间 " + day + " 分钟，夜间 "
                            + Math.addExact(earlyNight, lateNight) + " 分钟，封顶前 " + raw + " 分"));
            total = Math.addExact(total, capped);
            cursor = segmentEnd;
        }
        return total;
    }

    private static long segmentMinutes(Instant from, Instant to, Instant bandStart, Instant bandEnd) {
        Instant start = from.isAfter(bandStart) ? from : bandStart;
        Instant end = to.isBefore(bandEnd) ? to : bandEnd;
        return start.isBefore(end) ? ceilUnits(Duration.between(start, end).toMillis(), 60_000) : 0;
    }

    private static long ceilUnits(long count, long unit) {
        return count / unit + (count % unit == 0 ? 0 : 1);
    }

    private static long basisPoints(long cents, int bps) {
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long nonnegative(Long value, String name) {
        if (value == null) return 0;
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    private interface BenefitPolicy { long discount(long base); }

    record PriceBreakdown(long parkingBaseCents, long discountCents, long prepaidCents,
            long prepaidRefundCents, long parkingDueCents, long chargingCents,
            long exceptionCents, long amountDueCents, List<FeeItem> items, String rateVersion) {}
}
