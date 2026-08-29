package hex.events.hardening;

import hex.events.api.EventModuleSettings;
import hex.events.model.EventDefinition;

import java.time.*;
import java.util.*;

public final class SnapshotImmutabilityLogicTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        list.add("A");
        nested.put("list", list);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("nested", nested);

        EventModuleSettings settings = new EventModuleSettings(source);
        list.add("B");
        check(((List<?>) ((Map<?, ?>) settings.asMap().get("nested")).get("list")).size() == 1,
                "EventModuleSettings must deep snapshot nested lists");
        expectUnsupported(() -> ((Map<String, Object>) settings.asMap().get("nested")).put("x", 1));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<Object> cost = new ArrayList<>(List.of("pass"));
        snapshot.put("costs", cost);
        EventDefinition def = new EventDefinition(
                "snapshot", true, "Snapshot", "", "CLOCK", "hex:test", settings,
                new EventDefinition.Schedule(ZoneId.of("UTC"), List.of(new EventDefinition.WeeklySlot(DayOfWeek.MONDAY, LocalTime.NOON))),
                Duration.ofMinutes(10), Duration.ZERO,
                new EventDefinition.RegistrationPolicy(EventDefinition.RegistrationMode.REQUIRED, Duration.ofHours(1), EventDefinition.CancelUntil.START),
                new EventDefinition.LobbyPolicy(false, Duration.ZERO),
                new EventDefinition.CapacityPolicy(0, 10, EventDefinition.TooFewPolicy.CANCEL_AND_REFUND),
                new EventDefinition.JoinPolicy(true, false, Duration.ZERO, EventDefinition.LateJoinScope.REGISTERED_ONLY, true),
                List.of(), List.of(), List.of(), List.of(), List.of(), snapshot);
        cost.add("changed-after-instance-created");
        check(((List<?>) def.snapshot().get("costs")).size() == 1, "EventDefinition snapshot must be deep immutable");
        expectUnsupported(() -> ((List<Object>) def.snapshot().get("costs")).add("x"));

        System.out.println("SnapshotImmutabilityLogicTest OK");
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
