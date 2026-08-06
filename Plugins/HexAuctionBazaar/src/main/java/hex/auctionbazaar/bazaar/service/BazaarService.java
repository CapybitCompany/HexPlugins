package hex.auctionbazaar.bazaar.service;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.bazaar.model.BazaarStock;
import hex.auctionbazaar.bazaar.repository.BazaarStockRepository;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.util.InventoryFit;
import hex.auctionbazaar.util.ItemSerializer;
import hex.auctionbazaar.util.Money;
import hex.auctionbazaar.util.RefundCompensation;
import hex.economy.api.EconomyResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Instant buy / instant sell against the system. Prices are derived from
 * current stock by {@link BazaarPricer}. Items the buyer cannot fit into
 * their inventory always become a claim - never a world drop.
 *
 * Threading rules:
 *  - DB writes only via {@link HexCoreBridge#async(Supplier)} or
 *    {@link HexCoreBridge#asyncRun(Runnable)}.
 *  - Bukkit / inventory ops only on the main thread.
 *  - Stock change and transaction log run in the same {@code db.tx(...)}
 *    so they are atomic against other stock updates.
 */
public final class BazaarService {

    public enum BuyResult { OK, UNKNOWN_ITEM, BUY_DISABLED, NOT_ENOUGH_STOCK,
                            NOT_ENOUGH_MONEY, DB_FAILED, ECONOMY_UNAVAILABLE, INVALID_QTY,
                            /** Techniczny błąd systemu ekonomii (wyjątek/nieudana operacja, nie brak środków). */
                            ECONOMY_ERROR,
                            /** Funkcja Rynku wyłączona (bazaar.enabled:false) - egzekwowane serwerowo. */
                            FEATURE_DISABLED,
                            /** Brak uprawnień - sprawdzane też serwerowo, nie tylko w komendzie/GUI. */
                            NO_PERMISSION,
                            /** Kupno nieudane - pełny zwrot środków wykonany wprost przez Economy. */
                            REFUNDED,
                            /** Kupno nieudane - zwrot środków czeka jako claim (Odbiór przedmiotów). */
                            REFUND_PENDING,
                            /** Zakup zrealizowany, ale zwrot nadpłaty czeka jako claim. */
                            OVERPAY_REFUND_PENDING,
                            /** Niepewny stan ekwipunku lub nieodwracalna kompensacja - wymagana ręczna korekta. */
                            COMPENSATION_FAILED }
    public enum SellResult {
        OK,
        /** Sprzedaz sie udala, ale wyplata trafila do claims (deposit fail + claim ok). */
        OK_PENDING_CLAIM,
        /** Sprzedano; niesprzedana reszta bezpiecznie zapisana jako claim (odbiór). */
        OK_REST_CLAIMED,
        UNKNOWN_ITEM, SELL_DISABLED, NOT_ENOUGH_ITEMS,
        DB_FAILED, ECONOMY_UNAVAILABLE, INVALID_QTY,
        /** Zarowno deposit jak i money-claim sie nie powiodly - platnosc nieodzyskana. */
        PAYOUT_FAILED,
        /** Nic nie sprzedano - przedmioty zwrócone (np. transakcja magazynu nie powiodła się). */
        NOTHING_SOLD,
        /** Zwrot niesprzedanej reszty NIEPEWNY/NIEUDANY - stan krytyczny, wymagana ręczna korekta. */
        RETURN_FAILED,
        /** Funkcja Rynku wyłączona (bazaar.enabled:false) - egzekwowane serwerowo. */
        FEATURE_DISABLED,
        /** Brak uprawnień - sprawdzane też serwerowo. */
        NO_PERMISSION
    }

    public record BuyOutcome(BuyResult result, BazaarPrice price, BigDecimal total,
                             int deliveredAmount, boolean wentToClaim) {
        public static BuyOutcome ok(BazaarPrice p, BigDecimal total, int deliveredAmount, boolean claim) {
            return new BuyOutcome(BuyResult.OK, p, total, Math.max(0, deliveredAmount), claim);
        }
        public static BuyOutcome fail(BuyResult r) { return new BuyOutcome(r, null, null, 0, false); }
    }

    public record SellOutcome(SellResult result, BazaarPrice price, BigDecimal total, long amountSold) {
        public static SellOutcome ok(BazaarPrice p, BigDecimal total, long amountSold) {
            return new SellOutcome(SellResult.OK, p, total, amountSold);
        }
        public static SellOutcome okPendingClaim(BazaarPrice p, BigDecimal total, long amountSold) {
            return new SellOutcome(SellResult.OK_PENDING_CLAIM, p, total, amountSold);
        }
        public static SellOutcome fail(SellResult r) { return new SellOutcome(r, null, null, 0L); }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final EconomyBridge economy;
    private final BazaarStockRepository stocks;
    private final AuctionClaimRepository claims;
    private final AuditService audit;
    private final BazaarOrderService orderService;
    private final Supplier<BazaarConfig> configSupplier;
    private final Supplier<Boolean> requirePlainItem;
    /** Globalny przełącznik pluginu (enabled). false = tryb konserwacji: brak nowych komercyjnych mutacji. */
    private final java.util.function.BooleanSupplier pluginEnabled;

    public BazaarService(Plugin plugin,
                         HexCoreBridge hexCore,
                         EconomyBridge economy,
                         BazaarStockRepository stocks,
                         AuctionClaimRepository claims,
                         AuditService audit,
                         BazaarOrderService orderService,
                         Supplier<BazaarConfig> configSupplier,
                         Supplier<Boolean> requirePlainItem,
                         java.util.function.BooleanSupplier pluginEnabled) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.stocks = Objects.requireNonNull(stocks, "stocks");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.orderService = Objects.requireNonNull(orderService, "orderService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.requirePlainItem = Objects.requireNonNull(requirePlainItem, "requirePlainItem");
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
    }

    /**
     * Nowe komercyjne mutacje (instant buy/sell) dozwolone tylko gdy plugin NIE jest w trybie konserwacji
     * (global enabled) ORAZ Rynek jest włączony (bazaar.enabled). Egzekwowane SERWEROWO tuż przed mutacją.
     */
    private boolean commerceEnabled() {
        return pluginEnabled.getAsBoolean() && configSupplier.get().enabled();
    }

    private void onMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /**
     * Bezpieczna próba wykonania operacji wymagającej głównego wątku. Zwraca
     * false, gdy plugin został wyłączony albo scheduler odrzucił zadanie.
     */
    private boolean tryOnMain(Runnable r) {
        if (!plugin.isEnabled()) return false;
        if (Bukkit.isPrimaryThread()) {
            r.run();
            return true;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, r);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Domknięcie wyniku nie dotyka Bukkit API i nie zależy od schedulera pluginu. */
    private void completeNow(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "RYNEK: nie udało się domknąć wyniku operacji", t);
        }
    }

    /**
     * Synchroniczne (blokujące) seedowanie stanów - wołane na wątku DB jako część
     * inicjalizacji schematu. Wyjątek propaguje się, aby init schematu respektował
     * {@code database.required}.
     */
    public void seedItemsBlocking() {
        long now = System.currentTimeMillis();
        for (BazaarItemConfig item : configSupplier.get().items().values()) {
            stocks.ensureInitialStock(item.key(), item.initialStock(), now);
        }
    }

    /** Idempotent initial stock seeding (asynchroniczny wariant, np. do testów/diagnozy). */
    public CompletableFuture<Void> seedItems() {
        return hexCore.asyncRun(this::seedItemsBlocking).exceptionally(ex -> {
            logger.log(Level.WARNING, "RYNEK: inicjalizacja stanów magazynu nie powiodła się", ex);
            return null;
        });
    }

    public CompletableFuture<BazaarPrice> currentPrice(String itemKey) {
        BazaarConfig cfg = configSupplier.get();
        BazaarItemConfig item = cfg.item(itemKey).orElse(null);
        if (item == null) {
            return CompletableFuture.completedFuture(null);
        }
        return hexCore.async(() -> stocks.find(itemKey))
                .thenApply(opt -> {
                    long stock = opt.map(BazaarStock::stock).orElse(item.initialStock());
                    BigDecimal lastBuy = opt.map(BazaarStock::lastBuyPrice).orElse(null);
                    BigDecimal lastSell = opt.map(BazaarStock::lastSellPrice).orElse(null);
                    return BazaarPricer.compute(item, cfg.pricing(), stock, lastBuy, lastSell);
                })
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "RYNEK: odczyt aktualnej ceny nie powiódł się dla " + itemKey, ex);
                    return null;
                });
    }

    /**
     * Zdjecie ceny i stanu wszystkich przedmiotow w jednym wywolaniu DB.
     * Zwraca mape klucz -> Snapshot (cena, stan). Uzywane przez glowne GUI
     * Bazaar zeby uniknac N+1 zapytan.
     */
    public CompletableFuture<Map<String, Snapshot>> marketSnapshot() {
        BazaarConfig cfg = configSupplier.get();
        return hexCore.async(() -> {
            Map<String, BazaarStock> stockMap = stocks.findAll();
            Map<String, Snapshot> out = new LinkedHashMap<>();
            for (BazaarItemConfig item : cfg.items().values()) {
                BazaarStock stock = stockMap.get(item.key());
                long amount = stock == null ? item.initialStock() : stock.stock();
                BigDecimal lastBuy = stock == null ? null : stock.lastBuyPrice();
                BigDecimal lastSell = stock == null ? null : stock.lastSellPrice();
                BazaarPrice price = BazaarPricer.compute(item, cfg.pricing(), amount, lastBuy, lastSell);
                out.put(item.key(), new Snapshot(item, price, amount));
            }
            return out;
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "RYNEK: pobranie migawki rynku nie powiodło się", ex);
            return Map.of();
        });
    }

    public record Snapshot(BazaarItemConfig item, BazaarPrice price, long stock) {
        public BigDecimal spread() {
            if (price == null) return BigDecimal.ZERO;
            return price.buyPrice().subtract(price.sellPrice()).abs();
        }
    }

    // ---------------------------------------------------------------- buy

    /**
     * Natychmiastowe kupno. Priorytet dopasowania:
     *  1. Aktywne SELL-oferty w orderbooku (najnizsza cena, potem najstarsze).
     *  2. Reszta z magazynu systemowego po cenie system-buy.
     * Naleznosc jest sciagana z gracza raz - na podstawie pre-scan cost -
     * a ewentualna nadplata (gdy match ma race z innym kupujacym) jest zwracana.
     */
    public CompletableFuture<BuyOutcome> buy(Player buyer, String itemKey, int amount) {
        // SERWEROWA autoryzacja tuż przed mutacją (GUI mogło zostać otwarte przed disable/utratą praw).
        // commerceEnabled() = global enabled (tryb konserwacji) ORAZ bazaar.enabled.
        BazaarConfig gate = configSupplier.get();
        if (!commerceEnabled()) {
            return CompletableFuture.completedFuture(BuyOutcome.fail(BuyResult.FEATURE_DISABLED));
        }
        if (!buyer.hasPermission(gate.permBuy())) {
            return CompletableFuture.completedFuture(BuyOutcome.fail(BuyResult.NO_PERMISSION));
        }
        if (amount <= 0) {
            return CompletableFuture.completedFuture(BuyOutcome.fail(BuyResult.INVALID_QTY));
        }
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(BuyOutcome.fail(BuyResult.ECONOMY_UNAVAILABLE));
        }
        BazaarConfig cfg = configSupplier.get();
        BazaarItemConfig item = cfg.item(itemKey).orElse(null);
        if (item == null) {
            return CompletableFuture.completedFuture(BuyOutcome.fail(BuyResult.UNKNOWN_ITEM));
        }
        if (!item.buyEnabled()) {
            return CompletableFuture.completedFuture(BuyOutcome.fail(BuyResult.BUY_DISABLED));
        }
        // #7: górna granica sumy (maks. cena * ilość) MUSI mieścić się w DECIMAL(19,2) PRZED ekonomią/DB.
        // Faktyczny koszt jest <= tej granicy, więc gdy granica się mieści, mieści się też realna suma.
        if (Money.totalOrNull(item.maxPrice(), amount) == null) {
            return CompletableFuture.completedFuture(BuyOutcome.fail(BuyResult.INVALID_QTY));
        }

        CompletableFuture<BuyOutcome> result = new CompletableFuture<>();
        UUID buyerId = buyer.getUniqueId();
        String buyerName = buyer.getName();

        // Preview orderbook + snapshot magazynu w jednym kroku.
        hexCore.async(() -> {
                    // maxPrice = ceiling akceptowany przez kupujacego.
                    BazaarOrderService.MatchPreview preview =
                            orderService.previewMatchSellOffers(itemKey, amount, item.maxPrice());
                    BazaarStock stock = stocks.find(itemKey).orElseGet(() -> new BazaarStock(
                            itemKey, item.initialStock(), null, null, System.currentTimeMillis()));
                    return new BuyPlan(preview, stock);
                })
                .whenComplete((plan, planErr) -> {
                    if (planErr != null || plan == null) {
                        logger.log(Level.WARNING, "RYNEK: planowanie kupna nie powiodło się", planErr);
                        completeNow(() -> result.complete(BuyOutcome.fail(BuyResult.DB_FAILED)));
                        return;
                    }
                    long matchable = plan.preview.matchable();
                    long systemNeeded = Math.max(0L, amount - matchable);
                    if (matchable + Math.min(systemNeeded, plan.stock.stock()) < amount) {
                        completeNow(() ->
                                result.complete(BuyOutcome.fail(BuyResult.NOT_ENOUGH_STOCK)));
                        return;
                    }
                    BazaarPrice systemPrice = BazaarPricer.compute(item, cfg.pricing(),
                            plan.stock.stock(), plan.stock.lastBuyPrice(), plan.stock.lastSellPrice());
                    BigDecimal orderCost = plan.preview.totalMoney();
                    BigDecimal systemCost = systemPrice.buyPrice().multiply(new BigDecimal(systemNeeded));
                    BigDecimal totalCost = orderCost.add(systemCost);

                    economy.withdraw(buyerId, buyerName, totalCost, "bazaar-buy-" + itemKey)
                            .whenComplete((wd, wdErr) -> {
                                if (wdErr != null || wd == null || !wd.success()) {
                                    // Rozdziel brak środków (NOT_ENOUGH_FUNDS) od technicznego błędu ekonomii.
                                    boolean notEnough = wd != null && !wd.success()
                                            && "NOT_ENOUGH_FUNDS".equals(wd.reason());
                                    BuyResult r = notEnough ? BuyResult.NOT_ENOUGH_MONEY
                                            : BuyResult.ECONOMY_ERROR;
                                    completeNow(() -> result.complete(BuyOutcome.fail(r)));
                                    return;
                                }
                                executeBuyMatchAndSystem(buyer, buyerId, buyerName, item, cfg,
                                        amount, (int) matchable, (int) systemNeeded,
                                        orderCost, systemCost, plan.stock, systemPrice, result);
                            });
                });
        return result;
    }

    private record BuyPlan(BazaarOrderService.MatchPreview preview, BazaarStock stock) {}

    /**
     * Wynik ksiegowosci po dopasowaniu orderbooka. Uzywany przez
     * {@link #computeFinalBuyCharge} - wydzielony pure-function dla testu.
     */
    public record BuyChargeAccounting(int systemFill, BigDecimal systemCost,
                                       BigDecimal finalCharge, BigDecimal refund) {}

    /**
     * Pure function: wylicz ile jednostek mozemy jeszcze kupic z magazynu
     * systemowego bazujac na kredycie z gory pobranym od gracza.
     * Zapewnia niezmiennik "gracz nigdy nie dostaje wiecej niz zaplacil":
     *  - availableCredit = totalWithdrawn - actualOrderCost
     *  - maxAffordable = availableCredit / systemBuyPrice (dol zaokraglone)
     *  - systemFill = min(desiredSystemFill, maxAffordable, availableStock)
     *  - systemCost = systemBuyPrice * systemFill
     *  - finalCharge = actualOrderCost + systemCost
     *  - refund = totalWithdrawn - finalCharge
     *
     * Gdy systemBuyPrice = 0, gracz moze dostac cala systemNeeded za darmo
     * (kredyt wystarczy) - to legalne, bo system cena to 0.
     */
    public static BuyChargeAccounting computeFinalBuyCharge(BigDecimal totalWithdrawn,
                                                              BigDecimal actualOrderCost,
                                                              int desiredSystemFill,
                                                              long availableStock,
                                                              BigDecimal systemBuyPrice) {
        BigDecimal availableCredit = totalWithdrawn.subtract(actualOrderCost);
        int maxAffordable;
        if (systemBuyPrice == null || systemBuyPrice.signum() <= 0) {
            maxAffordable = desiredSystemFill;
        } else {
            // floor division: kredyt / cena
            long affordable = availableCredit
                    .divide(systemBuyPrice, 0, java.math.RoundingMode.FLOOR)
                    .longValue();
            maxAffordable = affordable < 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, affordable);
        }
        int cappedByStock = (int) Math.min(desiredSystemFill, availableStock);
        int systemFill = Math.min(cappedByStock, maxAffordable);
        if (systemFill < 0) systemFill = 0;
        BigDecimal systemCost = systemBuyPrice == null
                ? BigDecimal.ZERO
                : systemBuyPrice.multiply(new BigDecimal(systemFill));
        BigDecimal finalCharge = actualOrderCost.add(systemCost);
        BigDecimal refund = totalWithdrawn.subtract(finalCharge);
        if (refund.signum() < 0) {
            // Nie moze sie zdarzyc bo systemFill jest capped przez credit -
            // ale defensywnie normalizujemy.
            refund = BigDecimal.ZERO;
        }
        return new BuyChargeAccounting(systemFill, systemCost, finalCharge, refund);
    }

    /**
     * Krok 2 sciezki BUY: dopasowanie do orderbooka, ksiegowanie kredytu,
     * kupno pozostalej ilosci z magazynu i dostarczenie graczowi.
     * Niezmiennik: gracz otrzymuje TYLKO tyle przedmiotow, na ile wystarczy
     * mu wczesniej pobrany kredyt (orderCost + systemCost pre-scan). W razie
     * race-a orderbookowego finalny system fill jest ograniczony przez ten kredyt.
     */
    private void executeBuyMatchAndSystem(Player buyer, UUID buyerId, String buyerName,
                                           BazaarItemConfig item, BazaarConfig cfg,
                                           int requestedQty, int matchable, int systemNeeded,
                                           BigDecimal orderCost, BigDecimal systemCost,
                                           BazaarStock stock,
                                           BazaarPrice systemPrice,
                                           CompletableFuture<BuyOutcome> result) {
        String itemKey = item.key();
        BigDecimal totalWithdrawn = orderCost.add(systemCost);
        // Krok A: prawdziwe dopasowanie zlecen (jesli mamy co dopasowywac).
        // Budzet orderbook = orderCost z pre-scan. Nawet gdy tansze zlecenia
        // zniknely i pojawily sie drozsze, matcher nigdy nie utworzy claim-ow
        // dla SELL-owner-ow o wartosci wyzszej niz to co gracz zaplacil.
        BigDecimal orderBudget = orderCost;
        CompletableFuture<BazaarOrderService.MatchResult> matchStep = matchable > 0
                ? hexCore.async(() -> orderService.matchAgainstSellOffers(itemKey, matchable,
                        item.maxPrice(), buyerId, buyerName, orderBudget))
                : CompletableFuture.completedFuture(new BazaarOrderService.MatchResult(0L, BigDecimal.ZERO));

        matchStep.whenComplete((match, matchErr) -> {
            if (matchErr != null) {
                logger.log(Level.SEVERE, "RYNEK: krok dopasowania w księdze zleceń nie powiódł się", matchErr);
                // Nic nie dostarczono -> pełny, ŚLEDZONY zwrot; terminalny audyt powstaje w finalizeBuy.
                finalizeBuy(buyer, buyerId, buyerName, item, systemPrice, "księga zleceń", requestedQty, 0,
                        BigDecimal.ZERO, totalWithdrawn, result);
                return;
            }
            long actualMatched = match.matched();
            BigDecimal actualOrderCost = match.totalMoney();
            // Ile jeszcze potrzebujemy z systemu, zeby dobic do zamowionej ilosci.
            int shortfall = matchable + systemNeeded - (int) actualMatched;
            if (shortfall < 0) shortfall = 0;
            // Ksiegowosc kredytu: zabroni dostarczenia wiekszej ilosci niz wystarczy pieniedzy.
            BuyChargeAccounting acc = computeFinalBuyCharge(totalWithdrawn, actualOrderCost,
                    shortfall, stock.stock(), systemPrice.buyPrice());
            int systemFill = acc.systemFill();
            BigDecimal actualSystemCost = acc.systemCost();
            // Nadpłata (i ewentualna nieopłacona reszta) są rozliczane CENTRALNIE w finalizeBuy jako
            // zwrot = totalWithdrawn - kwota za faktycznie dostarczone przedmioty (śledzony, nie fire-and-forget).
            if (systemFill <= 0) {
                // Wszystko z księgi zleceń (lub kredyt nie wystarczył na system fill).
                String source = actualMatched > 0 ? "księga zleceń" : "brak";
                finalizeBuy(buyer, buyerId, buyerName, item, systemPrice, source,
                        requestedQty, (int) actualMatched,
                        actualOrderCost, totalWithdrawn, result);
                return;
            }
            // Krok C: kupno brakującej reszty z magazynu systemowego (systemFill).
            int finalSystemFill = systemFill;
            BigDecimal finalSystemCost = actualSystemCost;
            hexCore.async(() -> {
                        long now = System.currentTimeMillis();
                        return stocks.applyBuyWithLogTx(itemKey, finalSystemFill,
                                systemPrice.buyPrice(), buyerId, buyerName, finalSystemCost, now);
                    })
                    .whenComplete((stockOk, stockErr) -> {
                        if (stockErr != null || stockOk == null || !stockOk) {
                            logger.log(Level.SEVERE, "RYNEK: transakcja magazynu systemowego nie "
                                    + "powiodła się - dostawa częściowa", stockErr);
                            // Dostarczamy tylko część z księgi zleceń; resztę kredytu (nadpłata + część
                            // systemowa) zwraca finalizeBuy jako totalWithdrawn - actualOrderCost.
                            finalizeBuy(buyer, buyerId, buyerName, item, systemPrice,
                                    "księga zleceń", requestedQty,
                                    (int) actualMatched,
                                    actualOrderCost, totalWithdrawn, result);
                            return;
                        }
                        BigDecimal totalActual = actualOrderCost.add(finalSystemCost);
                        String source = actualMatched > 0 ? "hybryda (księga+magazyn)" : "magazyn systemowy";
                        finalizeBuy(buyer, buyerId, buyerName, item, systemPrice, source, requestedQty,
                                (int) actualMatched + finalSystemFill, totalActual, totalWithdrawn, result);
                    });
        });
    }

    /** Sposób, w jaki przedmiot trafił (lub nie) do gracza. Stany NIE mogą się zlewać. */
    public enum ItemDelivery {
        /** ADDED_FULLY: całość w ekwipunku. */
        IN_INVENTORY,
        /** NOT_FIT_REVERTED + potwierdzony insert: cały przedmiot jako jeden item-claim. */
        AS_CLAIM,
        /** NOT_FIT_REVERTED + nieudany insert: nic nie dostarczono (cenę trzeba zwrócić). */
        CLAIM_FAILED,
        /** STATE_UNCERTAIN: niepewny stan ekwipunku (BEZ claim-a i BEZ zwrotu). */
        UNCERTAIN,
        /** Brak przedmiotów do dostarczenia (np. pełne niepowodzenie dopasowania). */
        NONE
    }

    /** Terminalny wynik kupna: komunikat dla gracza ({@link BuyResult}) + status audytu. */
    public record BuyFinal(BuyResult buyResult, String auditResult) {}

    /**
     * Czyste/asynchroniczne mapowanie tri-state ekwipunku na sposób dostarczenia (testowalne):
     *  - {@code ADDED_FULLY}      -> IN_INVENTORY (bez claim-a);
     *  - {@code NOT_FIT_REVERTED} -> DOKŁADNIE jeden item-claim: sukces -> AS_CLAIM, błąd -> CLAIM_FAILED;
     *  - {@code STATE_UNCERTAIN}  -> UNCERTAIN (bez claim-a; wołający NIE zwraca pieniędzy).
     * Future zawsze terminalny; {@code claimInsert} wołane co najwyżej raz i tylko przy NOT_FIT_REVERTED.
     */
    public static CompletableFuture<ItemDelivery> resolveItemDelivery(
            InventoryFit.Result addResult,
            java.util.function.Supplier<CompletableFuture<Boolean>> claimInsert) {
        if (addResult == InventoryFit.Result.ADDED_FULLY) {
            return CompletableFuture.completedFuture(ItemDelivery.IN_INVENTORY);
        }
        if (addResult == InventoryFit.Result.STATE_UNCERTAIN) {
            return CompletableFuture.completedFuture(ItemDelivery.UNCERTAIN);
        }
        CompletableFuture<Boolean> ins;
        try {
            CompletableFuture<Boolean> f = claimInsert.get();
            ins = f == null ? CompletableFuture.completedFuture(false) : f.exceptionally(ex -> false);
        } catch (Throwable t) {
            ins = CompletableFuture.completedFuture(false);
        }
        return ins.thenApply(ok ->
                Boolean.TRUE.equals(ok) ? ItemDelivery.AS_CLAIM : ItemDelivery.CLAIM_FAILED);
    }

    /**
     * Czysta kombinacja sposobu dostawy i statusu zwrotu w terminalny wynik kupna (testowalne).
     * {@code refundStatus == null} oznacza brak wymaganego zwrotu. Żaden stan OK nie zlewa się z Fehler:
     *  - UNCERTAIN                                    -> COMPENSATION_FAILED / RESULT_FAILED (krytyczne, bez zwrotu);
     *  - CLAIM_FAILED bez zwrotu (przedmiot darmowy)  -> COMPENSATION_FAILED / RESULT_FAILED;
     *  - dowolny wymagany zwrot == FAILED             -> COMPENSATION_FAILED / RESULT_FAILED (pieniądze utracone);
     *  - IN_INVENTORY|AS_CLAIM, brak zwrotu / REFUNDED-> OK / RESULT_OK (dostawa + ewent. nadpłata zwrócona wprost);
     *  - IN_INVENTORY|AS_CLAIM, zwrot PENDING_CLAIM   -> OVERPAY_REFUND_PENDING / RESULT_REFUND_PENDING;
     *  - NONE|CLAIM_FAILED, zwrot REFUNDED            -> REFUNDED / RESULT_ROLLBACK (cały zakup cofnięty);
     *  - NONE|CLAIM_FAILED, zwrot PENDING_CLAIM       -> REFUND_PENDING / RESULT_REFUND_PENDING.
     */
    public static BuyFinal combineBuy(ItemDelivery delivery, RefundCompensation.Status refundStatus) {
        if (delivery == ItemDelivery.UNCERTAIN) {
            return new BuyFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED);
        }
        if (delivery == ItemDelivery.CLAIM_FAILED && refundStatus == null) {
            // przedmiotu nie dostarczono, a nie ma czego zwracać (darmowy) -> nadal niepowodzenie dostawy
            return new BuyFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED);
        }
        if (refundStatus == RefundCompensation.Status.FAILED) {
            return new BuyFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED);
        }
        boolean itemsWithPlayer = delivery == ItemDelivery.IN_INVENTORY || delivery == ItemDelivery.AS_CLAIM;
        if (itemsWithPlayer) {
            if (refundStatus == RefundCompensation.Status.PENDING_CLAIM) {
                return new BuyFinal(BuyResult.OVERPAY_REFUND_PENDING, AuditAction.RESULT_REFUND_PENDING);
            }
            return new BuyFinal(BuyResult.OK, AuditAction.RESULT_OK);   // brak zwrotu lub REFUNDED wprost
        }
        // NONE lub CLAIM_FAILED -> nic nie dostarczono; o wyniku decyduje zwrot
        if (refundStatus == RefundCompensation.Status.PENDING_CLAIM) {
            return new BuyFinal(BuyResult.REFUND_PENDING, AuditAction.RESULT_REFUND_PENDING);
        }
        if (refundStatus == RefundCompensation.Status.REFUNDED) {
            return new BuyFinal(BuyResult.REFUNDED, AuditAction.RESULT_ROLLBACK);
        }
        // Brak dostawy nigdy nie jest sukcesem, również dla przedmiotu o cenie 0.
        return new BuyFinal(BuyResult.DB_FAILED, AuditAction.RESULT_FAILED);
    }

    /**
     * Finalizacja kupna: dostarcz przedmioty (all-or-nothing), rozlicz ŚLEDZONY zwrot reszty kredytu,
     * napisz DOKŁADNIE jeden terminalny audyt i domknij wynik gracza. Wołane z wątku DB-callbacku.
     */
    private void finalizeBuy(Player buyer, UUID buyerId, String buyerName, BazaarItemConfig item,
                             BazaarPrice price, String source,
                             int requestedQty, int deliveredQty, BigDecimal chargedForDelivered,
                             BigDecimal totalWithdrawn,
                             CompletableFuture<BuyOutcome> result) {
        deliverItems(buyerId, buyer, item, deliveredQty).whenComplete((delivery, dErr) -> {
            ItemDelivery del = (dErr != null || delivery == null) ? ItemDelivery.UNCERTAIN : delivery;
            completeBuy(buyerId, buyerName, item, price, source, requestedQty,
                    deliveredQty, chargedForDelivered,
                    totalWithdrawn, del, result);
        });
    }

    /** Dostawa przedmiotu na wątku głównym (tri-state -> {@link ItemDelivery}). Nigdy nie rzuca do callera. */
    private CompletableFuture<ItemDelivery> deliverItems(UUID buyerId, Player buyer,
                                                         BazaarItemConfig item, int qty) {
        CompletableFuture<ItemDelivery> out = new CompletableFuture<>();
        if (qty <= 0) {
            out.complete(ItemDelivery.NONE);
            return out;
        }
        final ItemStack stack;
        try {
            stack = new ItemStack(item.material(), qty);
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "RYNEK: nie udało się utworzyć przedmiotu do dostawy", t);
            out.complete(ItemDelivery.UNCERTAIN);
            return out;
        }

        // Maszyna stanów PENDING->RUNNING: dokładnie jeden zwycięzca (CAS) między zadaniem
        // głównego wątku a fallbackiem timeout/disable. Dzięki temu:
        //  - zadanie, które NIGDY nie ruszyło -> bezpieczny PEŁNY claim (nic nie dotknęło ekwipunku);
        //  - zadanie, które ruszyło (mutacja mogła się zacząć) -> UNCERTAIN (bez claim/zwrotu);
        //  - spóźnione zadanie po wygranym fallbacku NIE mutuje już ekwipunku.
        AtomicBoolean started = new AtomicBoolean(false);

        Runnable deliver = () -> {
            if (out.isDone()) return;
            if (!started.compareAndSet(false, true)) return;   // fallback już wygrał -> nie mutuj
            try {
                if (!buyer.isOnline()) {
                    completeAsClaim(buyerId, stack, item.key(), out);
                    return;
                }
                InventoryFit.Result r = InventoryFit.tryAddFull(buyer, stack);
                resolveItemDelivery(r, () -> insertOverflowClaim(buyerId, stack, item.key()))
                        .whenComplete((d, e) -> out.complete(
                                (e != null || d == null) ? ItemDelivery.UNCERTAIN : d));
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "RYNEK: nieoczekiwany błąd dostawy przedmiotu", t);
                out.complete(ItemDelivery.UNCERTAIN);
            }
        };

        if (!buyer.isOnline() || !tryOnMain(deliver)) {
            // Gracz offline albo scheduler odrzucił zadanie: nic nie dotknęło PlayerInventory,
            // więc bezpiecznie zapisujemy cały przedmiot jako trwały claim.
            if (started.compareAndSet(false, true)) {
                completeAsClaim(buyerId, stack, item.key(), out);
            }
            return out;
        }
        // Fallback po 30 s (także gdy zaakceptowane zadanie zostanie porzucone przy disable):
        // NIGDY nie ruszyło -> pełny claim; już ruszyło -> UNCERTAIN. Zwycięstwo atomowe (CAS).
        CompletableFuture.runAsync(() -> {
            if (out.isDone()) return;
            if (started.compareAndSet(false, true)) {
                completeAsClaim(buyerId, stack, item.key(), out);   // zadanie nigdy nie ruszyło
            } else {
                out.complete(ItemDelivery.UNCERTAIN);               // no-op jeśli już domknięte
            }
        }, CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS));
        return out;
    }

    private void completeAsClaim(UUID buyerId, ItemStack stack, String itemKey,
                                 CompletableFuture<ItemDelivery> out) {
        resolveItemDelivery(InventoryFit.Result.NOT_FIT_REVERTED,
                () -> insertOverflowClaim(buyerId, stack, itemKey))
                .whenComplete((delivery, error) -> out.complete(
                        error == null && delivery != null ? delivery : ItemDelivery.CLAIM_FAILED));
    }

    /** Rozlicza zwrot reszty kredytu (śledzony) i przechodzi do finishBuy z terminalnym statusem. */
    private void completeBuy(UUID buyerId, String buyerName, BazaarItemConfig item,
                             BazaarPrice price, String source,
                             int requestedQty, int deliveredQty, BigDecimal chargedForDelivered,
                             BigDecimal totalWithdrawn,
                             ItemDelivery delivery, CompletableFuture<BuyOutcome> result) {
        BigDecimal surplus = totalWithdrawn.subtract(chargedForDelivered);
        BigDecimal refundOwed = surplus.signum() > 0 ? surplus : BigDecimal.ZERO;
        if (delivery == ItemDelivery.UNCERTAIN) {
            // Niepewny stan ekwipunku: część mogła już trafić do gracza -> BEZ zwrotu (ręczna korekta).
            logSevereBuyUncertain(System.nanoTime(), buyerId, item.key(), item.material(),
                    deliveredQty, chargedForDelivered);
            BigDecimal finalRefundOwed = refundOwed;
            completeNow(() -> finishBuy(buyerId, buyerName, item, price, source, requestedQty,
                    deliveredQty, chargedForDelivered, totalWithdrawn, finalRefundOwed,
                    delivery, null, result));
            return;
        }
        if (delivery == ItemDelivery.CLAIM_FAILED) {
            refundOwed = refundOwed.add(chargedForDelivered);   // przedmiotu nie dostarczono -> zwróć też jego cenę
        }
        if (refundOwed.signum() <= 0) {
            completeNow(() -> finishBuy(buyerId, buyerName, item, price, source, requestedQty,
                    deliveredQty, chargedForDelivered, totalWithdrawn, BigDecimal.ZERO,
                    delivery, null, result));
            return;
        }
        final BigDecimal amount = refundOwed;
        trackedBuyRefund(buyerId, buyerName, amount, item.key()).whenComplete((refund, e) -> {
            RefundCompensation.TrackedOutcome tracked = (e != null || refund == null)
                    ? new RefundCompensation.TrackedOutcome(RefundCompensation.Status.FAILED, null)
                    : refund;
            if (tracked.status() == RefundCompensation.Status.FAILED) {
                logSevereBuyRefundFailed(System.nanoTime(), buyerId, amount);
            }
            completeNow(() -> finishBuy(buyerId, buyerName, item, price, source, requestedQty,
                    deliveredQty, chargedForDelivered, totalWithdrawn, amount,
                    delivery, tracked, result));
        });
    }

    /** Kombinuje wynik, pisze JEDEN terminalny audyt i domyka future gracza (nawet gdy audyt rzuci). */
    private void finishBuy(UUID buyerId, String buyerName, BazaarItemConfig item,
                           BazaarPrice price, String source,
                           int requestedQty, int deliveredQty, BigDecimal chargedForDelivered,
                           BigDecimal totalWithdrawn, BigDecimal refundOwed, ItemDelivery delivery,
                           RefundCompensation.TrackedOutcome refund,
                           CompletableFuture<BuyOutcome> result) {
        RefundCompensation.Status refundStatus = refund == null ? null : refund.status();
        BuyFinal fin;
        try {
            fin = combineBuy(delivery, refundStatus);
        } catch (Throwable t) {
            fin = new BuyFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED);
        }
        BuyOutcome outcome = toOutcome(fin.buyResult(), price, chargedForDelivered, deliveredQty, delivery);
        // CompletableFuture.complete jest atomowym strażnikiem idempotencji: tylko
        // zwycięski terminalny callback może utworzyć wpis audytu.
        if (!result.complete(outcome)) return;
        try {
            writeBuyAudit(buyerId, buyerName, item.key(), requestedQty, deliveredQty,
                    totalWithdrawn, chargedForDelivered, refundOwed, source, delivery,
                    refund, fin.auditResult());
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "RYNEK: zapis audytu kupna nie powiódł się", t);
        }
    }

    public static BuyOutcome toOutcome(BuyResult r, BazaarPrice price, BigDecimal charged,
                                       int deliveredQty, ItemDelivery delivery) {
        return switch (r) {
            case OK -> BuyOutcome.ok(price, charged, deliveredQty, delivery == ItemDelivery.AS_CLAIM);
            case OVERPAY_REFUND_PENDING -> new BuyOutcome(BuyResult.OVERPAY_REFUND_PENDING, price,
                    charged, Math.max(0, deliveredQty), delivery == ItemDelivery.AS_CLAIM);
            default -> BuyOutcome.fail(r);   // REFUNDED / REFUND_PENDING / COMPENSATION_FAILED
        };
    }

    /** ŚLEDZONY zwrot: deposit -> (przy błędzie) money-claim -> terminalny {@link RefundCompensation.Status}. */
    private CompletableFuture<RefundCompensation.TrackedOutcome> trackedBuyRefund(
            UUID buyerId, String buyerName, BigDecimal amount, String itemKey) {
        return RefundCompensation.compensateTracked(
                () -> economy.deposit(buyerId, buyerName, amount, "bazaar-buy-refund-" + itemKey)
                        .thenApply(res -> res != null && res.success()),
                () -> hexCore.async(() -> claims.insertMoney(buyerId, amount,
                                "bazaar-buy-refund-claim-" + itemKey, null, System.currentTimeMillis())));
    }

    /** Jeden terminalny audyt BAZAAR_INSTANT_BUY z polskim, wyczerpującym powodem (bez NBT/sekretów). */
    private void writeBuyAudit(UUID buyerId, String buyerName, String itemKey,
                               int requestedQty, int deliveredQty, BigDecimal totalWithdrawn,
                               BigDecimal charged, BigDecimal refundOwed, String source,
                               ItemDelivery delivery, RefundCompensation.TrackedOutcome refund,
                               String auditResult) {
        RefundCompensation.Status refundStatus = refund == null ? null : refund.status();
        String reason = buildBuyAuditReason(source, requestedQty, deliveredQty, totalWithdrawn,
                charged, refundOwed, delivery, refundStatus);
        audit.log(audit.builder()
                .actor(buyerId, buyerName)
                .action(AuditAction.BAZAAR_INSTANT_BUY)
                .market(AuditAction.MARKET_BAZAAR)
                .itemKey(itemKey)
                .claimId(refund == null ? null : refund.claimId())
                .amount((long) deliveredQty)
                .total(charged)
                .result(auditResult)
                .reason(reason));
    }

    /** Pełny, testowalny opis finansowy jednego terminalnego kupna. */
    public static String buildBuyAuditReason(String source, int requestedQty, int deliveredQty,
                                             BigDecimal totalWithdrawn, BigDecimal charged,
                                             BigDecimal refundOwed, ItemDelivery delivery,
                                             RefundCompensation.Status refundStatus) {
        StringBuilder reason = new StringBuilder()
                .append("źródło=").append(source)
                .append(" zamówiono=").append(requestedQty)
                .append(" dostarczono=").append(deliveredQty)
                .append(" pobrano=").append(money(totalWithdrawn))
                .append(" rozliczono=").append(money(charged))
                .append(" zwrot_należny=").append(money(refundOwed))
                .append(" dostawa=").append(deliveryLabel(delivery));
        if (refundStatus != null) {
            reason.append(" zwrot=").append(refundLabel(refundStatus));
        } else if (refundOwed != null && refundOwed.signum() > 0) {
            reason.append(" zwrot=niewykonany_z_powodu_niepewnego_stanu");
        }
        return reason.toString();
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private static String deliveryLabel(ItemDelivery d) {
        return switch (d) {
            case IN_INVENTORY -> "ekwipunek";
            case AS_CLAIM -> "przedmiot zapisany do odbioru";
            case CLAIM_FAILED -> "nieudana";
            case UNCERTAIN -> "niepewna";
            case NONE -> "brak";
        };
    }

    private static String refundLabel(RefundCompensation.Status s) {
        return switch (s) {
            case REFUNDED -> "zwrócony wprost";
            case PENDING_CLAIM -> "czeka do odbioru";
            case FAILED -> "nieudany";
        };
    }

    /** Serializuje i wstawia CAŁY nadmiar jako jeden item-claim; true tylko po potwierdzonym insertcie. */
    private CompletableFuture<Boolean> insertOverflowClaim(UUID buyerId, ItemStack stack, String itemKey) {
        final byte[] blob;
        try {
            blob = ItemSerializer.serialize(stack);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "RYNEK: nie udało się zserializować nadmiaru zakupu", t);
            return CompletableFuture.completedFuture(false);
        }
        return hexCore.async(() -> claims.insertItem(buyerId, blob,
                        "bazaar-buy-overflow-" + itemKey, null, System.currentTimeMillis()))
                .handle((claimId, err) -> {
                    boolean ok = err == null && claimId != null && claimId >= 0;
                    if (!ok) {
                        logger.log(Level.SEVERE,
                                "RYNEK: zapis nadmiaru do odbioru (claim) nie powiódł się dla " + buyerId, err);
                    }
                    return ok;
                });
    }

    private final java.util.concurrent.atomic.AtomicLong lastSevereLogAt =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong lastRefundSevereLogAt =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /**
     * Rate-limitowany (60s) SEVERE dla niepewnego stanu ekwipunku przy kupnie. Zawiera identyfikatory
     * techniczne (tx/UUID/klucz/materiał/ilość/kwota), ale BEZ sekretów i BEZ pełnych danych NBT.
     */
    private void logSevereBuyUncertain(long txId, UUID buyerId, String itemKey,
                                       org.bukkit.Material material, int amount, BigDecimal money) {
        long now = System.currentTimeMillis();
        long last = lastSevereLogAt.get();
        if (now - last >= 60_000L && lastSevereLogAt.compareAndSet(last, now)) {
            logger.severe("RYNEK KUPNO: niepewny stan ekwipunku - tx=" + txId
                    + " gracz=" + buyerId
                    + " przedmiot=" + itemKey + " (" + material + ")"
                    + " ilość=" + amount
                    + " kwota=" + (money == null ? "0" : money.toPlainString())
                    + " - brak automatycznej kompensacji; wymagana ręczna kontrola");
        }
    }

    /** Rate-limitowany (60s) SEVERE, gdy ani zwrot, ani money-claim się nie powiodły. Bez sekretów. */
    private void logSevereBuyRefundFailed(long txId, UUID buyerId, BigDecimal amount) {
        long now = System.currentTimeMillis();
        long last = lastRefundSevereLogAt.get();
        if (now - last >= 60_000L && lastRefundSevereLogAt.compareAndSet(last, now)) {
            logger.severe("RYNEK KUPNO: kompensacja pieniężna nieodwracalna - tx=" + txId
                    + " gracz=" + buyerId
                    + " kwota=" + (amount == null ? "0" : amount.toPlainString())
                    + " - ani zwrot, ani money-claim nie powiodły się; wymagana ręczna korekta");
        }
    }

    // ---------------------------------------------------------------- sell

    /**
     * Natychmiastowa sprzedaz. Priorytet dopasowania:
     *  1. Aktywne BUY-zlecenia w orderbooku (najwyzsza cena, potem najstarsze).
     *  2. Reszta trafia do magazynu systemowego po cenie system-sell.
     * Przedmioty sciagane sa z ekwipunku upfront - w razie bledu sa
     * zwracane graczowi lub trafiaja do claims.
     */
    public CompletableFuture<SellOutcome> sell(Player seller, String itemKey, int amount) {
        // SERWEROWA autoryzacja tuż przed mutacją (GUI/komenda nie są jedyną granicą).
        // commerceEnabled() = global enabled (tryb konserwacji) ORAZ bazaar.enabled.
        BazaarConfig gate = configSupplier.get();
        if (!commerceEnabled()) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.FEATURE_DISABLED));
        }
        if (!seller.hasPermission(gate.permSell())) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.NO_PERMISSION));
        }
        if (amount <= 0) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.INVALID_QTY));
        }
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.ECONOMY_UNAVAILABLE));
        }
        BazaarConfig cfg = configSupplier.get();
        BazaarItemConfig item = cfg.item(itemKey).orElse(null);
        if (item == null) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.UNKNOWN_ITEM));
        }
        if (!item.sellEnabled()) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.SELL_DISABLED));
        }
        // #7: górna granica przychodu (maks. cena * ilość) MUSI mieścić się w DECIMAL(19,2) PRZED zdjęciem
        // przedmiotów/ekonomią. Sprawdzamy zanim usuniemy cokolwiek z ekwipunku.
        if (Money.totalOrNull(item.maxPrice(), amount) == null) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.INVALID_QTY));
        }

        UUID sellerId = seller.getUniqueId();
        String sellerName = seller.getName();
        CompletableFuture<SellOutcome> result = new CompletableFuture<>();

        boolean plainOnly = Boolean.TRUE.equals(requirePlainItem.get());
        if (!removeFromInventory(seller, item.material(), amount, plainOnly)) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.NOT_ENOUGH_ITEMS));
        }

        // Krok 1: dopasowanie do BUY-orderow (jesli sa i cena akceptowalna).
        // Floor = item.minPrice() aby zaakceptowac dowolne zlecenie w widelkach.
        hexCore.async(() -> orderService.matchAgainstBuyOrders(itemKey, amount,
                        item.minPrice(), sellerId, sellerName))
                .whenComplete((match, matchErr) -> {
                    if (matchErr != null) {
                        logger.log(Level.WARNING, "RYNEK: krok dopasowania sprzedaży w księdze zleceń "
                                + "nie powiódł się", matchErr);
                        // Nic nie sprzedano -> ŚLEDZONY zwrot wszystkich przedmiotów; wynik ZALEŻY od zwrotu:
                        // udany zwrot -> DB_FAILED (retry), nieudany/niepewny -> RETURN_FAILED (krytyczne).
                        returnThen(seller, item, amount, ret -> result.complete(restReturnFailed(ret)
                                ? SellOutcome.fail(SellResult.RETURN_FAILED)
                                : SellOutcome.fail(SellResult.DB_FAILED)));
                        return;
                    }
                    long matched = match.matched();
                    BigDecimal orderRevenue = match.totalMoney();
                    long remaining = amount - matched;
                    if (remaining <= 0) {
                        // Wszystko dopasowane w orderbooku - wystarczy zaplacic sprzedajacemu.
                        depositAndFinishSell(seller, sellerId, sellerName, item, amount, matched,
                                orderRevenue, orderRevenue, null, "realizacja z księgi zleceń",
                                ItemDelivery.NONE, result);
                        return;
                    }
                    // Krok 2: sprzedaz reszty do magazynu systemowego.
                    hexCore.async(() -> stocks.find(itemKey))
                            .whenComplete((opt, err) -> {
                                if (err != null) {
                                    // Zwróć tylko niedopasowaną część (śledzony), potem wypłać za dopasowaną.
                                    returnThen(seller, item, (int) remaining,
                                            ret -> depositAndFinishSell(seller, sellerId, sellerName, item,
                                                    amount, matched, orderRevenue, orderRevenue, null,
                                                    "częściowo: odczyt magazynu systemowego nie powiódł się",
                                                    ret, result));
                                    return;
                                }
                                BazaarStock stock = opt.orElseGet(() -> new BazaarStock(
                                        itemKey, item.initialStock(), null, null,
                                        System.currentTimeMillis()));
                                BazaarPrice price = BazaarPricer.compute(item, cfg.pricing(),
                                        stock.stock(), stock.lastBuyPrice(), stock.lastSellPrice());
                                BigDecimal systemRevenue =
                                        price.sellPrice().multiply(new BigDecimal(remaining));
                                long finalRemaining = remaining;
                                hexCore.async(() -> {
                                            long now = System.currentTimeMillis();
                                            return stocks.applySellWithLogTx(itemKey, finalRemaining,
                                                    price.sellPrice(), sellerId, sellerName,
                                                    systemRevenue, now);
                                        })
                                        .whenComplete((stockOk, stockErr) -> {
                                            if (stockErr != null || stockOk == null || !stockOk) {
                                                // Zwróć niedopasowaną resztę (śledzony), potem wypłać za dopasowaną.
                                                returnThen(seller, item, (int) finalRemaining,
                                                        ret -> depositAndFinishSell(seller, sellerId, sellerName,
                                                                item, amount, matched, orderRevenue, orderRevenue,
                                                                price, "częściowo: transakcja magazynu "
                                                                        + "systemowego nie powiodła się",
                                                                ret, result));
                                                return;
                                            }
                                            BigDecimal totalRevenue = orderRevenue.add(systemRevenue);
                                            depositAndFinishSell(seller, sellerId, sellerName, item,
                                                    amount, amount, totalRevenue, orderRevenue, price,
                                                    matched > 0 ? "hybryda (księga+magazyn)"
                                                            : "magazyn systemowy",
                                                    ItemDelivery.NONE, result);
                                        });
                            });
                });
        return result;
    }

    /**
     * Ostatni krok sprzedazy: deposit dla sprzedajacego + audit.
     * Semantyka bezpieczna:
     *  - Deposit sukces -> SellOutcome.ok (RESULT_OK).
     *  - Deposit blad + money-claim sukces -> OK_PENDING_CLAIM (REFUND_PENDING).
     *  - Deposit blad + money-claim blad -> PAYOUT_FAILED (FAILED).
     * SellOutcome nie jest ukonczony dopoki nie wiemy, ktora sciezka przeszla.
     */
    private void depositAndFinishSell(Player seller, UUID sellerId, String sellerName,
                                       BazaarItemConfig item, long requested, long amountSold, BigDecimal total,
                                       BigDecimal orderPortion, BazaarPrice fallbackPrice,
                                       String auditReason, ItemDelivery restReturn,
                                       CompletableFuture<SellOutcome> result) {
        BazaarPrice priceForOutcome = fallbackPrice != null ? fallbackPrice
                : new BazaarPrice(BigDecimal.ZERO, item.basePrice(), item.basePrice());

        if (amountSold <= 0) {
            finishSell(sellerId, sellerName, item.key(), auditReason, requested, 0L, BigDecimal.ZERO,
                    restReturn, SellResult.NOTHING_SOLD, priceForOutcome, result);
            return;
        }
        if (total == null || total.signum() <= 0) {
            finishSell(sellerId, sellerName, item.key(), auditReason + " - wypłata zerowa", requested,
                    amountSold, BigDecimal.ZERO, restReturn, SellResult.OK, priceForOutcome, result);
            return;
        }
        economy.deposit(sellerId, sellerName, total, "bazaar-sell-" + item.key())
                .whenComplete((depo, depoErr) -> {
                    if (depoErr == null && depo != null && depo.success()) {
                        finishSell(sellerId, sellerName, item.key(), auditReason, requested, amountSold,
                                total, restReturn, SellResult.OK, priceForOutcome, result);
                        return;
                    }
                    logger.log(Level.SEVERE,
                            "RYNEK: wypłata za sprzedaż nie powiodła się - próbuję money-claim dla "
                                    + sellerId, depoErr);
                    hexCore.async(() -> claims.insertMoney(sellerId, total,
                                    "bazaar-sell-refund-" + item.key(), null,
                                    System.currentTimeMillis()))
                            .whenComplete((cid, e) -> {
                                boolean claimOk = e == null && cid != null && cid >= 0;
                                if (!claimOk) {
                                    logger.log(Level.SEVERE,
                                            "RYNEK: nieodwracalny błąd wypłaty za sprzedaż dla "
                                                    + sellerId + " kwota=" + total, e);
                                }
                                finishSell(sellerId, sellerName, item.key(), auditReason, requested,
                                        amountSold, total, restReturn,
                                        claimOk ? SellResult.OK_PENDING_CLAIM : SellResult.PAYOUT_FAILED,
                                        priceForOutcome, result);
                            });
                });
    }

    /**
     * Terminalne złożenie wyniku sprzedaży: łączy status WYPŁATY z terminalnym statusem ZWROTU reszty
     * i pisze DOKŁADNIE jeden audyt. Kluczowe: nieudany/niepewny zwrot reszty (RETURN_FAILED) ma
     * PIERWSZEŃSTWO nad OK - nigdy nie meldujemy zwykłego „Sprzedano", gdy reszta nie wróciła bezpiecznie.
     * Audyt nie twierdzi „zwrócono", jeśli to nie zostało potwierdzone.
     */
    private void finishSell(UUID sellerId, String sellerName, String itemKey, String auditReason,
                            long requested, long amountSold, BigDecimal total, ItemDelivery restReturn,
                            SellResult payoutResult, BazaarPrice price, CompletableFuture<SellOutcome> result) {
        SellResult finalResult = combineSellResult(payoutResult, restReturn);
        String auditResult = switch (finalResult) {
            case OK, OK_REST_CLAIMED -> AuditAction.RESULT_OK;
            case OK_PENDING_CLAIM -> AuditAction.RESULT_REFUND_PENDING;
            case NOTHING_SOLD -> AuditAction.RESULT_ROLLBACK;
            default -> AuditAction.RESULT_FAILED;                      // PAYOUT_FAILED / RETURN_FAILED
        };
        String reason = auditReason + " (zamówiono=" + requested + " sprzedano=" + amountSold
                + " reszta=" + Math.max(0, requested - amountSold)
                + " zwrot-reszty=" + restLabel(restReturn)
                + " wypłata=" + (total == null ? "0" : total.toPlainString()) + ")";
        try {
            audit.log(audit.builder()
                    .actor(sellerId, sellerName)
                    .action(AuditAction.BAZAAR_INSTANT_SELL)
                    .market(AuditAction.MARKET_BAZAAR)
                    .itemKey(itemKey)
                    .amount(amountSold)
                    .total(total == null ? BigDecimal.ZERO : total)
                    .result(auditResult)
                    .reason(reason));
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "RYNEK: zapis audytu sprzedaży nie powiódł się", t);
        }
        SellOutcome outcome = switch (finalResult) {
            case OK -> SellOutcome.ok(price, total, amountSold);
            case OK_REST_CLAIMED -> new SellOutcome(SellResult.OK_REST_CLAIMED, price, total, amountSold);
            case OK_PENDING_CLAIM -> SellOutcome.okPendingClaim(price, total, amountSold);
            default -> SellOutcome.fail(finalResult);                  // NOTHING_SOLD / PAYOUT_FAILED / RETURN_FAILED
        };
        onMain(() -> result.complete(outcome));
    }

    /**
     * Main-thread inventory walk. Optionally restricted to plain (non-custom)
     * stacks so player gear with NBT cannot be drained as bulk goods.
     * Returns true and removes the items, false if there aren't enough.
     */
    private boolean removeFromInventory(Player p, Material mat, int needed, boolean plainOnly) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        int have = 0;
        for (ItemStack it : contents) {
            if (it == null || it.getType() != mat) continue;
            if (plainOnly && !PlainItemMatcher.isPlain(it, mat)) continue;
            have += it.getAmount();
            if (have >= needed) break;
        }
        if (have < needed) return false;
        int remaining = needed;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack it = contents[slot];
            if (it == null || it.getType() != mat) continue;
            if (plainOnly && !PlainItemMatcher.isPlain(it, mat)) continue;
            int take = Math.min(it.getAmount(), remaining);
            int leftover = it.getAmount() - take;
            ItemStack newValue;
            if (leftover <= 0) {
                newValue = null;
            } else {
                ItemStack copy = it.clone();
                copy.setAmount(leftover);
                newValue = copy;
            }
            // Zapis per-slot (sloty 0..35 = schowek): równoważny setStorageContents, modyfikuje tylko
            // zmienione sloty i jest w pełni wspierany przez środowiska testowe.
            p.getInventory().setItem(slot, newValue);
            remaining -= take;
        }
        return true;
    }

    /**
     * ŚLEDZONY zwrot niesprzedanych przedmiotów (all-or-nothing tri-state przez {@link #deliverItems})
     * i DOPIERO potem kontynuacja (np. wypłata za sprzedaną część + wynik gracza). Żadnego
     * fire-and-forget: wynik/busy-guard kończy się dopiero po TERMINALNYM zwrocie. Niepewny/nieudany
     * zwrot (UNCERTAIN/CLAIM_FAILED) -> SEVERE do ręcznej korekty (nigdy world-drop, nigdy dup).
     */
    private void returnThen(Player seller, BazaarItemConfig item, int qty, Consumer<ItemDelivery> then) {
        if (qty <= 0) {
            onMain(() -> then.accept(ItemDelivery.NONE));
            return;
        }
        deliverItems(seller.getUniqueId(), seller, item, qty).whenComplete((ret, e) -> {
            ItemDelivery d = (e != null || ret == null) ? ItemDelivery.UNCERTAIN : ret;
            if (restReturnFailed(d)) {
                logger.severe("RYNEK: zwrot niesprzedanych przedmiotów niepewny/nieudany dla "
                        + seller.getUniqueId() + " (ilość=" + qty + ", stan=" + d
                        + ") - wymagana ręczna korekta");
            }
            final ItemDelivery fd = d;
            onMain(() -> then.accept(fd));
        });
    }

    /** Zwrot reszty NIE jest bezpieczny (mogła nastąpić częściowa dostawa albo claim padł). */
    static boolean restReturnFailed(ItemDelivery d) {
        return d == ItemDelivery.UNCERTAIN || d == ItemDelivery.CLAIM_FAILED;
    }

    /**
     * Czysta reguła łączenia wyniku wypłaty ze statusem zwrotu reszty (testowalna).
     * Nieudany/niepewny zwrot reszty ma PIERWSZEŃSTWO nad OK (krytyczne, nigdy fałszywy „Sprzedano").
     */
    public static SellResult combineSellResult(SellResult payout, ItemDelivery restReturn) {
        if (restReturnFailed(restReturn)) {
            return SellResult.RETURN_FAILED;
        }
        if (payout == SellResult.OK && restReturn == ItemDelivery.AS_CLAIM) {
            return SellResult.OK_REST_CLAIMED;
        }
        return payout;
    }

    private static String restLabel(ItemDelivery d) {
        return switch (d) {
            case IN_INVENTORY -> "ekwipunek";
            case AS_CLAIM -> "odbiór (claim)";
            case CLAIM_FAILED -> "NIEUDANY";
            case UNCERTAIN -> "NIEPEWNY";
            case NONE -> "brak reszty";
        };
    }

}
