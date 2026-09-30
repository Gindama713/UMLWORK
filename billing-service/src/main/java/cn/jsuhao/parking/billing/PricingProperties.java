package cn.jsuhao.parking.billing;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parking.pricing")
public record PricingProperties(
        String version, int freeMinutes, LocalTime daytimeStart, LocalTime daytimeEnd,
        int dayFirstMinutes, long dayFirstCents, int dayStepMinutes, long dayStepCents,
        int nightStepMinutes, long nightStepCents, long dailyCapCents,
        int reservationDiscountBps, int monthlyDiscountBps, long lostCardCents,
        long longStayHours, int longStaySurchargeBps) {

    public PricingProperties {
        if (version == null || version.isBlank() || daytimeStart == null || daytimeEnd == null
                || !daytimeStart.isBefore(daytimeEnd) || freeMinutes < 0 || dayFirstMinutes <= 0
                || dayStepMinutes <= 0 || nightStepMinutes <= 0 || dayFirstCents < 0
                || dayStepCents < 0 || nightStepCents < 0 || dailyCapCents < 0
                || reservationDiscountBps < 0 || reservationDiscountBps > 10_000
                || monthlyDiscountBps < 0 || monthlyDiscountBps > 10_000
                || lostCardCents < 0 || longStayHours <= 0
                || longStaySurchargeBps < 0) {
            throw new IllegalArgumentException("Invalid parking.pricing configuration");
        }
    }
}
