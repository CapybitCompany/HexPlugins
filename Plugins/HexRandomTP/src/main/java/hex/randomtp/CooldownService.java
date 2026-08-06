package hex.randomtp;

import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CooldownService {
    private final Clock clock;
    private final Map<UUID, Long> lastSuccessfulUse = new ConcurrentHashMap<>();

    CooldownService() {
        this(Clock.systemUTC());
    }

    CooldownService(Clock clock) {
        this.clock = clock;
    }

    long remainingSeconds(Player player, RtpConfig config) {
        long cooldownSeconds = resolveCooldownSeconds(player, config);
        if (cooldownSeconds <= 0L) {
            return 0L;
        }

        Long lastUse = lastSuccessfulUse.get(player.getUniqueId());
        if (lastUse == null) {
            return 0L;
        }

        long remainingMillis = cooldownSeconds * 1000L - (clock.millis() - lastUse);
        if (remainingMillis <= 0L) {
            lastSuccessfulUse.remove(player.getUniqueId(), lastUse);
            return 0L;
        }

        return (remainingMillis + 999L) / 1000L;
    }

    void markSuccessfulUse(Player player) {
        lastSuccessfulUse.put(player.getUniqueId(), clock.millis());
    }

    private long resolveCooldownSeconds(Player player, RtpConfig config) {
        String bypassPermission = config.cooldownBypassPermission();
        if (!bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return 0L;
        }

        long result = config.defaultCooldownSeconds();
        for (Map.Entry<String, Long> entry : config.permissionCooldowns().entrySet()) {
            if (player.hasPermission(entry.getKey())) {
                result = Math.min(result, entry.getValue());
            }
        }
        return result;
    }
}
