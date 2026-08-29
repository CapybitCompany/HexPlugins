package hexcustomitems.config;

import hexcustomitems.model.CustomItemDefinition;
import org.bukkit.entity.EntityType;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HexCustomItemsConfig(
        String prefix,
        String givePermission,
        String reloadPermission,
        String itemPermissionDefault,
        int maxGiveAmount,
        String menuTitle,
        int menuShiftGiveAmount,
        Messages messages,
        Sounds sounds,
        RegionAwareness regionAwareness,
        Cooldowns cooldowns,
        Recipes recipes,
        MobDrops mobDrops,
        Map<String, CustomItemDefinition> items,
        Map<String, String> itemIds
) {
    public HexCustomItemsConfig(
            String prefix,
            String givePermission,
            String reloadPermission,
            String itemPermissionDefault,
            int maxGiveAmount,
            String menuTitle,
            int menuShiftGiveAmount,
            Messages messages,
            Sounds sounds,
            RegionAwareness regionAwareness,
            Cooldowns cooldowns,
            Recipes recipes,
            Map<String, CustomItemDefinition> items,
            Map<String, String> legacyCommandBindings
    ) {
        this(prefix, givePermission, reloadPermission, itemPermissionDefault, maxGiveAmount,
                menuTitle, menuShiftGiveAmount, messages, sounds, regionAwareness, cooldowns,
                recipes, new MobDrops(true, Map.of()), items, indexItemIds(items));
    }

    public HexCustomItemsConfig {
        prefix = Objects.requireNonNull(prefix, "prefix");
        givePermission = Objects.requireNonNull(givePermission, "givePermission");
        reloadPermission = Objects.requireNonNull(reloadPermission, "reloadPermission");
        itemPermissionDefault = itemPermissionDefault == null ? "true" : itemPermissionDefault;
        maxGiveAmount = Math.max(1, maxGiveAmount);
        menuTitle = Objects.requireNonNull(menuTitle, "menuTitle");
        menuShiftGiveAmount = Math.max(1, menuShiftGiveAmount);
        messages = Objects.requireNonNull(messages, "messages");
        sounds = Objects.requireNonNull(sounds, "sounds");
        regionAwareness = Objects.requireNonNull(regionAwareness, "regionAwareness");
        cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        recipes = Objects.requireNonNull(recipes, "recipes");
        mobDrops = Objects.requireNonNull(mobDrops, "mobDrops");
        items = Map.copyOf(Objects.requireNonNull(items, "items"));
        itemIds = Map.copyOf(Objects.requireNonNull(itemIds, "itemIds"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
    }

    public Map<String, String> legacyCommandBindings() {
        return Map.of();
    }

    private static Map<String, String> indexItemIds(Map<String, CustomItemDefinition> items) {
        Map<String, String> ids = new java.util.LinkedHashMap<>();
        if (items != null) {
            for (CustomItemDefinition item : items.values()) {
                ids.put(item.id(), item.key());
            }
        }
        return ids;
    }

    public record Messages(
            String noPermission,
            String useNoPermission,
            String playerNotFound,
            String invalidNumber,
            String itemNotFound,
            String usageMain,
            String usageGive,
            String reloaded,
            String givenSender,
            String givenTarget,
            String listHeader,
            String cooldownActive,
            String dropBlocked,
            String combatBlocked,
            String limitReached,
            String noTarget,
            String alreadyActive,
            String anvilBlocked
    ) {
        public Messages(
                String noPermission,
                String useNoPermission,
                String playerNotFound,
                String invalidNumber,
                String itemNotFound,
                String usageMain,
                String usageGive,
                String reloaded,
                String givenSender,
                String givenTarget,
                String listHeader,
                String cooldownActive,
                String dropBlocked
        ) {
            this(noPermission, useNoPermission, playerNotFound, invalidNumber, itemNotFound,
                    usageMain, usageGive, reloaded, givenSender, givenTarget, listHeader,
                    cooldownActive, dropBlocked,
                    "&cNie możesz użyć tego przedmiotu podczas walki.",
                    "&cOsiągnąłeś limit dla tego przedmiotu.",
                    "&cNie znaleziono celu.",
                    "&cTen efekt jest już aktywny.",
                    "&cNie możesz użyć tego przedmiotu w kowadle.");
        }

        public Messages {
            noPermission = Objects.requireNonNull(noPermission, "noPermission");
            useNoPermission = Objects.requireNonNull(useNoPermission, "useNoPermission");
            playerNotFound = Objects.requireNonNull(playerNotFound, "playerNotFound");
            invalidNumber = Objects.requireNonNull(invalidNumber, "invalidNumber");
            itemNotFound = Objects.requireNonNull(itemNotFound, "itemNotFound");
            usageMain = Objects.requireNonNull(usageMain, "usageMain");
            usageGive = Objects.requireNonNull(usageGive, "usageGive");
            reloaded = Objects.requireNonNull(reloaded, "reloaded");
            givenSender = Objects.requireNonNull(givenSender, "givenSender");
            givenTarget = Objects.requireNonNull(givenTarget, "givenTarget");
            listHeader = Objects.requireNonNull(listHeader, "listHeader");
            cooldownActive = Objects.requireNonNull(cooldownActive, "cooldownActive");
            dropBlocked = Objects.requireNonNull(dropBlocked, "dropBlocked");
            combatBlocked = Objects.requireNonNull(combatBlocked, "combatBlocked");
            limitReached = Objects.requireNonNull(limitReached, "limitReached");
            noTarget = Objects.requireNonNull(noTarget, "noTarget");
            alreadyActive = Objects.requireNonNull(alreadyActive, "alreadyActive");
            anvilBlocked = Objects.requireNonNull(anvilBlocked, "anvilBlocked");
        }
    }

    /** Standard-Sounds, genutzt bei der Backward-Compatibility-Übersetzung alter {@code effect}-Sektionen. */
    public record Sounds(
            String consume,
            String drink
    ) {
        public Sounds {
            consume = Objects.requireNonNull(consume, "consume");
            drink = Objects.requireNonNull(drink, "drink");
        }
    }

    public record RegionAwareness(
            boolean enabled,
            boolean failClosed,
            boolean respectPvp,
            String blockedMessage
    ) {
        public RegionAwareness {
            blockedMessage = Objects.requireNonNull(blockedMessage, "blockedMessage");
        }
    }

    public record Cooldowns(
            boolean persist,
            String file
    ) {
        public Cooldowns {
            file = (file == null || file.isBlank()) ? "cooldowns.yml" : file;
        }
    }

    public record Recipes(
            boolean enabled,
            Map<String, RecipeSpec> items
    ) {
        public Recipes {
            items = Map.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    public record RecipeSpec(
            String result,
            int amount,
            List<String> shape,
            Map<String, IngredientSpec> ingredients
    ) {
        public RecipeSpec(
                String type,
                List<String> shape,
                Map<String, Material> shapedIngredients,
                List<Material> shapelessIngredients,
                int amount
        ) {
            this(type == null || type.isBlank() ? "legacy" : type, amount, shape, convertIngredients(shapedIngredients));
        }

        public RecipeSpec {
            result = Objects.requireNonNull(result, "result").toLowerCase(java.util.Locale.ROOT);
            amount = Math.max(1, amount);
            shape = List.copyOf(shape == null ? List.of() : shape);
            ingredients = Map.copyOf(ingredients == null ? Map.of() : ingredients);
        }

        public String type() {
            return result;
        }

        public boolean shapeless() {
            return "shapeless".equalsIgnoreCase(result);
        }
    }

    private static Map<String, IngredientSpec> convertIngredients(Map<String, Material> shapedIngredients) {
        Map<String, IngredientSpec> result = new java.util.LinkedHashMap<>();
        if (shapedIngredients != null) {
            for (Map.Entry<String, Material> entry : shapedIngredients.entrySet()) {
                result.put(entry.getKey(), new IngredientSpec(entry.getValue(), null, null, 0, 1));
            }
        }
        return result;
    }

    public record IngredientSpec(
            Material material,
            String customItemId,
            String enchantment,
            int enchantmentLevel,
            int amount
    ) {
        public IngredientSpec {
            customItemId = customItemId == null || customItemId.isBlank() ? null : customItemId.toLowerCase(java.util.Locale.ROOT);
            enchantment = enchantment == null || enchantment.isBlank() ? null : enchantment.toLowerCase(java.util.Locale.ROOT);
            amount = Math.max(1, amount);
        }
    }

    public record MobDrops(
            boolean enabled,
            Map<EntityType, List<MobDropSpec>> byMob
    ) {
        public MobDrops {
            byMob = Map.copyOf(byMob == null ? Map.of() : byMob);
        }
    }

    public record MobDropSpec(
            String item,
            double chance,
            int amount
    ) {
        public MobDropSpec {
            item = Objects.requireNonNull(item, "item").toLowerCase(java.util.Locale.ROOT);
            chance = Math.max(0.0D, Math.min(100.0D, chance));
            amount = Math.max(1, amount);
        }
    }
}
