package hexnpc.service;

import hexnpc.config.HexNpcConfig;
import hexnpc.model.Dialogue;
import hexnpc.model.DialogueLine;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.util.LegacyFormat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class DialogueService {

    private final Plugin plugin;
    private final Supplier<HexNpcConfig> configSupplier;
    private final Map<UUID, Map<NpcId, Long>> lastTriggerTick = new HashMap<>();

    public DialogueService(Plugin plugin, Supplier<HexNpcConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public boolean isOnCooldown(Player player, NpcDefinition npc) {
        int cooldown = effectiveCooldown(npc.dialogue());
        if (cooldown <= 0) {
            return false;
        }
        Map<NpcId, Long> playerMap = lastTriggerTick.get(player.getUniqueId());
        if (playerMap == null) {
            return false;
        }
        Long last = playerMap.get(npc.id());
        if (last == null) {
            return false;
        }
        return (currentTick() - last) < cooldown;
    }

    /**
     * Spielt den Dialog für {@code player} ab und ruft danach {@code afterAll}.
     *
     * <p>Wichtig: {@code afterAll} bekommt explizit den im Moment der Ausführung
     * <em>aktuell verbundenen</em> Player. Beim sofortigen Pfad (kein Dialog) ist das
     * der übergebene Spieler. Beim verzögerten Pfad lösen wir den Spieler aus der
     * UUID neu auf und überspringen den Callback, falls er offline ging — damit
     * keine Console-/Player-Commands oder Shops auf einen abgemeldeten Spieler
     * losgelassen werden.</p>
     */
    public void speak(Player player, NpcDefinition npc, Consumer<Player> afterAll) {
        Dialogue dialogue = npc.dialogue();
        if (!dialogue.hasLines()) {
            if (afterAll != null && player != null && player.isOnline()) {
                afterAll.accept(player);
            }
            return;
        }
        if (isOnCooldown(player, npc)) {
            return;
        }
        recordTrigger(player, npc);

        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        UUID playerId = player.getUniqueId();
        long offset = 0L;
        int defaultDelay = configSupplier.get().dialogue().defaultLineDelayTicks();

        for (int i = 0; i < dialogue.lines().size(); i++) {
            DialogueLine line = dialogue.lines().get(i);
            long delay = i == 0
                    ? line.delayTicks()
                    : (line.delayTicks() > 0 ? line.delayTicks() : defaultDelay);
            offset += delay;
            final String text = line.text();
            scheduler.runTaskLater(plugin, () -> {
                Player current = plugin.getServer().getPlayer(playerId);
                if (current == null || !current.isOnline()) {
                    return;
                }
                String prefix = configSupplier.get().dialogue().prefix();
                String rendered = LegacyFormat.replace(prefix + text, "<player>", current.getName());
                current.sendMessage(LegacyFormat.component(rendered));
            }, offset);
        }
        if (afterAll != null) {
            scheduler.runTaskLater(plugin, () -> {
                Player current = plugin.getServer().getPlayer(playerId);
                if (current == null || !current.isOnline()) {
                    return;
                }
                afterAll.accept(current);
            }, offset);
        }
    }

    public void onPlayerQuit(UUID playerId) {
        lastTriggerTick.remove(playerId);
    }

    public void clearAll() {
        lastTriggerTick.clear();
    }

    private void recordTrigger(Player player, NpcDefinition npc) {
        lastTriggerTick
                .computeIfAbsent(player.getUniqueId(), id -> new HashMap<>())
                .put(npc.id(), currentTick());
    }

    private int effectiveCooldown(Dialogue dialogue) {
        if (dialogue.cooldownTicks() > 0) {
            return dialogue.cooldownTicks();
        }
        return configSupplier.get().dialogue().defaultCooldownTicks();
    }

    private long currentTick() {
        return plugin.getServer().getCurrentTick();
    }
}
