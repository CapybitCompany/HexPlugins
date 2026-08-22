package hex.minions.diagnostics;

import java.util.List;

/** Shared classification used by startup diagnostics and tests. */
public final class CustomItemCarrierPolicy {
    public enum Classification {
        RESOURCE_PACK_CUSTOM_ITEM,
        PLUGIN_IDENTIFIED_VANILLA_ITEM
    }

    public enum Category {
        SPECIAL_ITEM,
        GENERATED_COMPRESSION,
        STORAGE_CHEST_ITEM,
        RESOURCE_ITEM,
        MINION_ITEM,
        MINION_APPEARANCE_ITEM,
        SPECIAL_RECIPE_DIRECT_CMD_OUTPUT,
        MACHINE_RECIPE_DIRECT_CMD_OUTPUT
    }

    public record Rule(
            Category category,
            String factorySource,
            String pdcKeys,
            String idSource,
            String materialSource,
            String cmdSource,
            Classification classification,
            boolean requiresNonBlockCarrier,
            String reason
    ) { }

    private static final List<Rule> RULES = List.of(
            new Rule(Category.SPECIAL_ITEM, "SpecialItemRegistry.createItem", "item_kind=special_item; special_item_id", "special-items/registry id", "SpecialItemDefinition.material", "SpecialItemDefinition.customModelData", Classification.RESOURCE_PACK_CUSTOM_ITEM, true, "resource-pack special item"),
            new Rule(Category.GENERATED_COMPRESSION, "SpecialItemRegistry.addGeneratedCompression/createItem", "item_kind=special_item; special_item_id", "compressed_<resource>/super_compressed_<resource>", "raw vanilla material or resources.<raw>.compression.compressed-material", "0 for vanilla; compression CMD for custom resources", Classification.RESOURCE_PACK_CUSTOM_ITEM, false, "vanilla block-convertible resources use canonical vanilla block icons; custom resources keep resource-pack carriers"),
            new Rule(Category.STORAGE_CHEST_ITEM, "MinionItemFactory.createStorageChestItem", "item_kind=minion_storage_chest; storage_chest_id", "storage-chests id", "storage-chests.*.item.material", "storage-chests.*.item.custom-model-data", Classification.RESOURCE_PACK_CUSTOM_ITEM, true, "resource-pack storage item"),
            new Rule(Category.RESOURCE_ITEM, "MinionService.resourceStack/CustomResourceDropEngine.resourceStack/fallbackTinItem/MinionMenu resource icons", "none", "resources.yml id", "resources.*.material", "resources.*.custom-model-data", Classification.RESOURCE_PACK_CUSTOM_ITEM, true, "resource with CMD is rendered by the resource pack"),
            new Rule(Category.MINION_ITEM, "MinionItemFactory.createMinionItem/createPickupItem", "item_kind=minion; minion_type; minion_tier; optional minion_id", "minion-types id/tier", "minion-types.*.item.material", "minion-types.*.item.custom-model-data", Classification.PLUGIN_IDENTIFIED_VANILLA_ITEM, false, "current minion items intentionally use vanilla heads and CMD=0; if CMD becomes >0 diagnostics promote that concrete config entry to resource-pack custom"),
            new Rule(Category.MINION_APPEARANCE_ITEM, "ItemSpec.toItemStack via appearance.yml", "none", "appearance/equipment path", "appearance item material", "appearance item custom-model-data", Classification.PLUGIN_IDENTIFIED_VANILLA_ITEM, false, "visual equipment is not a persistent custom inventory identity; any concrete CMD>0 entry is still validated as a resource-pack carrier"),
            new Rule(Category.SPECIAL_RECIPE_DIRECT_CMD_OUTPUT, "SpecialItemRegistry.output", "none", "special recipe", "recipe output material", "recipe output custom-model-data", Classification.RESOURCE_PACK_CUSTOM_ITEM, true, "direct recipe output with CMD is a resource-pack item"),
            new Rule(Category.MACHINE_RECIPE_DIRECT_CMD_OUTPUT, "MachineService output", "none", "machine recipe", "machine recipe output material", "machine recipe output custom-model-data", Classification.RESOURCE_PACK_CUSTOM_ITEM, true, "direct machine output with CMD is a resource-pack item")
    );

    private CustomItemCarrierPolicy() { }

    public static List<Rule> rules() { return RULES; }

    public static Rule rule(Category category) {
        return RULES.stream().filter(rule -> rule.category() == category).findFirst().orElseThrow();
    }

    public static boolean requiresNonBlockCarrier(Category category) {
        return rule(category).requiresNonBlockCarrier();
    }

    public static Classification minionItemClassification(int customModelData) {
        return customModelData > 0 ? Classification.RESOURCE_PACK_CUSTOM_ITEM : Classification.PLUGIN_IDENTIFIED_VANILLA_ITEM;
    }

    public static boolean minionItemRequiresNonBlockCarrier(int customModelData) {
        return minionItemClassification(customModelData) == Classification.RESOURCE_PACK_CUSTOM_ITEM;
    }
}
