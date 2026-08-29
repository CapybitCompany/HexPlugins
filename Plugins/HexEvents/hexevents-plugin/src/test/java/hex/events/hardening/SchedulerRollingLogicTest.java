package hex.events.hardening;

import hex.events.api.EventModuleSettings;
import hex.events.api.EventState;
import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;
import hex.events.schedule.EventOccurrenceCompiler;
import hex.events.schedule.ScheduledTransition;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

public final class SchedulerRollingLogicTest {
    public static void main(String[] args) {
        EventOccurrenceCompiler compiler = new EventOccurrenceCompiler();
        EventDefinition def = definition();
        Instant now = Instant.parse("2026-08-28T07:00:00Z");
        Instant firstEnd = now.plus(Duration.ofDays(30));
        Instant secondEnd = now.plus(Duration.ofDays(60));

        List<EventInstance> first = compiler.compileBetween(def, now, firstEnd);
        List<EventInstance> extension = compiler.compileBetween(def, firstEnd.plusMillis(1), secondEnd);
        check(!first.isEmpty(), "first horizon must contain occurrences");
        check(!extension.isEmpty(), "rolling extension must contain occurrences after day 30");
        check(extension.stream().allMatch(i -> i.occurrenceAt().isAfter(firstEnd)), "extension may only contain missing range");

        Set<UUID> ids = new HashSet<>();
        first.forEach(i -> check(ids.add(i.id()), "duplicate instance id in first range"));
        extension.forEach(i -> check(ids.add(i.id()), "duplicate instance id across rolling ranges"));

        EventInstance sample = first.getFirst();
        ScheduledTransition a = new ScheduledTransition(sample.id(), ScheduledTransition.Type.START, sample.startAt());
        ScheduledTransition same = new ScheduledTransition(sample.id(), ScheduledTransition.Type.START, sample.startAt());
        ScheduledTransition other = new ScheduledTransition(sample.id(), ScheduledTransition.Type.END, sample.endAt());
        check(a.key().equals(same.key()), "transition key must be stable");
        check(!a.key().equals(other.key()), "different transition must have different key");

        System.out.println("SchedulerRollingLogicTest OK: first=" + first.size() + ", extension=" + extension.size());
    }

    private static EventDefinition definition() {
        return new EventDefinition(
                "weekly_test", true, "Weekly Test", "", "CLOCK", "hex:test", EventModuleSettings.empty(),
                new EventDefinition.Schedule(ZoneId.of("Europe/Warsaw"),
                        List.of(new EventDefinition.WeeklySlot(DayOfWeek.FRIDAY, LocalTime.of(19, 0)))),
                Duration.ofMinutes(30), Duration.ofMinutes(5),
                new EventDefinition.RegistrationPolicy(EventDefinition.RegistrationMode.REQUIRED, Duration.ofHours(24), EventDefinition.CancelUntil.START),
                new EventDefinition.LobbyPolicy(true, Duration.ofMinutes(5)),
                new EventDefinition.CapacityPolicy(0, 100, EventDefinition.TooFewPolicy.CANCEL_AND_REFUND),
                new EventDefinition.JoinPolicy(true, true, Duration.ofMinutes(10), EventDefinition.LateJoinScope.REGISTERED_ONLY, true),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of("nested", Map.of("value", 1))
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
