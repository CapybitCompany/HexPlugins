package hexpvpsmp.protection;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.persistence.PersistentDataType;

final class HexAuctionBazaarCompatibility {

    private static final NamespacedKey SIGN_PROMPT_SESSION =
            new NamespacedKey("hexauctionbazaar", "sign_prompt_session");

    private HexAuctionBazaarCompatibility() {
    }

    static boolean isPricePromptSign(Block block) {
        try {
            if (block != null && block.getState() instanceof Sign sign) {
                return sign.getPersistentDataContainer()
                        .has(SIGN_PROMPT_SESSION, PersistentDataType.STRING);
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }
}
