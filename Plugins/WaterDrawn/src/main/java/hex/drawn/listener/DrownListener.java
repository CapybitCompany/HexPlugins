package hex.drawn.listener;

import hex.core.api.ui.UiService;
import hex.core.api.ui.UiTokens;
import hex.drawn.WaterDrawnPlugin;
import hex.drawn.config.WaterDrawnConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DrownListener implements Listener {

    private final WaterDrawnPlugin plugin;
    private WaterDrawnConfig config;
    private final Map<UUID, Integer> ticksInWater = new HashMap<>();
    private final Set<UUID> countdownStarted = new HashSet<>();
    private final Set<UUID> pendingDrownDeath = new HashSet<>();

    private BukkitTask checkTask;

    public DrownListener(WaterDrawnPlugin plugin, WaterDrawnConfig config) {
        this.plugin = plugin;
        this.config = config;
        startChecking();
    }

    public void updateConfig(WaterDrawnConfig config) {
        this.config = config;
        restartTask();
        countdownStarted.clear();
    }

    private void startChecking() {
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkPlayers, 20L, config.getCheckIntervalTicks());
    }

    private void restartTask() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        startChecking();
    }

    private void checkPlayers() {
        if (!config.isEnabled()) {
            ticksInWater.clear();
            countdownStarted.clear();
            return;
        }

        int killTicks = config.getDrownSeconds() * 20;
        int interval = config.getCheckIntervalTicks();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                clearPlayerState(uuid);
                continue;
            }

            if (!config.isDrowningActiveAt(player.getLocation())) {
                clearPlayerState(uuid);
                continue;
            }

            if (!isDangerousContact(player)) {
                clearPlayerState(uuid);
                continue;
            }

            int ticks = ticksInWater.getOrDefault(uuid, 0) + interval;
            ticksInWater.put(uuid, ticks);

            if (ticks <= config.getImmediateWarningTicks()) {
                sendActionBarMessage(player, "drawn.warning.immediate", new UiTokens());
            } else {
                if (!countdownStarted.contains(uuid)) {
                    countdownStarted.add(uuid);
                    player.playSound(player.getLocation(), config.getCountdownStartSound(),
                            config.getCountdownStartSoundVolume(), config.getCountdownStartSoundPitch());
                }

                int remainingTicks = Math.max(0, killTicks - ticks);
                int remainingSeconds = (int) Math.ceil(remainingTicks / 20.0D);
                sendActionBarMessage(player, "drawn.warning.countdown",
                        UiTokens.of("seconds", String.valueOf(remainingSeconds)));
            }

            if (ticks >= killTicks) {
                applyLethalDrownHit(player, uuid);
            }
        }

        cleanupOfflinePlayers();
    }

    private void applyLethalDrownHit(Player player, UUID uuid) {
        boolean hadTotemInHand = hasTotemInHand(player);
        pendingDrownDeath.add(uuid);

        // Lethal hit; if totem is in hand, Minecraft consumes it and player survives.
        player.damage(config.getDrownDamage());

        if (!player.isDead()) {
            // Survived (usually via totem): restart warning -> countdown cycle.
            pendingDrownDeath.remove(uuid);
            clearPlayerState(uuid);

            if (hadTotemInHand) {
                sendActionBarMessage(player, "drawn.warning.immediate", new UiTokens());
            }
        } else {
            clearPlayerState(uuid);
        }
    }

    private boolean hasTotemInHand(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    private boolean isDangerousContact(Player player) {
        // Zasada specjalna: gdy poziom wody tonięcia osiągnął poziom gracza,
        // poniżej progu wysokości też wystarcza sam dotyk.
        if (player.getLocation().getBlockY() <= config.getEffectiveDrownWaterLevelY()) {
            return player.isInWater();
        }

        int playerY = player.getLocation().getBlockY();
        if (playerY < config.getHeadSubmergeBelowY()) {
            return isHeadSubmerged(player);
        }

        return player.isInWater();
    }

    private boolean isHeadSubmerged(Player player) {
        Material eyeBlock = player.getEyeLocation().getBlock().getType();
        return eyeBlock == Material.WATER || eyeBlock == Material.BUBBLE_COLUMN;
    }

    private void clearPlayerState(UUID uuid) {
        ticksInWater.remove(uuid);
        countdownStarted.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clearPlayerState(uuid);
        pendingDrownDeath.remove(uuid);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();

        if (pendingDrownDeath.remove(uuid)) {
            UiService ui = plugin.ui();
            if (ui != null) {
                // UiService renderuje MiniMessage -> plain text dla death message
                net.kyori.adventure.text.Component comp = ui.render("drawn.death",
                        UiTokens.of("player", player.getName()));
                event.deathMessage(comp);
            } else {
                event.setDeathMessage(config.getDeathMessage().replace("%player%", player.getName()));
            }
        }

        clearPlayerState(uuid);
    }

    private void cleanupOfflinePlayers() {
        Iterator<UUID> it = ticksInWater.keySet().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            if (Bukkit.getPlayer(uuid) == null) {
                it.remove();
                countdownStarted.remove(uuid);
                pendingDrownDeath.remove(uuid);
            }
        }
    }

    public void shutdown() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        ticksInWater.clear();
        countdownStarted.clear();
        pendingDrownDeath.clear();
    }

    /**
     * Wysyla actionbar przez HexCore UiService jesli dostepny,
     * w przeciwnym razie fallback na stary config.
     */
    private void sendActionBarMessage(Player player, String templateKey, UiTokens tokens) {
        UiService ui = plugin.ui();
        if (ui != null) {
            ui.sendActionBar(player, templateKey, tokens);
        } else {
            // Fallback: stary styl gdy HexCore niedostepny (softdepend)
            String fallback = switch (templateKey) {
                case "drawn.warning.immediate" -> config.getWarningImmediateActionbar();
                case "drawn.warning.countdown" -> config.getWarningCountdownActionbar()
                        .replace("%seconds%", tokens.asMap().getOrDefault("seconds", "?"));
                default -> "";
            };
            player.sendActionBar(net.kyori.adventure.text.Component.text(fallback));
        }
    }
}
