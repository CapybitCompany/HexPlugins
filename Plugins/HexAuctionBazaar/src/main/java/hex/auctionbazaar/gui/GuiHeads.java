package hex.auctionbazaar.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GuiHeads {

    private static final Pattern TEXTURE_URL_PATTERN = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private static final String NEXT_PAGE_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTU2YTM2MTg0NTllNDNiMjg3YjIyYjdlMjM1ZWM2OTk1OTQ1NDZjNmZjZDZkYzg0YmZjYTRjZjMwYWI5MzExIn19fQ==";
    private static final String PREV_PAGE_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2RjOWU0ZGNmYTQyMjFhMWZhZGMxYjViMmIxMWQ4YmVlYjU3ODc5YWYxYzQyMzYyMTQyYmFlMWVkZDUifX19";
    private static final String BACK_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2JkZjJjMzliYjVjYmEyNDQzMjllMDI4MGMwYjRhNDNlOWMzY2VhMjllMDZhYzIyMjcyMjM4ZmZiM2Q1ZTUzYiJ9fX0=";

    private GuiHeads() {
    }

    public static ItemStack nextPage(String name) {
        return headButton(NEXT_PAGE_TEXTURE, name);
    }

    public static ItemStack previousPage(String name) {
        return headButton(PREV_PAGE_TEXTURE, name);
    }

    public static ItemStack back(String name) {
        return headButton(BACK_TEXTURE, name);
    }

    private static ItemStack headButton(String textureBase64, String name) {
        ItemStack item = GuiFrame.button(Material.PLAYER_HEAD, name);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta skullMeta)) {
            return item;
        }
        try {
            String url = textureUrl(textureBase64);
            if (url.isBlank()) {
                return item;
            }
            PlayerProfile profile = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)), null);
            profile.getTextures().setSkin(new URL(url));
            skullMeta.setOwnerProfile(profile);
            item.setItemMeta(skullMeta);
        } catch (Throwable ignored) {
            return item;
        }
        return item;
    }

    private static String textureUrl(String textureBase64) {
        if (textureBase64 == null || textureBase64.isBlank()) {
            return "";
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(textureBase64), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURE_URL_PATTERN.matcher(decoded);
            return matcher.find() ? matcher.group(1) : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
