package hexnpc.config;

import org.bukkit.configuration.file.FileConfiguration;

public final class HexNpcConfigLoader {

    public HexNpcConfig load(FileConfiguration config) {
        boolean enabled = config.getBoolean("enabled", true);
        boolean debug = config.getBoolean("debug", false);

        HexNpcConfig.Dialogue dialogue = new HexNpcConfig.Dialogue(
                config.getInt("dialogue.default-line-delay-ticks", 20),
                config.getInt("dialogue.default-cooldown-ticks", 200),
                config.getString("dialogue.prefix", "")
        );

        HexNpcConfig.Proximity proximity = new HexNpcConfig.Proximity(
                config.getInt("proximity.scan-interval-ticks", 10),
                config.getDouble("proximity.default-radius", 3.0D),
                config.getInt("proximity.default-cooldown-ticks", 600)
        );

        HexNpcConfig.Render render = new HexNpcConfig.Render(
                config.getDouble("render.view-distance-blocks", 48.0D),
                config.getInt("render.tablist-remove-delay-ticks", 40)
        );

        return new HexNpcConfig(enabled, debug, dialogue, proximity, render);
    }
}
