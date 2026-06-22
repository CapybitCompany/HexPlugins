package hexnpc.shop.inventory;

import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralna fabryka {@link ItemStack} dla shop itemów. Rozróżnia trzy
 * niezależne reprezentacje tego samego {@link ShopItem}:
 *
 * <ul>
 *   <li><b>displayStack</b> — ikona pokazywana w GUI. Może mieć
 *       displayName, lore i dopisane linie z cenami.</li>
 *   <li><b>exactTemplate</b> — wzorzec używany przez SellMatch
 *       {@code EXACT_ITEM}. Zawiera wyłącznie meta skonfigurowane w
 *       shops.yml, bez dopisków GUI.</li>
 *   <li><b>tradeStack</b> — rzeczywisty item wydawany graczowi przy
 *       zakupie. Reguła: jeśli {@code sell-match: PLAIN_MATERIAL}, item
 *       jest „nagi" (sam Material i amount) tak, aby gracz mógł go
 *       następnie sprzedać przez ten sam shop. Dla
 *       {@code EXACT_ITEM} oddajemy pełną skonfigurowaną metę, bo
 *       wzorzec sprzedaży i tak jej oczekuje.</li>
 * </ul>
 *
 * Trzymanie tych trzech wariantów osobno zapobiega błędom typu „kupiłem
 * w shopie i nie mogę sprzedać", gdzie wydany Itemstack ma displayName,
 * a PLAIN_MATERIAL go odrzuca.
 */
public final class ShopItemStackFactory {

    private ShopItemStackFactory() {
    }

    /**
     * Wzorzec do dopasowania w trybie EXACT_ITEM. Materiał + amount oraz
     * meta z konfiguracji (displayName, lore). Bez dopisków GUI.
     */
    public static ItemStack exactTemplate(ShopItem item) {
        ItemStack stack = new ItemStack(item.material(), item.amount());
        applyConfiguredMeta(stack, item, List.of());
        return stack;
    }

    /**
     * Item wręczany graczowi przy zakupie. Dla PLAIN_MATERIAL wracamy
     * gołym materiałem (żeby gracz mógł go potem sprzedać). Dla
     * EXACT_ITEM zachowujemy meta z konfiguracji.
     */
    public static ItemStack tradeStack(ShopItem item) {
        if (item.sellMatch() == SellMatch.PLAIN_MATERIAL) {
            return new ItemStack(item.material(), item.amount());
        }
        return exactTemplate(item);
    }

    /**
     * Ikona w GUI. Pokazujemy skonfigurowaną metę plus dodatkowe linie
     * lore (np. ceny). Nie używać jako wzorca matchowania ani jako
     * realnego itemu do oddania graczowi.
     */
    public static ItemStack displayStack(ShopItem item, List<Component> extraLore) {
        ItemStack stack = new ItemStack(item.material(), item.amount());
        applyConfiguredMeta(stack, item, extraLore == null ? List.of() : extraLore);
        return stack;
    }

    private static void applyConfiguredMeta(ItemStack stack, ShopItem item, List<Component> extraLore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        String displayName = item.displayName();
        if (displayName != null && !displayName.isEmpty()) {
            meta.displayName(LegacyFormat.component(displayName));
        }
        List<Component> lore = new ArrayList<>();
        for (String line : item.lore()) {
            lore.add(LegacyFormat.component(line));
        }
        lore.addAll(extraLore);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
    }
}
