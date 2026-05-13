package hexchat.service;

import org.bukkit.entity.Player;

import java.util.Optional;

public final class NoopChatRankResolver implements ChatRankResolver {

    @Override
    public Optional<String> resolveRank(Player player) {
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> hasPermission(Player player, String permission) {
        return Optional.empty();
    }
}
