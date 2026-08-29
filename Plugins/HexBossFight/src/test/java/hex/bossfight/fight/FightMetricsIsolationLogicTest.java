package hex.bossfight.fight;

import hex.events.api.EventExecutionContext;
import hex.events.api.EventModuleSettings;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class FightMetricsIsolationLogicTest {
    public static void main(String[] args) {
        UUID instanceA = UUID.randomUUID();
        UUID instanceB = UUID.randomUUID();
        UUID entityA = UUID.randomUUID();
        UUID entityB = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        EventExecutionContext contextA = context(instanceA);
        EventExecutionContext contextB = context(instanceB);
        ActiveBossFight a = new ActiveBossFight(contextA, "same_boss", 1);
        ActiveBossFight b = new ActiveBossFight(contextB, "same_boss", 1);
        ActiveFightRegistry registry = new ActiveFightRegistry();
        registry.put(a);
        registry.put(b);
        registry.bindEntity(a, entityA);
        registry.bindEntity(b, entityB);

        a.participants.add(player);
        b.participants.add(player);
        a.stats(player).damage = 100;
        b.stats(player).damage = 25;

        require(registry.byEntity(entityA).orElseThrow() == a, "entity A must map to instance A");
        require(registry.byEntity(entityB).orElseThrow() == b, "entity B must map to instance B");
        require(a.stats(player).damage == 100, "instance A damage mixed");
        require(b.stats(player).damage == 25, "instance B damage mixed");

        registry.unbindEntity(a);
        require(registry.byEntity(entityA).isEmpty(), "dead boss entity must be unbound");
        require(registry.byInstance(instanceA).isPresent(), "fight must remain until HexEvents completion cleanup");
        require(registry.byEntity(entityB).orElseThrow() == b, "unbinding A must not touch B");

        System.out.println("FightMetricsIsolationLogicTest OK");
    }

    private static EventExecutionContext context(UUID id) {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        return new EventExecutionContext(id, "boss_test", "Boss", now, now, now.plusSeconds(1200),
                new EventModuleSettings(java.util.Map.of("boss-id", "same_boss", "spawn-location", 1)), Set.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
