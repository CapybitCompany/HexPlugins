package hex.endevent.ui;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.endevent.config.EndEventConfig;
import hex.endevent.model.EndEventSlot;
import hex.endevent.util.TimeTextFormatter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class EndEventBossBarService {
    private final HexApi hex;
    private volatile EndEventConfig config;
    private BossBar bossBar;
    private EndEventSlot activeSlot;
    private final Set<UUID> viewers = new HashSet<>();

    public EndEventBossBarService(HexApi hex, EndEventConfig config) {
        this.hex = hex;
        this.config = config;
    }

    public void reload(EndEventConfig config) {
        hideAll();
        this.config = config;
    }

    public void start(EndEventSlot slot) {
        this.activeSlot = slot;
        if (!config.bossBar().enabled()) return;
        this.bossBar = BossBar.bossBar(Component.empty(), 1.0f, color(config.bossBar().color()), overlay(config.bossBar().overlay()));
        tick(ZonedDateTime.now(config.zoneId()));
    }

    public void tick(ZonedDateTime now) {
        if (!config.bossBar().enabled() || activeSlot == null || bossBar == null) return;
        Duration total = Duration.between(activeSlot.start(), activeSlot.end());
        Duration remaining = Duration.between(now, activeSlot.end());
        long totalMillis = Math.max(1L, total.toMillis());
        long remainingMillis = Math.max(0L, remaining.toMillis());
        float progress = (float) Math.max(0.0, Math.min(1.0, remainingMillis / (double) totalMillis));
        bossBar.progress(progress);
        bossBar.name(hex.ui().render("endevent.bossbar.active",
                UiTokens.of("remaining", TimeTextFormatter.duration(remaining))
                        .put("closes", TimeTextFormatter.time(activeSlot.end()))));

        Set<UUID> desired = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == World.Environment.THE_END) desired.add(player.getUniqueId());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (desired.contains(id) && viewers.add(id)) player.showBossBar(bossBar);
            else if (!desired.contains(id) && viewers.remove(id)) player.hideBossBar(bossBar);
        }
        viewers.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    public void refreshPlayer(Player player) {
        if (bossBar == null || activeSlot == null || !config.bossBar().enabled()) return;
        boolean shouldSee = player.getWorld().getEnvironment() == World.Environment.THE_END;
        UUID id = player.getUniqueId();
        if (shouldSee && viewers.add(id)) player.showBossBar(bossBar);
        if (!shouldSee && viewers.remove(id)) player.hideBossBar(bossBar);
    }

    public void hide(Player player) {
        if (bossBar != null && viewers.remove(player.getUniqueId())) player.hideBossBar(bossBar);
    }

    public void hideAll() {
        if (bossBar != null) {
            for (UUID id : Set.copyOf(viewers)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) player.hideBossBar(bossBar);
            }
        }
        viewers.clear();
        bossBar = null;
        activeSlot = null;
    }

    private static BossBar.Color color(String raw) {
        try { return BossBar.Color.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return BossBar.Color.PURPLE; }
    }

    private static BossBar.Overlay overlay(String raw) {
        try { return BossBar.Overlay.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return BossBar.Overlay.PROGRESS; }
    }
}
