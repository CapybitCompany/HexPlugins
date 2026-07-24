package hexcustomitems.support;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CommandAction;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.ItemAction;
import hexcustomitems.model.PotionEffectSpec;
import hexcustomitems.model.SelfPotionAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;

/**
 * Test-Helfer zum Bauen valider Config-/Item-Objekte ohne YAML-Boilerplate.
 */
public final class TestConfig {

    private TestConfig() {
    }

    public static PotionEffectType potion(String key) {
        return Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static CustomItemDefinition selfPotionItem(String id, Material material, String potionKey,
                                                      int cooldownSeconds, int charges) {
        List<ItemAction> actions = List.of(
                new SelfPotionAction(new PotionEffectSpec(potion(potionKey), 5, 0), false));
        return new CustomItemDefinition(id, material, "<gold>" + id,
                List.of("<gray>Ładunki: <charges>/<max_charges>"), false, null, cooldownSeconds, charges, actions);
    }

    public static CustomItemDefinition commandItem(String id, Material material, CommandExecutorType executor,
                                                   List<String> commands, boolean offensive) {
        List<ItemAction> actions = List.of(new CommandAction(executor, commands, offensive));
        return new CustomItemDefinition(id, material, "<gold>" + id, List.of(), false, null, 0, 0, actions);
    }

    public static CustomItemDefinition item(String id, Material material, String permission,
                                            int cooldownSeconds, int charges, List<ItemAction> actions) {
        return new CustomItemDefinition(id, material, "<gold>" + id, List.of(), false, permission,
                cooldownSeconds, charges, actions);
    }

    public static HexCustomItemsConfig withItems(Map<String, CustomItemDefinition> items) {
        return withItems(items, defaultRecipes(), "true");
    }

    public static HexCustomItemsConfig withItems(Map<String, CustomItemDefinition> items,
                                                 HexCustomItemsConfig.Recipes recipes,
                                                 String itemDefault) {
        return new HexCustomItemsConfig(
                "<gray>[HCI] ",
                "hex.items.give",
                "hex.items.reload",
                itemDefault,
                64,
                "<gold>Menu",
                16,
                defaultMessages(),
                new HexCustomItemsConfig.Sounds("item.book.page_turn", "entity.generic.drink"),
                new HexCustomItemsConfig.RegionAwareness(true, false, true, "<red>Nie tutaj."),
                new HexCustomItemsConfig.Cooldowns(true, "cooldowns.yml"),
                recipes,
                items,
                Map.of()
        );
    }

    public static HexCustomItemsConfig.Recipes defaultRecipes() {
        return new HexCustomItemsConfig.Recipes(false, Map.of());
    }

    public static HexCustomItemsConfig.Messages defaultMessages() {
        return new HexCustomItemsConfig.Messages(
                "<red>Brak uprawnień.",
                "<red>Nie możesz użyć.",
                "<red>Gracz offline.",
                "<red>Zła liczba.",
                "<red>Brak itemu <item_id>.",
                "<gray>Użycie.",
                "<gray>Użycie give.",
                "<green>Przeładowano.",
                "<green>Dano <amount>x <item_name> -> <target>.",
                "<gray>Otrzymałeś <amount>x <item_name>.",
                "<gray>Itemy: <items>",
                "<red>Odczekaj <time>s.",
                "<red>Nie możesz wyrzucić."
        );
    }
}
