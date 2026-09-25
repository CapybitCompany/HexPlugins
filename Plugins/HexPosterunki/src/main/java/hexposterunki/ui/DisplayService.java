package hexposterunki.ui;

import hex.core.api.ui.UiTokens;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.mobs.RunTags;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Remote and local presentation of the active outpost.
 *
 * <p>Split on purpose:
 * <ul>
 *   <li>the boss bar is the long-range display - it reaches every player in the same world inside
 *       the configured radius (20.000 blocks by default) and carries name, status and distance;</li>
 *   <li>the {@link TextDisplay} hologram is a real entity at the outpost centre with a limited view
 *       range, so nothing is ever simulated over 20.000 blocks and no remote chunk is loaded.</li>
 * </ul>
 */
public final class DisplayService {

    private final PosterunkiUi ui;
    private final RunTags tags;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private UUID hologramId;

    public DisplayService(PosterunkiUi ui, RunTags tags) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    /** Main thread. Shows/updates the boss bar for everyone in range and hides it for the rest. */
    public void updateBossBar(OutpostDefinition outpost, String status, PosterunkiConfig.Ui settings) {
        if (outpost == null || !settings.bossbarEnabled()) {
            clearBossBars();
            return;
        }
        double maxSquared = settings.bossbarMaxDistanceSquared();
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean sameWorld = player.getWorld().getName().equals(outpost.world());
            double distanceSquared = sameWorld
                    ? outpost.region().horizontalDistanceSquared(player.getLocation().getX(), player.getLocation().getZ())
                    : Double.MAX_VALUE;
            if (!sameWorld || distanceSquared > maxSquared) {
                hide(player);
                continue;
            }
            long distance = Math.round(Math.sqrt(distanceSquared));
            Component text = ui.render(UiKeys.BOSSBAR_TEXT, UiTokens.of("name", outpost.displayName())
                    .put("status", status)
                    .put("distance", String.valueOf(distance)));
            BossBar bar = bars.get(player.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(text, 1.0F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                bars.put(player.getUniqueId(), bar);
                player.showBossBar(bar);
            } else {
                bar.name(text);
            }
        }
    }

    /** Sets the boss bar progress, e.g. remaining mobs of the current wave. */
    public void setBossBarProgress(float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        bars.values().forEach(bar -> bar.progress(clamped));
    }

    public void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    public void clearBossBars() {
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        bars.clear();
    }

    /**
     * Main thread. Creates or updates the hologram entity. It is only created when the centre chunk
     * is already loaded - the plugin holds chunk tickets for the active outpost, so this never
     * forces a remote chunk to load.
     */
    public void updateHologram(OutpostDefinition outpost, String runId, String status, String town,
                               PosterunkiConfig.Ui settings) {
        if (outpost == null || !settings.hologramEnabled()) {
            removeHologram();
            return;
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            return;
        }
        Location location = new Location(world, outpost.center().x(), outpost.center().y(), outpost.center().z());
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return;
        }

        Component text = ui.render(UiKeys.HOLOGRAM_TEXT, UiTokens.of("name", outpost.displayName())
                .put("status", status)
                .put("town", town));

        TextDisplay display = resolveHologram(world);
        if (display == null) {
            display = world.spawn(location, TextDisplay.class, spawned -> {
                spawned.setBillboard(Display.Billboard.CENTER);
                spawned.setViewRange(settings.hologramViewRange());
                spawned.setPersistent(false);
                tags.tagDisplay(spawned, runId, outpost.id());
            });
            hologramId = display.getUniqueId();
        }
        display.setViewRange(settings.hologramViewRange());
        display.text(text);
    }

    public void removeHologram() {
        if (hologramId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(hologramId);
        if (entity != null) {
            entity.remove();
        }
        hologramId = null;
    }

    /** Removes every temporary display entity this plugin ever left behind in the loaded worlds. */
    public int removeStaleDisplays() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (tags.isDisplay(entity)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        hologramId = null;
        return removed;
    }

    private TextDisplay resolveHologram(World world) {
        if (hologramId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(hologramId);
        if (entity instanceof TextDisplay display && display.isValid()
                && display.getWorld().equals(world)) {
            return display;
        }
        hologramId = null;
        return null;
    }
}
