package hexcustomitems.config;

import hexcustomitems.model.CommandAction;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.ItemAction;
import hexcustomitems.model.MessageAction;
import hexcustomitems.model.SelfPotionAction;
import hexcustomitems.model.SoundAction;
import hexcustomitems.support.PluginTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexCustomItemsConfigLoaderTest extends PluginTestBase {

    private HexCustomItemsConfig load() {
        return new HexCustomItemsConfigLoader(plugin).load();
    }

    @Test
    void defaultConfigLoadsSevenItems() {
        HexCustomItemsConfig config = load();
        assertEquals(7, config.items().size());
        assertTrue(config.items().keySet().containsAll(List.of(
                "jump_potion", "invisibility_cookie", "speed_potion",
                "hex_coin_1", "hex_coin_2", "hex_coin_3", "hex_coin_5")));
    }

    @Test
    void parsesAllActionTypes() {
        HexCustomItemsConfig config = load();

        // SELF_POTION + SOUND
        List<ItemAction> jump = config.items().get("jump_potion").actions();
        assertInstanceOf(SelfPotionAction.class, jump.get(0));
        assertTrue(jump.stream().anyMatch(a -> a instanceof SoundAction));

        // COMMAND (CONSOLE, "eco give") + MESSAGE + SOUND
        List<ItemAction> coin = config.items().get("hex_coin_1").actions();
        CommandAction command = (CommandAction) coin.get(0);
        assertEquals(CommandExecutorType.CONSOLE, command.executor());
        assertTrue(command.commands().get(0).contains("eco give"));
        assertTrue(coin.stream().anyMatch(a -> a instanceof MessageAction));
        assertTrue(coin.stream().anyMatch(a -> a instanceof SoundAction));

        // MESSAGE existiert auch im Ciastko
        assertTrue(config.items().get("invisibility_cookie").actions().stream()
                .anyMatch(a -> a instanceof MessageAction));
    }

    @Test
    void legacySelfPotionEffectIsTranslatedToAction() {
        plugin.getConfig().set("items.legacy_self.material", "POTION");
        plugin.getConfig().set("items.legacy_self.name", "<gold>Legacy");
        plugin.getConfig().set("items.legacy_self.effect.type", "SELF_POTION");
        plugin.getConfig().set("items.legacy_self.effect.potion", "SPEED");
        plugin.getConfig().set("items.legacy_self.effect.duration-seconds", 5);
        plugin.getConfig().set("items.legacy_self.effect.amplifier", 0);

        CustomItemDefinition item = load().items().get("legacy_self");
        assertTrue(item.actions().stream().anyMatch(a -> a instanceof SelfPotionAction));
    }

    @Test
    void legacyHexCoinsEffectIsTranslatedToCommand() {
        plugin.getConfig().set("items.legacy_coins.material", "PAPER");
        plugin.getConfig().set("items.legacy_coins.name", "<gold>Coins");
        plugin.getConfig().set("items.legacy_coins.effect.type", "HEX_COINS");
        plugin.getConfig().set("items.legacy_coins.effect.coins", 3);
        plugin.getConfig().set("items.legacy_coins.effect.command-template", "eco give %player% %coins%");

        List<ItemAction> actions = load().items().get("legacy_coins").actions();
        CommandAction command = (CommandAction) actions.stream()
                .filter(a -> a instanceof CommandAction).findFirst().orElseThrow();
        assertEquals(CommandExecutorType.CONSOLE, command.executor());
        assertTrue(command.commands().get(0).contains("3"));
    }

    @Test
    void itemWithOnlyInvalidActionIsSkipped() {
        plugin.getConfig().set("items.broken.material", "STONE");
        plugin.getConfig().set("items.broken.name", "<gold>Broken");
        plugin.getConfig().set("items.broken.actions", List.of(Map.of("type", "NONSENSE")));

        assertFalse(load().items().containsKey("broken"));
    }

    @Test
    void readsRecipesRegionCooldownsAndLegacyCommands() {
        HexCustomItemsConfig config = load();

        assertTrue(config.recipes().enabled());
        assertEquals("shaped", config.recipes().items().get("jump_potion").type());
        assertTrue(config.recipes().items().get("hex_coin_1").shapeless());

        assertTrue(config.regionAwareness().enabled());
        assertTrue(config.regionAwareness().respectPvp());

        assertTrue(config.cooldowns().persist());
        assertEquals("cooldowns.yml", config.cooldowns().file());

        assertEquals("jump_potion", config.legacyCommandBindings().get("hex_item_potkaskoku"));
    }
}
