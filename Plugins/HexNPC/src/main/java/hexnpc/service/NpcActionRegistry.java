package hexnpc.service;

import hexnpc.action.NpcActionHandler;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcActionRegistry {

    private final Map<String, NpcActionHandler> handlers = new ConcurrentHashMap<>();

    public void register(NpcActionHandler handler) {
        Objects.requireNonNull(handler, "handler");
        handlers.put(handler.id().toLowerCase(Locale.ROOT), handler);
    }

    public boolean unregister(String type) {
        if (type == null) {
            return false;
        }
        return handlers.remove(type.toLowerCase(Locale.ROOT)) != null;
    }

    public Optional<NpcActionHandler> resolve(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(type.toLowerCase(Locale.ROOT)));
    }

    public java.util.Set<String> known() {
        return java.util.Set.copyOf(handlers.keySet());
    }
}
