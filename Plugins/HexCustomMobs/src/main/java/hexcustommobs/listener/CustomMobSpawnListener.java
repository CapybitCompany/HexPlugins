package hexcustommobs.listener;

import hexcustommobs.config.HexCustomMobsConfig;
import hexcustommobs.service.CustomMobService;
import org.bukkit.block.Biome;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class CustomMobSpawnListener implements Listener {

    private final Plugin plugin;
    private final Supplier<HexCustomMobsConfig> configSupplier;
    private final CustomMobService customMobService;

    public CustomMobSpawnListener(
            Plugin plugin,
            Supplier<HexCustomMobsConfig> configSupplier,
            CustomMobService customMobService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.customMobService = Objects.requireNonNull(customMobService, "customMobService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        HexCustomMobsConfig config = configSupplier.get();
        if (!config.enabled()) {
            return;
        }
        if (!config.spawn().handledReasons().contains(event.getSpawnReason())) {
            return;
        }

        LivingEntity vanillaSpawn = event.getEntity();
        if (customMobService.isCustomMob(vanillaSpawn)) {
            return;
        }

        Biome biome = vanillaSpawn.getLocation().getBlock().getBiome();
        String worldName = vanillaSpawn.getWorld().getName();

        Optional<HexCustomMobsConfig.BiomeRule> ruleOptional = config.findRule(worldName, biome);
        if (ruleOptional.isEmpty()) {
            if (config.spawn().cancelVanillaOnRuleMiss()) {
                event.setCancelled(true);
            }
            debug("No rule for biome " + biome + " in world " + worldName);
            return;
        }

        HexCustomMobsConfig.BiomeRule rule = ruleOptional.get();
        if (ThreadLocalRandom.current().nextDouble() > rule.spawnChance()) {
            debug("Rule '" + rule.id() + "' chance miss");
            return;
        }

        if (customMobService.exceedsChunkLimit(vanillaSpawn.getLocation())) {
            debug("Chunk limit reached at " + vanillaSpawn.getLocation());
            return;
        }

        String mobId = customMobService.pickMobId(rule);
        if (mobId == null) {
            if (config.spawn().cancelVanillaOnRuleMiss()) {
                event.setCancelled(true);
            }
            debug("Rule '" + rule.id() + "' has no valid mob candidates");
            return;
        }

        HexCustomMobsConfig.MobTemplate template = customMobService.getTemplate(mobId);
        if (template == null) {
            return;
        }

        if (rule.replaceVanilla()) {
            event.setCancelled(true);
            LivingEntity custom = customMobService.spawnCustomMob(vanillaSpawn.getLocation(), mobId);
            debug("Replaced vanilla " + vanillaSpawn.getType() + " with " + mobId + " -> " + (custom != null));
            return;
        }

        if (template.type() == vanillaSpawn.getType()) {
            customMobService.applyTemplate(vanillaSpawn, mobId, template);
            debug("Applied template " + mobId + " on existing spawn " + vanillaSpawn.getType());
        } else {
            debug("Rule '" + rule.id() + "' not replacing vanilla and type mismatch (" + template.type() + " vs " + vanillaSpawn.getType() + ")");
        }
    }

    private void debug(String message) {
        if (!configSupplier.get().debug()) {
            return;
        }
        plugin.getLogger().info("[debug] " + message);
    }
}
