package hex.minigames.game.tag;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Delegates all role colors to GlowAPI's console command, without managing teams. */
public final class TagRoleDisplay {
    private final Plugin plugin;
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private final Map<UUID, Boolean> appliedRoles = new HashMap<>();
    private Set<UUID> viewers = Set.of();
    private boolean refreshNextTick;

    public TagRoleDisplay(Plugin plugin) { this.plugin = plugin; }

    public void show(Collection<Player> participants, TagRuntime runtime) {
        requireAvailable();
        for (Player player : participants) players.putIfAbsent(player.getUniqueId(), player);
        refresh(participants, runtime);
    }

    public void requireAvailable() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("GlowAPI")
                || plugin.getServer().getPluginCommand("glowapi:glow") == null) {
            throw new IllegalStateException("Berek wymaga wlaczonego GlowAPI z komenda /glow <color|off> [player].");
        }
    }

    /** Reapply for new viewers, including a delayed pass after their login packets. */
    public void refresh(Collection<Player> participants, TagRuntime runtime) {
        if (players.isEmpty()) return;
        Set<UUID> online = new HashSet<>();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) online.add(viewer.getUniqueId());
        boolean addedViewer = !viewers.containsAll(online);
        boolean resend = addedViewer || refreshNextTick;
        viewers = online;
        refreshNextTick = addedViewer;
        for (Player player : participants) {
            if (!players.containsKey(player.getUniqueId())) continue;
            boolean tagger = runtime.isTagger(player.getUniqueId());
            if (resend) appliedRoles.remove(player.getUniqueId());
            update(player, tagger);
        }
    }

    public void update(Player player, boolean tagger) {
        UUID id = player.getUniqueId();
        if (!players.containsKey(id) || Objects.equals(appliedRoles.get(id), tagger)) return;
        command(tagger ? "red" : "green", player);
        appliedRoles.put(id, tagger);
    }

    public void restore(UUID id) {
        Player player = players.get(id);
        if (player == null) return;
        // Quit is handled while the player is still addressable by GlowAPI's command.
        // GlowAPI itself also discards entity state on disconnect.
        if (player.isOnline()) command("off", player);
        players.remove(id);
        appliedRoles.remove(id);
    }

    public void clear() {
        for (UUID id : List.copyOf(players.keySet())) {
            try { restore(id); }
            catch (RuntimeException error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cannot clear GlowAPI color for " + id, error);
            }
        }
        viewers = Set.of();
        refreshNextTick = false;
    }

    private void command(String color, Player player) {
        if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "glowapi:glow " + color + " " + player.getName())) {
            throw new IllegalStateException("GlowAPI command failed for " + player.getName());
        }
    }
}
