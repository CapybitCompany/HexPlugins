package hexnpc.service;

import hexnpc.config.HexNpcConfig;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class NpcProximityService {

    private final Plugin plugin;
    private final NpcService npcService;
    private final NpcInteractionService interactionService;
    private final Supplier<HexNpcConfig> configSupplier;
    private final Map<UUID, Map<NpcId, Long>> lastFire = new HashMap<>();
    private BukkitTask task;

    public NpcProximityService(Plugin plugin,
                               NpcService npcService,
                               NpcInteractionService interactionService,
                               Supplier<HexNpcConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.npcService = Objects.requireNonNull(npcService, "npcService");
        this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void start() {
        stop();
        int interval = configSupplier.get().proximity().scanIntervalTicks();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scan, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastFire.clear();
    }

    public void onPlayerQuit(UUID playerId) {
        lastFire.remove(playerId);
    }

    private void scan() {
        HexNpcConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            return;
        }
        long now = plugin.getServer().getCurrentTick();
        double defaultRadius = config.proximity().defaultRadius();
        int defaultCooldown = config.proximity().defaultCooldownTicks();
        for (NpcDefinition npc : npcService.list()) {
            if (!npc.interaction().proximityEnabled()) {
                continue;
            }
            Location npcLocation = npc.location().toBukkit();
            if (npcLocation == null) {
                continue;
            }
            World world = npcLocation.getWorld();
            if (world == null) {
                continue;
            }
            double radius = npc.interaction().effectiveRadius(defaultRadius);
            double radiusSquared = radius * radius;
            int cooldown = npc.interaction().effectiveCooldownTicks(defaultCooldown);

            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(npcLocation) > radiusSquared) {
                    continue;
                }
                if (cooldown > 0) {
                    Map<NpcId, Long> playerMap = lastFire.get(player.getUniqueId());
                    if (playerMap != null) {
                        Long last = playerMap.get(npc.id());
                        if (last != null && (now - last) < cooldown) {
                            continue;
                        }
                    }
                    lastFire.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>())
                            .put(npc.id(), now);
                }
                interactionService.trigger(player, npc, InteractionTrigger.PROXIMITY);
            }
        }
    }
}
