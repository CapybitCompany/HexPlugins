package hexnpc.shop.inventory;

import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.function.Predicate;

/**
 * Buduje predykaty rozstrzygające, czy dany {@link ItemStack} z ekwipunku
 * pasuje do reguły sprzedaży shopu.
 *
 * <ul>
 *   <li><b>PLAIN_MATERIAL</b> — wymaga zgodności materiału. Gdy
 *       {@code preventSellingCustomItems = true}, dodatkowo odrzuca
 *       wszystko, co wygląda na customizowane (nazwa, lore, enchant,
 *       PDC, CustomModelData, zużycie).</li>
 *   <li><b>EXACT_ITEM</b> — wymaga zgodności typu i pełnej zgodności
 *       meta. Pola wprost konfigurowalne (displayName, lore, enchants,
 *       CustomModelData) sprawdzamy manualnie. Specjalistyczne podtypy
 *       meta (PotionMeta, SkullMeta, BookMeta, EnchantmentStorageMeta,
 *       FireworkMeta, MapMeta, BannerMeta, ArmorMeta itd.) porównujemy
 *       przez {@link ItemFactory#equals(ItemMeta, ItemMeta)} i wymóg
 *       identyczności klasy meta — dzięki czemu specyficzne pola tych
 *       podtypów też uczestniczą w porównaniu. Dodatkowo odrzucamy
 *       kandydatów z „podejrzaną" metą (PDC, damage > 0,
 *       AttributeModifiers, ItemFlags, unbreakable).</li>
 * </ul>
 */
public final class SellMatchPredicate {

    private SellMatchPredicate() {
    }

    /**
     * Buduje predykat. Dla EXACT_ITEM wywołujący powinien podać szablon
     * z {@link ShopItemStackFactory#exactTemplate(ShopItem)}, by w
     * porównaniu uwzględnić skonfigurowane displayName/lore.
     *
     * EXACT_ITEM celowo nie deleguje do
     * {@link ItemStack#isSimilar(ItemStack)} — niektóre środowiska
     * (m.in. MockBukkit) sprowadzają isSimilar do porównania typu, co
     * dawałoby fałszywe trafienia. Porównujemy więc pola jawnie, dzięki
     * czemu reguła jest deterministyczna niezależnie od implementacji.
     */
    public static Predicate<ItemStack> of(ShopItem item, ItemStack iconTemplate, boolean preventCustomItems) {
        SellMatch match = item.sellMatch();
        if (match == SellMatch.EXACT_ITEM) {
            return exactMatch(iconTemplate);
        }
        return plainMatch(item.material(), preventCustomItems);
    }

    private static Predicate<ItemStack> exactMatch(ItemStack template) {
        return stack -> {
            if (stack == null || template == null) {
                return false;
            }
            if (stack.getType() != template.getType()) {
                return false;
            }
            // Bierzemy meta przez getItemMeta() (nie hasItemMeta()), bo
            // dla AIR-niepustych stacków zwraca świeżą pustą metę
            // właściwego podtypu — co pozwala porównywać empty-vs-empty
            // i zachowuje informację o klasie podtypu.
            ItemMeta a = stack.getItemMeta();
            ItemMeta b = template.getItemMeta();
            return metaEquals(a, b);
        };
    }

    /**
     * Porównanie meta dla EXACT_ITEM. Łączy trzy warstwy:
     *
     * <ol>
     *   <li>Manualne porównanie pól wprost konfigurowalnych
     *       (displayName, lore, enchants, CustomModelData). Działa
     *       deterministycznie nawet gdy podstawowy
     *       {@link ItemFactory#equals} jest mniej rygorystyczny.</li>
     *   <li>Odrzucenie wszystkich „custom" pól, których szablon nigdy
     *       nie ustawia (PDC, damage, AttributeModifiers, ItemFlags,
     *       unbreakable).</li>
     *   <li>Pełne porównanie specjalistycznych podtypów meta — wymóg
     *       identyczności klasy oraz wynik {@code true} z
     *       {@link ItemFactory#equals}. Dzięki temu PotionEffects,
     *       skull owner, strony książki, banner patterns, armor trims
     *       itd. faktycznie biorą udział w decyzji.</li>
     * </ol>
     */
    private static boolean metaEquals(ItemMeta a, ItemMeta b) {
        // 1. Pola wprost konfigurowane.
        if (!equalDisplayName(a, b)) {
            return false;
        }
        if (!equalLore(a, b)) {
            return false;
        }
        if (!equalEnchants(a, b)) {
            return false;
        }
        if (!equalCustomModelData(a, b)) {
            return false;
        }
        // 2. Pola, których szablon nigdy nie ma — kandydat też nie może.
        if (hasPdcEntries(a) || hasPdcEntries(b)) {
            return false;
        }
        if (nonZeroDamage(a) || nonZeroDamage(b)) {
            return false;
        }
        if (hasAttributeModifiers(a) || hasAttributeModifiers(b)) {
            return false;
        }
        if (hasItemFlagsSet(a) || hasItemFlagsSet(b)) {
            return false;
        }
        if (isUnbreakable(a) || isUnbreakable(b)) {
            return false;
        }
        // 3. Specjalistyczne podtypy meta.
        if (!sameConcreteMetaType(a, b)) {
            return false;
        }
        if (!fullMetaEquals(a, b)) {
            return false;
        }
        return true;
    }

