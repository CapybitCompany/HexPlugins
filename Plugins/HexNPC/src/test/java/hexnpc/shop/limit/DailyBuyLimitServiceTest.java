package hexnpc.shop.limit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dzienny limit kupna: pozostała ilość, akumulacja, brak limitu, reset dobowy
 * oraz trwałość między instancjami.
 */
class DailyBuyLimitServiceTest {

    private final Logger logger = Logger.getLogger("test");

    private DailyBuyLimitService service(File file, AtomicLong today) {
        return new DailyBuyLimitService(file, logger, today::get);
    }

    @Test
    void remainingUnderLimit() {
        AtomicLong today = new AtomicLong(100);
        DailyBuyLimitService svc = service(null, today);
        UUID uuid = UUID.randomUUID();
        String key = DailyBuyLimitService.key("shop", "diamond");
        assertEquals(64, svc.remaining(uuid, key, 64));
        svc.record(uuid, key, 20);
        assertEquals(44, svc.remaining(uuid, key, 64));
        assertEquals(20, svc.purchasedToday(uuid, key));
    }

    @Test
    void remainingAtLimitIsZero() {
        AtomicLong today = new AtomicLong(100);
        DailyBuyLimitService svc = service(null, today);
        UUID uuid = UUID.randomUUID();
        String key = DailyBuyLimitService.key("shop", "diamond");
        svc.record(uuid, key, 64);
        assertEquals(0, svc.remaining(uuid, key, 64));
    }

    @Test
    void unlimitedWhenLimitZeroOrNegative() {
        DailyBuyLimitService svc = service(null, new AtomicLong(1));
        UUID uuid = UUID.randomUUID();
        String key = DailyBuyLimitService.key("shop", "stone");
        assertEquals(DailyBuyLimitService.UNLIMITED, svc.remaining(uuid, key, 0));
        svc.record(uuid, key, 1000);
        assertEquals(DailyBuyLimitService.UNLIMITED, svc.remaining(uuid, key, 0));
    }

    @Test
    void resetsAtDayBoundary() {
        AtomicLong today = new AtomicLong(100);
        DailyBuyLimitService svc = service(null, today);
        UUID uuid = UUID.randomUUID();
        String key = DailyBuyLimitService.key("shop", "diamond");
        svc.record(uuid, key, 50);
        assertEquals(50, svc.purchasedToday(uuid, key));
        // Nowy dzień -> licznik zresetowany.
        today.set(101);
        assertEquals(0, svc.purchasedToday(uuid, key));
        assertEquals(64, svc.remaining(uuid, key, 64));
        svc.record(uuid, key, 10);
        assertEquals(10, svc.purchasedToday(uuid, key));
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        File file = dir.resolve("buy-limits.yml").toFile();
        AtomicLong today = new AtomicLong(200);
        UUID uuid = UUID.randomUUID();
        String key = DailyBuyLimitService.key("shop", "diamond");

        DailyBuyLimitService first = service(file, today);
        first.load();
        first.record(uuid, key, 33);
        first.save();
        assertTrue(file.exists());

        DailyBuyLimitService second = service(file, new AtomicLong(200));
        second.load();
        assertEquals(33, second.purchasedToday(uuid, key));
        assertEquals(31, second.remaining(uuid, key, 64));
    }

    @Test
    void staleDayNotPersistedRehydratesAsZero(@TempDir Path dir) {
        File file = dir.resolve("buy-limits.yml").toFile();
        UUID uuid = UUID.randomUUID();
        String key = DailyBuyLimitService.key("shop", "diamond");

        DailyBuyLimitService first = service(file, new AtomicLong(300));
        first.load();
        first.record(uuid, key, 40);
        first.save();

        // Wczytanie w innym dniu -> stary wpis liczy się jako 0.
        DailyBuyLimitService second = service(file, new AtomicLong(301));
        second.load();
        assertEquals(0, second.purchasedToday(uuid, key));
    }
}
