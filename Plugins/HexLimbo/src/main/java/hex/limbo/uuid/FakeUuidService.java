package hex.limbo.uuid;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Generates stable offline UUIDs for cracked players. The algorithm matches the convention used by
 * Spigot/Bukkit offline mode: MD5 of {@code "OfflinePlayer:" + name}. This keeps existing offline
 * worlds compatible with backend plugins that already key data by this UUID, while still being
 * unique and stable per username.
 *
 * <p>The repository is the source of truth: if a username is already registered, callers must look
 * up the stored UUID instead of regenerating it, which protects against in-flight rename changes.
 */
public final class FakeUuidService {

    public UUID forName(String username) {
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("username must not be empty");
        }
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
