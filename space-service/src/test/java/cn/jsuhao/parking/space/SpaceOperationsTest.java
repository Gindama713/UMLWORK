package cn.jsuhao.parking.space;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:space_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false",
        "parking.recommendation.max-results=2"
})
class SpaceOperationsTest {
    @Autowired SpaceOperations operations;
    @Autowired SpaceController controller;

    @Test
    void airportSeedHasBalancedCapacityAndOneChargerPerChargingSpace() {
        var seeded = operations.spaces(null, false).stream()
                .filter(space -> space.spaceId().startsWith("f1000000-0000-0000-0000-"))
                .toList();
        assertEquals(63, seeded.size());
        assertEquals(30, seeded.stream().filter(space -> space.type().equals("NORMAL")).count());
        assertEquals(7, seeded.stream().filter(space -> space.type().equals("ACCESSIBLE")).count());
        assertEquals(12, seeded.stream().filter(space -> space.type().equals("CHARGING")).count());
        assertEquals(14, seeded.stream().filter(space -> space.type().equals("RESERVATION")).count());
        for (var space : seeded) {
            if (space.type().equals("CHARGING")) assertEquals(1, operations.chargers(space.spaceId()).size());
        }
    }

    @Test
    void twelveDifferentCarsKeepDistinctSpacesAndCanAllRelease() {
        Map<String, String> occupied = new LinkedHashMap<>();
        try {
            for (int index = 32; index <= 43; index++) {
                String spaceId = String.format("f1000000-0000-0000-0000-%012d", index);
                String sessionId = UUID.randomUUID().toString();
                assertEquals(sessionId, operations.occupy(spaceId, sessionId,
                        UUID.randomUUID().toString()).parkingSessionId());
                occupied.put(spaceId, sessionId);
            }
            assertEquals(12, occupied.size());
            for (var space : operations.spaces("NORMAL", false)) {
                if (occupied.containsKey(space.spaceId())) {
                    assertEquals("OCCUPIED", space.status());
                    assertEquals(occupied.get(space.spaceId()), space.parkingSessionId());
                }
            }
            String first = occupied.keySet().iterator().next();
            assertThrows(SpaceException.class, () -> operations.occupy(first,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        } finally {
            occupied.forEach((spaceId, sessionId) -> assertEquals("AVAILABLE",
                    operations.release(spaceId, sessionId, UUID.randomUUID().toString()).status()));
        }
    }

    @Test
    void recommendationIsReadOnlyAndNeverPromisesOccupation() {
        var candidates = controller.recommendations("T1", "NORMAL").data();
        assertEquals(2, candidates.size());
        assertEquals("A-001", candidates.getFirst().number());
        assertEquals(true, candidates.stream().allMatch(s -> s.status().equals("AVAILABLE")));
        assertEquals(true, candidates.stream().allMatch(s -> s.walkingMinutes() > 0));
        var first = candidates.getFirst();
        operations.occupy(first.spaceId(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertEquals(false, operations.recommendations("T1", "NORMAL").stream()
                .anyMatch(s -> s.spaceId().equals(first.spaceId())));
        assertThrows(SpaceException.class, () -> controller.recommendations("T1", "RESERVATION"));
    }

    @Test
    void internalListKeepsOccupiedReservationSpacesVisible() {
        var space = operations.create("RESERVATION", "P3", "D",
                "D-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID().toString());
        operations.occupy(space.spaceId(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var internal = controller.internalSpaces("RESERVATION").data();
        assertEquals(controller.spaces("RESERVATION").data(), internal);
        assertEquals("OCCUPIED", internal.stream().filter(s -> s.spaceId().equals(space.spaceId()))
                .findFirst().orElseThrow().status());
    }

    @Test
    void occupancyAndChargingAreAtomicAndIdempotent() {
        String spaceId = "f1000000-0000-0000-0000-000000000004";
        String chargerId = "c1000000-0000-0000-0000-000000000001";
        String sessionId = UUID.randomUUID().toString();
        String competingSessionId = UUID.randomUUID().toString();
        assertEquals("OCCUPIED", operations.occupy(spaceId, sessionId, "test-occupy").status());
        assertEquals(sessionId, operations.occupy(spaceId, sessionId, "test-occupy").parkingSessionId());
        assertThrows(SpaceException.class, () -> operations.occupy(spaceId, competingSessionId, "other-occupy"));
        assertThrows(SpaceException.class, () -> operations.occupy(spaceId, competingSessionId, "test-occupy"));

        var charging = operations.startCharging(chargerId, sessionId, "2026-10-01T08:00:00+08:00", "start-charge");
        assertEquals(charging, operations.activeCharging(sessionId).getFirst());
        assertEquals(charging, new ChargingController(operations).active(sessionId).data().getFirst());
        assertThrows(SpaceException.class, () -> operations.settle(spaceId, sessionId, "early-settlement"));
        assertThrows(SpaceException.class, () -> operations.release(spaceId, sessionId, "release-too-early"));
        assertEquals(charging.chargingSessionId(), operations.startCharging(
                chargerId, sessionId, "2026-10-01T08:00:00+08:00", "start-charge").chargingSessionId());
        assertThrows(SpaceException.class, () -> operations.startCharging(
                chargerId, sessionId, "2026-10-01T08:01:00+08:00", "other-start"));

        var finished = operations.finishCharging(charging.chargingSessionId(),
                "2026-10-01T09:00:00.1234567+08:00", new BigDecimal("2.555"), "finish-charge");
        assertEquals("FINISHED", finished.status());
        assertEquals(0, operations.activeCharging(sessionId).size());
        assertEquals(383L, finished.chargingCents());
        assertEquals(383L, operations.chargingFees(sessionId).chargingCents());
        assertEquals(383L, operations.settle(spaceId, sessionId, "settlement").chargingCents());
        assertEquals(383L, operations.settle(spaceId, sessionId, "settlement-retry").chargingCents());
        assertThrows(SpaceException.class, () -> operations.startCharging(
                chargerId, sessionId, "2026-10-01T09:01:00+08:00", "start-after-settlement"));
        assertEquals(383L, operations.finishCharging(charging.chargingSessionId(),
                "2026-10-01T09:00:00.1234567+08:00", new BigDecimal("2.555"), "finish-charge").chargingCents());
        assertEquals(finished, operations.finishCharging(charging.chargingSessionId(),
                "2026-10-01T09:00:00.1234567+08:00", new BigDecimal("2.555"), "another-finish-key"));
        assertEquals("AVAILABLE", operations.release(spaceId, sessionId, "release-space").status());
        assertEquals("AVAILABLE", operations.release(spaceId, sessionId, "release-space").status());
        assertThrows(SpaceException.class, () -> operations.release(spaceId, competingSessionId, "other-release"));
    }

    @Test
    void twoSessionsCannotOccupyOneSpace() throws Exception {
        String spaceId = "f1000000-0000-0000-0000-000000000002";
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tryOccupy(spaceId, start));
            var second = pool.submit(() -> tryOccupy(spaceId, start));
            start.countDown();
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
        }
    }

    @Test
    void newChargingSpaceIncludesCharger() {
        var space = operations.create("CHARGING", "B2", "C", "C-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString());
        assertEquals(1, operations.chargers(space.spaceId()).size());
    }

    @Test
    void settlementAndNewChargingCannotBothWin() throws Exception {
        var space = operations.create("CHARGING", "B2", "C", "C-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString());
        String charger = operations.chargers(space.spaceId()).getFirst().chargerId();
        String session = UUID.randomUUID().toString();
        operations.occupy(space.spaceId(), session, UUID.randomUUID().toString());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var freeze = pool.submit(() -> race(start, () -> operations.settle(space.spaceId(), session, UUID.randomUUID().toString())));
            var charge = pool.submit(() -> race(start, () -> operations.startCharging(charger, session,
                    "2026-10-01T08:00:00+08:00", UUID.randomUUID().toString())));
            start.countDown();
            assertEquals(1, (freeze.get(5, TimeUnit.SECONDS) ? 1 : 0) + (charge.get(5, TimeUnit.SECONDS) ? 1 : 0));
        }
    }

    private boolean race(CountDownLatch start, Runnable operation) throws InterruptedException {
        start.await();
        try { operation.run(); return true; }
        catch (SpaceException rejected) { return false; }
    }

    private boolean tryOccupy(String spaceId, CountDownLatch start) {
        try {
            start.await();
            operations.occupy(spaceId, UUID.randomUUID().toString(), UUID.randomUUID().toString());
            return true;
        } catch (SpaceException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
