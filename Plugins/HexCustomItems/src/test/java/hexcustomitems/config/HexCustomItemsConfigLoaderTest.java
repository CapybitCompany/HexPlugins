package hexcustomitems.config;

import hexcustomitems.model.CommandAction;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.ItemAction;
import hexcustomitems.model.SoundAction;
import hexcustomitems.model.SpecialAction;
import hexcustomitems.support.PluginTestBase;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexCustomItemsConfigLoaderTest extends PluginTestBase {

    private HexCustomItemsConfig load() {
        return new HexCustomItemsConfigLoader(plugin).load();
    }

    @Test
    void defaultConfigLoadsConfiguredItems() {
        HexCustomItemsConfig config = load();
        assertEquals(19, config.items().size());
        assertTrue(config.items().keySet().containsAll(List.of(
                "boss_ticket", "red_heart", "golden_heart", "efficiency_6_book",
                "ancient_scale", "afk_key", "epic_key", "premium_key",
                "darkness_powder", "spider_grenade", "phoenix_heart", "butcher_hook",
                "mining_luck", "hunter_skull", "kinetic_charge", "invisibility_cookie",
                "coin_1", "coin_3", "coin_6")));
    }

    @Test
    void readsVisibleIdAndModelData() {
        HexCustomItemsConfig config = load();

        assertEquals("hex:red_heart", config.items().get("red_heart").id());
        assertEquals(10002, config.items().get("red_heart").modelData());
        assertEquals("red_heart", config.itemIds().get("hex:red_heart"));
    }

    @Test
    void parsesSpecialCommandAndSoundActions() {
        HexCustomItemsConfig config = load();

        ItemAction redHeart = config.items().get("red_heart").actions().getFirst();
        assertInstanceOf(SpecialAction.class, redHeart);
        assertEquals("RED_HEART", ((SpecialAction) redHeart).kind());

        List<ItemAction> coin = config.items().get("coin_3").actions();
        CommandAction command = (CommandAction) coin.getFirst();
        assertEquals(CommandExecutorType.CONSOLE, command.executor());
        assertTrue(command.commands().getFirst().contains("eco give %player% 3"));
        assertTrue(coin.stream().anyMatch(a -> a instanceof SoundAction));
    }

    @Test
    void itemsWithoutUseActionsAreAllowed() {
        HexCustomItemsConfig config = load();

        assertTrue(config.items().get("boss_ticket").actions().isEmpty());
        assertTrue(config.items().get("ancient_scale").actions().isEmpty());
    }

    @Test
    void readsCustomCraftingAndMobDrops() {
        HexCustomItemsConfig config = load();

        HexCustomItemsConfig.RecipeSpec recipe = config.recipes().items().get("efficiency_6_book");
        assertEquals("hex:efficiency_6_book", recipe.result());
        assertEquals(Material.ENCHANTED_BOOK, recipe.ingredients().get("E").material());
        assertEquals("efficiency", recipe.ingredients().get("E").enchantment());
        assertEquals(5, recipe.ingredients().get("E").enchantmentLevel());
        assertEquals("hex:ancient_scale", recipe.ingredients().get("B").customItemId());

        assertTrue(config.mobDrops().byMob().containsKey(EntityType.GUARDIAN));
        assertEquals("hex:ancient_scale", config.mobDrops().byMob().get(EntityType.GUARDIAN).getFirst().item());
    }
}
