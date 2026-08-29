package hex.events.registry;

import hex.events.api.EventAvailability;
import hex.events.api.HexEventModule;
import hex.events.api.ModuleRegistration;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class EventModuleRegistry {
    private final Map<String, HexEventModule> modules = new ConcurrentHashMap<>();

    public ModuleRegistration register(HexEventModule module) {
        if (module == null) throw new IllegalArgumentException("module == null");
        String id = normalize(module.moduleId());
        if (id.isBlank()) throw new IllegalArgumentException("moduleId jest pusty");
        HexEventModule existing = modules.putIfAbsent(id, module);
        if (existing != null && existing != module) throw new IllegalStateException("Moduł już zarejestrowany: " + id);
        return new Registration(id, module);
    }

    public Optional<HexEventModule> find(String moduleId) { return Optional.ofNullable(modules.get(normalize(moduleId))); }
    public EventAvailability availability(String moduleId) { return find(moduleId).isPresent() ? EventAvailability.AVAILABLE : EventAvailability.MODULE_UNAVAILABLE; }
    public Map<String, HexEventModule> snapshot() { return Map.copyOf(modules); }

    private static String normalize(String id) { return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT); }

    private final class Registration implements ModuleRegistration {
        private final String id;
        private final HexEventModule module;
        private volatile boolean active = true;
        private Registration(String id, HexEventModule module) { this.id = id; this.module = module; }
        @Override public String moduleId() { return id; }
        @Override public boolean active() { return active && modules.get(id) == module; }
        @Override public void close() {
            if (!active) return;
            active = false;
            modules.remove(id, module);
        }
    }
}
