package hexnpc.shop;

import hexnpc.shop.audit.AuditAction;
import hexnpc.shop.audit.AuditEntry;
import hexnpc.shop.audit.AuditStatus;
import hexnpc.shop.audit.ShopAuditLog;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.economy.TxResult;
import hexnpc.shop.gui.ConfirmAction;
import hexnpc.shop.gui.SellAllQuote;
import hexnpc.shop.gui.ShopGuiBuilder;
import hexnpc.shop.inventory.InventoryOps;
import hexnpc.shop.inventory.SellMatchPredicate;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.item.HexCustomItemsBridge;
import hexnpc.shop.model.ShopItemActionType;
import hexnpc.shop.model.ShopRewardType;
import hexnpc.shop.limit.DailyBuyLimitService;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopCurrency;
import hexnpc.shop.sign.SignInputService;
import hexnpc.util.LegacyFormat;
import hexnpc.workflow.WorkflowService;
import hexnpc.shop.model.ShopItemAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Sound;
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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Główny serwis sklepów HexNPC. Otwiera GUI (paginacja, wybór ilości,
 * potwierdzenie dużych transakcji) i prowadzi maszynę stanów buy/sell,
 * dopuszczając tylko jedną aktywną transakcję na gracza (ochrona przed dupem).
 *
 * <p>Wejścia GUI ({@code requestBuy/requestSell/requestSellAll}) mogą najpierw
 * pokazać widok potwierdzenia; autorytatywne wykonanie i pełną walidację
 * zapewniają metody {@code buy/sell/sellAll}, które są też punktem wejścia dla
 * przycisku „Potwierdź". Audyt jest zapisywany fire-and-forget i nigdy nie
 * wpływa na wynik finansowy.
 */
public final class ShopService {

    private final Plugin plugin;
    private final ShopRegistry registry;
    private final EconomyBridge economy;
    private final Supplier<ShopConfig> configSupplier;
    private final Logger logger;
    private final DailyBuyLimitService buyLimits;
    private final SignInputService signService;
    private final ShopAuditLog auditLog;
    private final HexCustomItemsBridge customItems;
    private final WorkflowService workflowService;
    private final java.util.Set<UUID> busy = ConcurrentHashMap.newKeySet();

    /** Konstruktor pełny — używany przez plugin z współdzielonymi serwisami. */
    public ShopService(Plugin plugin,
                       ShopRegistry registry,
                       EconomyBridge economy,
                       Supplier<ShopConfig> configSupplier,
                       Logger logger,
                       DailyBuyLimitService buyLimits,
                       SignInputService signService,
                       ShopAuditLog auditLog,
                       WorkflowService workflowService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.buyLimits = buyLimits != null ? buyLimits
                : new DailyBuyLimitService(null, logger);
        this.signService = signService != null ? signService
                : new SignInputService(plugin, configSupplier);
        this.auditLog = auditLog;
        this.customItems = new HexCustomItemsBridge(logger);
        this.workflowService = workflowService;
    }

    /** Full 1.3/1.4-compatible constructor. */
    public ShopService(Plugin plugin,
                       ShopRegistry registry,
                       EconomyBridge economy,
                       Supplier<ShopConfig> configSupplier,
                       Logger logger,
                       DailyBuyLimitService buyLimits,
                       SignInputService signService,
                       ShopAuditLog auditLog) {
        this(plugin, registry, economy, configSupplier, logger, buyLimits, signService, auditLog, null);
    }

    public ShopService(Plugin plugin, ShopRegistry registry, EconomyBridge economy,
                       Supplier<ShopConfig> configSupplier, Logger logger,
                       DailyBuyLimitService buyLimits, SignInputService signService) {
        this(plugin, registry, economy, configSupplier, logger, buyLimits, signService, null);
    }

    /** Konstruktor kompatybilny wstecz (serwisy pomocnicze tworzone wewnętrznie). */
    public ShopService(Plugin plugin, ShopRegistry registry, EconomyBridge economy,
                       Supplier<ShopConfig> configSupplier, Logger logger) {
        this(plugin, registry, economy, configSupplier, logger, null, null, null);
    }

    public ShopRegistry registry() {
        return registry;
    }

    public boolean isBusy(Player player) {
        return busy.contains(player.getUniqueId());
    }

    /**
     * Kontener błędów GUI. LinkageError jest istotny przy opcjonalnych pluginach/API:
     * nie może już powodować „cichego” przerwania kliknięcia NPC bez diagnostyki.
     */
    private boolean openInventorySafely(Player player, Shop shop, String view,
                                        Supplier<Inventory> inventoryFactory) {
        try {
            Inventory inv = inventoryFactory.get();
            player.openInventory(inv);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            String shopId = shop == null ? "<unknown>" : shop.id();
            logger.log(Level.SEVERE,
                    "HexNPC: failed to open shop '" + shopId + "' view=" + view
                            + " for player=" + player.getName(), ex);
            sendMessage(player, "&cNie udało się otworzyć sklepu. Zgłoś problem administracji.");
            return false;
        }
    }

