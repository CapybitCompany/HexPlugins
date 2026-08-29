package hex.events.schedule;

import hex.events.api.EventState;
import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class EventOccurrenceCompiler {
    public List<EventInstance> compile(EventDefinition definition, Instant now, int daysAhead) {
        Instant to = now.plus(Duration.ofDays(Math.max(7, daysAhead)));
        return compileBetween(definition, now.minus(Duration.ofDays(1)), to);
    }

    /** Compile only occurrences whose occurrence timestamp is inside the requested range. */
    public List<EventInstance> compileBetween(EventDefinition definition, Instant fromInclusive, Instant toInclusive) {
        if (!definition.enabled() || toInclusive.isBefore(fromInclusive)) return List.of();
        ZoneId zone = definition.schedule().zoneId();
        LocalDate firstDate = fromInclusive.atZone(zone).toLocalDate().minusDays(1);
        LocalDate lastDate = toInclusive.atZone(zone).toLocalDate().plusDays(1);
        List<EventInstance> result = new ArrayList<>();
        Set<Instant> seenOccurrences = new HashSet<>();

        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            DayOfWeek dow = date.getDayOfWeek();
            for (EventDefinition.WeeklySlot weekly : definition.schedule().weekly()) {
                if (weekly.day() != dow) continue;
                ZonedDateTime occurrence = ZonedDateTime.of(date, weekly.time(), zone);
                addOccurrence(definition, occurrence, fromInclusive, toInclusive, seenOccurrences, result);
            }
        }

        for (EventDefinition.OneTimeSlot oneTime : definition.schedule().oneTime()) {
            ZonedDateTime occurrence = oneTime.dateTime().atZone(zone);
            addOccurrence(definition, occurrence, fromInclusive, toInclusive, seenOccurrences, result);
        }
        result.sort(Comparator.comparing(EventInstance::startAt).thenComparing(i -> i.id().toString()));
        return result;
    }

    private static void addOccurrence(EventDefinition definition,
                                      ZonedDateTime occurrence,
                                      Instant fromInclusive,
                                      Instant toInclusive,
                                      Set<Instant> seenOccurrences,
                                      List<EventInstance> result) {
        Instant occurrenceAt = occurrence.toInstant();
        if (occurrenceAt.isBefore(fromInclusive) || occurrenceAt.isAfter(toInclusive)) return;
        if (!seenOccurrences.add(occurrenceAt)) return;

        Instant lobbyAt = occurrenceAt;
        Instant startAt = definition.lobby().enabled() ? occurrence.plus(definition.lobby().duration()).toInstant() : occurrenceAt;
        Instant endAt = startAt.plus(definition.duration());
        Instant registrationOpenAt = occurrenceAt.minus(definition.registration().opensBefore());
        Instant prepareAt = occurrenceAt.minus(definition.prepareBefore());
        Instant lateJoinCloseAt = definition.join().lateJoin()
                ? (definition.join().lateJoinFor().isZero() ? endAt : startAt.plus(definition.join().lateJoinFor()))
                : startAt;
        if (lateJoinCloseAt.isAfter(endAt)) lateJoinCloseAt = endAt;
        UUID id = deterministicId(definition.id(), occurrenceAt);
        result.add(new EventInstance(id, definition, occurrenceAt, registrationOpenAt, prepareAt,
                lobbyAt, startAt, lateJoinCloseAt, endAt, EventState.SCHEDULED));
    }

    public List<ScheduledTransition> transitions(EventInstance instance) {
        List<ScheduledTransition> result = new ArrayList<>();
        EventDefinition def = instance.definition();
        if (def.bossBar().enabled()) {
            Instant bossBarShowAt = instance.occurrenceAt().minus(def.bossBar().showBefore());
            result.add(new ScheduledTransition(instance.id(), ScheduledTransition.Type.BOSSBAR_SHOW, bossBarShowAt));
        }
        if (def.registration().enabled()) result.add(new ScheduledTransition(instance.id(), ScheduledTransition.Type.REGISTRATION_OPEN, instance.registrationOpenAt()));
        result.add(new ScheduledTransition(instance.id(), ScheduledTransition.Type.PREPARE, instance.prepareAt()));
        if (def.lobby().enabled()) result.add(new ScheduledTransition(instance.id(), ScheduledTransition.Type.LOBBY, instance.lobbyAt()));
        result.add(new ScheduledTransition(instance.id(), ScheduledTransition.Type.START, instance.startAt()));
        if (def.join().lateJoin()) result.add(new ScheduledTransition(instance.id(), ScheduledTransition.Type.LATE_JOIN_CLOSE, instance.lateJoinCloseAt()));
        result.add(new ScheduledTransition(instance.id(), ScheduledTransition.Type.END, instance.endAt()));
        result.sort(Comparator.naturalOrder());
        return result;
    }

    public static UUID deterministicId(String eventId, Instant occurrenceAt) {
        String seed = "hexevents:" + eventId + ":" + occurrenceAt.toEpochMilli();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
