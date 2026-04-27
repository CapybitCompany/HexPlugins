package hex.core.service.flags;

import hex.core.api.config.ConfigKey;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.flags.FlagContext;
import hex.core.api.config.ConfigService;

import java.util.Map;

public final class FeatureFlagServiceImpl implements FeatureFlagService {

    public static final ConfigKey<FlagsConfig> FLAGS_KEY = new ConfigKey<>("flags", FlagsConfig.class);

    private final ConfigService configs;

    public FeatureFlagServiceImpl(ConfigService configs) {
        this.configs = configs;
    }

    @Override
    public boolean isEnabled(String flagKey, FlagContext ctx, boolean defaultValue) {
        FlagsConfig cfg = configs.get(FLAGS_KEY);

        // 1) arena override
        if (ctx != null && ctx.arenaId() != null) {
            Boolean v = getNested(cfg.getArenas(), ctx.arenaId(), flagKey);
            if (v != null) return v;
        }

        // 2) game override
        if (ctx != null && ctx.gameId() != null) {
            Boolean v = getNested(cfg.getGames(), ctx.gameId(), flagKey);
            if (v != null) return v;
        }

        // 3) global
        Boolean v = cfg.getGlobal().get(flagKey);
        return v != null ? v : defaultValue;
    }

    private static Boolean getNested(Map<String, Map<String, Boolean>> map, String key1, String flagKey) {
        Map<String, Boolean> inner = map.get(key1);
        if (inner == null) return null;
        return inner.get(flagKey);
    }
}
