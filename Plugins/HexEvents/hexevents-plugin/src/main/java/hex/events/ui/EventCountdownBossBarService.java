package hex.events.ui;

import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;
import hex.events.util.CountdownBossBarText;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Global countdown boss bars shown to online players before an event's public
 * scheduled start (occurrenceAt). Only active countdown bars are ticked, so the
 * cost is independent of the 30-day compiled event calendar.
 */
public final class EventCountdownBossBarService {
    private final Plugin plugin;
    private final Clock clock;
    private final Map<UUID, ActiveBar> active = new HashMap<>();
    private BukkitTask task;

    public EventCountdownBossBarService(Plugin plugin, Clock clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    public void show(EventInstance instance) {
        EventDefinition.BossBarPolicy policy = instance.definition().bossBar();
        if (!policy.enabled() || instance.state().terminal()) return;
        Instant now = clock.instant();
        Instant publicStart = instance.occurrenceAt();
        if (!now.isBefore(publicStart)) { hide(instance.id()); return; }
        if (now.isBefore(publicStart.minus(policy.showBefore()))) return;

        ActiveBar existing = active.get(instance.id());
        if (existing != null && existing.instance() == instance && existing.policy().equals(policy)) {
            update(existing, now);
            return;
        }
        if (existing != null) hide(instance.id());

        BossBar bar = Bukkit.createBossBar("", color(policy.color()), style(policy.style()));
        ActiveBar created = new ActiveBar(instance, policy, bar, new HashSet<>());
        active.put(instance.id(), created);
        update(created, now);
        ensureTask();
    }

    public void hide(UUID instanceId) {
        ActiveBar entry = active.remove(instanceId);
        if (entry == null) return;
        entry.bar().removeAll();
        entry.viewers().clear();
        stopTaskIfIdle();
    }

    public void hideAll() {
        for (ActiveBar bar : active.values()) bar.bar().removeAll();
        active.clear();
        if (task != null) { task.cancel(); task = null; }
    }

    public int activeCount() { return active.size(); }

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void stopTaskIfIdle() {
        if (active.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        Instant now = clock.instant();
        for (UUID instanceId : Set.copyOf(active.keySet())) {
            ActiveBar entry = active.get(instanceId);
            if (entry == null) continue;
            if (entry.instance().state().terminal() || !now.isBefore(entry.instance().occurrenceAt())) {
                hide(instanceId);
                continue;
            }
            update(entry, now);
        }
    }

    private void update(ActiveBar entry, Instant now) {
        EventInstance instance = entry.instance();
        EventDefinition def = instance.definition();
        Duration remaining = Duration.between(now, instance.occurrenceAt());
        if (remaining.isNegative() || remaining.isZero()) { hide(instance.id()); return; }

        String title = CountdownBossBarText.render(entry.policy().title(), def.id(), def.displayName(),
                instance.occurrenceAt(), def.schedule().zoneId(), remaining);
        entry.bar().setTitle(ChatColor.translateAlternateColorCodes('&', title));
        entry.bar().setProgress(CountdownBossBarText.progress(remaining, entry.policy().showBefore()));

        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            if (entry.viewers().add(playerId)) entry.bar().addPlayer(player);
        }
        for (UUID viewerId : Set.copyOf(entry.viewers())) {
            if (online.contains(viewerId)) continue;
            Player stale = Bukkit.getPlayer(viewerId);
            if (stale != null) entry.bar().removePlayer(stale);
            entry.viewers().remove(viewerId);
        }
    }

    private static BarColor color(String raw) {
        try { return BarColor.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return BarColor.YELLOW; }
    }

    private static BarStyle style(String raw) {
        try { return BarStyle.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return BarStyle.SOLID; }
    }

    private record ActiveBar(EventInstance instance, EventDefinition.BossBarPolicy policy, BossBar bar, Set<UUID> viewers) { }
}
