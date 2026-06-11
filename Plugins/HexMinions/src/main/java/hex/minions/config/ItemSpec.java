package hex.minions.config;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ItemSpec(
        Material material,
        int customModelData,
        String leatherColor,
        String playerName,
        String textureUrl,
        String textureBase64
) {
    private static final Pattern TEXTURE_URL_PATTERN = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    public static ItemSpec empty() {
        return new ItemSpec(null, 0, "", "", "", "");
    }

    public static ItemSpec fromConfig(ConfigurationSection section, Material fallback) {
        if (section == null) {
            return new ItemSpec(fallback, 0, "", "", "", "");
        }
        Material material = parseMaterial(section.getString("material"), fallback);
        String playerName = section.getString("player-name", section.getString("owner", ""));
        String textureUrl = section.getString("texture-url", section.getString("skin-url", ""));
        String textureBase64 = section.getString("texture-base64", section.getString("base64", ""));
        if ((material == null || material == Material.PLAYER_HEAD) && (!playerName.isBlank() || !textureUrl.isBlank() || !textureBase64.isBlank())) {
            material = Material.PLAYER_HEAD;
        }
        return new ItemSpec(
                material,
                Math.max(0, section.getInt("custom-model-data", 0)),
                section.getString("color", ""),
                playerName,
                textureUrl,
                textureBase64
        );
    }

    public boolean present() {
        return material != null;
    }

    public ItemStack toItemStack(Plugin plugin) {
        if (material == null) {
            return null;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (customModelData > 0) {
                meta.setCustomModelData(customModelData);
            }
            if (meta instanceof LeatherArmorMeta leatherMeta && leatherColor != null && !leatherColor.isBlank()) {
                leatherMeta.setColor(parseColor(leatherColor));
            }
            if (meta instanceof SkullMeta skullMeta) {
                applySkull(plugin, skullMeta);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public String headOwner() {
        return playerName == null ? "" : playerName;
    }

    public String headTextureBase64() {
        return textureBase64 == null ? "" : textureBase64;
    }

    public String headTextureUrl() {
        return textureUrl == null ? "" : textureUrl;
    }

    public static String base64FromUrl(String url) {
        if (blank(url)) return "";
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private void applySkull(Plugin plugin, SkullMeta skullMeta) {
        try {
            String resolvedUrl = !blank(textureUrl) ? textureUrl : urlFromBase64(textureBase64);
            if (!blank(resolvedUrl)) {
                PlayerProfile profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(resolvedUrl.getBytes(StandardCharsets.UTF_8)), null);
                profile.getTextures().setSkin(new URL(resolvedUrl));
                skullMeta.setOwnerProfile(profile);
                return;
            }
            if (!blank(playerName)) {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Nie udało się ustawić główki miniona: " + throwable.getMessage());
        }
    }

    private static String urlFromBase64(String base64) {
        if (blank(base64)) {
            return "";
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURE_URL_PATTERN.matcher(decoded);
            return matcher.find() ? matcher.group(1) : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static Color parseColor(String raw) {
        try {
            String value = raw.startsWith("#") ? raw.substring(1) : raw;
            return Color.fromRGB(Integer.parseInt(value, 16));
        } catch (Exception ignored) {
            return Color.WHITE;
        }
    }

    private static Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw);
        return material == null ? fallback : material;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}


