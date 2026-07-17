package hexchat.integration.bukkit;

import hexchat.service.PlayerDirectory;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Implementacja {@link PlayerDirectory} oparta o Bukkit/Paper. Preferuje graczy online,
 * a offline rozwiązuje z lokalnego cache (bez blokujących zapytań do sieci Mojang).
 */
public final class BukkitPlayerDirectory implements PlayerDirectory {

    private final Server server;

    public BukkitPlayerDirectory(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Optional<ResolvedPlayer> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        Player online = server.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new ResolvedPlayer(online.getUniqueId(), online.getName()));
        }

        OfflinePlayer offline = server.getOfflinePlayerIfCached(name);
        if (offline != null && (offline.hasPlayedBefore() || offline.isOnline())) {
            String resolvedName = offline.getName() != null ? offline.getName() : name;
            return Optional.of(new ResolvedPlayer(offline.getUniqueId(), resolvedName));
        }

        return Optional.empty();
    }

    @Override
    public List<String> onlineNames(String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            String name = player.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(needle)) {
                names.add(name);
            }
        }
        return names;
    }

    @Override
    public void notifyIfOnline(UUID playerId, Consumer<Player> action) {
        Player player = server.getPlayer(playerId);
        if (player != null) {
            action.accept(player);
        }
    }
}