    // ================= Otwieranie GUI =================

    public boolean openShop(Player player, String shopId) {
        return openShop(player, shopId, 0);
    }

    /** Otwiera główny widok shopu na danej stronie. Tylko z głównego wątku. */
    public boolean openShop(Player player, String shopId, int page) {
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
        Shop shop = maybe.get();
        return openInventorySafely(player, shop, "MAIN", () ->
                new ShopGuiBuilder(cfg, economy, customItems).buildMain(shop, page, player));
    }

    /** Otwiera widok szczegółów z domyślną ilością (bazowy amount). */
    public void openDetail(Player player, Shop shop, ShopItem item) {
        openDetail(player, shop, item, defaultQuantity(item), 0);
    }

    /** Otwiera widok szczegółów z konkretną wybraną ilością i stroną źródłową. */
    public void openDetail(Player player, Shop shop, ShopItem item, int selectedQuantity, int originPage) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        if (alreadyOwned(player, item) && item.hasOwnedAction()) {
            executeConfiguredAction(player, shop, item, item.ownedAction());
            return;
        }
        int qty = clampQuantity(selectedQuantity);
        int buyRemaining = buyRemaining(player.getUniqueId(), shop, item);
        SellAllQuote quote = computeSellAllQuote(player, shop, item, cfg);
        if (item.singlePurchaseView()) {
            openInventorySafely(player, shop, "DETAIL_SINGLE", () ->
                    new ShopGuiBuilder(cfg, economy, customItems)
                            .buildSinglePurchase(shop, item, originPage));
            return;
        }
        openInventorySafely(player, shop, "DETAIL", () ->
                new ShopGuiBuilder(cfg, economy, customItems)
                        .buildDetail(shop, item, qty, originPage, buyRemaining, quote));
    }

    /** Wraca z widoku szczegółów do głównego widoku na stronie źródłowej. */
    public void back(Player player, Shop shop, int originPage) {
        openShop(player, shop.id(), originPage);
    }
    /**
     * Akcja nietransakcyjnego kafla w MAIN (np. wejście do Particle-Effects).
     * Komenda działa jako gracz; brak ceny oznacza, że HexNPC niczego nie pobiera.
     */
    public void executeItemAction(Player player, Shop shop, ShopItem item) {
        if (item == null) return;
        executeConfiguredAction(player, shop, item, item.action());
    }

    private void executeConfiguredAction(Player player, Shop shop, ShopItem item, ShopItemAction action) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null || action == null || action.type() == ShopItemActionType.NONE) return;
        if (action.type() == ShopItemActionType.DETAILS) {
            openDetail(player, shop, item, item.amount(), 0);
            return;
        }
        if (action.type() == ShopItemActionType.RUN_WORKFLOW) {
            if (workflowService == null || !workflowService.canRun(action.workflow())) {
                sendMessage(player, cfg.messages().actionFailed());
                logger.warning("HexNPC: shop '" + shop.id() + "' item '" + item.id()
                        + "' references unavailable workflow '" + action.workflow() + "'");
                return;
            }
            player.closeInventory();
            workflowService.startWorkflow(player, action.workflow(),
                    "shop:" + shop.id(), item.id(), "left_click");
            return;
        }
        if (action.type() == ShopItemActionType.PLAYER_COMMAND) {
            String command = renderCommand(action.command(), player, shop, item, item.amount());
            if (command.startsWith("/")) command = command.substring(1);
            String finalCommand = command;
            player.closeInventory();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                boolean ok = player.performCommand(finalCommand);
                if (!ok) sendMessage(player, cfg.messages().actionFailed());
            });
        }
    }


    /** Publiczna migawka oferty „Sprzedaj wszystko" (podgląd; liczona ponownie przy akcji). */
    public SellAllQuote sellAllQuote(Player player, Shop shop, ShopItem item) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return SellAllQuote.empty();
        }
        return computeSellAllQuote(player, shop, item, cfg);
    }

    /**
     * „Sprzedaj wszystko": bieżąca liczba pasujących sztuk i proporcjonalny
     * gesamtpreis. To tylko podgląd — przy kliknięciu/potwierdzeniu liczone
     * ponownie. Używa DOKŁADNIE tego samego predykatu co realna transakcja.
     */
    private SellAllQuote computeSellAllQuote(Player player, Shop shop, ShopItem item, ShopConfig cfg) {
        if (!cfg.enableSellAll() || !item.hasSellPrice()) {
            return SellAllQuote.empty();
        }
        Predicate<ItemStack> predicate = sellPredicate(item, cfg);
        int amount = InventoryOps.countMatching(player.getInventory(), predicate);
        if (amount <= 0) {
            return SellAllQuote.empty();
        }
        BigDecimal price = PriceCalculator.total(item.sellPrice(), item.amount(), amount, priceScale(shop, cfg));
        return new SellAllQuote(amount, price);
    }

    // ================= Wejścia GUI (bramka potwierdzenia) =================

    /** Wejście GUI dla kupna: może pokazać potwierdzenie, zanim wykona. */
    public void requestBuy(Player player, Shop shop, ShopItem item, int quantity, int originPage) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        int qty = item.singlePurchaseView() ? defaultQuantity(item) : clampQuantity(quantity);
        if (!item.singlePurchaseView() && cfg.confirmation().requiresConfirmation(qty)) {
            openConfirmation(player, shop, item, ConfirmAction.BUY, qty, originPage);
            return;
        }
        buy(player, shop, item, qty);
    }

    /** Wejście GUI dla sprzedaży wybranej ilości. */
    public void requestSell(Player player, Shop shop, ShopItem item, int quantity, int originPage) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        int qty = clampQuantity(quantity);
        if (cfg.confirmation().requiresConfirmation(qty)) {
            openConfirmation(player, shop, item, ConfirmAction.SELL, qty, originPage);
            return;
        }
        sell(player, shop, item, qty);
    }

    /** Wejście GUI dla „Sprzedaj wszystko": liczy aktualną ilość i ewentualnie potwierdza. */
    public void requestSellAll(Player player, Shop shop, ShopItem item, int originPage) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        if (!cfg.enableSellAll()) {
            return;
        }
        if (!item.hasSellPrice()) {
            sendMessage(player, cfg.messages().sellDisabled());
            return;
        }
        Predicate<ItemStack> predicate = sellPredicate(item, cfg);
        int available = InventoryOps.countMatching(player.getInventory(), predicate);
        if (available <= 0) {
            sendMessage(player, cfg.messages().nothingToSell());
            return;
        }
        if (cfg.confirmation().requiresConfirmation(available)) {
            openConfirmation(player, shop, item, ConfirmAction.SELL_ALL, available, originPage);
            return;
        }
        sellAll(player, shop, item);
    }

    private void openConfirmation(Player player, Shop shop, ShopItem item,
                                  ConfirmAction action, int quantity, int originPage) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        BigDecimal price = action == ConfirmAction.BUY
                ? PriceCalculator.total(item.buyPrice(), item.amount(), quantity, priceScale(shop, cfg))
                : PriceCalculator.total(item.sellPrice(), item.amount(), quantity, priceScale(shop, cfg));
        openInventorySafely(player, shop, "CONFIRM", () ->
                new ShopGuiBuilder(cfg, economy, customItems)
                        .buildConfirmation(shop, item, action, quantity, price, originPage));
    }

    /** Przycisk „Potwierdź": wykonuje bezpośrednio wewnętrzny, walidujący tor. */
    public void confirmTransaction(Player player, Shop shop, ShopItem item,
                                   ConfirmAction action, int quantity, int originPage) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        // Zamykamy GUI potwierdzenia — brak podwójnego kliknięcia w confirm.
        player.closeInventory();
        // Ponowne, autorytatywne rozwiązanie sklepu/itemu (obrona przed reloadem).
        Shop current = registry.find(shop.id()).orElse(null);
        if (current == null) {
            sendMessage(player, cfg.messages().shopNotFound().replace("<shop>", shop.id()));
            return;
        }
        ShopItem currentItem = current.item(item.id()).orElse(null);
        if (currentItem == null) {
            sendMessage(player, cfg.messages().shopNotFound().replace("<shop>", shop.id()));
            return;
        }
        switch (action) {
            case BUY -> buy(player, current, currentItem, quantity);
            case SELL -> sell(player, current, currentItem, quantity);
            case SELL_ALL -> sellAll(player, current, currentItem);
        }
    }

    /** Przycisk „Anuluj": wraca do widoku szczegółów bez żadnej zmiany. */
    public void cancelConfirmation(Player player, Shop shop, ShopItem item, int quantity, int originPage) {
        openDetail(player, shop, item, quantity, originPage);
    }

    // ================= Kupno (autorytatywne wykonanie) =================

    public void buy(Player player, Shop shop, ShopItem item) {
        buy(player, shop, item, defaultQuantity(item));
    }

    /**
     * Buy flow: walidacja miejsca i limitu dziennego, blokada (busy),
     * asynchroniczny withdraw, wydanie itemów (wielo-stosowo) all-or-nothing,
     * refund przy nieudanym wydaniu, zapis dziennego limitu po sukcesie, audyt.
     */
    public void buy(Player player, Shop shop, ShopItem item, int quantity) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) return;
        if (!item.hasBuyPrice()) {
            sendMessage(player, cfg.messages().buyDisabled());
            return;
        }
        if (item.hasPostPurchaseAction()
                && item.postPurchaseAction().type() == ShopItemActionType.RUN_WORKFLOW
                && (workflowService == null || !workflowService.canRun(item.postPurchaseAction().workflow()))) {
            sendMessage(player, cfg.messages().actionFailed());
            logger.warning("HexNPC: purchase blocked before charge because post-purchase workflow '"
                    + item.postPurchaseAction().workflow() + "' is unavailable for shop '"
                    + shop.id() + "' item '" + item.id() + "'");
            return;
        }
        if (!economy.isAvailable(shop.currency())) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }

        int qty = item.singlePurchaseView() ? defaultQuantity(item) : clampQuantity(quantity);
        UUID uuid = player.getUniqueId();
        String limitKey = DailyBuyLimitService.key(shop.id(), item.id());
        String txId = UUID.randomUUID().toString();
        BigDecimal price = PriceCalculator.total(item.buyPrice(), item.amount(), qty, priceScale(shop, cfg));

        if (alreadyOwned(player, item)) {
            denyAlreadyPurchased(player, cfg);
            auditDenied(txId, player, shop, item, AuditAction.BUY, qty, price, "ALREADY_PURCHASED");
            return;
        }

        if (item.hasBuyLimit()) {
            int remaining = buyLimits.remaining(uuid, limitKey, item.maxBuyAmount());
            if (qty > remaining) {
                sendMessage(player, cfg.messages().buyLimitReached()
                        .replace("<limit>", String.valueOf(item.maxBuyAmount()))
                        .replace("<remaining>", String.valueOf(Math.max(0, remaining))));
                auditDenied(txId, player, shop, item, AuditAction.BUY, qty, price, "DAILY_LIMIT");
                return;
            }
        }

        // Przygotowanie nagrody PRZED pobraniem waluty. Błędny ID custom itemu
        // nigdy nie może zakończyć się charge bez nagrody.
        ItemStack preparedCustomReward = null;
        if (item.reward().type() == ShopRewardType.HEX_CUSTOM_ITEM) {
            preparedCustomReward = customItems.create(item.reward().itemId(), item.reward().amount()).orElse(null);
            if (preparedCustomReward == null) {
                sendMessage(player, cfg.messages().customItemUnavailable());
                auditDenied(txId, player, shop, item, AuditAction.BUY, qty, price, "CUSTOM_ITEM_UNAVAILABLE");
                return;
            }
        }

        if (item.reward().type() == ShopRewardType.ITEM) {
            ItemStack unit = ShopItemStackFactory.tradeUnit(item);
            if (!InventoryOps.canFitQuantity(player.getInventory(), unit, qty)) {
                sendMessage(player, cfg.messages().inventoryFull());
                auditDenied(txId, player, shop, item, AuditAction.BUY, qty, price, "INVENTORY_FULL");
                return;
            }
        } else if (preparedCustomReward != null && !InventoryOps.canFitFully(player.getInventory(), preparedCustomReward)) {
            sendMessage(player, cfg.messages().inventoryFull());
            auditDenied(txId, player, shop, item, AuditAction.BUY, qty, price, "INVENTORY_FULL");
            return;
        }

        if (!busy.add(uuid)) {
            sendMessage(player, cfg.messages().tradeBusy());
            return;
        }
        // Ownership jest sprawdzany ponownie już pod lockiem.
        if (alreadyOwned(player, item)) {
            busy.remove(uuid);
            denyAlreadyPurchased(player, cfg);
            auditDenied(txId, player, shop, item, AuditAction.BUY, qty, price, "ALREADY_PURCHASED_LOCKED");
            return;
        }

        String name = player.getName();
        ItemStack finalPreparedCustomReward = preparedCustomReward;
        economy.withdraw(uuid, name, shop.currency(), price,
                        "HexNPC shop buy " + shop.id() + ":" + item.id() + " x" + qty)
                .whenComplete((result, error) -> runMainThread(() -> {
                    if (error != null || result == null || !result.success()) {
                        try {
                            handleBuyFailure(player, shop, cfg, result, error);
                            if (result != null && result.isInsufficientFunds()) {
                                auditDenied(txId, player, shop, item, AuditAction.BUY, qty, price, "NOT_ENOUGH_FUNDS");
                            } else {
                                audit(txId, player, shop, item, AuditAction.BUY, qty, 0, price, null,
                                        AuditStatus.FAILED, reasonOf(result, error));
                            }
                        } finally {
                            busy.remove(uuid);
                        }
                        return;
                    }

                    // Ktoś mógł nadać permission w czasie oczekiwania na ekonomię.
                    if (alreadyOwned(player, item)) {
                        refundPayment(player, shop, item, price, cfg.messages().alreadyPurchased(), txId, qty,
                                "EXTERNAL_OWNERSHIP_CHANGED", () -> busy.remove(uuid));
                        return;
                    }

                    BigDecimal balanceAfter = result.balance();
                    switch (item.reward().type()) {
                        case ITEM -> {
                            ItemStack give = ShopItemStackFactory.tradeUnit(item);
                            if (!InventoryOps.giveQuantityAllOrNothing(player, give, qty)) {
                                refundPayment(player, shop, item, price, cfg.messages().inventoryFull(), txId, qty,
                                        "INVENTORY_FULL", () -> busy.remove(uuid));
                                return;
                            }
                            completePurchase(player, shop, item, cfg, qty, qty, price, balanceAfter,
                                    limitKey, txId);
                        }
                        case HEX_CUSTOM_ITEM -> {
                            if (finalPreparedCustomReward == null
                                    || !InventoryOps.giveAllOrNothing(player, finalPreparedCustomReward)) {
                                refundPayment(player, shop, item, price, cfg.messages().inventoryFull(), txId, qty,
                                        "CUSTOM_ITEM_GIVE_FAILED", () -> busy.remove(uuid));
                                return;
                            }
                            int granted = finalPreparedCustomReward.getAmount();
                            completePurchase(player, shop, item, cfg, qty, granted, price, balanceAfter,
                                    limitKey, txId);
                        }
                        case CONSOLE_COMMANDS -> {
                            if (!dispatchConsoleReward(player, shop, item, qty)) {
                                refundPayment(player, shop, item, price, cfg.messages().rewardFailed(), txId, qty,
                                        "REWARD_COMMAND_FAILED", () -> busy.remove(uuid));
                                return;
                            }
                            verifyCommandReward(player, shop, item, cfg, qty, price, balanceAfter,
                                    limitKey, txId, 0);
                        }
                    }
                }));
    }

    private void completePurchase(Player player, Shop shop, ShopItem item, ShopConfig cfg,
                                  int requestedQty, int grantedQty, BigDecimal price, BigDecimal balanceAfter,
                                  String limitKey, String txId) {
        ShopItemAction postAction = item.postPurchaseAction();
        boolean runPostAction = item.hasPostPurchaseAction();
        try {
            if (item.hasBuyLimit()) {
                buyLimits.record(player.getUniqueId(), limitKey, requestedQty);
            }
            sendMessage(player, cfg.messages().bought()
                    .replace("<amount>", String.valueOf(grantedQty))
                    .replace("<item>", labelOf(item))
                    .replace("<price>", formatPrice(shop, price)));
            audit(txId, player, shop, item, AuditAction.BUY, requestedQty, grantedQty, price, balanceAfter,
                    AuditStatus.SUCCESS, null);
            if (item.singlePurchaseView()) {
                player.closeInventory();
            }
        } finally {
            busy.remove(player.getUniqueId());
        }
        // Post-purchase runs only after the transaction is fully settled/unlocked.
        if (runPostAction && player.isOnline()) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> executeConfiguredAction(player, shop, item, postAction));
        }
    }

    private boolean dispatchConsoleReward(Player player, Shop shop, ShopItem item, int quantity) {
        for (String raw : item.reward().commands()) {
            String command = renderCommand(raw, player, shop, item, quantity);
            if (command.startsWith("/")) command = command.substring(1);
            if (command.isBlank() || !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                return false;
            }
        }
        return true;
    }

    private void verifyCommandReward(Player player, Shop shop, ShopItem item, ShopConfig cfg,
                                     int qty, BigDecimal price, BigDecimal balanceAfter,
                                     String limitKey, String txId, int attempt) {
        String permission = item.reward().verifyPermission();
        if (permission == null || permission.isBlank() || player.hasPermission(permission)) {
            completePurchase(player, shop, item, cfg, qty, qty, price, balanceAfter, limitKey, txId);
            return;
        }
        // LuckPerms zwykle aktualizuje permission natychmiast, ale zostawiamy
        // dwa krótkie retry dla providerów, które publikują zmianę deferred.
        if (attempt < 2) {
            long delay = attempt == 0 ? 1L : 2L;
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> verifyCommandReward(player, shop, item, cfg, qty, price, balanceAfter,
                            limitKey, txId, attempt + 1), delay);
            return;
        }
        refundPayment(player, shop, item, price, cfg.messages().rewardFailed(), txId, qty,
                "REWARD_VERIFICATION_FAILED", () -> busy.remove(player.getUniqueId()));
    }

    private boolean alreadyOwned(Player player, ShopItem item) {
        return item != null && item.oneTime().enabled()
                && player.hasPermission(item.oneTime().permission());
    }

    private void denyAlreadyPurchased(Player player, ShopConfig cfg) {
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        sendMessage(player, cfg.messages().alreadyPurchased());
    }

    private String renderCommand(String template, Player player, Shop shop, ShopItem item, int quantity) {
        if (template == null) return "";
        return template
                .replace("<player>", player.getName())
                .replace("<uuid>", player.getUniqueId().toString())
                .replace("<shop>", shop.id())
                .replace("<item>", item.id())
                .replace("<quantity>", String.valueOf(quantity));
    }

    private void handleBuyFailure(Player player, Shop shop, ShopConfig cfg, TxResult result, Throwable error) {
        if (result != null && result.isEconomyUnavailable()) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }
        if (result != null && result.isInsufficientFunds()) {
            if (shop.currency() == ShopCurrency.HEX_COINS) {
                sendMessage(player, cfg.messages().notEnoughCurrency()
                        .replace("<currency>", economy.currencyName(shop.currency())));
            } else {
                sendMessage(player, cfg.messages().notEnoughMoney());
            }
            return;
        }
        String reason = error != null ? safe(error.getMessage())
                : (result == null ? "null" : safe(result.reason()));
        sendMessage(player, cfg.messages().transactionFailed().replace("<reason>", reason));
    }

    // ================= Sprzedaż (autorytatywne wykonanie) =================

    public void sell(Player player, Shop shop, ShopItem item) {
        sell(player, shop, item, defaultQuantity(item));
    }

    /** Sprzedaje dokładnie {@code quantity} pasujących sztuk. */
    public void sell(Player player, Shop shop, ShopItem item, int quantity) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        if (!item.hasSellPrice()) {
            sendMessage(player, cfg.messages().sellDisabled());
            return;
        }
        if (!economy.isAvailable(shop.currency())) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }
        int qty = clampQuantity(quantity);
        String txId = UUID.randomUUID().toString();
        Predicate<ItemStack> predicate = sellPredicate(item, cfg);
        if (InventoryOps.countMatching(player.getInventory(), predicate) < qty) {
            sendMessage(player, cfg.messages().notEnoughItems());
            auditDenied(txId, player, shop, item, AuditAction.SELL, qty,
                    PriceCalculator.total(item.sellPrice(), item.amount(), qty, priceScale(shop, cfg)), "NOT_ENOUGH_ITEMS");
            return;
        }
        executeSell(player, shop, item, cfg, predicate, qty, AuditAction.SELL,
                txId, cfg.messages().notEnoughItems());
    }

    /**
     * Sprzedaje WSZYSTKIE pasujące itemy z ekwipunku głównego i hotbara (bez
     * zbroi/off-hand). Bez ograniczenia limitem kupna. Migawka usuniętych
     * itemów i faktycznej ilości powstaje przed operacją ekonomii.
     */
    public void sellAll(Player player, Shop shop, ShopItem item) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        if (!cfg.enableSellAll()) {
            return;
        }
        if (!item.hasSellPrice()) {
            sendMessage(player, cfg.messages().sellDisabled());
            return;
        }
        if (!economy.isAvailable(shop.currency())) {
            sendMessage(player, cfg.messages().economyMissing());
            return;
        }
        String txId = UUID.randomUUID().toString();
        Predicate<ItemStack> predicate = sellPredicate(item, cfg);
        int available = InventoryOps.countMatching(player.getInventory(), predicate);
        if (available <= 0) {
            sendMessage(player, cfg.messages().nothingToSell());
            auditDenied(txId, player, shop, item, AuditAction.SELL_ALL, 0, BigDecimal.ZERO, "NOTHING_TO_SELL");
            return;
        }
        executeSell(player, shop, item, cfg, predicate, available, AuditAction.SELL_ALL,
                txId, cfg.messages().nothingToSell());
    }

    /** Wspólny rdzeń sprzedaży: usunięcie all-or-nothing + async deposit + rollback + audyt. */
    private void executeSell(Player player, Shop shop, ShopItem item, ShopConfig cfg,
                             Predicate<ItemStack> predicate, int requestedQty, AuditAction action,
                             String txId, String shortageMessage) {
        UUID uuid = player.getUniqueId();
        if (!busy.add(uuid)) {
            sendMessage(player, cfg.messages().tradeBusy());
            return;
        }
        Optional<List<ItemStack>> removedOpt = InventoryOps.removeAllOrNothing(
                player.getInventory(), predicate, requestedQty);
        if (removedOpt.isEmpty()) {
            busy.remove(uuid);
            sendMessage(player, shortageMessage);
            auditDenied(txId, player, shop, item, action, requestedQty,
                    PriceCalculator.total(item.sellPrice(), item.amount(), requestedQty, priceScale(shop, cfg)),
                    "NOT_ENOUGH_ITEMS");
            return;
        }
        // Stała migawka faktycznie zabranych stosów i faktycznej ilości.
        List<ItemStack> removedStacks = removedOpt.get();
        int actualQuantity = totalAmount(removedStacks);
        BigDecimal price = PriceCalculator.total(item.sellPrice(), item.amount(), actualQuantity, priceScale(shop, cfg));
        String name = player.getName();
        economy.deposit(uuid, name, shop.currency(), price, "HexNPC shop sell " + shop.id() + ":" + item.id() + " x" + actualQuantity)
                .whenComplete((result, error) -> runMainThread(() -> {
                    try {
                        if (error != null || result == null || !result.success()) {
                            returnRemovedOrDrop(player, shop, item, removedStacks);
                            sendSellFailureMessage(player, cfg, result, error);
                            AuditStatus status = error != null ? AuditStatus.FAILED : AuditStatus.ROLLED_BACK;
                            audit(txId, player, shop, item, action, requestedQty, 0, price, null,
                                    status, reasonOf(result, error));
                            return;
                        }
                        sendMessage(player, cfg.messages().sold()
                                .replace("<amount>", String.valueOf(actualQuantity))
                                .replace("<item>", labelOf(item))
                                .replace("<price>", formatPrice(shop, price)));
                        audit(txId, player, shop, item, action, requestedQty, actualQuantity, price,
                                result.balance(), AuditStatus.SUCCESS, null);
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
        sendMessage(player, cfg.messages().transactionFailed().replace("<reason>", reason));
    }

    // ================= Własna ilość (sign / czat) =================

    /** Kompatybilny wariant — bez zachowania wybranej ilości. */
    public void requestCustomQuantity(Player player, Shop shop, ShopItem item, int originPage) {
        requestCustomQuantity(player, shop, item, originPage, defaultQuantity(item));
    }

    /**
     * Otwiera edytor wprowadzania własnej ilości dla itemu. {@code selectedQuantity}
     * (bieżąca ilość sesji GUI) jest zachowywana: przy timeout oraz przy
     * pustej/nieprawidłowej wpisce widok szczegółów wraca z tą samą ilością.
     */
    public void requestCustomQuantity(Player player, Shop shop, ShopItem item, int originPage, int selectedQuantity) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null || !cfg.enableCustomQuantity()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String shopId = shop.id();
        String itemId = item.id();
        int keptQuantity = clampQuantity(selectedQuantity);
        // Zamykamy GUI, żeby edytor tabliczki / czat nie kolidował z chestem.
        player.closeInventory();
        signService.request(player,
                useSign -> {
                    sendMessage(player, cfg.messages().signPrompt()
                            .replace("<min>", "1")
                            .replace("<max>", String.valueOf(ShopConfig.MAX_QUANTITY)));
                    if (!useSign) {
                        sendMessage(player, cfg.messages().signChatFallback());
                    }
                },
                () -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        sendMessage(online, cfg.messages().signChatFallback());
                    }
                },
                raw -> handleCustomQuantityInput(uuid, shopId, itemId, originPage, keptQuantity, raw),
                () -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        sendMessage(online, cfg.messages().signExpired());
                        // Twardy timeout: wróć do szczegółów z ZACHOWANĄ ilością.
                        Shop shop2 = registry.find(shopId).orElse(null);
                        if (shop2 != null) {
                            shop2.item(itemId).ifPresent(it ->
                                    openDetail(online, shop2, it, keptQuantity, originPage));
                        }
                    }
                });
    }

    /** Kompatybilny wariant (bez zachowania ilości) — używa domyślnej. */
    public void handleCustomQuantityInput(UUID uuid, String shopId, String itemId, int originPage, String raw) {
        handleCustomQuantityInput(uuid, shopId, itemId, originPage, 0, raw);
    }

    /**
     * Przetwarza surowe wejście własnej ilości (główny wątek). Waliduje, po czym
     * ponownie otwiera widok szczegółów z tą samą informacją o sklepie/itemie/
     * stronie. Przy błędzie zachowuje {@code selectedQuantity} (jeśli podana),
     * przy sukcesie używa nowej ilości. Shop/item są rozwiązywane po ID.
     */
    public void handleCustomQuantityInput(UUID uuid, String shopId, String itemId, int originPage,
                                          int selectedQuantity, String raw) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        ShopConfig cfg = configSupplier.get();
        if (cfg == null) {
            return;
        }
        Shop shop = registry.find(shopId).orElse(null);
        if (shop == null) {
            sendMessage(player, cfg.messages().shopNotFound().replace("<shop>", String.valueOf(shopId)));
            return;
        }
        ShopItem item = shop.item(itemId).orElse(null);
        if (item == null) {
            sendMessage(player, cfg.messages().shopNotFound().replace("<shop>", String.valueOf(shopId)));
            return;
        }
        int restoreQuantity = selectedQuantity > 0 ? clampQuantity(selectedQuantity) : defaultQuantity(item);
        QuantityParser.Result result = QuantityParser.parse(raw, 1, ShopConfig.MAX_QUANTITY);
        if (!result.ok()) {
            switch (result.error()) {
                case TOO_SMALL -> sendMessage(player, cfg.messages().quantityTooSmall().replace("<min>", "1"));
                case TOO_LARGE -> sendMessage(player, cfg.messages().quantityTooLarge()
                        .replace("<max>", String.valueOf(ShopConfig.MAX_QUANTITY)));
                default -> sendMessage(player, cfg.messages().invalidQuantity());
            }
            openDetail(player, shop, item, restoreQuantity, originPage);
            return;
        }
        openDetail(player, shop, item, result.value(), originPage);
    }

    // ================= Pomocnicze =================

    /** Ile sztuk itemu gracz może jeszcze dziś kupić (MAX_VALUE = bez limitu). */
    public int buyRemaining(UUID uuid, Shop shop, ShopItem item) {
        if (!item.hasBuyLimit()) {
            return DailyBuyLimitService.UNLIMITED;
        }
        return buyLimits.remaining(uuid, DailyBuyLimitService.key(shop.id(), item.id()), item.maxBuyAmount());
    }

    public DailyBuyLimitService buyLimits() {
        return buyLimits;
    }

    public SignInputService signService() {
        return signService;
    }

    private Predicate<ItemStack> sellPredicate(ShopItem item, ShopConfig cfg) {
        ItemStack matchTemplate = ShopItemStackFactory.exactTemplate(item);
        return SellMatchPredicate.of(item, matchTemplate, cfg.preventSellingCustomItems());
    }

    /** Legacy MONEY zachowuje dokładnie stary formatter; multi API tylko dla HEX_COINS. */
    private String formatPrice(Shop shop, BigDecimal value) {
        if (shop == null || shop.currency() == ShopCurrency.MONEY) {
            return economy.format(value);
        }
        return economy.format(shop.currency(), value);
    }

    private int priceScale(Shop shop, ShopConfig cfg) {
        return shop != null && shop.currency() == ShopCurrency.HEX_COINS ? 0 : cfg.priceScale();
    }

    private int defaultQuantity(ShopItem item) {
        return clampQuantity(item.amount());
    }

    private int clampQuantity(int quantity) {
        if (quantity < 1) {
            return 1;
        }
        return Math.min(ShopConfig.MAX_QUANTITY, quantity);
    }

    private static int totalAmount(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack != null) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static String reasonOf(TxResult result, Throwable error) {
        if (error != null) {
            return safe(error.getMessage());
        }
        return result == null ? "null" : safe(result.reason());
    }

    /** Refunduje pobraną kwotę po nieudanym rewardzie / race ownership. */
    private void refundPayment(Player player, Shop shop, ShopItem item, BigDecimal price,
                               String message, String txId, int requestedQty, String auditReason,
                               Runnable onDone) {
        ShopConfig cfg = configSupplier.get();
        sendMessage(player, message);
        economy.deposit(player.getUniqueId(), player.getName(), shop.currency(), price,
                        "HexNPC shop refund " + shop.id() + ":" + item.id())
                .whenComplete((refund, refundError) -> runMainThread(() -> {
                    try {
                        boolean refundOk = refundError == null && refund != null && refund.success();
                        if (!refundOk) {
                            String reason = refundError != null ? refundError.getMessage()
                                    : (refund == null ? "null" : refund.reason());
                            logger.log(Level.WARNING, "HexNPC: refund failed for player " + player.getName()
                                    + " shop=" + shop.id() + " item=" + item.id()
                                    + " price=" + price + " reason=" + reason);
                            if (cfg != null) {
                                sendMessage(player, cfg.messages().transactionFailed()
                                        .replace("<reason>", reason == null ? "refund-failed" : reason));
                            }
                            audit(txId, player, shop, item, AuditAction.BUY, requestedQty, 0, price, null,
                                    AuditStatus.REFUND_FAILED, reason);
                        } else {
                            audit(txId, player, shop, item, AuditAction.BUY, requestedQty, 0, price,
                                    refund.balance(), AuditStatus.REFUNDED, auditReason);
                        }
                    } finally {
                        if (onDone != null) onDone.run();
                    }
                }));
    }

    /**
     * Oddaje graczowi dokładnie zabrane stosy; gdy brak miejsca — resztę
     * wyrzuca pod stopy. Tożsamość itemu jest zachowana w obie strony.
     */
    private void returnRemovedOrDrop(Player player, Shop shop, ShopItem item, List<ItemStack> removed) {
        if (removed.isEmpty()) {
            return;
        }
        if (InventoryOps.giveAllOrNothing(player, removed)) {
            return;
        }
        for (ItemStack stack : removed) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack.clone());
            for (ItemStack remaining : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), remaining);
            }
        }
        logger.warning("HexNPC: deposit failed and inventory was full — partially returned + dropped "
                + totalAmount(removed) + "x " + item.material() + " for " + player.getName()
                + " (shop=" + shop.id() + " item=" + item.id() + ")");
    }

    // ================= Audyt =================

    private void audit(String txId, Player player, Shop shop, ShopItem item, AuditAction action,
                       int requestedQty, int actualQty, BigDecimal totalPrice, BigDecimal balanceAfter,
                       AuditStatus status, String reason) {
        if (auditLog == null) {
            return;
        }
        try {
            auditLog.record(new AuditEntry(txId, player.getUniqueId(), player.getName(),
                    shop.id(), item.id(), item.material().name(), action,
                    requestedQty, actualQty, totalPrice, balanceAfter, status, reason));
        } catch (Throwable ignored) {
            // Audyt nigdy nie może wpłynąć na transakcję.
        }
    }

    private void auditDenied(String txId, Player player, Shop shop, ShopItem item, AuditAction action,
                            int requestedQty, BigDecimal totalPrice, String reason) {
        ShopConfig cfg = configSupplier.get();
        if (cfg == null || !cfg.auditLog().logDenied()) {
            return;
        }
        audit(txId, player, shop, item, action, requestedQty, 0, totalPrice, null,
                AuditStatus.DENIED, reason);
    }

    private void runMainThread(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private void send(Player player, ShopConfig cfg, Function<hexnpc.shop.config.ShopMessages, String> picker) {
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
