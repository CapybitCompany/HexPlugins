package pl.hexnetwork.hexnametags;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.hexnetwork.hexnametags.api.HexNameTagsApi;
import pl.hexnetwork.hexnametags.model.NameTagStyle;
import pl.hexnetwork.hexnametags.model.RenderedTag;
import pl.hexnetwork.hexnametags.model.TargetTag;
import pl.hexnetwork.hexnametags.packet.PacketNameTagService;
import pl.hexnetwork.hexnametags.persistence.NameTagPersistenceService;
import pl.hexnetwork.hexnametags.persistence.PersistedNameTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NameTagManager implements HexNameTagsApi {
    private final JavaPlugin plugin;
    private final PacketNameTagService packetService;
    private final NameTagPersistenceService persistenceService;

    private final Map<UUID, TargetTag> targetTags = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, RenderedTag>> renderedByViewer = new ConcurrentHashMap<>();

    private NameTagStyle defaultStyle = NameTagStyle.defaults();
    private long visibilityRefreshIntervalTicks = 10L;
    private long movementUpdateIntervalTicks = 2L;
    private long passengerReassertIntervalTicks = 0L;
    private int teleportDurationTicks = 2;
    private double viewDistance = 48.0D;
    private double minMoveDistanceSquared = 0.0004D;
    private boolean showOwnTag = true;
    private boolean mountAsPassenger = false;
    private long tickCounter = 0L;
    private BukkitTask task;

    public NameTagManager(JavaPlugin plugin, NameTagPersistenceService persistenceService) {
        this.plugin = plugin;
        this.persistenceService = persistenceService;
        this.packetService = new PacketNameTagService(plugin);
    }

    public void reloadSettings() {
        plugin.reloadConfig();
        this.defaultStyle = NameTagStyle.fromConfig(plugin.getConfig().getConfigurationSection("style"));
        this.visibilityRefreshIntervalTicks = Math.max(1L, plugin.getConfig().getLong("refresh-interval-ticks", 10L));
        this.viewDistance = Math.max(4.0D, plugin.getConfig().getDouble("view-distance", 48.0D));
        this.showOwnTag = plugin.getConfig().getBoolean("show-own-tag", true);

        ConfigurationSection rendering = plugin.getConfig().getConfigurationSection("rendering");
        String mode = rendering == null ? "interpolated-follow" : rendering.getString("mode", "interpolated-follow");
        if (rendering != null
                && "packet-text-display-passenger".equalsIgnoreCase(mode)
                && !rendering.contains("movement-update-interval-ticks")) {
            plugin.getLogger().warning("Detected legacy 1.2.2 rendering config. Forcing mode=interpolated-follow; regenerate or update config.yml.");
            mode = "interpolated-follow";
        }
        this.mountAsPassenger = mode != null && (mode.equalsIgnoreCase("packet-text-display-passenger") || mode.equalsIgnoreCase("passenger"));
        this.movementUpdateIntervalTicks = Math.max(1L, rendering == null ? 2L : rendering.getLong("movement-update-interval-ticks", 2L));
        this.teleportDurationTicks = Math.max(0, Math.min(59, rendering == null ? 2 : rendering.getInt("teleport-duration-ticks", 2)));
        this.passengerReassertIntervalTicks = Math.max(0L, rendering == null ? 0L : rendering.getLong("reassert-passenger-interval-ticks", 0L));
        double minMoveDistance = Math.max(0.0D, rendering == null ? 0.02D : rendering.getDouble("min-move-distance", 0.02D));
        this.minMoveDistanceSquared = minMoveDistance * minMoveDistance;

        this.persistenceService.reloadSettings();
        applyDefaultStyleToActiveTags();

        // Config reload can switch rendering mode or movement parameters. Existing fake entities were
        // created with the previous mode, so respawn them cleanly instead of trying to mutate them in place.
        clearAllRendered();
    }

    public void start() {
        stopTaskOnly();
        this.tickCounter = 0L;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        if (persistenceService.shouldLoadOnlineOnStart()) {
            Bukkit.getScheduler().runTaskLater(plugin, this::loadPersistentTagsForOnlinePlayers, 20L);
        }
    }

    public void stop() {
        stopTaskOnly();
        clearAllRendered();
        targetTags.clear();
    }

    public void removeViewer(Player viewer) {
        Map<UUID, RenderedTag> rendered = renderedByViewer.remove(viewer.getUniqueId());
        if (rendered == null) {
            return;
        }
        for (Map.Entry<UUID, RenderedTag> entry : rendered.entrySet()) {
            Entity target = Bukkit.getEntity(entry.getKey());
            packetService.destroy(viewer, target, entry.getValue());
        }
    }

    public void unloadTarget(Entity target) {
        if (target == null) {
            return;
        }
        UUID targetUuid = target.getUniqueId();
        targetTags.remove(targetUuid);
        removeRenderedTarget(targetUuid);
    }

    public void refreshViewer(Player viewer) {
        Map<UUID, RenderedTag> rendered = renderedByViewer.get(viewer.getUniqueId());
        if (rendered == null) {
            return;
        }
        for (Map.Entry<UUID, RenderedTag> entry : new ArrayList<>(rendered.entrySet())) {
            Entity target = Bukkit.getEntity(entry.getKey());
            packetService.destroy(viewer, target, entry.getValue());
        }
        rendered.clear();
    }

    public void loadPersistentTagsForOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPersistentPlayerTag(player);
        }
    }

    public void loadPersistentPlayerTag(Player player) {
        if (player == null || !player.isOnline() || !persistenceService.isAvailable()) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        persistenceService.loadPlayer(playerUuid).thenAccept(optional -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player current = Bukkit.getPlayer(playerUuid);
            if (current == null || !current.isOnline()) {
                return;
            }
            optional.ifPresent(tag -> applyPersistentPlayerTag(current, tag));
        }));
    }

    public boolean isShowOwnTag() {
        return showOwnTag;
    }

    public java.util.Optional<List<Component>> getTagLines(Entity target) {
        if (target == null) {
            return java.util.Optional.empty();
        }
        TargetTag targetTag = targetTags.get(target.getUniqueId());
        if (targetTag == null || targetTag.lines().isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(List.copyOf(targetTag.lines()));
    }

    public void refreshNow() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::refreshNow);
            return;
        }
        cleanupDeadTargets();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            updateViewer(viewer, true, true, mountAsPassenger && passengerReassertIntervalTicks > 0L);
        }
    }

    public void refreshTargetNow(Entity target) {
        if (target == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshTargetNow(target));
            return;
        }
        UUID targetUuid = target.getUniqueId();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Map<UUID, RenderedTag> rendered = renderedByViewer.get(viewer.getUniqueId());
            if (rendered == null) {
                continue;
            }
            RenderedTag removed = rendered.remove(targetUuid);
            if (removed != null) {
                packetService.destroy(viewer, target, removed);
            }
        }
        refreshNow();
    }

    @Override
    public void setPlayerTag(Player target, List<Component> lines) {
        setEntityTag(target, lines);
    }

    @Override
    public void setEntityTag(Entity target, List<Component> lines) {
        if (target == null || lines == null || lines.isEmpty()) {
            return;
        }
        targetTags.put(target.getUniqueId(), new TargetTag(target, List.copyOf(lines), defaultStyle));
        if (target instanceof Player player) {
            persistenceService.savePlayer(player.getUniqueId(), lines);
        }
        refreshTargetNow(target);
    }

    public void setTemporaryPlayerTag(Player target, List<Component> lines) {
        setTemporaryEntityTag(target, lines, defaultStyle);
    }

    public void setTemporaryEntityTag(Entity target, List<Component> lines, NameTagStyle style) {
        if (target == null || lines == null || lines.isEmpty()) {
            return;
        }
        targetTags.put(target.getUniqueId(), new TargetTag(target, List.copyOf(lines), style));
        refreshTargetNow(target);
    }

    public void setEntityTag(Entity target, List<Component> lines, NameTagStyle style) {
        if (target == null || lines == null || lines.isEmpty()) {
            return;
        }
        targetTags.put(target.getUniqueId(), new TargetTag(target, List.copyOf(lines), style));
        if (target instanceof Player player) {
            persistenceService.savePlayer(player.getUniqueId(), lines);
        }
        refreshTargetNow(target);
    }

    @Override
    public void clearTag(Entity target) {
        if (target == null) {
            return;
        }
        UUID targetUuid = target.getUniqueId();
        targetTags.remove(targetUuid);
        if (target instanceof Player) {
            persistenceService.deletePlayer(targetUuid);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Map<UUID, RenderedTag> rendered = renderedByViewer.get(viewer.getUniqueId());
            if (rendered == null) {
                continue;
            }
            RenderedTag removed = rendered.remove(targetUuid);
            if (removed != null) {
                packetService.destroy(viewer, target, removed);
            }
        }
    }

    @Override
    public boolean hasTag(Entity target) {
        return target != null && targetTags.containsKey(target.getUniqueId());
    }

    private void applyPersistentPlayerTag(Player player, PersistedNameTag tag) {
        if (tag == null || tag.lines().isEmpty()) {
            return;
        }
        targetTags.put(player.getUniqueId(), new TargetTag(player, tag.lines(), defaultStyle));
        refreshTargetNow(player);
    }

    private void tick() {
        tickCounter++;

        boolean visibilityTick = tickCounter % visibilityRefreshIntervalTicks == 0L;
        boolean movementTick = !mountAsPassenger && tickCounter % movementUpdateIntervalTicks == 0L;
        boolean reassertMountTick = mountAsPassenger
                && passengerReassertIntervalTicks > 0L
                && tickCounter % passengerReassertIntervalTicks == 0L;

        if (!visibilityTick && !movementTick && !reassertMountTick) {
            return;
        }

        if (visibilityTick) {
            cleanupDeadTargets();
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            updateViewer(viewer, visibilityTick, movementTick, reassertMountTick);
        }
    }

    private void applyDefaultStyleToActiveTags() {
        for (Map.Entry<UUID, TargetTag> entry : targetTags.entrySet()) {
            Entity target = Bukkit.getEntity(entry.getKey());
            if (target != null && target.isValid()) {
                targetTags.put(entry.getKey(), new TargetTag(target, entry.getValue().lines(), defaultStyle));
            }
        }
    }

    private void updateViewer(Player viewer, boolean updateVisibility, boolean updateMovement, boolean reassertMount) {
        Map<UUID, RenderedTag> rendered = renderedByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashMap<>());

        if (updateVisibility) {
            updateViewerVisibilityAndText(viewer, rendered);
        }

        if (updateMovement || reassertMount) {
            updateViewerMovement(viewer, rendered, updateMovement, reassertMount);
        }
    }

    private void updateViewerVisibilityAndText(Player viewer, Map<UUID, RenderedTag> rendered) {
        for (TargetTag targetTag : targetTags.values()) {
            Entity target = Bukkit.getEntity(targetTag.targetUuid());
            if (!shouldRender(viewer, target)) {
                RenderedTag existing = rendered.remove(targetTag.targetUuid());
                if (existing != null) {
                    packetService.destroy(viewer, target, existing);
                }
                continue;
            }

            RenderedTag existing = rendered.get(targetTag.targetUuid());
            String signature = packetService.signature(targetTag.lines(), targetTag.style());

            if (existing == null) {
                RenderedTag spawned = packetService.spawn(
                        viewer,
                        target,
                        targetTag.lines(),
                        targetTag.style(),
                        mountAsPassenger,
                        teleportDurationTicks
                );
                rendered.put(targetTag.targetUuid(), spawned);
                continue;
            }

            if (!existing.signature().equals(signature)) {
                packetService.updateText(viewer, existing.entityId(), targetTag.lines(), targetTag.style(), teleportDurationTicks);
                existing.signature(signature);
            }
        }

        Iterator<Map.Entry<UUID, RenderedTag>> iterator = rendered.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RenderedTag> entry = iterator.next();
            if (!targetTags.containsKey(entry.getKey())) {
                Entity target = Bukkit.getEntity(entry.getKey());
                packetService.destroy(viewer, target, entry.getValue());
                iterator.remove();
            }
        }
    }

    private void updateViewerMovement(Player viewer, Map<UUID, RenderedTag> rendered, boolean updateMovement, boolean reassertMount) {
        Iterator<Map.Entry<UUID, RenderedTag>> iterator = rendered.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RenderedTag> entry = iterator.next();
            TargetTag targetTag = targetTags.get(entry.getKey());
            Entity target = Bukkit.getEntity(entry.getKey());
            if (targetTag == null || !shouldRender(viewer, target)) {
                packetService.destroy(viewer, target, entry.getValue());
                iterator.remove();
                continue;
            }

            RenderedTag renderedTag = entry.getValue();
            if (updateMovement && !renderedTag.mounted()) {
                packetService.follow(viewer, target, renderedTag, minMoveDistanceSquared);
            }
            if (reassertMount && renderedTag.mounted()) {
                packetService.refreshMount(viewer, target, renderedTag.entityId());
            }
        }
    }

    private boolean shouldRender(Player viewer, Entity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (!showOwnTag && viewer.getUniqueId().equals(target.getUniqueId())) {
            return false;
        }
        World viewerWorld = viewer.getWorld();
        if (!viewerWorld.equals(target.getWorld())) {
            return false;
        }
        return viewer.getLocation().distanceSquared(target.getLocation()) <= viewDistance * viewDistance;
    }

    private void cleanupDeadTargets() {
        Iterator<Map.Entry<UUID, TargetTag>> iterator = targetTags.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TargetTag> entry = iterator.next();
            Entity target = Bukkit.getEntity(entry.getKey());
            if (target == null || !target.isValid() || target.isDead()) {
                removeRenderedTarget(entry.getKey());
                iterator.remove();
            }
        }
    }

    private void removeRenderedTarget(UUID targetUuid) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Map<UUID, RenderedTag> rendered = renderedByViewer.get(viewer.getUniqueId());
            if (rendered == null) {
                continue;
            }
            RenderedTag removed = rendered.remove(targetUuid);
            if (removed != null) {
                packetService.destroy(viewer, null, removed);
            }
        }
    }

    private void clearAllRendered() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            removeViewer(viewer);
        }
        renderedByViewer.clear();
    }

    private void stopTaskOnly() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
