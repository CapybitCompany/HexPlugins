package hex.auctionbazaar.bazaar.service;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.util.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zarzadzanie zleceniami Bazaar (Buy Order / Sell Offer).
 *
 * Dla BUY ORDER:
 *  - gracz zamraza cala kase upfront (amount * price)
 *  - zlecenie oczekuje az ktos wykona instant-sell po tej lub nizszej cenie
 *  - zdjete przedmioty trafiaja jako claim
 *
 * Dla SELL OFFER:
 *  - gracz zdaje fizyczne przedmioty upfront
 *  - zlecenie oczekuje az ktos wykona instant-buy po tej lub wyzszej cenie
 *  - uzyskane pieniadze trafiaja jako claim
 *
 * Anulowanie:
 *  - BUY: zwrot niewykorzystanej reserved_money
 *  - SELL: zwrot niewykorzystanej ilosci przedmiotow poprzez claim
 */
public final class BazaarOrderService {

    public enum PlaceResult {
        OK,
        UNKNOWN_ITEM,
        INVALID_QTY,
        INVALID_PRICE,
        TOO_MANY_OPEN,
        NOT_ENOUGH_MONEY,
        NOT_ENOUGH_ITEMS,
        ECONOMY_UNAVAILABLE,
        DB_FAILED,
        FEATURE_DISABLED
    }

    public record PlaceOutcome(PlaceResult result, Long orderId, BigDecimal totalReserved) {
        public static PlaceOutcome ok(long id, BigDecimal total) {
            return new PlaceOutcome(PlaceResult.OK, id, total);
        }
        public static PlaceOutcome fail(PlaceResult r) {
            return new PlaceOutcome(r, null, null);
        }
    }

    public enum CancelResult { OK, NOT_FOUND, NOT_OWNER, NOT_OPEN, DB_FAILED }

    private final Plugin plugin;
    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final EconomyBridge economy;
    private final BazaarOrderRepository orders;
    private final AuctionClaimRepository claims;
    private final AuditService audit;
    private final Supplier<BazaarConfig> configSupplier;
    private final Supplier<Integer> maxOpenPerPlayer;

    public BazaarOrderService(Plugin plugin,
                              HexCoreBridge hexCore,
                              EconomyBridge economy,
                              BazaarOrderRepository orders,
                              AuctionClaimRepository claims,
                              AuditService audit,
                              Supplier<BazaarConfig> configSupplier,
                              Supplier<Integer> maxOpenPerPlayer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.maxOpenPerPlayer = Objects.requireNonNull(maxOpenPerPlayer, "maxOpenPerPlayer");
    }

