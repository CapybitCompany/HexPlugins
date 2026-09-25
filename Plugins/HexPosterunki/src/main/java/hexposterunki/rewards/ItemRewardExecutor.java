package hexposterunki.rewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/** Grants vanilla items; anything that does not fit the inventory drops at the player's feet. */
public final class ItemRewardExecutor implements RewardExecutor {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @Override
    public String id() {
        return "items";
    }

    @Override
    public boolean supports(RewardComponent.Type type) {
        return type == RewardComponent.Type.ITEM;
    }

    @Override
    public DeliveryOutcome deliver(Player player, RewardComponent component, String payoutId) {
        Material material = Material.matchMaterial(component.value());
        if (material == null || material.isAir()) {
            // Nothing was handed over and nothing ever will be with this material.
            return DeliveryOutcome.notExecuted("nieznany materiał " + component.value());
        }
        ItemStack stack = new ItemStack(material, component.amount());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (component.displayName() != null && !component.displayName().isBlank()) {
                meta.displayName(MINI.deserialize(component.displayName()));
            }
            if (!component.lore().isEmpty()) {
                meta.lore(component.lore().stream().map(line -> (Component) MINI.deserialize(line)).toList());
            }
            stack.setItemMeta(meta);
        }
        try {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            leftover.values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            return DeliveryOutcome.ok();
        } catch (RuntimeException exception) {
            // The stack may be partially in the inventory already - do not guess.
            return DeliveryOutcome.unknown("błąd przy wydawaniu przedmiotu: " + exception.getMessage());
        }
    }
}
