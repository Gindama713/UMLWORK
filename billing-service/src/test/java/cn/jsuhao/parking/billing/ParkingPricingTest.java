package cn.jsuhao.parking.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ParkingPricingTest {
    private final ParkingPricing pricing = new ParkingPricing(new PricingProperties(
            "v1-test", 15, LocalTime.of(8, 0), LocalTime.of(22, 0),
            60, 600, 30, 200, 60, 200, 6000,
            1000, 2000, 2000, 48, 2000));

    private ParkingPricing.PriceBreakdown price(String from, String to, String benefit,
            long prepaid, long charging, String exception) {
        return pricing.calculate(new BillRequest("session", OffsetDateTime.parse(from),
                OffsetDateTime.parse(to), benefit, prepaid, charging, exception));
    }

    @Test
    void freeBoundaryAndFirstStep() {
        assertEquals(0, price("2026-10-01T08:00:00+08:00", "2026-10-01T08:15:00+08:00",
                "NONE", 0, 0, "NONE").amountDueCents());
        assertEquals(600, price("2026-10-01T08:00:00+08:00", "2026-10-01T08:16:00+08:00",
                "NONE", 0, 0, "NONE").amountDueCents());
        assertEquals(1000, price("2026-10-01T08:00:00+08:00", "2026-10-01T10:00:00+08:00",
                "NONE", 0, 0, "NONE").parkingBaseCents());
    }

    @Test
    void reservationDiscountPrepaymentAndRefund() {
        var bill = price("2026-10-01T08:00:00+08:00", "2026-10-01T10:00:00+08:00",
                "RESERVATION", 500, 300, "NONE");
        assertEquals(1000, bill.parkingBaseCents());
        assertEquals(100, bill.discountCents());
        assertEquals(500, bill.prepaidCents());
        assertEquals(400, bill.parkingDueCents());
        assertEquals(700, bill.amountDueCents());
        assertEquals(0, bill.prepaidRefundCents());

        var shortStay = price("2026-10-01T08:00:00+08:00", "2026-10-01T08:10:00+08:00",
                "RESERVATION", 500, 0, "NONE");
        assertEquals(500, shortStay.prepaidRefundCents());
        assertEquals(0, shortStay.amountDueCents());
    }

    @Test
    void splitAtDayNightAndMidnight() {
        assertEquals(800, price("2026-10-01T21:40:00+08:00", "2026-10-01T22:10:00+08:00",
                "NONE", 0, 0, "NONE").parkingBaseCents());
        assertEquals(200, price("2026-10-01T23:45:00+08:00", "2026-10-02T00:15:00+08:00",
                "NONE", 0, 0, "NONE").parkingBaseCents());
    }

    @Test
    void dailyCapAndLongStaySurcharge() {
        var overnight = price("2026-10-01T08:00:00+08:00", "2026-10-02T08:00:00+08:00",
                "NONE", 0, 0, "NONE");
        assertEquals(7600, overnight.parkingBaseCents());
        var longStay = price("2026-10-01T08:00:00+08:00", "2026-10-03T08:01:00+08:00",
                "MONTHLY", 0, 150, "LOST_CARD");
        assertEquals(2000 + longStay.parkingBaseCents() / 5, longStay.exceptionCents());
        assertEquals(longStay.parkingBaseCents() * 2 / 10, longStay.discountCents());
        assertEquals(longStay.parkingDueCents() + longStay.exceptionCents() + 150,
                longStay.amountDueCents());
    }

    @Test
    void rejectInvalidDurationAndPrepaymentWithoutReservation() {
        assertThrows(IllegalArgumentException.class, () -> price(
                "2026-10-01T08:00:00+08:00", "2026-10-01T08:00:00+08:00", "NONE", 0, 0, "NONE"));
        assertThrows(IllegalArgumentException.class, () -> price(
                "2026-10-01T08:00:00+08:00", "2026-10-01T09:00:00+08:00", "MONTHLY", 500, 0, "NONE"));
    }
}
