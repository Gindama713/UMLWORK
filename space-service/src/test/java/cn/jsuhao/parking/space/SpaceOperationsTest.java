package cn.jsuhao.parking.space;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
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
        "spring.cloud.nacos.discovery.enabled=false"
})
class SpaceOperationsTest {
    @Autowired SpaceOperations operations;

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
        assertThrows(SpaceException.class, () -> operations.settle(spaceId, sessionId, "early-settlement"));
        assertThrows(SpaceException.class, () -> operations.release(spaceId, sessionId, "release-too-early"));
        assertEquals(charging.chargingSessionId(), operations.startCharging(
                chargerId, sessionId, "2026-10-01T08:00:00+08:00", "start-charge").chargingSessionId());
        assertThrows(SpaceException.class, () -> operations.startCharging(
                chargerId, sessionId, "2026-10-01T08:01:00+08:00", "other-start"));

        var finished = operations.finishCharging(charging.chargingSessionId(),
                "2026-10-01T09:00:00.1234567+08:00", new BigDecimal("2.555"), "finish-charge");
        assertEquals("FINISHED", finished.status());
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
