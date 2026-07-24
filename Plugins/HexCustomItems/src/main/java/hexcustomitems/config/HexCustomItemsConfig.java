package hexcustomitems.config;

import hexcustomitems.model.CustomItemDefinition;
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
        Map<String, CustomItemDefinition> items,
        Map<String, String> legacyCommandBindings
) {
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
        items = Map.copyOf(Objects.requireNonNull(items, "items"));
        legacyCommandBindings = Map.copyOf(Objects.requireNonNull(legacyCommandBindings, "legacyCommandBindings"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
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
            String dropBlocked
    ) {
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

    /**
     * Rezept-Definition. {@code shapedIngredients} (Zeichen -&gt; Material) wird für
     * {@code shaped} genutzt, {@code shapelessIngredients} für {@code shapeless}.
     */
    public record RecipeSpec(
            String type,
            List<String> shape,
            Map<String, Material> shapedIngredients,
            List<Material> shapelessIngredients,
            int amount
    ) {
        public RecipeSpec {
            type = (type == null || type.isBlank()) ? "shaped" : type.toLowerCase(java.util.Locale.ROOT);
            shape = List.copyOf(shape == null ? List.of() : shape);
            shapedIngredients = Map.copyOf(shapedIngredients == null ? Map.of() : shapedIngredients);
            shapelessIngredients = List.copyOf(shapelessIngredients == null ? List.of() : shapelessIngredients);
            amount = Math.max(1, amount);
        }

        public boolean shapeless() {
            return "shapeless".equals(type);
        }
    }
}
