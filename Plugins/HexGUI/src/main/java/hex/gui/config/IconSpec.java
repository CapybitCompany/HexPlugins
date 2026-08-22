package hex.gui.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record IconSpec(
        Material material,
        int customModelData,
        String itemModel,
        String playerName,
        String textureUrl,
        String textureHash,
        String textureBase64
) {
    private static final Pattern TEXTURE_URL_PATTERN = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    public static IconSpec from(ConfigurationSection section, Plugin plugin, String entryId) {
        if (section == null) {
            return new IconSpec(Material.STONE, 0, "", "", "", "", "");
        }

        String materialName = section.getString("material", "STONE");
        Material material = Material.matchMaterial(materialName == null ? "STONE" : materialName);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("[config] entries." + entryId + ".icon.material='" + materialName + "' jest nieprawidłowe. Używam STONE.");
            material = Material.STONE;
        }

        String playerName = clean(section.getString("player-name", section.getString("owner", "")));
        String textureUrl = clean(section.getString("texture-url", section.getString("skin-url", "")));
        String textureHash = clean(section.getString("texture-hash", ""));
        String textureBase64 = clean(section.getString("texture-base64", section.getString("base64", "")));

        if ((!playerName.isBlank() || !textureUrl.isBlank() || !textureHash.isBlank() || !textureBase64.isBlank())
                && material != Material.PLAYER_HEAD) {
            plugin.getLogger().warning("[config] entries." + entryId + " ma teksturę główki, więc material został zmieniony na PLAYER_HEAD.");
            material = Material.PLAYER_HEAD;
        }

        return new IconSpec(
                material,
                Math.max(0, section.getInt("custom-model-data", section.getInt("custom_model_data", 0))),
                clean(section.getString("item-model", section.getString("item_model", ""))),
                playerName,
                textureUrl,
                textureHash,
                textureBase64
        );
    }

    public ItemStack create(Plugin plugin) {
        ItemStack stack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        if (customModelData > 0) {
            try {
                meta.setCustomModelData(customModelData);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Nie udało się ustawić custom-model-data=" + customModelData + ": " + throwable.getMessage());
            }
        }

        applyItemModelReflectively(meta, plugin);
        if (meta instanceof SkullMeta skullMeta) {
            applySkull(skullMeta, plugin);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    private void applyItemModelReflectively(ItemMeta meta, Plugin plugin) {
        if (itemModel == null || itemModel.isBlank()) return;
        NamespacedKey key = NamespacedKey.fromString(itemModel);
        if (key == null) {
            plugin.getLogger().warning("Nieprawidłowy item-model: " + itemModel);
            return;
        }
        try {
            meta.getClass().getMethod("setItemModel", NamespacedKey.class).invoke(meta, key);
        } catch (NoSuchMethodException ignored) {
            // Paper/Bukkit 1.21.1 może nie udostępniać jeszcze komponentu item_model.
            // custom-model-data nadal działa i pozostaje podstawową metodą dla tej wersji.
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Nie udało się ustawić item-model '" + itemModel + "': " + throwable.getMessage());
        }
    }

    private void applySkull(SkullMeta meta, Plugin plugin) {
        try {
            String resolvedUrl = resolveTextureUrl();
            if (!resolvedUrl.isBlank()) {
                PlayerProfile profile = Bukkit.createPlayerProfile(
                        UUID.nameUUIDFromBytes(resolvedUrl.getBytes(StandardCharsets.UTF_8)),
                        null
                );
                profile.getTextures().setSkin(new URL(resolvedUrl));
                meta.setOwnerProfile(profile);
                return;
            }
            if (playerName != null && !playerName.isBlank()) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Nie udało się ustawić własnej tekstury główki: " + throwable.getMessage());
        }
    }

    private String resolveTextureUrl() {
        if (textureUrl != null && !textureUrl.isBlank()) return textureUrl;
        if (textureHash != null && !textureHash.isBlank()) {
            return "https://textures.minecraft.net/texture/" + textureHash;
        }
        if (textureBase64 == null || textureBase64.isBlank()) return "";
        try {
            String decoded = new String(Base64.getDecoder().decode(textureBase64), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURE_URL_PATTERN.matcher(decoded);
            return matcher.find() ? matcher.group(1) : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
