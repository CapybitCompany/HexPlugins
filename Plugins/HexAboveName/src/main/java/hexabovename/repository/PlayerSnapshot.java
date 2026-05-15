package hexabovename.repository;

import java.util.Objects;
import java.util.UUID;

public record PlayerSnapshot(
        UUID uuid,
        String name
) {
    public PlayerSnapshot {
        uuid = Objects.requireNonNull(uuid, "uuid");
        name = Objects.requireNonNull(name, "name");
    }
}
