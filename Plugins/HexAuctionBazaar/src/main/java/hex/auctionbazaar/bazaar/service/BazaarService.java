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
                            NOT_ENOUGH_MONEY, DB_FAILED, ECONOMY_UNAVAILABLE, INVALID_QTY }
    public enum SellResult {
        OK,
        /** Sprzedaz sie udala, ale wyplata trafila do claims (deposit fail + claim ok). */
        OK_PENDING_CLAIM,
        UNKNOWN_ITEM, SELL_DISABLED, NOT_ENOUGH_ITEMS,
        DB_FAILED, ECONOMY_UNAVAILABLE, INVALID_QTY,
        /** Zarowno deposit jak i money-claim sie nie powiodly - platnosc nieodzyskana. */
        PAYOUT_FAILED
    }

    public record BuyOutcome(BuyResult result, BazaarPrice price, BigDecimal total, boolean wentToClaim) {
        public static BuyOutcome ok(BazaarPrice p, BigDecimal total, boolean claim) {
            return new BuyOutcome(BuyResult.OK, p, total, claim);
        }
        public static BuyOutcome fail(BuyResult r) { return new BuyOutcome(r, null, null, false); }
    }

    public record SellOutcome(SellResult result, BazaarPrice price, BigDecimal total) {
        public static SellOutcome ok(BazaarPrice p, BigDecimal total) {
            return new SellOutcome(SellResult.OK, p, total);
        }
        public static SellOutcome okPendingClaim(BazaarPrice p, BigDecimal total) {
            return new SellOutcome(SellResult.OK_PENDING_CLAIM, p, total);
        }
        public static SellOutcome fail(SellResult r) { return new SellOutcome(r, null, null); }
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

    public BazaarService(Plugin plugin,
                         HexCoreBridge hexCore,
                         EconomyBridge economy,
                         BazaarStockRepository stocks,
                         AuctionClaimRepository claims,
                         AuditService audit,
                         BazaarOrderService orderService,
                         Supplier<BazaarConfig> configSupplier,
                         Supplier<Boolean> requirePlainItem) {
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
    }

    private void onMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /** Idempotent initial stock seeding (called on enable / reload). */
    public CompletableFuture<Void> seedItems() {
        return hexCore.asyncRun(() -> {
            long now = System.currentTimeMillis();
            for (BazaarItemConfig item : configSupplier.get().items().values()) {
                stocks.ensureInitialStock(item.key(), item.initialStock(), now);
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "bazaar stock seed failed", ex);
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
                    logger.log(Level.WARNING, "currentPrice failed for " + itemKey, ex);
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
            logger.log(Level.WARNING, "marketSnapshot failed", ex);
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
                        logger.log(Level.WARNING, "bazaar buy planning failed", planErr);
                        onMain(() -> result.complete(BuyOutcome.fail(BuyResult.DB_FAILED)));
                        return;
                    }
                    long matchable = plan.preview.matchable();
                    long systemNeeded = Math.max(0L, amount - matchable);
                    if (matchable + Math.min(systemNeeded, plan.stock.stock()) < amount) {
                        onMain(() -> result.complete(BuyOutcome.fail(BuyResult.NOT_ENOUGH_STOCK)));
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
                                    onMain(() -> result.complete(BuyOutcome.fail(BuyResult.NOT_ENOUGH_MONEY)));
                                    return;
                                }
                                executeBuyMatchAndSystem(buyer, buyerId, buyerName, item, cfg,
                                        (int) matchable, (int) systemNeeded,
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
                                           int matchable, int systemNeeded,
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
                logger.log(Level.SEVERE, "bazaar buy match step failed", matchErr);
                economy.deposit(buyerId, buyerName, totalWithdrawn,
                        "bazaar-buy-match-error-refund-" + itemKey).exceptionally(ex -> {
                    logger.log(Level.SEVERE, "match-error deposit failed", ex);
                    hexCore.async(() -> claims.insertMoney(buyerId, totalWithdrawn,
                            "bazaar-buy-match-error-refund-" + itemKey, null,
                            System.currentTimeMillis()));
                    return null;
                });
                onMain(() -> result.complete(BuyOutcome.fail(BuyResult.DB_FAILED)));
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
            BigDecimal refund = acc.refund();
            // Krok B: refund nadwyzki (moze byc niezerowa gdy race obcial matching
            // i/lub magazyn ma za malo).
            if (refund.signum() > 0) {
                economy.deposit(buyerId, buyerName, refund,
                        "bazaar-buy-refund-" + itemKey).exceptionally(ex -> {
                    logger.log(Level.SEVERE, "buy refund deposit failed", ex);
                    hexCore.async(() -> claims.insertMoney(buyerId, refund,
                            "bazaar-buy-refund-" + itemKey, null,
                            System.currentTimeMillis()));
                    return null;
                });
            }
            if (systemFill <= 0) {
                // Wszystko z orderbooka (lub kredyt nie wystarczyl na system fill).
                if (actualMatched > 0) {
                    audit.log(audit.builder()
                            .actor(buyerId, buyerName)
                            .action(AuditAction.BAZAAR_INSTANT_BUY)
                            .market(AuditAction.MARKET_BAZAAR)
                            .itemKey(itemKey)
                            .amount(actualMatched)
                            .total(actualOrderCost)
                            .result(AuditAction.RESULT_OK)
                            .reason("all filled by orderbook"));
                }
                onMain(() -> giveOrClaim(buyer, item, (int) actualMatched, systemPrice,
                        actualOrderCost, result));
                return;
            }
            // Krok C: system stock buy dla brakujacej reszty (systemFill).
            int finalSystemFill = systemFill;
            BigDecimal finalSystemCost = actualSystemCost;
            hexCore.async(() -> {
                        long now = System.currentTimeMillis();
                        return stocks.applyBuyWithLogTx(itemKey, finalSystemFill,
                                systemPrice.buyPrice(), buyerId, buyerName, finalSystemCost, now);
                    })
                    .whenComplete((stockOk, stockErr) -> {
                        if (stockErr != null || stockOk == null || !stockOk) {
                            // System zawiodl - zwracamy TYLKO to co za nie zaplacilismy
                            // (finalSystemCost), matched juz dostarczone i platne.
                            audit.log(audit.builder()
                                    .actor(buyerId, buyerName)
                                    .action(AuditAction.BAZAAR_INSTANT_BUY)
                                    .market(AuditAction.MARKET_BAZAAR)
                                    .itemKey(itemKey)
                                    .amount((long) actualMatched)
                                    .total(actualOrderCost)
                                    .result(AuditAction.RESULT_ROLLBACK)
                                    .reason("system stock tx failed - partial delivery"));
                            if (finalSystemCost.signum() > 0) {
                                economy.deposit(buyerId, buyerName, finalSystemCost,
                                        "bazaar-buy-system-refund-" + itemKey).exceptionally(ex -> {
                                    logger.log(Level.SEVERE, "system-refund deposit failed", ex);
                                    hexCore.async(() -> claims.insertMoney(buyerId, finalSystemCost,
                                            "bazaar-buy-system-refund-" + itemKey, null,
                                            System.currentTimeMillis()));
                                    return null;
                                });
                            }
                            onMain(() -> giveOrClaim(buyer, item, (int) actualMatched, systemPrice,
                                    actualOrderCost, result));
                            return;
                        }
                        BigDecimal totalActual = actualOrderCost.add(finalSystemCost);
                        audit.log(audit.builder()
                                .actor(buyerId, buyerName)
                                .action(AuditAction.BAZAAR_INSTANT_BUY)
                                .market(AuditAction.MARKET_BAZAAR)
                                .itemKey(itemKey)
                                .amount((long) (actualMatched + finalSystemFill))
                                .total(totalActual)
                                .result(AuditAction.RESULT_OK)
                                .reason(actualMatched > 0
                                        ? "hybrid orderbook+system fill"
                                        : "system stock fill"));
                        onMain(() -> giveOrClaim(buyer, item,
                                (int) actualMatched + finalSystemFill,
                                systemPrice, totalActual, result));
                    });
        });
    }

    /**
     * Main-thread item delivery with all-or-nothing semantics:
     *  1. Try to add the full stack via {@link InventoryFit#tryAddFullOrRevert}.
     *     If it fits completely, complete OK without any claim.
     *  2. Otherwise nothing was placed in the inventory; insert the entire
     *     purchase as a single item-claim.
     *  3. If the claim insert fails, refund the buyer the full price.
     *     If the refund deposit also fails, fall back to a money-claim so
     *     the money is never lost.
     */
    private void giveOrClaim(Player buyer, BazaarItemConfig item, int amount,
                             BazaarPrice price, BigDecimal total, CompletableFuture<BuyOutcome> result) {
        UUID buyerId = buyer.getUniqueId();
        String buyerName = buyer.getName();
        ItemStack stack = new ItemStack(item.material(), amount);

        if (InventoryFit.tryAddFullOrRevert(buyer, stack)) {
            result.complete(BuyOutcome.ok(price, total, false));
            return;
        }

        // Inventory could not fit the full purchase. Try to put the entire
        // stack into a claim row. If that succeeds the buyer keeps nothing
        // in the inventory and picks the items up via /hexauction claims.
        final byte[] blob;
        try {
            blob = ItemSerializer.serialize(stack);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not serialize overflow", t);
            refundOrMoneyClaim(buyerId, buyerName, total, item.key(), result, price, total);
            return;
        }
        hexCore.async(() -> claims.insertItem(buyerId, blob,
                        "bazaar-buy-overflow-" + item.key(), null, System.currentTimeMillis()))
                .whenComplete((claimId, err) -> {
                    if (err != null || claimId == null || claimId < 0) {
                        logger.log(Level.SEVERE,
                                "overflow claim insert failed for " + buyerId, err);
                        refundOrMoneyClaim(buyerId, buyerName, total, item.key(), result, price, total);
                        return;
                    }
                    onMain(() -> result.complete(BuyOutcome.ok(price, total, true)));
                });
    }

    /**
     * Last-resort compensation for a bazaar buy where the item could neither
     * be placed in the inventory nor stored as an item-claim. Refund the
     * money; if the deposit fails too, persist it as a money-claim so the
     * funds are never lost.
     */
    private void refundOrMoneyClaim(UUID buyerId, String buyerName, BigDecimal total, String itemKey,
                                     CompletableFuture<BuyOutcome> result,
                                     BazaarPrice price, BigDecimal totalForReport) {
        economy.deposit(buyerId, buyerName, total, "bazaar-buy-refund-" + itemKey)
                .whenComplete((depo, depoErr) -> {
                    if (depoErr == null && depo != null && depo.success()) {
                        // Money is back; from the buyer's perspective the whole
                        // purchase was reverted. Surface this as DB_FAILED so
                        // the caller does not announce a successful purchase.
                        onMain(() -> result.complete(BuyOutcome.fail(BuyResult.DB_FAILED)));
                        return;
                    }
                    logger.log(Level.SEVERE,
                            "buy refund deposit failed; inserting money claim for " + buyerId, depoErr);
                    hexCore.async(() -> claims.insertMoney(buyerId, total,
                                    "bazaar-buy-refund-claim-" + itemKey,
                                    null, System.currentTimeMillis()))
                            .whenComplete((id, claimErr) -> {
                                if (claimErr != null || id == null || id < 0) {
                                    logger.log(Level.SEVERE,
                                            "money-claim insert for buy refund failed for " + buyerId,
                                            claimErr);
                                }
                                onMain(() -> result.complete(BuyOutcome.fail(BuyResult.DB_FAILED)));
                            });
                });
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
                        logger.log(Level.WARNING, "bazaar sell match step failed", matchErr);
                        onMain(() -> {
                            giveBack(seller, sellerId, item.material(), amount);
                            result.complete(SellOutcome.fail(SellResult.DB_FAILED));
                        });
                        return;
                    }
                    long matched = match.matched();
                    BigDecimal orderRevenue = match.totalMoney();
                    long remaining = amount - matched;
                    if (remaining <= 0) {
                        // Wszystko dopasowane w orderbooku - wystarczy zaplacic sprzedajacemu.
                        depositAndFinishSell(seller, sellerId, sellerName, item, matched,
                                orderRevenue, orderRevenue, null, "orderbook fill", result);
                        return;
                    }
                    // Krok 2: sprzedaz reszty do magazynu systemowego.
                    hexCore.async(() -> stocks.find(itemKey))
                            .whenComplete((opt, err) -> {
                                if (err != null) {
                                    onMain(() -> {
                                        // Cofnij tylko niedopasowana czesc.
                                        giveBack(seller, sellerId, item.material(), (int) remaining);
                                        // Za dopasowana czesc wciaz zaplacimy.
                                        depositAndFinishSell(seller, sellerId, sellerName, item, matched,
                                                orderRevenue, orderRevenue, null,
                                                "partial: system lookup failed", result);
                                    });
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
                                                onMain(() -> {
                                                    giveBack(seller, sellerId, item.material(),
                                                            (int) finalRemaining);
                                                    // Wciaz platnosc za czesc dopasowana w orderbooku.
                                                    depositAndFinishSell(seller, sellerId, sellerName, item,
                                                            matched, orderRevenue, orderRevenue, price,
                                                            "partial: system tx failed", result);
                                                });
                                                return;
                                            }
                                            BigDecimal totalRevenue = orderRevenue.add(systemRevenue);
                                            depositAndFinishSell(seller, sellerId, sellerName, item,
                                                    amount, totalRevenue, orderRevenue, price,
                                                    matched > 0 ? "hybrid orderbook+system fill"
                                                            : "system stock fill",
                                                    result);
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
                                       BazaarItemConfig item, long amountSold, BigDecimal total,
                                       BigDecimal orderPortion, BazaarPrice fallbackPrice,
                                       String auditReason,
                                       CompletableFuture<SellOutcome> result) {
        String itemKey = item.key();
        BazaarPrice priceForOutcome = fallbackPrice != null ? fallbackPrice
                : new BazaarPrice(BigDecimal.ZERO, item.basePrice(), item.basePrice());
        if (total == null || total.signum() <= 0) {
            audit.log(audit.builder()
                    .actor(sellerId, sellerName)
                    .action(AuditAction.BAZAAR_INSTANT_SELL)
                    .market(AuditAction.MARKET_BAZAAR)
                    .itemKey(itemKey)
                    .amount(amountSold)
                    .total(BigDecimal.ZERO)
                    .result(AuditAction.RESULT_OK)
                    .reason(auditReason + " - zero payout"));
            onMain(() -> result.complete(SellOutcome.ok(priceForOutcome, BigDecimal.ZERO)));
            return;
        }
        economy.deposit(sellerId, sellerName, total, "bazaar-sell-" + itemKey)
                .whenComplete((depo, depoErr) -> {
                    if (depoErr == null && depo != null && depo.success()) {
                        audit.log(audit.builder()
                                .actor(sellerId, sellerName)
                                .action(AuditAction.BAZAAR_INSTANT_SELL)
                                .market(AuditAction.MARKET_BAZAAR)
                                .itemKey(itemKey)
                                .amount(amountSold)
                                .total(total)
                                .result(AuditAction.RESULT_OK)
                                .reason(auditReason));
                        onMain(() -> result.complete(SellOutcome.ok(priceForOutcome, total)));
                        return;
                    }
                    logger.log(Level.SEVERE,
                            "sell deposit failed - trying money claim for " + sellerId, depoErr);
                    hexCore.async(() -> claims.insertMoney(sellerId, total,
                                    "bazaar-sell-refund-" + itemKey, null,
                                    System.currentTimeMillis()))
                            .whenComplete((cid, e) -> {
                                boolean claimOk = e == null && cid != null && cid >= 0;
                                audit.log(audit.builder()
                                        .actor(sellerId, sellerName)
                                        .action(AuditAction.BAZAAR_INSTANT_SELL)
                                        .market(AuditAction.MARKET_BAZAAR)
                                        .itemKey(itemKey)
                                        .amount(amountSold)
                                        .total(total)
                                        .claimId(claimOk ? cid : null)
                                        .result(claimOk
                                                ? AuditAction.RESULT_REFUND_PENDING
                                                : AuditAction.RESULT_FAILED)
                                        .reason(claimOk
                                                ? "sell deposit failed - money claim path"
                                                : "sell deposit + money claim both failed"));
                                if (claimOk) {
                                    onMain(() -> result.complete(
                                            SellOutcome.okPendingClaim(priceForOutcome, total)));
                                } else {
                                    logger.log(Level.SEVERE,
                                            "sell payout unrecoverable for " + sellerId
                                                    + " total=" + total, e);
                                    onMain(() -> result.complete(
                                            SellOutcome.fail(SellResult.PAYOUT_FAILED)));
                                }
                            });
                });
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
            if (leftover <= 0) {
                contents[slot] = null;
            } else {
                ItemStack copy = it.clone();
                copy.setAmount(leftover);
                contents[slot] = copy;
            }
            remaining -= take;
        }
        p.getInventory().setStorageContents(contents);
        return true;
    }

    private void giveBack(Player p, UUID owner, Material mat, int amount) {
        if (p != null && p.isOnline()) {
            var leftover = p.getInventory().addItem(new ItemStack(mat, amount));
            for (ItemStack rest : leftover.values()) {
                claimItemAsync(owner, rest, "bazaar-sell-refund");
            }
            return;
        }
        claimItemAsync(owner, new ItemStack(mat, amount), "bazaar-sell-refund");
    }

    private void claimItemAsync(UUID owner, ItemStack item, String reason) {
        if (owner == null || item == null) return;
        final byte[] blob;
        try {
            blob = ItemSerializer.serialize(item);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not serialize refund item", t);
            return;
        }
        hexCore.async(() -> claims.insertItem(owner, blob, reason, null, System.currentTimeMillis()))
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "refund claim insert failed for " + owner, ex);
                    return -1L;
                });
    }
}
