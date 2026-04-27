package hex.core.service.config;

import org.bukkit.plugin.Plugin;
import hex.core.api.config.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigServiceImpl implements ConfigService {

    private final Plugin plugin;
    private final YamlConfigLoader yaml = new YamlConfigLoader();

    private final Map<String, ConfigSpec<?>> specsById = new ConcurrentHashMap<>();
    private final Map<ConfigKey<?>, Object> valuesByKey = new ConcurrentHashMap<>();

    public ConfigServiceImpl(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public <T> void register(ConfigSpec<T> spec) {
        Objects.requireNonNull(spec, "spec");

        String id = spec.key().id();
        if (specsById.putIfAbsent(id, spec) != null) {
            throw new IllegalStateException("Config id already registered: " + id);
        }

        // initial load
        ReloadResult res = reload(spec.key());
        if (!res.success()) {
            plugin.getLogger().severe("[HexCore] Failed to load config " + id + ": " + res.message());
            for (String e : res.validationErrors()) plugin.getLogger().severe(" - " + e);
            // fail-fast tylko jeśli to krytyczne; tu zostawiamy default i lecimy dalej
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(ConfigKey<T> key) {
        Object v = valuesByKey.get(key);
        if (v == null) throw new NoSuchElementException("Config not loaded: " + key);
        return (T) v;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(ConfigKey<T> key) {
        return Optional.ofNullable((T) valuesByKey.get(key));
    }

    @Override
    public ReloadResult reload(String id) {
        ConfigSpec<?> spec = specsById.get(id);
        if (spec == null) return ReloadResult.failed("Unknown config id: " + id, List.of());
        return reload(spec.key());
    }

    @Override
    public ReloadResult reload(ConfigKey<?> key) {
        ConfigSpec<?> spec = specsById.get(key.id());
        if (spec == null) return ReloadResult.failed("Unknown config key: " + key, List.of());

        if (spec.reloadPolicy() == ReloadPolicy.RESTART_REQUIRED) {
            return ReloadResult.failed("Reload not allowed (restart required): " + key.id(), List.of());
        }

        try {
            Object loaded = yaml.load(spec.file(), spec.key().type());
            if (loaded == null) {
                // if missing: create default
                Object def = spec.defaultSupplier().get();
                yaml.saveDefault(spec.file(), def);
                loaded = def;
            }

            @SuppressWarnings("unchecked")
            Validator<Object> validator = (Validator<Object>) spec.validator();
            ValidationResult vr = validator.validate(loaded);

            if (!vr.isOk()) {
                return ReloadResult.failed("Validation failed for " + key.id(), vr.errors());
            }

            // commit atomically (dla pojedynczego configu wystarczy)
            valuesByKey.put(spec.key(), loaded);

            return ReloadResult.ok("Reloaded " + key.id());
        } catch (Exception e) {
            plugin.getLogger().severe("[HexCore] Reload error for " + key.id() + ": " + e.getMessage());
            return ReloadResult.failed("Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
        }
    }
}
