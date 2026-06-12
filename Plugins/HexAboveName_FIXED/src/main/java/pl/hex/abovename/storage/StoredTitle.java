package pl.hex.abovename.storage;

import java.util.Objects;
import java.util.UUID;

public record StoredTitle(UUID uuid, String name, String title) {
    public StoredTitle {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
    }
}
