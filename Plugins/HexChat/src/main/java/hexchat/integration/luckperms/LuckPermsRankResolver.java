package hexchat.integration.luckperms;

import hexchat.service.ChatRankResolver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.platform.PlayerAdapter;
import net.luckperms.api.util.Tristate;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class LuckPermsRankResolver implements ChatRankResolver {

    private final PlayerAdapter<Player> playerAdapter;

    private LuckPermsRankResolver(LuckPerms luckPerms) {
        this.playerAdapter = luckPerms.getPlayerAdapter(Player.class);
    }

    public static Optional<LuckPermsRankResolver> create() {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            return Optional.of(new LuckPermsRankResolver(luckPerms));
        } catch (IllegalStateException | NoClassDefFoundError ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> resolveRank(Player player) {
        return resolveUser(player).flatMap(user -> {
            String group = user.getPrimaryGroup();
            if (group == null || group.isBlank()) {
                group = user.getCachedData().getMetaData().getPrimaryGroup();
            }

            if (group == null || group.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(group);
        });
    }

    @Override
    public Optional<Boolean> hasPermission(Player player, String permission) {
        try {
            var user = playerAdapter.getUser(player);
            if (user == null) {
                return Optional.empty();
            }

            Tristate state = user.getCachedData()
                    .getPermissionData()
                    .checkPermission(permission);
            if (state == Tristate.UNDEFINED) {
                return Optional.empty();
            }
            return Optional.of(state.asBoolean());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<User> resolveUser(Player player) {
        try {
            return Optional.ofNullable(playerAdapter.getUser(player));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
