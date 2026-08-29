package hexcustomitems.api;

import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.service.CustomItemRegistryService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class HexCustomItemsApiImpl implements HexCustomItemsApi {
    private final CustomItemRegistryService registry;

    public HexCustomItemsApiImpl(CustomItemRegistryService registry) {
        this.registry = registry;
    }

    @Override
    public Optional<ItemStack> create(String itemId, int amount) {
        if (amount <= 0) return Optional.empty();
        CustomItemDefinition definition = registry.findById(itemId);
        if (definition == null) return Optional.empty();
        return Optional.of(registry.createItem(definition, amount));
    }

    @Override
    public String resolveId(ItemStack stack) {
        return registry.resolveItemId(stack);
    }

    @Override
    public int count(Player player, String itemId) {
        if (player == null || itemId == null || itemId.isBlank()) return 0;
        String expected = normalize(itemId);
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null) continue;
            String actual = registry.resolveItemId(stack);
            if (actual != null && normalize(actual).equals(expected)) count += stack.getAmount();
        }
        return count;
    }

    @Override
    public boolean has(Player player, String itemId, int amount) {
        return amount > 0 && count(player, itemId) >= amount;
    }

    @Override
    public TakeResult take(Player player, String itemId, int amount) {
        if (player == null || amount <= 0) return TakeResult.fail("INVALID_ARGUMENT");
        if (!has(player, itemId, amount)) return TakeResult.fail("NOT_ENOUGH_ITEMS");
        String expected = normalize(itemId);
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null) continue;
            String actual = registry.resolveItemId(stack);
            if (actual == null || !normalize(actual).equals(expected)) continue;
            int take = Math.min(remaining, stack.getAmount());
            if (take >= stack.getAmount()) player.getInventory().setItem(slot, null);
            else {
                ItemStack clone = stack.clone();
                clone.setAmount(stack.getAmount() - take);
                player.getInventory().setItem(slot, clone);
            }
            remaining -= take;
        }
        return remaining == 0 ? TakeResult.ok(amount) : TakeResult.fail("INVENTORY_CHANGED");
    }

    @Override
    public GiveResult give(Player player, String itemId, int amount) {
        if (player == null || amount <= 0) return GiveResult.fail("INVALID_ARGUMENT");
        CustomItemDefinition definition = registry.findById(itemId);
        if (definition == null) return GiveResult.fail("UNKNOWN_ITEM");

        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int stackAmount = definition.usesCharges()
                    ? 1
                    : Math.min(remaining, Math.max(1, definition.material().getMaxStackSize()));
            stacks.add(registry.createItem(definition, stackAmount, player));
            remaining -= stackAmount;
        }

        // addItem może częściowo zmodyfikować inventory, a następnie zwrócić leftovers.
        // W refundach oznaczałoby to ryzyko duplikacji przy ponowieniu. Najpierw robimy
        // deterministic preflight po storage slots; na main thread inventory nie zmieni się
        // pomiędzy checkiem i właściwym addItem bez udziału naszego kodu.
        if (!canFit(player, stacks)) return GiveResult.fail("INVENTORY_FULL");

        for (ItemStack stack : stacks) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            if (!leftovers.isEmpty()) return GiveResult.fail("INVENTORY_CHANGED");
        }
        return GiveResult.ok(amount);
    }

    private static boolean canFit(Player player, java.util.List<ItemStack> toAdd) {
        java.util.List<ItemStack> simulated = new java.util.ArrayList<>();
        ItemStack[] storage;
        try { storage = player.getInventory().getStorageContents(); }
        catch (NoSuchMethodError ignored) { storage = player.getInventory().getContents(); }
        for (ItemStack stack : storage) simulated.add(stack == null ? null : stack.clone());

        for (ItemStack incoming : toAdd) {
            int remaining = incoming.getAmount();
            int max = Math.max(1, incoming.getType().getMaxStackSize());
            for (int i = 0; i < simulated.size() && remaining > 0; i++) {
                ItemStack existing = simulated.get(i);
                if (existing == null || !existing.isSimilar(incoming)) continue;
                int free = Math.max(0, max - existing.getAmount());
                int put = Math.min(free, remaining);
                if (put > 0) { existing.setAmount(existing.getAmount() + put); remaining -= put; }
            }
            for (int i = 0; i < simulated.size() && remaining > 0; i++) {
                if (simulated.get(i) != null) continue;
                int put = Math.min(max, remaining);
                ItemStack clone = incoming.clone(); clone.setAmount(put); simulated.set(i, clone); remaining -= put;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
