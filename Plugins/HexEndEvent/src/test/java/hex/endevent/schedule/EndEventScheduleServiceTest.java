package hex.endevent.schedule;

import hex.endevent.config.EndEventConfig;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndEventScheduleServiceTest {
    private EndEventConfig config() {
        return new EndEventConfig(
                true,
                ZoneId.of("Europe/Warsaw"),
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                List.of(
                        new EndEventConfig.ScheduleEntry(DayOfWeek.TUESDAY, java.time.LocalTime.of(18, 0)),
                        new EndEventConfig.ScheduleEntry(DayOfWeek.FRIDAY, java.time.LocalTime.of(19, 0)),
                        new EndEventConfig.ScheduleEntry(DayOfWeek.SUNDAY, java.time.LocalTime.of(17, 0))
                ),
                true, "hexendevent.bypass", Duration.ofSeconds(3),
                "world_the_end", "world", true, EndEventConfig.SeedMode.RANDOM, 0L, true,
                new EndEventConfig.BossBarConfig(true, 20, "PURPLE", "PROGRESS"), "runtime.yml"
        );
    }

    @Test
    void tuesdayBeforeOpeningFindsTuesday() {
        var service = new EndEventScheduleService(config());
        var now = ZonedDateTime.of(2026, 8, 18, 17, 0, 0, 0, config().zoneId());
        assertEquals(DayOfWeek.TUESDAY, service.nextSlot(now).start().getDayOfWeek());
        assertEquals(18, service.nextSlot(now).start().getHour());
    }

    @Test
    void tuesdayDuringEventIsActive() {
        var service = new EndEventScheduleService(config());
        var now = ZonedDateTime.of(2026, 8, 18, 18, 30, 0, 0, config().zoneId());
        assertTrue(service.activeSlot(now).isPresent());
        assertEquals(20, service.activeSlot(now).orElseThrow().end().getHour());
    }

    @Test
    void saturdayFindsSunday() {
        var service = new EndEventScheduleService(config());
        var now = ZonedDateTime.of(2026, 8, 22, 12, 0, 0, 0, config().zoneId());
        assertEquals(DayOfWeek.SUNDAY, service.nextSlot(now).start().getDayOfWeek());
        assertEquals(17, service.nextSlot(now).start().getHour());
    }

    @Test
    void sundayAfterClosingFindsTuesday() {
        var service = new EndEventScheduleService(config());
        var now = ZonedDateTime.of(2026, 8, 23, 19, 1, 0, 0, config().zoneId());
        assertEquals(DayOfWeek.TUESDAY, service.nextSlot(now).start().getDayOfWeek());
    }
}
