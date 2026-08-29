package hex.events.hardening;

import hex.events.api.EventModuleSettings;
import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;
import hex.events.schedule.EventOccurrenceCompiler;

import java.time.*;
import java.util.List;
import java.util.Map;

public final class OneTimeScheduleLogicTest {
    public static void main(String[] args) {
        ZoneId zone = ZoneId.of("Europe/Warsaw");
        LocalDateTime onceAt = LocalDateTime.of(2026, 9, 15, 19, 0, 37);
        EventDefinition def = new EventDefinition(
                "one_time_test", true, "One Time", "", "CLOCK", "hex:test", EventModuleSettings.empty(),
                new EventDefinition.Schedule(zone, List.of(), List.of(new EventDefinition.OneTimeSlot(onceAt))),
                Duration.ofMinutes(30), Duration.ZERO,
                new EventDefinition.RegistrationPolicy(EventDefinition.RegistrationMode.DISABLED, Duration.ZERO, EventDefinition.CancelUntil.START),
                new EventDefinition.LobbyPolicy(false, Duration.ZERO),
                new EventDefinition.CapacityPolicy(0, 0, EventDefinition.TooFewPolicy.START_ANYWAY),
                new EventDefinition.JoinPolicy(false, false, Duration.ZERO, EventDefinition.LateJoinScope.ELIGIBLE_PLAYERS, true),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()
        );

        Instant from = LocalDateTime.of(2026, 9, 15, 0, 0).atZone(zone).toInstant();
        Instant to = LocalDateTime.of(2026, 9, 16, 0, 0).atZone(zone).toInstant();
        List<EventInstance> instances = new EventOccurrenceCompiler().compileBetween(def, from, to);
        if (instances.size() != 1) throw new AssertionError("expected one one-time occurrence, got " + instances.size());
        EventInstance instance = instances.getFirst();
        if (instance.occurrenceAt().getEpochSecond() != onceAt.atZone(zone).toInstant().getEpochSecond())
            throw new AssertionError("one-time occurrence timestamp mismatch");
        if (def.schedule().kindAt(instance.occurrenceAt()) != EventDefinition.ScheduleKind.ONE_TIME)
            throw new AssertionError("one-time occurrence must be classified as ONE_TIME");

        EventDefinition mixed = new EventDefinition(
                "mixed_test", true, "Mixed", "", "CLOCK", "hex:test", EventModuleSettings.empty(),
                new EventDefinition.Schedule(zone,
                        List.of(new EventDefinition.WeeklySlot(DayOfWeek.TUESDAY, LocalTime.of(18, 0))),
                        List.of(new EventDefinition.OneTimeSlot(onceAt))),
                Duration.ofMinutes(30), Duration.ZERO,
                new EventDefinition.RegistrationPolicy(EventDefinition.RegistrationMode.DISABLED, Duration.ZERO, EventDefinition.CancelUntil.START),
                new EventDefinition.LobbyPolicy(false, Duration.ZERO),
                new EventDefinition.CapacityPolicy(0, 0, EventDefinition.TooFewPolicy.START_ANYWAY),
                new EventDefinition.JoinPolicy(false, false, Duration.ZERO, EventDefinition.LateJoinScope.ELIGIBLE_PLAYERS, true),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()
        );
        List<EventInstance> mixedInstances = new EventOccurrenceCompiler().compileBetween(mixed, from, to);
        if (mixedInstances.size() != 2) throw new AssertionError("mixed schedule should expose recurring + one-time occurrences");
        long oneTimeCount = mixedInstances.stream().filter(i -> mixed.schedule().kindAt(i.occurrenceAt()) == EventDefinition.ScheduleKind.ONE_TIME).count();
        long recurringCount = mixedInstances.stream().filter(i -> mixed.schedule().kindAt(i.occurrenceAt()) == EventDefinition.ScheduleKind.RECURRING).count();
        if (oneTimeCount != 1 || recurringCount != 1) throw new AssertionError("mixed schedule classification mismatch");

        System.out.println("OneTimeScheduleLogicTest OK");
    }
}
