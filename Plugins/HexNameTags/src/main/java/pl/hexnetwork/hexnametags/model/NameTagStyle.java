package pl.hexnetwork.hexnametags.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public record NameTagStyle(
        float translationX,
        float translationY,
        float translationZ,
        float scale,
        Billboard billboard,
        int lineWidth,
        byte textOpacity,
        int backgroundColor,
        boolean shadow,
        boolean seeThrough,
        boolean defaultBackground,
        Alignment alignment,
        float displayViewRange
) {
    /**
     * Vanilla TextDisplay renders the text block around its origin. One text line is 9 pixels high
     * and display text is scaled by 0.025 block per pixel before the Display scale metadata is applied.
     */
    private static final float TEXT_LINE_HEIGHT_BLOCKS = 9.0F * 0.025F;

    public enum Billboard {
        FIXED((byte) 0),
        VERTICAL((byte) 1),
        HORIZONTAL((byte) 2),
        CENTER((byte) 3);

        private final byte protocolId;

        Billboard(byte protocolId) {
            this.protocolId = protocolId;
        }

        public byte protocolId() {
            return protocolId;
        }

        public static Billboard fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return CENTER;
            }
            try {
                return Billboard.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CENTER;
            }
        }
    }

    public enum Alignment {
        CENTER((byte) 0),
        LEFT((byte) 0x08),
        RIGHT((byte) 0x10);

        private final byte flag;

        Alignment(byte flag) {
            this.flag = flag;
        }

        public byte flag() {
            return flag;
        }

        public static Alignment fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return CENTER;
            }
            try {
                return Alignment.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CENTER;
            }
        }
    }

    public byte textFlags() {
        byte flags = 0;
        if (shadow) {
            flags |= 0x01;
        }
        if (seeThrough) {
            flags |= 0x02;
        }
        if (defaultBackground) {
            flags |= 0x04;
        }
        flags |= alignment.flag();
        return flags;
    }

    public float displayTranslationY(int lineCount) {
        int safeLineCount = Math.max(1, lineCount);
        return translationY + (safeLineCount * TEXT_LINE_HEIGHT_BLOCKS * scale) / 2.0F;
    }

    public static NameTagStyle fromConfig(ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }
        return new NameTagStyle(
                (float) section.getDouble("translation-x", 0.0D),
                (float) section.getDouble("bottom-offset-y", 0.2D),
                (float) section.getDouble("translation-z", 0.0D),
                (float) section.getDouble("scale", 1.0D),
                Billboard.fromConfig(section.getString("billboard", "center")),
                section.getInt("line-width", 220),
                parseByte(section.getString("text-opacity"), (byte) -1),
                parseArgb(section.getString("background-color", "0x40000000"), 0x40000000),
                section.getBoolean("shadow", true),
                section.getBoolean("see-through", false),
                section.getBoolean("default-background", false),
                Alignment.fromConfig(section.getString("alignment", "center")),
                (float) section.getDouble("display-view-range", 64.0D)
        );
    }

    public static NameTagStyle defaults() {
        return new NameTagStyle(
                0.0F,
                0.2F,
                0.0F,
                1.0F,
                Billboard.CENTER,
                220,
                (byte) -1,
                0x40000000,
                true,
                false,
                false,
                Alignment.CENTER,
                64.0F
        );
    }

    private static byte parseByte(String value, byte fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return (byte) Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseArgb(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim().replace("_", "");
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return (int) Long.parseLong(trimmed.substring(2), 16);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
