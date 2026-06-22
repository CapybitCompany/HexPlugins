package hexnpc.config;

import hexnpc.shop.config.ShopConfig;

import java.util.Objects;

public record HexNpcConfig(
        boolean enabled,
        boolean debug,
        Dialogue dialogue,
        Proximity proximity,
        Render render,
        ShopConfig shops
) {
    public HexNpcConfig {
        dialogue = Objects.requireNonNull(dialogue, "dialogue");
        proximity = Objects.requireNonNull(proximity, "proximity");
        render = Objects.requireNonNull(render, "render");
        shops = shops == null ? ShopConfig.defaults() : shops;
    }

    public record Dialogue(
            int defaultLineDelayTicks,
            int defaultCooldownTicks,
            String prefix
    ) {
        public Dialogue {
            defaultLineDelayTicks = Math.max(0, defaultLineDelayTicks);
            defaultCooldownTicks = Math.max(0, defaultCooldownTicks);
            prefix = prefix == null ? "" : prefix;
        }
    }

    public record Proximity(
            int scanIntervalTicks,
            double defaultRadius,
            int defaultCooldownTicks
    ) {
        public Proximity {
            scanIntervalTicks = Math.max(1, scanIntervalTicks);
            defaultRadius = Math.max(0.5D, defaultRadius);
            defaultCooldownTicks = Math.max(0, defaultCooldownTicks);
        }
    }

    public record Render(
            double viewDistanceBlocks,
            int tablistRemoveDelayTicks
    ) {
        public Render {
            viewDistanceBlocks = Math.max(8.0D, viewDistanceBlocks);
            tablistRemoveDelayTicks = Math.max(0, tablistRemoveDelayTicks);
        }
    }
}
