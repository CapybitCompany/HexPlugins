package hexnpc.config;

import hexnpc.shop.config.ShopConfig;

import java.util.Objects;

public record HexNpcConfig(
        boolean enabled,
        boolean debug,
        Dialogue dialogue,
        Proximity proximity,
        Render render,
        Skins skins,
        ShopConfig shops
) {
    public HexNpcConfig {
        dialogue = Objects.requireNonNull(dialogue, "dialogue");
        proximity = Objects.requireNonNull(proximity, "proximity");
        render = Objects.requireNonNull(render, "render");
        skins = skins == null ? Skins.defaults() : skins;
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
            int tablistRemoveDelayTicks,
            double sittingYOffset
    ) {
        /** Default-Versatz der Sitz-Pose: 1.0 Bloecke nach unten, damit der NPC am Boden sitzt. */
        public static final double DEFAULT_SITTING_Y_OFFSET = -1.0D;

        public Render {
            viewDistanceBlocks = Math.max(8.0D, viewDistanceBlocks);
            tablistRemoveDelayTicks = Math.max(0, tablistRemoveDelayTicks);
        }

        /**
         * Rueckwaerts-kompatibler Konstruktor ohne Sitz-Offset — nutzt den Default.
         */
        public Render(double viewDistanceBlocks, int tablistRemoveDelayTicks) {
            this(viewDistanceBlocks, tablistRemoveDelayTicks, DEFAULT_SITTING_Y_OFFSET);
        }
    }

    /**
     * Skin-Aufloesung inkl. MineSkin v2. API-Key/User-Agent sind konfigurierbar,
     * keine Secrets hardcoded. Bei fehlendem/leerem Key laufen nur oeffentliche
     * Aufrufe; Fehler fallen still auf den Default-Skin zurueck.
     */
    public record Skins(
            MineSkin mineskin
    ) {
        public Skins {
            mineskin = mineskin == null ? MineSkin.defaults() : mineskin;
        }

        public static Skins defaults() {
            return new Skins(MineSkin.defaults());
        }

        public record MineSkin(
                boolean enabled,
                String apiKey,
                String userAgent,
                String baseUrl,
                int requestTimeoutSeconds,
                int maxPollAttempts,
                long pollIntervalMillis
        ) {
            public static final String DEFAULT_BASE_URL = "https://api.mineskin.org";
            public static final String DEFAULT_USER_AGENT = "HexNPC/1.0";

            public MineSkin {
                apiKey = trimToNull(apiKey);
                userAgent = (userAgent == null || userAgent.isBlank()) ? DEFAULT_USER_AGENT : userAgent.trim();
                baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl.trim());
                requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
                maxPollAttempts = Math.max(1, maxPollAttempts);
                pollIntervalMillis = Math.max(100L, pollIntervalMillis);
            }

            public static MineSkin defaults() {
                return new MineSkin(false, null, DEFAULT_USER_AGENT, DEFAULT_BASE_URL, 20, 10, 2000L);
            }

            public boolean hasApiKey() {
                return apiKey != null;
            }

            private static String trimToNull(String v) {
                if (v == null) {
                    return null;
                }
                String t = v.trim();
                return t.isEmpty() ? null : t;
            }

            private static String stripTrailingSlash(String v) {
                return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
            }
        }
    }
}
