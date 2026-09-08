package hexbuildbattle.queue;

import hexbuildbattle.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class QueueManager {

    private final Supplier<PluginConfig> configSupplier;
    private final LinkedHashSet<UUID> queuedPlayers = new LinkedHashSet<>();

    public QueueManager(Supplier<PluginConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public boolean enqueue(Player player) {
        removeOfflinePlayers();
        if (queuedPlayers.contains(player.getUniqueId())) {
            return true;
        }
        if (queuedPlayers.size() >= configSupplier.get().maxPlayers()) {
            return false;
        }
        return queuedPlayers.add(player.getUniqueId());
    }

    public boolean remove(UUID playerId) {
        return queuedPlayers.remove(playerId);
    }

    public boolean contains(UUID playerId) {
        return queuedPlayers.contains(playerId);
    }

    public int onlineSize() {
        removeOfflinePlayers();
        return queuedPlayers.size();
    }

    public List<UUID> onlineSnapshot() {
        removeOfflinePlayers();
        int maxPlayers = configSupplier.get().maxPlayers();
        List<UUID> snapshot = new ArrayList<>(Math.min(maxPlayers, queuedPlayers.size()));
        for (UUID playerId : queuedPlayers) {
            if (Bukkit.getPlayer(playerId) != null) {
                snapshot.add(playerId);
            }
            if (snapshot.size() >= maxPlayers) {
                break;
            }
        }
        return snapshot;
    }

    public List<Player> onlinePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID playerId : onlineSnapshot()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    public void clear() {
        queuedPlayers.clear();
    }

    public Set<UUID> queuedPlayerIds() {
        removeOfflinePlayers();
        return Set.copyOf(queuedPlayers);
    }

    private void removeOfflinePlayers() {
        queuedPlayers.removeIf(playerId -> Bukkit.getPlayer(playerId) == null);
    }
}
