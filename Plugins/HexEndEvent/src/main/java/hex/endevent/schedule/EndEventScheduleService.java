package hex.endevent.schedule;

import hex.endevent.config.EndEventConfig;
import hex.endevent.model.EndEventSlot;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class EndEventScheduleService {
    private static final DateTimeFormatter EVENT_ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm_VV", Locale.ROOT);
    private final EndEventConfig config;

    public EndEventScheduleService(EndEventConfig config) {
        this.config = config;
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(config.zoneId());
    }

    public Optional<EndEventSlot> activeSlot(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(config.zoneId());
        return slotsAround(local, -7, 0).stream()
                .filter(slot -> slot.contains(local))
                .max(Comparator.comparing(EndEventSlot::start));
    }

    public EndEventSlot nextSlot(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(config.zoneId());
        return slotsAround(local, 0, 14).stream()
                .filter(slot -> slot.start().isAfter(local))
                .min(Comparator.comparing(EndEventSlot::start))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono kolejnego slotu End Event"));
    }

    public Optional<EndEventSlot> slotInPreparationWindow(ZonedDateTime now) {
        EndEventSlot next = nextSlot(now);
        ZonedDateTime prepareAt = next.start().minus(config.prepareBefore());
        return !now.isBefore(prepareAt) && now.isBefore(next.start()) ? Optional.of(next) : Optional.empty();
    }

    private List<EndEventSlot> slotsAround(ZonedDateTime now, int daysBefore, int daysAfter) {
        java.util.ArrayList<EndEventSlot> result = new java.util.ArrayList<>();
        LocalDate base = now.toLocalDate();
        for (int offset = daysBefore; offset <= daysAfter; offset++) {
            LocalDate date = base.plusDays(offset);
            DayOfWeek day = date.getDayOfWeek();
            for (EndEventConfig.ScheduleEntry entry : config.schedule()) {
                if (entry.day() != day) continue;
                ZonedDateTime start = date.atTime(entry.time()).atZone(config.zoneId());
                ZonedDateTime end = start.plus(config.duration());
                result.add(new EndEventSlot(start, end, start.format(EVENT_ID)));
            }
        }
        return result;
    }
}
