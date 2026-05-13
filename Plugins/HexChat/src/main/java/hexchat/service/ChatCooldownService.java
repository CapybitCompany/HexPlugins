package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.permission.HexChatPermissions;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ChatCooldownService {

    private final Supplier<HexChatConfig> configSupplier;
    private volatile ChatRankResolver chatRankResolver;
    private final ConcurrentHashMap<UUID, Long> nextAllowedChatAtNanosByPlayer = new ConcurrentHashMap<>();
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    public ChatCooldownService(Supplier<HexChatConfig> configSupplier, ChatRankResolver chatRankResolver) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.chatRankResolver = Objects.requireNonNull(chatRankResolver, "chatRankResolver");
    }

    public void updateRankResolver(ChatRankResolver chatRankResolver) {
        this.chatRankResolver = Objects.requireNonNull(chatRankResolver, "chatRankResolver");
    }

    public CooldownResult checkAndApply(Player player) {
        HexChatConfig.Cooldown cooldown = configSupplier.get().cooldown();
        if (!cooldown.enabled()) {
            return CooldownResult.allowed();
        }

        if (hasPermission(player, cooldown.bypassPermission())) {
            return CooldownResult.allowed();
        }

        long nowNanos = System.nanoTime();
        long cooldownSeconds = resolveCooldownSeconds(player, cooldown);
        if (cooldownSeconds <= 0) {
            clear(player);
            return CooldownResult.allowed();
        }

        UUID playerId = player.getUniqueId();

        AtomicReference<CooldownResult> resultRef = new AtomicReference<>();
        nextAllowedChatAtNanosByPlayer.compute(playerId, (uuid, nextAllowedAtNanos) -> {
            if (nextAllowedAtNanos != null && nextAllowedAtNanos > nowNanos) {
                long remainingSeconds = remainingSeconds(nowNanos, nextAllowedAtNanos);
                resultRef.set(CooldownResult.blocked(remainingSeconds));
                return nextAllowedAtNanos;
            }

            resultRef.set(CooldownResult.allowed());
            return nowNanos + TimeUnit.SECONDS.toNanos(cooldownSeconds);
        });

        CooldownResult result = resultRef.get();
        return result != null ? result : CooldownResult.allowed();
    }

    public void clear(Player player) {
        nextAllowedChatAtNanosByPlayer.remove(player.getUniqueId());
    }

    private long resolveCooldownSeconds(Player player, HexChatConfig.Cooldown cooldown) {
        long resolved = cooldown.defaultSeconds();

        if (cooldown.useLuckPermsPrimaryGroup()) {
            resolved = resolveByRank(player, cooldown, resolved);
        }

        for (HexChatConfig.PermissionCooldown override : cooldown.permissionOverrides()) {
            if (hasPermission(player, override.permission())) {
                resolved = Math.min(resolved, override.seconds());
            }
        }
        return Math.max(0, resolved);
    }

    private long resolveByRank(Player player, HexChatConfig.Cooldown cooldown, long fallback) {
        return chatRankResolver.resolveRank(player)
                .map(this::normalizeRank)
                .flatMap(rank -> cooldown.rankCooldowns().stream()
                        .filter(override -> normalizeRank(override.rank()).equals(rank))
                        .map(override -> (long) override.seconds())
                        .findFirst()
                )
                .orElse(fallback);
    }

    private String normalizeRank(String input) {
        return input.toLowerCase(Locale.ROOT).trim();
    }

    private boolean hasPermission(Player player, String permission) {
        if (player.isOp() || player.hasPermission(HexChatPermissions.ADMIN)) {
            return true;
        }

        boolean bukkitPermission = player.hasPermission(permission);
        return chatRankResolver.hasPermission(player, permission)
                .map(luckPermsPermission -> luckPermsPermission || bukkitPermission)
                .orElse(bukkitPermission);
    }

    private long remainingSeconds(long nowNanos, long nextAllowedAtNanos) {
        long nanos = nextAllowedAtNanos - nowNanos;
        if (nanos <= 0) {
            return 1;
        }
        return Math.max(1, (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }

    public record CooldownResult(
            boolean blocked,
            long secondsLeft
    ) {
        public static CooldownResult allowed() {
            return new CooldownResult(false, 0);
        }

        public static CooldownResult blocked(long secondsLeft) {
            return new CooldownResult(true, Math.max(1, secondsLeft));
        }
    }
}
