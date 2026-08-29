package hex.events.hardening;

import hex.events.util.CountdownBossBarText;
import hex.events.api.EventModuleSettings;
import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;
import hex.events.schedule.EventOccurrenceCompiler;
import hex.events.schedule.ScheduledTransition;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public final class CountdownBossBarLogicTest {
    public static void main(String[] args) {
        assert CountdownBossBarText.progress(Duration.ofMinutes(30), Duration.ofMinutes(30)) == 1.0;
        assert Math.abs(CountdownBossBarText.progress(Duration.ofMinutes(15), Duration.ofMinutes(30)) - 0.5) < 0.0001;
        assert CountdownBossBarText.progress(Duration.ZERO, Duration.ofMinutes(30)) == 0.0;
        assert CountdownBossBarText.progress(Duration.ofMinutes(60), Duration.ofMinutes(30)) == 1.0;

        Instant start = Instant.parse("2026-08-29T18:00:00Z");
        String rendered = CountdownBossBarText.render("{event}|{event_id}|{time}|{minutes}|{seconds}|{start_time}|{start_date}",
                "boss", "&cBoss", start, ZoneId.of("Europe/Warsaw"), Duration.ofSeconds(125));
        if (!rendered.equals("&cBoss|boss|2 min 05 s|3|125|20:00|29.08.2026"))
            throw new AssertionError(rendered);

        EventDefinition def = new EventDefinition(
                "countdown_test", true, "Countdown", "", "CLOCK", "hex:test", EventModuleSettings.empty(),
                new EventDefinition.Schedule(ZoneId.of("UTC"), java.util.List.of(new EventDefinition.WeeklySlot(DayOfWeek.SATURDAY, LocalTime.of(18, 0)))),
                Duration.ofMinutes(30), Duration.ZERO,
                new EventDefinition.RegistrationPolicy(EventDefinition.RegistrationMode.DISABLED, Duration.ZERO, EventDefinition.CancelUntil.START),
                new EventDefinition.LobbyPolicy(false, Duration.ZERO),
                new EventDefinition.CapacityPolicy(0, 0, EventDefinition.TooFewPolicy.START_ANYWAY),
                new EventDefinition.JoinPolicy(false, false, Duration.ZERO, EventDefinition.LateJoinScope.REGISTERED_ONLY, true),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        if (!def.bossBar().enabled() || !def.bossBar().showBefore().equals(Duration.ofMinutes(30)))
            throw new AssertionError("default bossbar policy must be enabled/30m");
        EventInstance occurrence = new EventOccurrenceCompiler().compileBetween(def, start.minus(Duration.ofDays(1)), start.plus(Duration.ofDays(1))).stream().findFirst().orElseThrow();
        ScheduledTransition show = new EventOccurrenceCompiler().transitions(occurrence).stream()
                .filter(t -> t.type() == ScheduledTransition.Type.BOSSBAR_SHOW).findFirst().orElseThrow();
        if (!show.at().equals(occurrence.occurrenceAt().minus(Duration.ofMinutes(30))))
            throw new AssertionError("bossbar transition time mismatch");

        System.out.println("CountdownBossBarLogicTest OK");
    }
}
