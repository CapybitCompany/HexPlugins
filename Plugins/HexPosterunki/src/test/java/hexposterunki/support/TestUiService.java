package hexposterunki.support;

import hex.core.api.config.ConfigKey;
import hex.core.api.config.ConfigService;
import hex.core.api.config.ConfigSpec;
import hex.core.api.config.ReloadResult;
import hex.core.api.ui.UiService;
import hex.core.service.ui.UiConfig;
import hex.core.service.ui.UiServiceImpl;

import java.util.Optional;

/**
 * Builds the <b>real</b> HexCore {@link UiServiceImpl} on top of an in-memory {@link UiConfig}.
 *
 * <p>Only HexCore's config plumbing (which reads {@code ui.yml} from disk) is stubbed. Template
 * registration, namespace normalisation and MiniMessage rendering are the genuine production code,
 * which is the point: the namespace bug lived exactly there.
 */
public final class TestUiService {

    private final UiConfig config = new UiConfig();
    private final UiServiceImpl service;

    public TestUiService() {
        this.config.setPrefix("");
        this.service = new UiServiceImpl(new InMemoryConfigService(config));
    }

    public UiService service() {
        return service;
    }

    public UiConfig config() {
        return config;
    }

    /** Lets a test verify that an admin override still wins over a registered default. */
    public void override(String key, String template) {
        config.getOverrides().put(key, template);
    }

    private record InMemoryConfigService(UiConfig config) implements ConfigService {

        @Override
        public <T> void register(ConfigSpec<T> spec) {
            // Nothing to load: the config instance is supplied directly.
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T get(ConfigKey<T> key) {
            return (T) config;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Optional<T> find(ConfigKey<T> key) {
            return Optional.of((T) config);
        }

        @Override
        public ReloadResult reload(String id) {
            return ReloadResult.ok(id);
        }

        @Override
        public ReloadResult reload(ConfigKey<?> key) {
            return ReloadResult.ok(key.id());
        }
    }
}
