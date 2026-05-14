package hexpvphandler.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record HexPvPHandlerConfig(
        String togglePermission,
        boolean blocked,
        Set<String> exemptWorlds,
        Messages messages
) {
    public HexPvPHandlerConfig {
        togglePermission = normalizePermission(togglePermission);
        exemptWorlds = normalizeWorlds(exemptWorlds);
        messages = Objects.requireNonNull(messages, "messages");
    }

    public boolean isWorldExempt(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return exemptWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static String normalizePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return "admin.blokujpvp";
        }
        return permission;
    }

    private static Set<String> normalizeWorlds(Set<String> worlds) {
        if (worlds == null || worlds.isEmpty()) {
            return Set.of("survival");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String world : worlds) {
            if (world == null || world.isBlank()) {
                continue;
            }
            normalized.add(world.toLowerCase(Locale.ROOT));
        }
        if (normalized.isEmpty()) {
            normalized.add("survival");
        }
        return Set.copyOf(normalized);
    }

    public record Messages(
            String prefix,
            String noPermission,
            String pvpBlocked,
            String pvpUnblocked,
            String pvpAlreadyBlocked,
            String pvpAlreadyUnblocked,
            String pvpStatusBlocked,
            String pvpStatusUnblocked
    ) {
        public Messages {
            prefix = Objects.requireNonNull(prefix, "prefix");
            noPermission = Objects.requireNonNull(noPermission, "noPermission");
            pvpBlocked = Objects.requireNonNull(pvpBlocked, "pvpBlocked");
            pvpUnblocked = Objects.requireNonNull(pvpUnblocked, "pvpUnblocked");
            pvpAlreadyBlocked = Objects.requireNonNull(pvpAlreadyBlocked, "pvpAlreadyBlocked");
            pvpAlreadyUnblocked = Objects.requireNonNull(pvpAlreadyUnblocked, "pvpAlreadyUnblocked");
            pvpStatusBlocked = Objects.requireNonNull(pvpStatusBlocked, "pvpStatusBlocked");
            pvpStatusUnblocked = Objects.requireNonNull(pvpStatusUnblocked, "pvpStatusUnblocked");
        }
    }
}
