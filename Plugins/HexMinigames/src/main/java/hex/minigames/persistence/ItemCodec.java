package hex.minigames.persistence;

import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

final class ItemCodec {
    private ItemCodec() {
    }

    static String encode(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeObject(value);
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception error) {
            throw new IllegalStateException("Could not encode Bukkit object", error);
        }
    }

    @SuppressWarnings("unchecked")
    static ItemStack[] decodeItems(String raw) {
        Object decoded = decode(raw);
        return decoded instanceof ItemStack[] items ? items : new ItemStack[0];
    }

    static ItemStack decodeItem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Object decoded = decode(raw);
        return decoded instanceof ItemStack item ? item : null;
    }

    @SuppressWarnings("unchecked")
    static List<PotionEffect> decodeEffects(String raw) {
        Object decoded = decode(raw);
        return decoded instanceof List<?> list ? (List<PotionEffect>) list : List.of();
    }

    private static Object decode(String raw) {
        try {
            byte[] bytes = Base64.getDecoder().decode(raw == null ? "" : raw);
            try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                return in.readObject();
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not decode Bukkit object", error);
        }
    }
}
