package hex.events.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;

public record EngineConfig(
        ZoneId displayZone,
        int calendarDaysAhead,
        long schedulerPeriodTicks,
        long calendarMaintenanceTicks,
        boolean hideUnavailableEvents,
        boolean debug
) {
    public static EngineConfig load(FileConfiguration config) {
        ZoneId zone = ZoneId.of(config.getString("engine.display-timezone", "Europe/Warsaw"));
        int days = Math.max(7, Math.min(60, config.getInt("engine.calendar-days-ahead", 30)));
        long period = Math.max(1L, config.getLong("engine.scheduler-period-ticks", 20L));
        long maintenanceMinutes = Math.max(5L, config.getLong("engine.calendar-maintenance-minutes", 360L));
        long maintenanceTicks = maintenanceMinutes * 60L * 20L;
        return new EngineConfig(zone, days, period, maintenanceTicks,
                config.getBoolean("engine.hide-unavailable-events", false),
                config.getBoolean("engine.debug", false));
    }
}
