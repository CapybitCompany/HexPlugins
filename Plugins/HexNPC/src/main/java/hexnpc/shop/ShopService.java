package hexnpc.shop;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.economy.TxResult;
import hexnpc.shop.gui.ShopGuiBuilder;
import hexnpc.shop.inventory.InventoryOps;
import hexnpc.shop.inventory.SellMatchPredicate;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.util.LegacyFormat;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Główny serwis shopów HexNPC. Otwiera GUI i prowadzi maszynę stanów
 * transakcji buy/sell, dopuszczając tylko jedną aktywną transakcję na
 * gracza (ochrona przed dupowaniem).
 *
 * Model wątkowy:
 *  - Walidacja oraz mutacje ekwipunku zawsze na głównym wątku serwera.
 *  - Operacje ekonomii są asynchroniczne (patrz {@link EconomyBridge}).
 *    Wyniki wracają na główny wątek przed kolejnymi mutacjami.
 */
public final class ShopService {

    private final Plugin plugin;
    private final ShopRegistry registry;
    private final EconomyBridge economy;
    private final Supplier<ShopConfig> configSupplier;
    private final Logger logger;
    private final java.util.Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public ShopService(Plugin plugin,
                       ShopRegistry registry,
                       EconomyBridge economy,
                       Supplier<ShopConfig> configSupplier,
                       Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ShopRegistry registry() {
        return registry;
    }

    public boolean isBusy(Player player) {
        return busy.contains(player.getUniqueId());
    }

    /** Otwiera główny widok shopu dla gracza. Tylko z głównego wątku. */
    public boolean openShop(Player player, String shopId) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null || !cfg.enabled()) {
            send(player, cfg, msgs -> msgs.shopNotFound().replace("<shop>", String.valueOf(shopId)));
            return false;
        }
        Optional<Shop> maybe = registry.find(shopId);
        if (maybe.isEmpty()) {
            send(player, cfg, msgs -> msgs.shopNotFound().replace("<shop>", String.valueOf(shopId)));
            return false;
        }
        Inventory inv = new ShopGuiBuilder(cfg, economy).buildMain(maybe.get());
        player.openInventory(inv);
        return true;
    }

    /** Otwiera widok szczegółów konkretnego itemu. Tylko z głównego wątku. */
    public void openDetail(Player player, Shop shop, ShopItem item) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        Inventory inv = new ShopGuiBuilder(cfg, economy).buildDetail(shop, item);
        player.openInventory(inv);
    }

    /** Zamyka widok szczegółów i wraca do głównego widoku shopu. */
    public void back(Player player, Shop shop) {
        openShop(player, shop.id());
    }

    /**
     * Buy flow:
     *  1. Walidacja miejsca w ekwipunku i dostępności ekonomii.
     *  2. Założenie blokady transakcji (busy).
     *  3. Asynchroniczny withdraw.
     *  4. Po sukcesie wręczamy item na głównym wątku. Jeśli give zawiedzie
     *     (np. race condition na pełnym ekwipunku), uruchamiamy
     *     asynchroniczny refund — i blokadę zwalniamy dopiero po jego
     *     zakończeniu, żeby gracz nie mógł rozpocząć drugiej transakcji
     *     w czasie nieukończonego rollbacku.
     */
    public void buy(Player player, Shop shop, ShopItem item) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        if (!item.hasBuyPrice()) {
            return;
        }
        if (!economy.isAvailable()) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }
        ItemStack stack = ShopItemStackFactory.tradeStack(item);
        if (!InventoryOps.canFitFully(player.getInventory(), stack)) {
            sendMessage(player, cfg.messages().inventoryFull());
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            sendMessage(player, cfg.messages().tradeBusy());
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        BigDecimal price = item.buyPrice();
        // Async withdraw to atomic check: gdy gracza nie stać na stos,
        // backend zwraca success=false z reasonem NOT_ENOUGH_FUNDS.
        // Główny wątek nigdy nie blokuje się na osobnym has().
        economy.withdraw(uuid, name, price, "HexNPC shop buy " + shop.id() + ":" + item.id())
                .whenComplete((result, error) -> runMainThread(() -> {
                    if (error != null || result == null || !result.success()) {
                        try {
                            handleBuyFailure(player, cfg, result, error);
                        } finally {
                            busy.remove(uuid);
                        }
                        return;
                    }
                    ItemStack give = ShopItemStackFactory.tradeStack(item);
                    if (!InventoryOps.giveAllOrNothing(player, give)) {
                        // Refund jest asynchroniczny — busy zwalnia
                        // refundAfterFailedGive po zakończeniu refundu.
                        refundAfterFailedGive(player, shop, item, price,
                                () -> busy.remove(uuid));
                        return;
                    }
                    try {
                        sendMessage(player, cfg.messages().bought()
                                .replace("<amount>", String.valueOf(item.amount()))
                                .replace("<item>", labelOf(item))
                                .replace("<price>", economy.format(price)));
                    } finally {
                        busy.remove(uuid);
                    }
                }));
    }

    private void handleBuyFailure(Player player, ShopConfig cfg, TxResult result, Throwable error) {
        if (result != null && result.isEconomyUnavailable()) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }
        if (result != null && result.isInsufficientFunds()) {
            sendMessage(player, cfg.messages().notEnoughMoney());
            return;
        }
        String reason = error != null ? safe(error.getMessage())
                : (result == null ? "null" : safe(result.reason()));
        sendMessage(player, cfg.messages().transactionFailed()
                .replace("<reason>", reason));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Sell flow:
     *  1. Walidacja, że gracz ma wystarczająco dużo pasujących itemów.
     *  2. Założenie blokady transakcji (busy).
     *  3. Usunięcie itemów all-or-nothing na głównym wątku.
     *  4. Asynchroniczny deposit.
     *  5. Przy nieudanym deposit zwracamy itemy all-or-nothing. Jeśli
     *     ekwipunek nie ma już miejsca (race), wyrzucamy nadmiar pod
     *     stopy gracza.
     */
    public void sell(Player player, Shop shop, ShopItem item) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        if (!item.hasSellPrice()) {
            return;
        }
        if (!economy.isAvailable()) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }
        // Wzorzec dla EXACT_ITEM: meta z konfiguracji bez ozdobników GUI.
        // PLAIN_MATERIAL i tak ignoruje template — używamy więc
        // exactTemplate dla obu trybów; predykat zdecyduje co z tym zrobić.
        ItemStack matchTemplate = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> predicate = SellMatchPredicate.of(item, matchTemplate,
                cfg.preventSellingCustomItems());
        if (InventoryOps.countMatching(player.getInventory(), predicate) < item.amount()) {
            sendMessage(player, cfg.messages().notEnoughItems());
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            sendMessage(player, cfg.messages().tradeBusy());
            return;
        }
        Optional<List<ItemStack>> removedOpt = InventoryOps.removeAllOrNothing(
                player.getInventory(), predicate, item.amount());
        if (removedOpt.isEmpty()) {
            busy.remove(player.getUniqueId());
            sendMessage(player, cfg.messages().notEnoughItems());
            return;
        }
        // Snapshot rzeczywiście zabranych stosów — z nazwą, lore,
        // enchantami, PDC, uszkodzeniem itp. Przy nieudanym deposit
        // oddamy dokładnie te stosy, nie syntetyzowany material+amount.
        List<ItemStack> removedStacks = removedOpt.get();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        BigDecimal price = item.sellPrice();
        economy.deposit(uuid, name, price, "HexNPC shop sell " + shop.id() + ":" + item.id())
                .whenComplete((result, error) -> runMainThread(() -> {
                    try {
                        if (error != null || result == null || !result.success()) {
                            returnRemovedOrDrop(player, shop, item, removedStacks);
                            sendSellFailureMessage(player, cfg, result, error);
                            return;
                        }
                        sendMessage(player, cfg.messages().sold()
                                .replace("<amount>", String.valueOf(item.amount()))
                                .replace("<item>", labelOf(item))
                                .replace("<price>", economy.format(price)));
                    } finally {
                        busy.remove(uuid);
                    }
                }));
    }

    private void sendSellFailureMessage(Player player, ShopConfig cfg, TxResult result, Throwable error) {
        if (result != null && result.isEconomyUnavailable()) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }
        String reason = error != null ? safe(error.getMessage())
                : (result == null ? "null" : safe(result.reason()));
        sendMessage(player, cfg.messages().transactionFailed()
                .replace("<reason>", reason));
    }

    /**
     * Refunduje pobraną kwotę po nieudanym wręczeniu itemu. Wywołuje
     * {@code onDone} dokładnie raz, niezależnie od wyniku — w tym
     * miejscu zwalniana jest blokada transakcji gracza.
     */
    private void refundAfterFailedGive(Player player, Shop shop, ShopItem item,
                                       BigDecimal price, Runnable onDone) {
        ShopConfig cfg = configSupplier.get();
        sendMessage(player, cfg.messages().inventoryFull());
        economy.deposit(player.getUniqueId(), player.getName(), price,
                "HexNPC shop refund " + shop.id() + ":" + item.id())
                .whenComplete((refund, refundError) -> runMainThread(() -> {
                    try {
                        if (refundError != null || refund == null || !refund.success()) {
                            String reason = refundError != null ? refundError.getMessage()
                                    : (refund == null ? "null" : refund.reason());
                            logger.log(Level.WARNING,
                                    "HexNPC: refund failed for player " + player.getName()
                                            + " shop=" + shop.id() + " item=" + item.id()
                                            + " price=" + price + " reason=" + reason);
                            sendMessage(player, cfg.messages().transactionFailed()
                                    .replace("<reason>", reason == null ? "refund-failed" : reason));
                        }
                    } finally {
                        if (onDone != null) {
                            onDone.run();
                        }
                    }
                }));
    }

    /**
     * Oddaje graczowi dokładnie te stosy, które wcześniej zabraliśmy.
     * Gdy w ekwipunku nie ma miejsca na całość, resztę wyrzucamy pod
     * stopy gracza i logujemy ostrzeżenie. Tożsamość itemu (nazwa, lore,
     * enchant, PDC) jest zachowana w obie strony.
     */
    private void returnRemovedOrDrop(Player player, Shop shop, ShopItem item, List<ItemStack> removed) {
        if (removed.isEmpty()) {
            return;
        }
        if (InventoryOps.giveAllOrNothing(player, removed)) {
            return;
        }
        // All-or-nothing nie zadziałało: oddajemy ile się da per slot,
        // resztę dropujemy — nic nie zostaje zduplikowane, nic nie znika.
        for (ItemStack stack : removed) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack.clone());
            for (ItemStack remaining : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), remaining);
            }
        }
        logger.warning("HexNPC: deposit failed and inventory was full — partially returned + dropped "
                + item.amount() + "x " + item.material() + " for " + player.getName()
                + " (shop=" + shop.id() + " item=" + item.id() + ")");
    }

    private void runMainThread(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private void send(Player player, ShopConfig cfg, java.util.function.Function<hexnpc.shop.config.ShopMessages, String> picker) {
        if (cfg == null) {
            return;
        }
        sendMessage(player, picker.apply(cfg.messages()));
    }

    private void sendMessage(Player player, String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        player.sendMessage(LegacyFormat.component(legacy));
    }

    private String labelOf(ShopItem item) {
        if (item.displayName() != null && !item.displayName().isEmpty()) {
            return item.displayName();
        }
        return item.material().name().toLowerCase().replace('_', ' ');
    }
}