    private static boolean sameConcreteMetaType(ItemMeta a, ItemMeta b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        // Wymagamy dokładnie tej samej klasy, żeby np. PotionMetaMock
        // nie zrównał się z gołym ItemMetaMock.
        return a.getClass().equals(b.getClass());
    }

    private static boolean fullMetaEquals(ItemMeta a, ItemMeta b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        try {
            return Bukkit.getItemFactory().equals(a, b);
        } catch (Throwable t) {
            // ItemFactory.equals może rzucić dla niezgodnych typów —
            // klasa już dopasowana wyżej, więc spadamy na bezpośredni
            // equals, który każda implementacja podklasy meta nadpisuje.
            try {
                return a.equals(b);
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    private static boolean equalDisplayName(ItemMeta a, ItemMeta b) {
        boolean aHas = a != null && a.hasDisplayName();
        boolean bHas = b != null && b.hasDisplayName();
        if (aHas != bHas) {
            return false;
        }
        return !aHas || java.util.Objects.equals(a.displayName(), b.displayName());
    }

    private static boolean equalLore(ItemMeta a, ItemMeta b) {
        boolean aHas = a != null && a.hasLore();
        boolean bHas = b != null && b.hasLore();
        if (aHas != bHas) {
            return false;
        }
        return !aHas || java.util.Objects.equals(a.lore(), b.lore());
    }

    private static boolean equalEnchants(ItemMeta a, ItemMeta b) {
        boolean aHas = a != null && a.hasEnchants();
        boolean bHas = b != null && b.hasEnchants();
        if (aHas != bHas) {
            return false;
        }
        return !aHas || java.util.Objects.equals(a.getEnchants(), b.getEnchants());
    }

    private static boolean equalCustomModelData(ItemMeta a, ItemMeta b) {
        boolean aHas = a != null && a.hasCustomModelData();
        boolean bHas = b != null && b.hasCustomModelData();
        if (aHas != bHas) {
            return false;
        }
        return !aHas || a.getCustomModelData() == b.getCustomModelData();
    }

    private static boolean hasPdcEntries(ItemMeta meta) {
        if (meta == null) {
            return false;
        }
        try {
            return meta.getPersistentDataContainer() != null
                    && !meta.getPersistentDataContainer().isEmpty();
        } catch (Throwable t) {
            // Niektóre forki mogą rzucać przy dostępie do PDC;
            // bezpieczniej odrzucić niż wpuścić nieznany stan.
            return true;
        }
    }

    private static boolean nonZeroDamage(ItemMeta meta) {
        if (!(meta instanceof Damageable damageable)) {
            return false;
        }
        try {
            return damageable.hasDamage() && damageable.getDamage() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAttributeModifiers(ItemMeta meta) {
        if (meta == null) {
            return false;
        }
        try {
            if (!meta.hasAttributeModifiers()) {
                return false;
            }
            var modifiers = meta.getAttributeModifiers();
            return modifiers != null && !modifiers.isEmpty();
        } catch (Throwable t) {
            // Jeśli API niedostępne — obecność traktujemy jako podejrzaną.
            return true;
        }
    }

    private static boolean hasItemFlagsSet(ItemMeta meta) {
        if (meta == null) {
            return false;
        }
        try {
            return meta.getItemFlags() != null && !meta.getItemFlags().isEmpty();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isUnbreakable(ItemMeta meta) {
        if (meta == null) {
            return false;
        }
        try {
            return meta.isUnbreakable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Predicate<ItemStack> plainMatch(Material expected, boolean preventCustomItems) {
        return stack -> {
            if (stack == null) {
                return false;
            }
            if (stack.getType() != expected) {
                return false;
            }
            if (!preventCustomItems) {
                return true;
            }
            return isPlain(stack);
        };
    }

    /**
     * Zwraca true, jeśli stos jest „nagim" vanilla itemem — bez nazwy,
     * lore, enchantów, PDC, CustomModelData ani uszkodzenia.
     */
    public static boolean isPlain(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName()) {
            return false;
        }
        if (meta.hasLore()) {
            return false;
        }
        if (meta.hasEnchants()) {
            return false;
        }
        if (meta.hasCustomModelData()) {
            return false;
        }
        if (hasPdcEntries(meta)) {
            return false;
        }
        if (nonZeroDamage(meta)) {
            return false;
        }
        if (hasAttributeModifiers(meta)) {
            return false;
        }
        if (isUnbreakable(meta)) {
            return false;
        }
        return true;
    }
}
