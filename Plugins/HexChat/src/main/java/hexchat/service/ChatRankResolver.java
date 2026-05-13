package hexchat.service;

import org.bukkit.entity.Player;

import java.util.Optional;

public interface ChatRankResolver {

    Optional<String> resolveRank(Player player);

    Optional<Boolean> hasPermission(Player player, String permission);
}