    private void onMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    public CompletableFuture<List<BazaarOrder>> listOpen(UUID owner, int limit) {
        return hexCore.async(() -> orders.findOpenByOwner(owner, limit))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "listOpen failed", ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<BazaarOrder>> listAll(UUID owner, int limit) {
        return hexCore.async(() -> orders.findByOwner(owner, limit))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "listAll failed", ex);
                    return List.of();
                });
    }

    /**
     * Wylicza timestamp wygasniecia dla nowo skladanego zlecenia.
     * Wspoldzielone przez placeBuyOrder i placeSellOffer aby oba
     * rodzaje zlecen zachowywaly identyczna semantyke wygasania.
     * Zwraca null gdy expiry jest wylaczone (order-expiry-seconds <= 0).
     */
    public static Long computeExpiresAt(BazaarConfig cfg, long placeNow) {
        long seconds = cfg.orderExpirySeconds();
        return seconds > 0 ? placeNow + seconds * 1000L : null;
    }

    // ---------------------------------------------------------------- BUY ORDER

    /**
     * Wystaw zlecenie kupna. Gracz placi z gory amount*price - kasa
     * zamrozona az do zrealizowania lub anulowania.
     */
    public CompletableFuture<PlaceOutcome> placeBuyOrder(Player buyer, String itemKey,
                                                         long amount, BigDecimal price) {
        BazaarConfig cfg = configSupplier.get();
        if (!cfg.enabled()) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.FEATURE_DISABLED));
        }
        BazaarItemConfig item = cfg.item(itemKey).orElse(null);
        if (item == null) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.UNKNOWN_ITEM));
        }
        if (amount <= 0) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.INVALID_QTY));
        }
        if (price == null || price.signum() <= 0
                || price.compareTo(item.minPrice()) < 0
                || price.compareTo(item.maxPrice()) > 0) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.INVALID_PRICE));
        }
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.ECONOMY_UNAVAILABLE));
        }
        UUID uuid = buyer.getUniqueId();
        String name = buyer.getName();
        BigDecimal total = price.multiply(new BigDecimal(amount));

        CompletableFuture<PlaceOutcome> result = new CompletableFuture<>();
        int maxOpen = Math.max(1, maxOpenPerPlayer.get());
        hexCore.async(() -> orders.countOpenByOwner(uuid))
                .whenComplete((count, err) -> {
                    if (err != null) {
                        onMain(() -> result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED)));
                        return;
                    }
                    if (count >= maxOpen) {
                        onMain(() -> result.complete(PlaceOutcome.fail(PlaceResult.TOO_MANY_OPEN)));
                        return;
                    }
                    economy.withdraw(uuid, name, total, "bazaar-buy-order-reserve-" + itemKey)
                            .whenComplete((res, weErr) -> {
                                if (weErr != null || res == null || !res.success()) {
                                    onMain(() -> result.complete(PlaceOutcome.fail(PlaceResult.NOT_ENOUGH_MONEY)));
                                    return;
                                }
                                long placeNow = System.currentTimeMillis();
                                Long expiresAt = computeExpiresAt(configSupplier.get(), placeNow);
                                hexCore.async(() -> orders.insert(uuid, name, itemKey, OrderSide.BUY,
                                                amount, price, total, placeNow, expiresAt))
                                        .whenComplete((id, insErr) -> {
                                            if (insErr != null || id == null) {
                                                compensateBuyPlacementFailure(uuid, name, itemKey, total,
                                                        insErr, result);
                                                return;
                                            }
                                            audit.log(audit.builder()
                                                    .actor(uuid, name)
                                                    .action(AuditAction.BAZAAR_BUY_ORDER_PLACED)
                                                    .market(AuditAction.MARKET_BAZAAR)
                                                    .itemKey(itemKey)
                                                    .orderId(id)
                                                    .amount(amount)
                                                    .unitPrice(price)
                                                    .total(total)
                                                    .result(AuditAction.RESULT_OK));
                                            onMain(() -> result.complete(PlaceOutcome.ok(id, total)));
                                        });
                            });
                });
        return result;
    }

    /**
     * Kompensacja po nieudanym INSERT zlecenia BUY.
     * Kolejnosc:
     *  1. Sprobuj zdeponowac zwrot bezposrednio graczowi.
     *  2. Jesli deposit sie nie powiedzie - stworz money-claim.
     *  3. Jesli claim insert sie nie powiedzie - loguj SEVERE + audit FAILED/ROLLBACK,
     *     zwroc DB_FAILED. Gracz nie zostanie okklamany, ze zamowienie sie udalo.
     * Uwaga: PlaceOutcome zawsze != OK w tej sciezce.
     */
    private void compensateBuyPlacementFailure(UUID uuid, String name, String itemKey,
                                                BigDecimal total, Throwable insertErr,
                                                CompletableFuture<PlaceOutcome> result) {
        logger.log(Level.WARNING, "buy order insert failed for " + uuid + " item=" + itemKey, insertErr);
        economy.deposit(uuid, name, total, "bazaar-buy-order-refund-" + itemKey)
                .whenComplete((depo, depoErr) -> {
                    boolean depositOk = depoErr == null && depo != null && depo.success();
                    if (depositOk) {
                        audit.log(audit.builder()
                                .actor(uuid, name)
                                .action(AuditAction.BAZAAR_REFUND)
                                .market(AuditAction.MARKET_BAZAAR)
                                .itemKey(itemKey)
                                .total(total)
                                .result(AuditAction.RESULT_ROLLBACK)
                                .reason("buy order insert failed - refunded via deposit"));
                        onMain(() -> result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED)));
                        return;
                    }
                    hexCore.async(() -> claims.insertMoney(uuid, total,
                                    "bazaar-buy-order-refund-claim-" + itemKey,
                                    null, System.currentTimeMillis()))
                            .whenComplete((claimId, claimErr) -> {
                                if (claimErr == null && claimId != null && claimId >= 0) {
                                    audit.log(audit.builder()
                                            .actor(uuid, name)
                                            .action(AuditAction.BAZAAR_REFUND)
                                            .market(AuditAction.MARKET_BAZAAR)
                                            .itemKey(itemKey)
                                            .total(total)
                                            .claimId(claimId)
                                            .result(AuditAction.RESULT_REFUND_PENDING)
                                            .reason("buy order insert failed - refunded via money claim"));
                                    onMain(() -> result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED)));
                                    return;
                                }
                                logger.log(Level.SEVERE,
                                        "buy order refund path FAILED for " + uuid
                                                + " item=" + itemKey + " total=" + total
                                                + " - money not recoverable automatically",
                                        claimErr);
                                audit.log(audit.builder()
                                        .actor(uuid, name)
                                        .action(AuditAction.BAZAAR_REFUND)
                                        .market(AuditAction.MARKET_BAZAAR)
                                        .itemKey(itemKey)
                                        .total(total)
                                        .result(AuditAction.RESULT_FAILED)
                                        .reason("buy order insert failed - refund path also failed"));
                                onMain(() -> result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED)));
                            });
                });
    }

    // ---------------------------------------------------------------- SELL OFFER

    /**
     * Wystaw oferte sprzedazy. Zdejmuje fizyczne przedmioty z ekwipunku.
     * Zwrot przy anulowaniu odbywa sie przez system claim.
     */
    public CompletableFuture<PlaceOutcome> placeSellOffer(Player seller, String itemKey,
                                                          long amount, BigDecimal price) {
        BazaarConfig cfg = configSupplier.get();
        if (!cfg.enabled()) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.FEATURE_DISABLED));
        }
        BazaarItemConfig item = cfg.item(itemKey).orElse(null);
        if (item == null) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.UNKNOWN_ITEM));
        }
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.INVALID_QTY));
        }
        if (price == null || price.signum() <= 0
                || price.compareTo(item.minPrice()) < 0
                || price.compareTo(item.maxPrice()) > 0) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.INVALID_PRICE));
        }
        UUID uuid = seller.getUniqueId();
        String name = seller.getName();

        int intAmount = (int) amount;
        boolean removed = removeFromInventory(seller, item.material(), intAmount, cfg.requirePlainItem());
        if (!removed) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.NOT_ENOUGH_ITEMS));
        }

        CompletableFuture<PlaceOutcome> result = new CompletableFuture<>();
        int maxOpen = Math.max(1, maxOpenPerPlayer.get());
        hexCore.async(() -> orders.countOpenByOwner(uuid))
                .whenComplete((count, err) -> {
                    if (err != null) {
                        onMain(() -> {
                            giveBack(seller, uuid, item.material(), intAmount);
                            result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED));
                        });
                        return;
                    }
                    if (count >= maxOpen) {
                        onMain(() -> {
                            giveBack(seller, uuid, item.material(), intAmount);
                            result.complete(PlaceOutcome.fail(PlaceResult.TOO_MANY_OPEN));
                        });
                        return;
                    }
                    long now = System.currentTimeMillis();
                    Long expiresAt = computeExpiresAt(configSupplier.get(), now);
                    hexCore.async(() -> orders.insert(uuid, name, itemKey, OrderSide.SELL,
                                    amount, price, null, now, expiresAt))
                            .whenComplete((id, insErr) -> {
                                if (insErr != null || id == null) {
                                    onMain(() -> {
                                        giveBack(seller, uuid, item.material(), intAmount);
                                        result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED));
                                    });
                                    return;
                                }
                                BigDecimal total = price.multiply(new BigDecimal(amount));
                                audit.log(audit.builder()
                                        .actor(uuid, name)
                                        .action(AuditAction.BAZAAR_SELL_OFFER_PLACED)
                                        .market(AuditAction.MARKET_BAZAAR)
                                        .itemKey(itemKey)
                                        .orderId(id)
                                        .amount(amount)
                                        .unitPrice(price)
                                        .total(total)
                                        .result(AuditAction.RESULT_OK));
                                onMain(() -> result.complete(PlaceOutcome.ok(id, total)));
                            });
                });
        return result;
    }

    // ---------------------------------------------------------------- CANCEL

    /**
     * Anulowanie zlecenia z bezpieczna refundacja.
     * Wszystkie operacje refundacji (money-claim / item-claim) sa uruchamiane
     * w tej samej transakcji co zmiana stanu na CANCELLED - dzieki temu
     * anulowanie nie moze zostac potwierdzone dopoki srodki nie sa
     * bezpiecznie zapisane. Zwraca OK dopiero gdy refundacja przeszla.
     */
    public CompletableFuture<CancelResult> cancel(Player player, long orderId) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        CompletableFuture<CancelResult> result = new CompletableFuture<>();

        hexCore.async(() -> orders.findById(orderId))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        onMain(() -> result.complete(CancelResult.DB_FAILED));
                        return;
                    }
                    BazaarOrder o = opt.orElse(null);
                    if (o == null) {
                        onMain(() -> result.complete(CancelResult.NOT_FOUND));
                        return;
                    }
                    if (!o.ownerUuid().equals(uuid)) {
                        onMain(() -> result.complete(CancelResult.NOT_OWNER));
                        return;
                    }
                    if (!o.state().isOpen()) {
                        onMain(() -> result.complete(CancelResult.NOT_OPEN));
                        return;
                    }
                    // Uruchamiamy zmiane stanu + refundacje w tej samej transakcji.
                    // Jesli refund hook rzuci wyjatek, cala transakcja jest wycofywana.
                    hexCore.async(() -> {
                                long now = System.currentTimeMillis();
                                return orders.tryCancelWithRefundTx(orderId, uuid, now,
                                        (tx, snap) -> buildRefundInsideTx(tx, snap));
                            })
                            .whenComplete((cancelOpt, cErr) -> {
                                if (cErr != null) {
                                    logger.log(Level.SEVERE,
                                            "cancel refund tx failed for order " + orderId, cErr);
                                    audit.log(audit.builder()
                                            .actor(uuid, name)
                                            .action(AuditAction.BAZAAR_ORDER_CANCELLED)
                                            .market(AuditAction.MARKET_BAZAAR)
                                            .orderId(orderId)
                                            .result(AuditAction.RESULT_FAILED)
                                            .reason("cancel refund transaction failed"));
                                    onMain(() -> result.complete(CancelResult.DB_FAILED));
                                    return;
                                }
                                if (cancelOpt == null || cancelOpt.isEmpty()) {
                                    onMain(() -> result.complete(CancelResult.NOT_OPEN));
                                    return;
                                }
                                BazaarOrder cancelled = cancelOpt.get();
                                audit.log(audit.builder()
                                        .actor(uuid, name)
                                        .action(AuditAction.BAZAAR_ORDER_CANCELLED)
                                        .market(AuditAction.MARKET_BAZAAR)
                                        .itemKey(cancelled.itemKey())
                                        .orderId(cancelled.id())
                                        .amount(cancelled.amountRemaining())
                                        .unitPrice(cancelled.pricePerUnit())
                                        .total(cancelled.reservedMoney())
                                        .result(AuditAction.RESULT_OK)
                                        .reason("cancel with refund"));
                                onMain(() -> result.complete(CancelResult.OK));
                            });
                });
        return result;
    }

    /**
     * Skladamy refundacje w ramach transakcji anulowania.
     * BUY: money-claim = reserved_money jesli > 0.
     * SELL: item-claims dla kazdego pelnego stacka pozostalej ilosci.
     * Rzucenie RuntimeException wycofuje cala transakcje - zlecenie NIE zostanie
     * anulowane, wiec gracz moze sprobowac ponownie.
     */
    private void buildRefundInsideTx(hex.core.api.db.Db tx, BazaarOrder cancelled) {
        long now = System.currentTimeMillis();
        if (cancelled.side() == OrderSide.BUY) {
            BigDecimal reserved = cancelled.reservedMoney();
            if (reserved != null && reserved.signum() > 0) {
                AuctionClaimRepository.insertMoneyTx(tx, cancelled.ownerUuid(), reserved,
                        "bazaar-order-refund-" + cancelled.id(), null, now);
            }
            return;
        }
        // SELL
        BazaarItemConfig item = configSupplier.get().item(cancelled.itemKey()).orElse(null);
        if (item == null) {
            throw new IllegalStateException("bazaar item config missing during refund: "
                    + cancelled.itemKey());
        }
        long remaining = cancelled.amountRemaining();
        while (remaining > 0) {
            int stackAmt = (int) Math.min(remaining, item.material().getMaxStackSize());
            ItemStack stack = new ItemStack(item.material(), stackAmt);
            byte[] blob;
            try {
                blob = ItemSerializer.serialize(stack);
            } catch (Throwable t) {
                throw new RuntimeException("serialize failed during cancel refund", t);
            }
            AuctionClaimRepository.insertItemTx(tx, cancelled.ownerUuid(), blob,
                    "bazaar-order-refund-" + cancelled.id(), null, now);
            remaining -= stackAmt;
        }
    }

    // ---------------------------------------------------------------- MATCHING

    /**
     * Symulacja dopasowania do zlecen kupna (dla instant SELL).
     * Nie zmienia stanu w DB - sluzy do wyliczenia szacowanego przychodu
     * zanim wywolamy prawdziwy matching. Cena kazdego zlecenia musi byc
     * >= sellFloorPrice.
     */
    public MatchPreview previewMatchBuyOrders(String itemKey, long wantAmount,
                                               BigDecimal sellFloorPrice) {
        List<BazaarOrder> candidates = orders.topOpenBuyOrders(itemKey, 20);
        long remaining = wantAmount;
        BigDecimal totalMoney = BigDecimal.ZERO;
        for (BazaarOrder o : candidates) {
            if (remaining <= 0) break;
            if (o.pricePerUnit().compareTo(sellFloorPrice) < 0) break;
            long take = Math.min(remaining, o.amountRemaining());
            if (take <= 0) continue;
            totalMoney = totalMoney.add(o.pricePerUnit().multiply(new BigDecimal(take)));
            remaining -= take;
        }
        return new MatchPreview(wantAmount - remaining, totalMoney);
    }

    /**
     * Symulacja dopasowania do ofert sprzedazy (dla instant BUY).
     * Cena kazdej oferty musi byc <= buyCeilPrice.
     */
    public MatchPreview previewMatchSellOffers(String itemKey, long wantAmount,
                                                BigDecimal buyCeilPrice) {
        List<BazaarOrder> candidates = orders.topOpenSellOffers(itemKey, 20);
        long remaining = wantAmount;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (BazaarOrder o : candidates) {
            if (remaining <= 0) break;
            if (o.pricePerUnit().compareTo(buyCeilPrice) > 0) break;
            long take = Math.min(remaining, o.amountRemaining());
            if (take <= 0) continue;
            totalCost = totalCost.add(o.pricePerUnit().multiply(new BigDecimal(take)));
            remaining -= take;
        }
        return new MatchPreview(wantAmount - remaining, totalCost);
    }

    /**
     * Wyjatek sygnalizujacy blad matchingu ktory MUSI wywolac rollback
     * transakcji. Uzywany zamiast return false zeby Db.tx nie zacommitowal
     * czesciowo zmutowanego stanu (fill juz zapisany, ale money-consume
     * lub claim-insert nie).
     */
    static final class MatchTxAbort extends RuntimeException {
        MatchTxAbort(String msg) { super(msg); }
    }

    /**
     * Faktyczne dopasowanie do zlecen kupna. Uzywane przez instant-SELL.
     * Rezerwuje pieniadze z reserved_money (juz zablokowane przy skladaniu
     * zlecenia BUY) i tworzy claim przedmiotow dla wystawiajacego zlecenie
     * BUY - wszystko w tej samej transakcji per zlecenie.
     * Zwraca (matched, revenue) - kwote naleznu sprzedajacemu (caller
     * musi zdeponowac ja u sprzedajacego).
     *
     * Semantyka transakcyjna:
     *  - kazde niepowodzenie w tx (MatchTxAbort lub inny RuntimeException)
     *    powoduje rollback tej konkretnej per-order tx,
     *  - ale wczesniejsze zacommitowane fills musza zostac zachowane w wynikowym
     *    {@link MatchResult}. Dlatego wyjatek per-order jest przechwytywany,
     *    petla przerwana, ale zwracamy dotychczasowy partial match.
     */
    public MatchResult matchAgainstBuyOrders(String itemKey, long wantAmount,
                                             BigDecimal sellFloorPrice,
                                             UUID counterparty, String counterpartyName) {
        // Fail-fast: bez konfiguracji przedmiotu w ogole nie zaczynamy dopasowywac.
        BazaarItemConfig itemCheck = configSupplier.get().item(itemKey).orElse(null);
        if (itemCheck == null) {
            logger.log(Level.WARNING, "matchAgainstBuyOrders: missing item config " + itemKey);
            return new MatchResult(0L, BigDecimal.ZERO);
        }
        long remaining = wantAmount;
        BigDecimal totalReceived = BigDecimal.ZERO;
        long now = System.currentTimeMillis();

        while (remaining > 0) {
            var bestOpt = orders.peekBestBuyOrder(itemKey);
            if (bestOpt.isEmpty()) break;
            BazaarOrder best = bestOpt.get();
            if (best.pricePerUnit().compareTo(sellFloorPrice) < 0) break;
            long take = Math.min(remaining, best.amountRemaining());
            if (take <= 0) break;
            BigDecimal filledMoney = best.pricePerUnit().multiply(new BigDecimal(take));
            long orderId = best.id();
            long finalTake = take;
            BigDecimal finalMoney = filledMoney;
            boolean isFinalFill = take >= best.amountRemaining();

            boolean applied;
            try {
                applied = hexCore.rawDb().tx(tx -> {
                    if (!BazaarOrderRepository.tryFillPortionTx(tx, orderId, finalTake, now)) {
                        // Zwrot false PRZED jakakolwiek mutacja - Db.tx commituje pusto.
                        return false;
                    }
                    // Od tego miejsca UPDATE jest juz zapisany w transakcji.
                    // Kazde niepowodzenie MUSI throwac, zeby wycofac zmiane.
                    if (!BazaarOrderRepository.tryConsumeReservedMoneyTx(tx, orderId, finalMoney)) {
                        throw new MatchTxAbort("reserved_money consume failed for order " + orderId);
                    }
                    BazaarItemConfig item = configSupplier.get().item(itemKey).orElse(null);
                    if (item == null) {
                        // Konfiguracja zniknela w trakcie meczu - nie mozemy wydac przedmiotow.
                        throw new MatchTxAbort("bazaar item config missing for " + itemKey);
                    }
                    long left = finalTake;
                    while (left > 0) {
                        int stackAmt = (int) Math.min(left, item.material().getMaxStackSize());
                        ItemStack stack = new ItemStack(item.material(), stackAmt);
                        byte[] blob;
                        try {
                            blob = ItemSerializer.serialize(stack);
                        } catch (Throwable t) {
                            throw new MatchTxAbort("serialize failed for match delivery: " + t.getMessage());
                        }
                        AuctionClaimRepository.insertItemTx(tx, best.ownerUuid(), blob,
                                "bazaar-order-fill-" + orderId, null, now);
                        left -= stackAmt;
                    }
                    return true;
                });
            } catch (RuntimeException abort) {
                // Kazdy wyjatek na tym zleceniu -> rollback per-order,
                // ale wczesniejsze zacommitowane fills zachowujemy w partial result.
                logger.log(Level.WARNING,
                        "matchAgainstBuyOrders tx rolled back for order " + orderId
                                + ": " + abort.getMessage());
                audit.log(audit.builder()
                        .actor(counterparty, counterpartyName)
                        .action(AuditAction.BAZAAR_ORDER_PARTIAL_FILL)
                        .market(AuditAction.MARKET_BAZAAR)
                        .itemKey(itemKey)
                        .orderId(orderId)
                        .amount(take)
                        .result(AuditAction.RESULT_ROLLBACK)
                        .reason(abort.getMessage()));
                break;
            }
            if (!applied) break;

            audit.log(audit.builder()
                    .actor(counterparty, counterpartyName)
                    .action(isFinalFill
                            ? AuditAction.BAZAAR_ORDER_FILLED
                            : AuditAction.BAZAAR_ORDER_PARTIAL_FILL)
                    .market(AuditAction.MARKET_BAZAAR)
                    .itemKey(itemKey)
                    .orderId(orderId)
                    .amount(take)
                    .unitPrice(best.pricePerUnit())
                    .total(filledMoney)
                    .result(AuditAction.RESULT_OK)
                    .reason("instant-sell fill"));

            remaining -= take;
            totalReceived = totalReceived.add(filledMoney);
        }

        long matched = wantAmount - remaining;
        return new MatchResult(matched, totalReceived);
    }

    /**
     * Backward-compat: bez limitu wydatku (maxSpend = null).
     */
    public MatchResult matchAgainstSellOffers(String itemKey, long wantAmount,
                                              BigDecimal buyCeilPrice,
                                              UUID counterparty, String counterpartyName) {
        return matchAgainstSellOffers(itemKey, wantAmount, buyCeilPrice,
                counterparty, counterpartyName, null);
    }

    /**
     * Faktyczne dopasowanie do ofert sprzedazy. Uzywane przez instant-BUY.
     * Kazdy match w osobnej transakcji.
     *
     * <p>maxSpend: gorne ograniczenie na sume wyplat do sprzedajacych (SELL-offer
     * ownerow). Jest to budzet, ktory kupujacy z gory pobral z konta - matching
     * NIGDY nie moze wygenerowac claim-ow na wieksza sume, nawet jesli pre-scan
     * pokazywal tansze zlecenia, ktore wsrod race'ow zniknely i pojawily sie
     * drozsze. Jesli kolejne dopasowanie by przekroczylo budzet, matching sie
     * konczy z partial fill i zwraca dotychczasowy wynik. Gdy maxSpend jest
     * null, limit budzetu nie obowiazuje (wariant pre-scan / test).</p>
     *
     * <p>Semantyka bledow: kazdy RuntimeException per-order tx rollbackuje ten
     * jeden order, ale wczesniejsze zacommitowane fills MUSZA zostac zachowane
     * w wyniku - dlatego wyjatek per-order jest przechwytywany i petla przerwana.</p>
     */
    public MatchResult matchAgainstSellOffers(String itemKey, long wantAmount,
                                              BigDecimal buyCeilPrice,
                                              UUID counterparty, String counterpartyName,
                                              BigDecimal maxSpend) {
        BazaarItemConfig itemCheck = configSupplier.get().item(itemKey).orElse(null);
        if (itemCheck == null) {
            logger.log(Level.WARNING, "matchAgainstSellOffers: missing item config " + itemKey);
            return new MatchResult(0L, BigDecimal.ZERO);
        }
        long remaining = wantAmount;
        BigDecimal totalPaid = BigDecimal.ZERO;
        long now = System.currentTimeMillis();

        while (remaining > 0) {
            var bestOpt = orders.peekBestSellOffer(itemKey);
            if (bestOpt.isEmpty()) break;
            BazaarOrder best = bestOpt.get();
            if (best.pricePerUnit().compareTo(buyCeilPrice) > 0) break;
            long baseTake = Math.min(remaining, best.amountRemaining());
            if (baseTake <= 0) break;
            BigDecimal unitPrice = best.pricePerUnit();
            long take = capTakeToBudget(baseTake, unitPrice, maxSpend, totalPaid);
            if (take <= 0) break;
            BigDecimal filledMoney = unitPrice.multiply(new BigDecimal(take));
            long orderId = best.id();
            long finalTake = take;
            BigDecimal finalMoney = filledMoney;
            boolean isFinalFill = take >= best.amountRemaining();

            boolean applied;
            try {
                applied = hexCore.rawDb().tx(tx -> {
                    if (!BazaarOrderRepository.tryFillPortionTx(tx, orderId, finalTake, now)) {
                        return false;
                    }
                    // Po mutacji: kazde niepowodzenie -> rollback.
                    try {
                        AuctionClaimRepository.insertMoneyTx(tx, best.ownerUuid(), finalMoney,
                                "bazaar-order-fill-" + orderId, null, now);
                    } catch (RuntimeException ex) {
                        throw new MatchTxAbort("money claim insert failed: " + ex.getMessage());
                    }
                    return true;
                });
            } catch (RuntimeException abort) {
                // Wyjatek per-order (rollback tej tx), ale nie tracimy juz zacommitowanych fills.
                logger.log(Level.WARNING,
                        "matchAgainstSellOffers tx rolled back for order " + orderId
                                + ": " + abort.getMessage());
                audit.log(audit.builder()
                        .actor(counterparty, counterpartyName)
                        .action(AuditAction.BAZAAR_ORDER_PARTIAL_FILL)
                        .market(AuditAction.MARKET_BAZAAR)
                        .itemKey(itemKey)
                        .orderId(orderId)
                        .amount(take)
                        .result(AuditAction.RESULT_ROLLBACK)
                        .reason(abort.getMessage()));
                break;
            }
            if (!applied) break;

            audit.log(audit.builder()
                    .actor(counterparty, counterpartyName)
                    .action(isFinalFill
                            ? AuditAction.BAZAAR_ORDER_FILLED
                            : AuditAction.BAZAAR_ORDER_PARTIAL_FILL)
                    .market(AuditAction.MARKET_BAZAAR)
                    .itemKey(itemKey)
                    .orderId(orderId)
                    .amount(take)
                    .unitPrice(unitPrice)
                    .total(filledMoney)
                    .result(AuditAction.RESULT_OK)
                    .reason("instant-buy fill"));

            remaining -= take;
            totalPaid = totalPaid.add(filledMoney);
        }

        long matched = wantAmount - remaining;
        return new MatchResult(matched, totalPaid);
    }

    public record MatchPreview(long matchable, BigDecimal totalMoney) {}
    public record MatchResult(long matched, BigDecimal totalMoney) {}

    /**
     * Pure function: ile jednostek moze wziac buyer z danego SELL-offer, biorac
     * pod uwage limit budzetu.
     *
     *  - Gdy maxSpend jest null: brak limitu, zwraca 'baseTake'.
     *  - Gdy pozostaly budzet (maxSpend - alreadyPaid) <= 0: zwraca 0
     *    (matching konczy sie natychmiast).
     *  - Gdy unitPrice <= 0: zwraca 'baseTake' - darmowe fille nie wyczerpuja budzetu.
     *  - Wpp. zwraca min(baseTake, floor(remainingBudget / unitPrice)).
     *
     * Niezmiennik: return * unitPrice + alreadyPaid <= maxSpend.
     */
    public static long capTakeToBudget(long baseTake, BigDecimal unitPrice,
                                        BigDecimal maxSpend, BigDecimal alreadyPaid) {
        if (baseTake <= 0) return 0;
        if (maxSpend == null) return baseTake;
        BigDecimal remainingBudget = maxSpend.subtract(alreadyPaid == null ? BigDecimal.ZERO : alreadyPaid);
        if (remainingBudget.signum() <= 0) return 0;
        if (unitPrice == null || unitPrice.signum() <= 0) return baseTake;
        long affordable = remainingBudget
                .divide(unitPrice, 0, java.math.RoundingMode.FLOOR)
                .longValue();
        if (affordable <= 0) return 0;
        return Math.min(baseTake, affordable);
    }

    // ---------------------------------------------------------------- EXPIRY

    /**
     * Wygasa otwarte zlecenia ktorych pole expires_at zdazylo minac.
     * Refundacje (money-claim / item-claim) dziejа sie w tej samej
     * transakcji co zmiana stanu na EXPIRED - dokladnie jak przy cancel.
     * Zwraca liczbe zlecen przetworzonych w tym cyklu.
     */
    public CompletableFuture<Integer> expireDueOrders(int batchSize) {
        return hexCore.async(() -> {
            long now = System.currentTimeMillis();
            List<BazaarOrder> due = orders.findOpenExpired(now, batchSize);
            int processed = 0;
            for (BazaarOrder o : due) {
                try {
                    var opt = orders.tryExpireWithRefundTx(o.id(), now,
                            (tx, snap) -> buildRefundInsideTx(tx, snap));
                    if (opt.isPresent()) {
                        BazaarOrder ex = opt.get();
                        audit.log(audit.builder()
                                .actor(ex.ownerUuid(), ex.ownerName())
                                .action(AuditAction.BAZAAR_ORDER_CANCELLED)
                                .market(AuditAction.MARKET_BAZAAR)
                                .itemKey(ex.itemKey())
                                .orderId(ex.id())
                                .amount(ex.amountRemaining())
                                .unitPrice(ex.pricePerUnit())
                                .total(ex.reservedMoney())
                                .result(AuditAction.RESULT_OK)
                                .reason("order expired"));
                        processed++;
                    }
                } catch (Throwable t) {
                    logger.log(Level.WARNING,
                            "order expiry tx failed for order " + o.id(), t);
                    audit.log(audit.builder()
                            .action(AuditAction.BAZAAR_ORDER_CANCELLED)
                            .market(AuditAction.MARKET_BAZAAR)
                            .orderId(o.id())
                            .result(AuditAction.RESULT_FAILED)
                            .reason("order expiry tx failed"));
                }
            }
            return processed;
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "order expiry sweep failed", ex);
            return 0;
        });
    }

    // ---------------------------------------------------------------- helpers

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
                claimItemAsync(owner, rest, "bazaar-order-refund");
            }
            return;
        }
        claimItemAsync(owner, new ItemStack(mat, amount), "bazaar-order-refund");
    }

    private void claimItemAsync(UUID owner, ItemStack item, String reason) {
        if (owner == null || item == null) return;
        final byte[] blob;
        try {
            blob = ItemSerializer.serialize(item);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "cancel refund serialize failed", t);
            return;
        }
        hexCore.async(() -> claims.insertItem(owner, blob, reason, null, System.currentTimeMillis()))
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "cancel refund insert failed", ex);
                    return -1L;
                });
    }
}
