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
import hex.auctionbazaar.util.InventoryExtract;
import hex.auctionbazaar.util.InventoryFit;
import hex.auctionbazaar.util.ItemSerializer;
import hex.auctionbazaar.util.Money;
import hex.auctionbazaar.util.SafeTime;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
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
        /** Techniczny błąd systemu ekonomii (wyjątek/null/inny błąd, nie brak środków) - punkt #6. */
        ECONOMY_ERROR,
        DB_FAILED,
        FEATURE_DISABLED,
        /** Zwrot wystawionych przedmiotów po błędzie NIEPEWNY/NIEUDANY - stan krytyczny, ręczna korekta. */
        COMPENSATION_FAILED,
        /** Brak uprawnień - sprawdzane też serwerowo, nie tylko w komendzie/GUI. */
        NO_PERMISSION,
        /** Trwa już składanie zlecenia tego gracza (busy-guard) - blokuje podwójny klik. */
        BUSY
    }

    public record PlaceOutcome(PlaceResult result, Long orderId, BigDecimal totalReserved) {
        public static PlaceOutcome ok(long id, BigDecimal total) {
            return new PlaceOutcome(PlaceResult.OK, id, total);
        }
        public static PlaceOutcome fail(PlaceResult r) {
            return new PlaceOutcome(r, null, null);
        }
    }

    public enum CancelResult { OK, NOT_FOUND, NOT_OWNER, NOT_OPEN, DB_FAILED,
        /** Brak uprawnień (bazaar.permissions.order-cancel) - sprawdzane też serwerowo. */
        NO_PERMISSION }

    /** Wynik usunięcia widocznego wpisu anulowanego zlecenia (punkt #5). */
    public enum RemoveResult { OK, NOT_FOUND, NOT_OWNER, NOT_CANCELLED, DB_FAILED,
        /** Brak uprawnień (bazaar.permissions.order-cancel) - sprawdzane też serwerowo. */
        NO_PERMISSION }

    /**
     * Wynik śledzonego, batchowego zwrotu wystawionych przedmiotów po błędzie (punkt #1):
     *  - ADDED_FULLY: wszystkie oryginalne stacki bezpiecznie w ekwipunku;
     *  - CLAIMED: nie zmieściły się, ale WSZYSTKIE zapisane atomowo jako claim-y (jedna transakcja);
     *  - STATE_UNCERTAIN: stan ekwipunku niepewny - BEZ claim (możliwa częściowa dostawa -> duplikacja);
     *  - CLAIM_FAILED: atomowy zapis claim-ów nie powiódł się.
     * ADDED_FULLY/CLAIMED = zwrot bezpieczny; STATE_UNCERTAIN/CLAIM_FAILED = krytyczny (COMPENSATION_FAILED).
     */
    public enum ReturnOutcome { ADDED_FULLY, CLAIMED, STATE_UNCERTAIN, CLAIM_FAILED;
        public boolean safe() { return this == ADDED_FULLY || this == CLAIMED; }
    }

    /**
     * Seam wątku głównego / odzysku zwrotu SELL (testowalność). Kapsułkuje (1) planowanie zadania na
     * wątku głównym oraz (2) all-or-nothing próbę zwrotu do ekwipunku - dzięki temu ścieżki: odrzucenia
     * planowania, „przyjęte, ale niewykonane" oraz drain przy disable są w pełni deterministycznie
     * testowalne (bez sleepów, bez @Disabled).
     */
    public interface ReturnRecoverySeam {
        /** Zaplanuj zadanie na wątku głównym. RZUCA, gdy planowanie jest odrzucone (np. przy wyłączaniu). */
        void dispatchMain(Runnable task);

        /** MUSI działać na wątku głównym. All-or-nothing dodanie do ekwipunku (tri-state). Nigdy nie rzuca. */
        InventoryFit.Result tryReturnToInventory(Player seller, List<ItemStack> stacks);
    }

    /** Stan odzysku zwrotu (genau-einmal): PENDING -&gt; RUNNING -&gt; TERMINAL. */
    private enum RecoveryState { PENDING, RUNNING, TERMINAL }

    /**
     * Zarejestrowana operacja odzysku zwrotu wystawionych stacków. Trzyma KLONY oryginałów (pełne
     * NBT/PDC/nazwa/lore/enchanty/CMD) oraz atomowy stan, tak że DOKŁADNIE JEDEN aktor - zaplanowany
     * runnable, obsługa odrzucenia planowania albo drain przy disable - wykonuje zwrot/claim, a future
     * domyka się dokładnie raz.
     */
    private static final class ReturnRecovery {
        final long recoveryId;
        final UUID uuid;
        final Player seller;
        final List<ItemStack> stacks;
        final CompletableFuture<ReturnOutcome> out;
        final java.util.concurrent.atomic.AtomicReference<RecoveryState> state =
                new java.util.concurrent.atomic.AtomicReference<>(RecoveryState.PENDING);

        ReturnRecovery(long recoveryId, UUID uuid, Player seller, List<ItemStack> stacks,
                       CompletableFuture<ReturnOutcome> out) {
            this.recoveryId = recoveryId;
            this.uuid = uuid;
            this.seller = seller;
            this.stacks = stacks;
            this.out = out;
        }
    }

    /** Rozmiar strony przy skanowaniu orderbooka w podglądzie (bez sztywnego cap-u na 20). */
    private static final int PREVIEW_PAGE = 100;

    private final Plugin plugin;
    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final EconomyBridge economy;
    private final BazaarOrderRepository orders;
    private final AuctionClaimRepository claims;
    private final AuditService audit;
    private final Supplier<BazaarConfig> configSupplier;
    private final Supplier<Integer> maxOpenPerPlayer;
    /** Globalny przełącznik pluginu (enabled). false = tryb konserwacji: brak nowych komercyjnych mutacji. */
    private final java.util.function.BooleanSupplier pluginEnabled;

    /**
     * Busy-guard per gracz (punkt #1): jeden aktywny proces składania zlecenia (BUY/SELL) na gracza.
     * Blokuje podwójny klik - drugie równoległe wejście dostaje {@link PlaceResult#BUSY}, więc nie usuwa
     * tych samych przedmiotów dwa razy ani nie przekracza limitu zleceń przez dwa równoległe count+insert.
     * Zwalniany DOPIERO po terminalnym zwrocie/insert/kompensacji (przez {@code result.whenComplete}).
     */
    private final java.util.Set<UUID> placing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Zarejestrowane, jeszcze NIE-terminalne operacje odzysku zwrotu wystawionych przedmiotów. Drain przy
     * disable przejmuje z tego zbioru operacje nierozpoczęte (PENDING), by żaden zdjęty stack nie zniknął.
     */
    private final java.util.Set<ReturnRecovery> pendingReturns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Stabilny identyfikator diagnostyczny zwrotu, używany przy ręcznej korekcie po timeout-cie disable. */
    private final java.util.concurrent.atomic.AtomicLong recoverySequence =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** Seam wątku głównego / odzysku (produkcja: Bukkit scheduler + {@link InventoryFit}; test: atrapa). */
    private final ReturnRecoverySeam recoverySeam;
    /** Rate-limit (60s) dla SEVERE logu niepewnego/nieudanego zwrotu oferty. */
    private final java.util.concurrent.atomic.AtomicLong lastReturnSevereLogAt =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** Rate-limit (60s) dla SEVERE logu nieodwracalnego zwrotu środków po nieudanym zapisie zlecenia BUY. */
    private final java.util.concurrent.atomic.AtomicLong lastBuyRefundSevereLogAt =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public BazaarOrderService(Plugin plugin,
                              HexCoreBridge hexCore,
                              EconomyBridge economy,
                              BazaarOrderRepository orders,
                              AuctionClaimRepository claims,
                              AuditService audit,
                              Supplier<BazaarConfig> configSupplier,
                              Supplier<Integer> maxOpenPerPlayer,
                              java.util.function.BooleanSupplier pluginEnabled) {
        this(plugin, hexCore, economy, orders, claims, audit, configSupplier, maxOpenPerPlayer,
                pluginEnabled, null);
    }

    /** Wariant z wstrzykiwaną {@link ReturnRecoverySeam} (testowalność odzysku zwrotu SELL). */
    public BazaarOrderService(Plugin plugin,
                              HexCoreBridge hexCore,
                              EconomyBridge economy,
                              BazaarOrderRepository orders,
                              AuctionClaimRepository claims,
                              AuditService audit,
                              Supplier<BazaarConfig> configSupplier,
                              Supplier<Integer> maxOpenPerPlayer,
                              java.util.function.BooleanSupplier pluginEnabled,
                              ReturnRecoverySeam recoverySeam) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.maxOpenPerPlayer = Objects.requireNonNull(maxOpenPerPlayer, "maxOpenPerPlayer");
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
        this.recoverySeam = recoverySeam != null ? recoverySeam : defaultRecoverySeam(plugin);
    }

    /** Produkcyjny seam: planowanie przez Bukkit scheduler + all-or-nothing zwrot przez {@link InventoryFit}. */
    private static ReturnRecoverySeam defaultRecoverySeam(Plugin plugin) {
        return new ReturnRecoverySeam() {
            @Override
            public void dispatchMain(Runnable task) {
                Bukkit.getScheduler().runTask(plugin, task);
            }

            @Override
            public InventoryFit.Result tryReturnToInventory(Player seller, List<ItemStack> stacks) {
                return InventoryFit.tryAddAllFull(seller, stacks);
            }
        };
    }

    /**
     * Nowe komercyjne mutacje (składanie zleceń BUY/SELL) dozwolone tylko gdy plugin NIE jest w trybie
     * konserwacji (global enabled) ORAZ Rynek jest włączony (bazaar.enabled). Egzekwowane SERWEROWO.
     * Akcje odzysku (anulowanie, usunięcie wpisu) świadomie NIE przechodzą przez tę bramkę.
     */
    private boolean commerceEnabled() {
        return pluginEnabled.getAsBoolean() && configSupplier.get().enabled();
    }

    /**
     * Punkt #6: klasyfikacja NIEUDANEGO withdraw przy składaniu zlecenia BUY. Wyjątek/null/inny błąd
     * techniczny -> {@link PlaceResult#ECONOMY_ERROR}; wyłącznie {@code NOT_ENOUGH_FUNDS} ->
     * {@link PlaceResult#NOT_ENOUGH_MONEY}.
     */
    public static PlaceResult classifyWithdrawFailure(boolean hadException, boolean nullResult, String reason) {
        if (hadException || nullResult) {
            return PlaceResult.ECONOMY_ERROR;
        }
        return "NOT_ENOUGH_FUNDS".equals(reason) ? PlaceResult.NOT_ENOUGH_MONEY : PlaceResult.ECONOMY_ERROR;
    }

    private void onMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    public CompletableFuture<List<BazaarOrder>> listOpen(UUID owner, int limit) {
        return hexCore.async(() -> orders.findOpenByOwner(owner, limit))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie aktywnych zleceń nie powiodło się", ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<BazaarOrder>> listAll(UUID owner, int limit) {
        return hexCore.async(() -> orders.findByOwner(owner, limit))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie historii zleceń nie powiodło się", ex);
                    return List.of();
                });
    }

    /** Stronicowany widok wszystkich zleceń gracza (LIMIT/OFFSET po stronie DB). */
    public CompletableFuture<List<BazaarOrder>> listAllPaged(UUID owner, int limit, int offset) {
        return hexCore.async(() -> orders.pageByOwner(owner, limit, offset))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie strony zleceń nie powiodło się", ex);
                    return List.of();
                });
    }

    /** Liczba wszystkich zleceń gracza (do stronicowania GUI). */
    public CompletableFuture<Integer> countAll(UUID owner) {
        return hexCore.async(() -> orders.countByOwner(owner))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Zliczenie zleceń nie powiodło się", ex);
                    return 0;
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
        // SafeTime chroni przed przepełnieniem now + seconds*1000 (nigdy ujemny expiresAt - punkt #8).
        return seconds > 0 ? SafeTime.deadlineMillis(placeNow, seconds) : null;
    }

    // ---------------------------------------------------------------- BUY ORDER

    /**
     * Wystaw zlecenie kupna. Gracz placi z gory amount*price - kasa
     * zamrozona az do zrealizowania lub anulowania.
     */
    public CompletableFuture<PlaceOutcome> placeBuyOrder(Player buyer, String itemKey,
                                                         long amount, BigDecimal price) {
        BazaarConfig cfg = configSupplier.get();
        // SERWEROWA bramka: commerceEnabled() = global enabled (tryb konserwacji) ORAZ bazaar.enabled.
        if (!commerceEnabled()) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.FEATURE_DISABLED));
        }
        if (!buyer.hasPermission(cfg.permOrderBuy())) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.NO_PERMISSION));
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
        // Jednolita skala (2, HALF_UP) i ochrona przed przepełnieniem DECIMAL(19,2) PRZED abbuchung.
        BigDecimal unitPrice = Money.normalize(price);
        BigDecimal total = Money.totalOrNull(unitPrice, amount);
        if (total == null) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.INVALID_PRICE));
        }
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.ECONOMY_UNAVAILABLE));
        }
        UUID uuid = buyer.getUniqueId();
        String name = buyer.getName();

        // Busy-guard: jeden aktywny proces składania na gracza. Limit + insert biegną pod tym samym
        // per-player lockiem, więc dwa szybkie klik nie przekroczą limitu zleceń (punkt #1).
        if (!placing.add(uuid)) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.BUSY));
        }
        CompletableFuture<PlaceOutcome> result = new CompletableFuture<>();
        result.whenComplete((r, e) -> placing.remove(uuid));   // zwolnienie DOPIERO po terminalnym wyniku
        int maxOpen = Math.max(1, maxOpenPerPlayer.get());
        // Domknięcia idą WPROST (bez onMain): CompletableFuture nie wymaga wątku głównego, a odrzucenie
        // schedulera przy wyłączaniu nie może zawiesić future ani busy-guarda. Wołający (GUI/komenda)
        // sam przełącza się na wątek główny w swoim thenAccept. Synchroniczne odrzucenie hexCore.async
        // jest złapane, by future zawsze był terminalny.
        CompletableFuture<Integer> countFuture;
        try {
            countFuture = hexCore.async(() -> orders.countOpenByOwner(uuid));
        } catch (Throwable t) {
            result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED));
            return result;
        }
        countFuture.whenComplete((count, err) -> {
                    if (err != null) {
                        result.complete(PlaceOutcome.fail(PlaceResult.DB_FAILED));
                        return;
                    }
                    if (count >= maxOpen) {
                        result.complete(PlaceOutcome.fail(PlaceResult.TOO_MANY_OPEN));
                        return;
                    }
                    economy.withdraw(uuid, name, total, "bazaar-buy-order-reserve-" + itemKey)
                            .whenComplete((res, weErr) -> {
                                if (weErr != null || res == null || !res.success()) {
                                    // Rozdziel brak środków (NOT_ENOUGH_FUNDS) od technicznego błędu ekonomii.
                                    PlaceResult r = classifyWithdrawFailure(weErr != null, res == null,
                                            res == null ? null : res.reason());
                                    result.complete(PlaceOutcome.fail(r));
                                    return;
                                }
                                long placeNow = System.currentTimeMillis();
                                Long expiresAt = computeExpiresAt(configSupplier.get(), placeNow);
                                // Środki POBRANE: od tego miejsca każda porażka MUSI przejść przez śledzoną
                                // kompensację (deposit -> money-claim -> terminal), inaczej gracz straci kasę.
                                CompletableFuture<Long> insertFuture;
                                try {
                                    insertFuture = hexCore.async(() -> orders.insert(uuid, name, itemKey,
                                            OrderSide.BUY, amount, unitPrice, total, placeNow, expiresAt));
                                } catch (Throwable t) {
                                    // Synchroniczne odrzucenie zgłoszenia async PO pobraniu środków -> kompensacja.
                                    compensateBuyPlacementFailure(uuid, name, itemKey, total, t, result);
                                    return;
                                }
                                insertFuture.whenComplete((id, insErr) -> {
                                            if (insErr != null || id == null) {
                                                compensateBuyPlacementFailure(uuid, name, itemKey, total,
                                                        insErr, result);
                                                return;
                                            }
                                            // Domknij PRZED audytem - audyt nie może zawiesić future/busy-guarda.
                                            if (result.complete(PlaceOutcome.ok(id, total))) {
                                                try {
                                                    audit.log(audit.builder()
                                                            .actor(uuid, name)
                                                            .action(AuditAction.BAZAAR_BUY_ORDER_PLACED)
                                                            .market(AuditAction.MARKET_BAZAAR)
                                                            .itemKey(itemKey)
                                                            .orderId(id)
                                                            .amount(amount)
                                                            .unitPrice(unitPrice)
                                                            .total(total)
                                                            .result(AuditAction.RESULT_OK));
                                                } catch (Throwable t) {
                                                    logger.log(Level.WARNING, "RYNEK: zapis audytu "
                                                            + "zlecenia kupna nie powiódł się", t);
                                                }
                                            }
                                        });
                            });
                });
        return result;
    }

    /**
     * Kompensacja po nieudanym INSERT zlecenia BUY (środki JUŻ pobrane). Terminalne stany:
     *  1. Deposit OK -> {@link PlaceResult#DB_FAILED}, audyt ROLLBACK (środki zwrócone wprost).
     *  2. Deposit nieudany, money-claim OK -> {@link PlaceResult#DB_FAILED}, audyt REFUND_PENDING
     *     (środki czekają jako claim do odbioru).
     *  3. Deposit ORAZ money-claim nieudane -> {@link PlaceResult#COMPENSATION_FAILED} (stan KRYTYCZNY),
     *     audyt FAILED, rate-limitowany polski SEVERE - wymagana ręczna korekta.
     * Future i busy-guard kończą się DOPIERO po ustaleniu tego terminalnego statusu (bez fire-and-forget,
     * bez podwójnej wypłaty). Domknięcia idą WPROST (bez onMain) - odrzucenie schedulera nie zawiesza future.
     * Uwaga: PlaceOutcome zawsze != OK w tej ścieżce.
     */
    private void compensateBuyPlacementFailure(UUID uuid, String name, String itemKey,
                                                BigDecimal total, Throwable insertErr,
                                                CompletableFuture<PlaceOutcome> result) {
        logger.log(Level.WARNING, "Zapis zlecenia kupna nie powiódł się dla " + uuid
                + " przedmiot=" + itemKey, insertErr);
        economy.deposit(uuid, name, total, "bazaar-buy-order-refund-" + itemKey)
                .whenComplete((depo, depoErr) -> {
                    // Deposit „ok" tylko bez wyjątku, nie-null i success=true (fail/null/wyjątek = nie ok).
                    boolean depositOk = depositRefundOk(depoErr != null, depo == null,
                            depo != null && depo.success());
                    if (depositOk) {
                        // claimOk=false: money-claim nie był próbowany (deposit wystarczył).
                        finishBuyRefund(uuid, name, itemKey, total, null, true, false, null, result);
                        return;
                    }
                    CompletableFuture<Long> claimFuture;
                    try {
                        claimFuture = hexCore.async(() -> claims.insertMoney(uuid, total,
                                "bazaar-buy-order-refund-claim-" + itemKey,
                                null, System.currentTimeMillis()));
                    } catch (Throwable t) {
                        // Synchroniczne odrzucenie zgłoszenia async: ani deposit, ani claim -> KRYTYCZNE.
                        finishBuyRefund(uuid, name, itemKey, total, null, false, false, t, result);
                        return;
                    }
                    claimFuture.whenComplete((claimId, claimErr) -> {
                        // Claim „ok" tylko dla potwierdzonego, nieujemnego id (wyjątek/null/ujemne = nie ok).
                        boolean claimOk = claimRefundOk(claimErr != null, claimId == null,
                                claimId == null ? -1L : claimId);
                        finishBuyRefund(uuid, name, itemKey, total, claimOk ? claimId : null,
                                false, claimOk, claimErr, result);
                    });
                });
    }

    /** Czysta decyzja terminalnego stanu kompensacji BUY (punkt #3): wynik dla gracza + status audytu. */
    public record BuyRefundOutcome(PlaceResult result, String auditResult) {}

    /**
     * Czysta klasyfikacja wyniku zwrotu bezpośredniego (deposit) po nieudanym zapisie BUY (punkt #3).
     * „Ok" tylko gdy nie było wyjątku ({@code !hadError}), wynik nie jest null ({@code !nullResult})
     * i {@code success}. Pokrywa: success/false/null/exception.
     */
    public static boolean depositRefundOk(boolean hadError, boolean nullResult, boolean success) {
        return !hadError && !nullResult && success;
    }

    /**
     * Czysta klasyfikacja wyniku zapisu money-claimu zwrotu BUY (punkt #3). „Ok" tylko gdy nie było
     * wyjątku, id nie jest null i jest nieujemne. Pokrywa: success/false(ujemne)/null/exception.
     */
    public static boolean claimRefundOk(boolean hadError, boolean nullId, long id) {
        return !hadError && !nullId && id >= 0;
    }

    /**
     * Czysta, testowalna klasyfikacja kompensacji BUY (punkt #3):
     *  - deposit OK -> {@link PlaceResult#DB_FAILED} + audyt ROLLBACK (środki zwrócone wprost);
     *  - deposit nieudany, money-claim OK -> {@link PlaceResult#DB_FAILED} + audyt REFUND_PENDING;
     *  - deposit ORAZ money-claim nieudane -> {@link PlaceResult#COMPENSATION_FAILED} + audyt FAILED
     *    (stan krytyczny). „Nieudany" obejmuje wyjątek, null i success=false / ujemne id.
     */
    public static BuyRefundOutcome classifyBuyRefund(boolean depositOk, boolean claimOk) {
        if (depositOk) {
            return new BuyRefundOutcome(PlaceResult.DB_FAILED, AuditAction.RESULT_ROLLBACK);
        }
        if (claimOk) {
            return new BuyRefundOutcome(PlaceResult.DB_FAILED, AuditAction.RESULT_REFUND_PENDING);
        }
        return new BuyRefundOutcome(PlaceResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED);
    }

    /**
     * Terminalne domknięcie kompensacji BUY: wynik+audyt z {@link #classifyBuyRefund}, przy stanie
     * krytycznym rate-limitowany polski SEVERE. Future/busy-guard kończą się dopiero tutaj.
     */
    private void finishBuyRefund(UUID uuid, String name, String itemKey, BigDecimal total, Long claimId,
                                 boolean depositOk, boolean claimOk, Throwable cause,
                                 CompletableFuture<PlaceOutcome> result) {
        BuyRefundOutcome outcome = classifyBuyRefund(depositOk, claimOk);
        String reason = depositOk
                ? "zapis zlecenia kupna nieudany - zwrot bezpośredni"
                : claimOk
                        ? "zapis zlecenia kupna nieudany - zwrot jako money-claim"
                        : "zapis zlecenia kupna i automatyczny zwrot nie powiodły się";
        if (outcome.result() == PlaceResult.COMPENSATION_FAILED) {
            logSevereBuyRefundFailed(uuid, itemKey, total, cause);
        }
        audit.log(audit.builder()
                .actor(uuid, name)
                .action(AuditAction.BAZAAR_REFUND)
                .market(AuditAction.MARKET_BAZAAR)
                .itemKey(itemKey)
                .total(total)
                .claimId(claimId)
                .result(outcome.auditResult())
                .reason(reason));
        result.complete(PlaceOutcome.fail(outcome.result()));
    }

    /** Rate-limitowany (60s) SEVERE nieodwracalnego zwrotu środków BUY: tx-id + UUID + kwota; bez sekretów. */
    private void logSevereBuyRefundFailed(UUID uuid, String itemKey, BigDecimal total, Throwable cause) {
        long now = System.currentTimeMillis();
        long last = lastBuyRefundSevereLogAt.get();
        if (now - last >= 60_000L && lastBuyRefundSevereLogAt.compareAndSet(last, now)) {
            logger.log(Level.SEVERE,
                    "RYNEK ZLECENIE KUPNA: nieodwracalny zwrot środków - tx=" + System.nanoTime()
                            + " gracz=" + uuid + " przedmiot=" + itemKey
                            + " kwota=" + (total == null ? "0" : total.toPlainString())
                            + " - ani zwrot bezpośredni, ani money-claim nie powiodły się;"
                            + " wymagana ręczna korekta (bez danych dostępowych do bazy)",
                    cause);
        }
    }

    // ---------------------------------------------------------------- SELL OFFER

    /**
     * Wystaw oferte sprzedazy. Zdejmuje fizyczne przedmioty z ekwipunku.
     * Zwrot przy anulowaniu odbywa sie przez system claim.
     */
    public CompletableFuture<PlaceOutcome> placeSellOffer(Player seller, String itemKey,
                                                          long amount, BigDecimal price) {
        BazaarConfig cfg = configSupplier.get();
        // SERWEROWA bramka: commerceEnabled() = global enabled (tryb konserwacji) ORAZ bazaar.enabled.
        if (!commerceEnabled()) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.FEATURE_DISABLED));
        }
        if (!seller.hasPermission(cfg.permOrderSell())) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.NO_PERMISSION));
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
        // Jednolita skala i ochrona przed przepełnieniem DECIMAL(19,2) (spójny audyt/cena).
        BigDecimal unitPrice = Money.normalize(price);
        if (Money.totalOrNull(unitPrice, amount) == null) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.INVALID_PRICE));
        }
        UUID uuid = seller.getUniqueId();
        String name = seller.getName();

        // Busy-guard PRZED usunięciem przedmiotów: drugie równoległe wejście (podwójny klik) dostaje
        // BUSY i NIE usuwa tych samych przedmiotów drugi raz (punkt #1). Zwalniany DOPIERO po terminalnym
        // wyniku (przez result.whenComplete) - w KAŻDEJ ścieżce.
        if (!placing.add(uuid)) {
            return CompletableFuture.completedFuture(PlaceOutcome.fail(PlaceResult.BUSY));
        }
        CompletableFuture<PlaceOutcome> result = new CompletableFuture<>();
        result.whenComplete((r, e) -> placing.remove(uuid));

        int intAmount = (int) amount;
        Material mat = item.material();
        // SELL-order jest zawsze fungible: WYMUSZAMY plain-only, niezależnie od require-plain-item. Dzięki
        // temu przy cancel/expiry zwrot rekonstruowany jako new ItemStack(material, amount) NIE niszczy
        // żadnych metadanych - bo przedmioty z NBT/nazwą/enchantami w ogóle nie trafiają do SELL-order
        // (brak cichej utraty metadanych - punkt #1 „ważne").
        //
        // Zdejmowanie przez śledzoną seam ze snapshotem i typizowanym wynikiem: częściowa awaria
        // (setSlot rzuca po kilku slotach) NIE zostawia częściowo opróżnionego ekwipunku - albo wszystko
        // zdjęte, albo dokładnie przywrócone, albo (gdy przywrócenie padło) stan NIEPEWNY bez auto-zwrotu.
        InventoryExtract.Result extraction = InventoryExtract.extract(seller, intAmount,
                it -> it.getType() == mat && PlainItemMatcher.isPlain(it, mat));
        if (extraction.status() != InventoryExtract.Status.REMOVED) {
            // Żadne przedmioty nie są „w locie": domykamy terminalnie (busy-guard zwalnia result.whenComplete).
            // Niepewny stan ekwipunku -> rate-limitowany SEVERE (bez automatycznego zwrotu - część mogła
            // zostać zdjęta, ryzyko duplikacji); bezpieczne stany dają zwykłe błędy (nic nie zdjęto).
            if (extraction.status() == InventoryExtract.Status.STATE_UNCERTAIN) {
                logSevereReturn(uuid, intAmount, "niepewny stan ekwipunku przy zdejmowaniu oferty");
            }
            result.complete(PlaceOutcome.fail(classifyExtractFailure(extraction.status())));
            return result;
        }
        List<ItemStack> removed = extraction.removed();

        // Od tego miejsca przedmioty są ZDJĘTE. Każdy dalszy błąd (odczyt limitu/configu, synchroniczne
        // odrzucenie async, planowanie main-thread) MUSI zakończyć się terminalnie i - jeśli zdjęcie było
        // bezpieczne - zwrócić przedmioty. Backstop poniżej łapie synchroniczne wyjątki z tej orkiestracji.
        try {
            beginAsyncSellPlacement(seller, uuid, name, itemKey, amount, unitPrice, removed, result);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "RYNEK: nieoczekiwany błąd po zdjęciu oferty dla " + uuid
                    + " - zwracam wystawione przedmioty", t);
            returnStacksThenFail(seller, uuid, removed, PlaceResult.DB_FAILED, result);
        }
        return result;
    }

    /**
     * Czysta, testowalna klasyfikacja NIE-terminalnego zdjęcia przy składaniu SELL-oferty (punkt #1):
     *  - {@code NOT_ENOUGH} -> {@link PlaceResult#NOT_ENOUGH_ITEMS} (za mało; nic nie zdjęto);
     *  - {@code REVERTED} -> {@link PlaceResult#DB_FAILED} (bezpiecznie: nic nie zdjęto/dokładnie
     *    przywrócono - zwykły błąd, retry);
     *  - {@code STATE_UNCERTAIN} -> {@link PlaceResult#COMPENSATION_FAILED} (stan krytyczny; wołający
     *    dodatkowo loguje rate-limitowany SEVERE i NIE tworzy claim - część mogła zostać zdjęta).
     */
    public static PlaceResult classifyExtractFailure(InventoryExtract.Status status) {
        return switch (status) {
            case NOT_ENOUGH -> PlaceResult.NOT_ENOUGH_ITEMS;
            case REVERTED -> PlaceResult.DB_FAILED;
            case STATE_UNCERTAIN -> PlaceResult.COMPENSATION_FAILED;
            case REMOVED -> throw new IllegalStateException("REMOVED nie jest błędem zdjęcia");
        };
    }

    /**
     * Orkiestracja po BEZPIECZNYM zdjęciu przedmiotów: count -> insert. Każde synchroniczne odrzucenie
     * {@code hexCore.async(...)} (count/insert) oraz błąd DB kończy się zwrotem przedmiotów i terminalnym
     * wynikiem; sukces domyka wynik PRZED audytem (audyt nie może zawiesić future ani busy-guarda).
     */
    private void beginAsyncSellPlacement(Player seller, UUID uuid, String name, String itemKey,
                                         long amount, BigDecimal unitPrice, List<ItemStack> removed,
                                         CompletableFuture<PlaceOutcome> result) {
        int maxOpen = Math.max(1, maxOpenPerPlayer.get());
        CompletableFuture<Integer> countFuture;
        try {
            countFuture = hexCore.async(() -> orders.countOpenByOwner(uuid));
        } catch (Throwable t) {
            // Synchroniczne odrzucenie zgłoszenia async PO zdjęciu -> zwróć przedmioty (bezpiecznie).
            returnStacksThenFail(seller, uuid, removed, PlaceResult.DB_FAILED, result);
            return;
        }
        countFuture.whenComplete((count, err) -> {
            if (err != null) {
                returnStacksThenFail(seller, uuid, removed, PlaceResult.DB_FAILED, result);
                return;
            }
            if (count >= maxOpen) {
                returnStacksThenFail(seller, uuid, removed, PlaceResult.TOO_MANY_OPEN, result);
                return;
            }
            long now = System.currentTimeMillis();
            Long expiresAt = computeExpiresAt(configSupplier.get(), now);
            CompletableFuture<Long> insertFuture;
            try {
                insertFuture = hexCore.async(() -> orders.insert(uuid, name, itemKey, OrderSide.SELL,
                        amount, unitPrice, null, now, expiresAt));
            } catch (Throwable t) {
                // Synchroniczne odrzucenie zgłoszenia async przy insert -> zwróć przedmioty (bezpiecznie).
                returnStacksThenFail(seller, uuid, removed, PlaceResult.DB_FAILED, result);
                return;
            }
            insertFuture.whenComplete((id, insErr) -> {
                if (insErr != null || id == null) {
                    returnStacksThenFail(seller, uuid, removed, PlaceResult.DB_FAILED, result);
                    return;
                }
                BigDecimal total = Money.totalOrNull(unitPrice, amount);
                // Domknij wynik PRZED audytem - complete jest atomowym strażnikiem i nie zależy od schedulera.
                if (result.complete(PlaceOutcome.ok(id, total))) {
                    try {
                        audit.log(audit.builder()
                                .actor(uuid, name)
                                .action(AuditAction.BAZAAR_SELL_OFFER_PLACED)
                                .market(AuditAction.MARKET_BAZAAR)
                                .itemKey(itemKey)
                                .orderId(id)
                                .amount(amount)
                                .unitPrice(unitPrice)
                                .total(total)
                                .result(AuditAction.RESULT_OK));
                    } catch (Throwable t) {
                        logger.log(Level.WARNING,
                                "RYNEK: zapis audytu wystawienia oferty nie powiódł się", t);
                    }
                }
            });
        });
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
        // SERWEROWA autoryzacja. Anulowanie = akcja ODZYSKU (zwrot środków/przedmiotów) - świadomie NIE
        // przez bramkę trybu konserwacji, ale WYMAGA uprawnienia order-cancel (punkt #5).
        if (!player.hasPermission(configSupplier.get().permOrderCancel())) {
            return CompletableFuture.completedFuture(CancelResult.NO_PERMISSION);
        }
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
                                            "Transakcja anulowania i zwrotu zlecenia " + orderId
                                                    + " nie powiodła się", cErr);
                                    audit.log(audit.builder()
                                            .actor(uuid, name)
                                            .action(AuditAction.BAZAAR_ORDER_CANCELLED)
                                            .market(AuditAction.MARKET_BAZAAR)
                                            .orderId(orderId)
                                            .result(AuditAction.RESULT_FAILED)
                                            .reason("transakcja anulowania i zwrotu nie powiodła się"));
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
                                        .reason("anulowanie ze zwrotem"));
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
                throw new RuntimeException("serializacja zwrotu podczas anulowania nie powiodła się", t);
            }
            AuctionClaimRepository.insertItemTx(tx, cancelled.ownerUuid(), blob,
                    "bazaar-order-refund-" + cancelled.id(), null, now);
            remaining -= stackAmt;
        }
    }

    // ---------------------------------------------------------------- REMOVE CANCELLED HISTORY

    /**
     * Czysta decyzja (bez DB): czy dany wpis można usunąć z historii.
     * Usuwać można WYŁĄCZNIE własne, ANULOWANE zlecenia - ACTIVE/PARTIALLY_FILLED/
     * FILLED/EXPIRED nigdy tą ścieżką. Wynik OK oznacza "kwalifikuje się", a
     * właściwe usunięcie wykonuje {@link #removeCancelled}.
     */
    public static RemoveResult decideRemove(BazaarOrder order, UUID requester) {
        if (order == null) return RemoveResult.NOT_FOUND;
        if (requester == null || !order.ownerUuid().equals(requester)) return RemoveResult.NOT_OWNER;
        if (order.state() != OrderState.CANCELLED) return RemoveResult.NOT_CANCELLED;
        return RemoveResult.OK;
    }

    /**
     * Usuń/zarchiwizuj wyłącznie własny, anulowany wpis historii. Sprawdzenie
     * właściciela i stanu jest po stronie serwera; usunięcie parametrycznie i
     * asynchronicznie przez HexCore. Nie rusza istniejących claim-ów/zwrotów -
     * usuwa tylko wiersz zlecenia (bazaar-order claims mają listing_id=NULL).
     */
    public CompletableFuture<RemoveResult> removeCancelled(Player player, long orderId) {
        // SERWEROWA autoryzacja (co najmniej ta sama permisja zarządcza co anulowanie zlecenia).
        if (!player.hasPermission(configSupplier.get().permOrderCancel())) {
            return CompletableFuture.completedFuture(RemoveResult.NO_PERMISSION);
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        CompletableFuture<RemoveResult> result = new CompletableFuture<>();
        hexCore.async(() -> orders.findById(orderId))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        logger.log(Level.WARNING, "Odczyt anulowanego zlecenia nie powiódł się", err);
                        onMain(() -> result.complete(RemoveResult.DB_FAILED));
                        return;
                    }
                    BazaarOrder o = opt.orElse(null);
                    RemoveResult decision = decideRemove(o, uuid);
                    if (decision != RemoveResult.OK) {
                        onMain(() -> result.complete(decision));
                        return;
                    }
                    hexCore.async(() -> orders.deleteCancelledByOwner(orderId, uuid))
                            .whenComplete((ok, delErr) -> {
                                if (delErr != null) {
                                    logger.log(Level.WARNING, "Usunięcie anulowanego zlecenia nie powiodło się",
                                            delErr);
                                    onMain(() -> result.complete(RemoveResult.DB_FAILED));
                                    return;
                                }
                                if (ok == null || !ok) {
                                    // Race: zniknęło lub zmieniło stan między odczytem a usunięciem.
                                    onMain(() -> result.complete(RemoveResult.NOT_FOUND));
                                    return;
                                }
                                audit.log(audit.builder()
                                        .actor(uuid, name)
                                        .action(AuditAction.BAZAAR_ORDER_REMOVED)
                                        .market(AuditAction.MARKET_BAZAAR)
                                        .itemKey(o.itemKey())
                                        .orderId(orderId)
                                        .result(AuditAction.RESULT_OK)
                                        .reason("usunięto wpis historii"));
                                onMain(() -> result.complete(RemoveResult.OK));
                            });
                });
        return result;
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
        // BUY-order akceptowany, gdy jego cena >= floor sprzedającego.
        return scanPreview(wantAmount, PREVIEW_PAGE,
                offset -> orders.pageOpenBuyOrders(itemKey, PREVIEW_PAGE, offset),
                o -> o.pricePerUnit().compareTo(sellFloorPrice) >= 0);
    }

    /** Kontrakt pobierania kolejnej strony orderbooka (offset -&gt; lista w kolejności dopasowania). */
    public interface OrderPageFetcher {
        List<BazaarOrder> page(int offset);
    }

    /**
     * Czysty, testowalny skan podglądu: sumuje możliwe dopasowanie stronami aż do pokrycia
     * {@code wantAmount}, przekroczenia limitu ceny ({@code priceOk} zwraca false) albo wyczerpania
     * orderbooka. BEZ sztywnego cap-u i BEZ niekontrolowanego pobrania całości.
     */
    public static MatchPreview scanPreview(long wantAmount, int pageSize,
                                           OrderPageFetcher fetcher,
                                           java.util.function.Predicate<BazaarOrder> priceOk) {
        long remaining = wantAmount;
        BigDecimal total = BigDecimal.ZERO;
        int offset = 0;
        while (remaining > 0) {
            List<BazaarOrder> batch = fetcher.page(offset);
            if (batch == null || batch.isEmpty()) break;
            boolean priceStop = false;
            for (BazaarOrder o : batch) {
                if (remaining <= 0) break;
                if (!priceOk.test(o)) { priceStop = true; break; }   // limit ceny -> stop
                long take = Math.min(remaining, o.amountRemaining());
                if (take <= 0) continue;
                total = total.add(o.pricePerUnit().multiply(new BigDecimal(take)));
                remaining -= take;
            }
            if (priceStop || batch.size() < pageSize) break;         // limit ceny lub ostatnia strona
            offset += pageSize;
        }
        return new MatchPreview(wantAmount - remaining, total);
    }

    /**
     * Symulacja dopasowania do ofert sprzedazy (dla instant BUY).
     * Cena kazdej oferty musi byc <= buyCeilPrice.
     */
    public MatchPreview previewMatchSellOffers(String itemKey, long wantAmount,
                                                BigDecimal buyCeilPrice) {
        // Oferta SPRZEDAŻY akceptowana, gdy jej cena <= ceil kupującego.
        return scanPreview(wantAmount, PREVIEW_PAGE,
                offset -> orders.pageOpenSellOffers(itemKey, PREVIEW_PAGE, offset),
                o -> o.pricePerUnit().compareTo(buyCeilPrice) <= 0);
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
            logger.log(Level.WARNING, "Dopasowanie zleceń kupna: brak konfiguracji przedmiotu " + itemKey);
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
                        throw new MatchTxAbort("pobranie zarezerwowanych środków nie powiodło się dla zlecenia "
                                + orderId);
                    }
                    BazaarItemConfig item = configSupplier.get().item(itemKey).orElse(null);
                    if (item == null) {
                        // Konfiguracja zniknela w trakcie meczu - nie mozemy wydac przedmiotow.
                        throw new MatchTxAbort("brak konfiguracji przedmiotu Rynku: " + itemKey);
                    }
                    long left = finalTake;
                    while (left > 0) {
                        int stackAmt = (int) Math.min(left, item.material().getMaxStackSize());
                        ItemStack stack = new ItemStack(item.material(), stackAmt);
                        byte[] blob;
                        try {
                            blob = ItemSerializer.serialize(stack);
                        } catch (Throwable t) {
                            throw new MatchTxAbort("serializacja dostawy nie powiodła się: " + t.getMessage());
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
                        "Wycofano transakcję dopasowania zlecenia kupna " + orderId
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
                    .reason("realizacja sprzedaży natychmiastowej"));

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
            logger.log(Level.WARNING, "Dopasowanie ofert sprzedaży: brak konfiguracji przedmiotu " + itemKey);
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
                        throw new MatchTxAbort("zapis money-claimu nie powiódł się: " + ex.getMessage());
                    }
                    return true;
                });
            } catch (RuntimeException abort) {
                // Wyjatek per-order (rollback tej tx), ale nie tracimy juz zacommitowanych fills.
                logger.log(Level.WARNING,
                        "Wycofano transakcję dopasowania oferty sprzedaży " + orderId
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
                    .reason("realizacja kupna natychmiastowego"));

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
                                .reason("zlecenie wygasło"));
                        processed++;
                    }
                } catch (Throwable t) {
                    logger.log(Level.WARNING,
                            "Transakcja wygaśnięcia zlecenia " + o.id() + " nie powiodła się", t);
                    audit.log(audit.builder()
                            .action(AuditAction.BAZAAR_ORDER_CANCELLED)
                            .market(AuditAction.MARKET_BAZAAR)
                            .orderId(o.id())
                            .result(AuditAction.RESULT_FAILED)
                            .reason("transakcja wygaśnięcia zlecenia nie powiodła się"));
                }
            }
            return processed;
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Skanowanie wygasłych zleceń nie powiodło się", ex);
            return 0;
        });
    }

    // ---------------------------------------------------------------- helpers

    /**
     * ŚLEDZONY zwrot wystawionych stacków po błędzie, z genau-einmal-Semantik (PENDING-&gt;RUNNING-&gt;
     * TERMINAL). Recovery jest REJESTROWANE (z klonami oryginałów, pełne NBT/PDC/nazwa/lore/enchanty/CMD)
     * PRZED zaplanowaniem na wątek główny, więc drain przy disable może je bezpiecznie przejąć i żaden
     * zdjęty stack nie zniknie. Wynik jest ZAWSZE terminalny (busy-guard zwalnia się przez
     * {@code result.whenComplete} dopiero po nim):
     *  - ADDED_FULLY -> wszystko wróciło do ekwipunku;
     *  - CLAIMED -> nie zmieściło się/offline -> WSZYSTKIE stacki atomowo jako claim (jedna transakcja);
     *  - STATE_UNCERTAIN -> próba ekwipunku REALNIE się zaczęła i nie da się jej rozstrzygnąć/cofnąć:
     *    BEZ claim, BEZ drugiej próby (ryzyko duplikacji) -> COMPENSATION_FAILED + rate-limitowany SEVERE;
     *  - CLAIM_FAILED -> atomowy zapis claim-ów nie powiódł się -> COMPENSATION_FAILED + rate-limitowany SEVERE.
     * KLUCZOWE: odrzucenie planowania ZANIM runnable ruszył NIE jest „niepewne" - ekwipunek jest
     * gwarantowanie nietknięty, więc próbujemy atomowy claim (CLAIMED/CLAIM_FAILED), a NIE STATE_UNCERTAIN
     * (co przy disable groziłoby trwałą utratą przedmiotów). Żadnego world-dropu; ekwipunek zmieniany
     * wyłącznie na wątku głównym.
     */
    private CompletableFuture<ReturnOutcome> returnStacksTracked(Player seller, UUID uuid,
                                                                 List<ItemStack> stacks) {
        CompletableFuture<ReturnOutcome> out = new CompletableFuture<>();
        if (stacks == null || stacks.isEmpty()) {
            out.complete(ReturnOutcome.ADDED_FULLY);
            return out;
        }
        // Rejestracja z KLONAMI oryginałów PRZED planowaniem (drain przy disable przejmie nierozpoczęte).
        ReturnRecovery rec = new ReturnRecovery(recoverySequence.incrementAndGet(), uuid, seller,
                cloneAll(stacks), out);
        pendingReturns.add(rec);
        try {
            recoverySeam.dispatchMain(() -> tryExecuteReturn(rec));
        } catch (Throwable t) {
            // Planowanie ODRZUCONE zanim runnable ruszył -> ekwipunek GWARANTOWANIE nietknięty.
            rejectedBeforeStart(rec, t);
        }
        return out;
    }

    /** CAS PENDING-&gt;RUNNING; tylko zwycięzca wykonuje zwrot na wątku głównym (genau-einmal). */
    private void tryExecuteReturn(ReturnRecovery rec) {
        if (rec.state.compareAndSet(RecoveryState.PENDING, RecoveryState.RUNNING)) {
            doReturnOnMain(rec);
        }
    }

    /**
     * MUSI działać na wątku głównym. Próba all-or-nothing zwrotu do ekwipunku, a przy przepełnieniu/offline
     * atomowy claim. STATE_UNCERTAIN tylko wtedy, gdy próba ekwipunku REALNIE się zaczęła i nie da się jej
     * bezpiecznie rozstrzygnąć - wówczas BEZ claim (ryzyko duplikacji). Domyka recovery terminalnie.
     */
    private void doReturnOnMain(ReturnRecovery rec) {
        try {
            boolean online = rec.seller != null && rec.seller.isOnline();
            InventoryFit.Result add = online
                    ? recoverySeam.tryReturnToInventory(rec.seller, cloneAll(rec.stacks))
                    : InventoryFit.Result.NOT_FIT_REVERTED;   // offline: pomiń ekwipunek, od razu claim
            switch (add) {
                case ADDED_FULLY -> completeRecovery(rec, ReturnOutcome.ADDED_FULLY);
                case NOT_FIT_REVERTED -> claimAllInOneTx(rec.uuid, rec.stacks).whenComplete((ok, e) ->
                        completeRecovery(rec, (e == null && Boolean.TRUE.equals(ok))
                                ? ReturnOutcome.CLAIMED : ReturnOutcome.CLAIM_FAILED));
                default -> {   // STATE_UNCERTAIN: próba ekwipunku realnie się zaczęła i nie da się rozstrzygnąć
                    logSevereReturn(rec.uuid, rec.stacks.size(), "niepewny stan ekwipunku");
                    completeRecovery(rec, ReturnOutcome.STATE_UNCERTAIN);
                }
            }
        } catch (Throwable t) {
            logger.log(Level.SEVERE,
                    "RYNEK: zwrot oferty - nieoczekiwany błąd na wątku głównym dla " + rec.uuid, t);
            completeRecovery(rec, ReturnOutcome.STATE_UNCERTAIN);
        }
    }

    /**
     * Odrzucone planowanie ZANIM runnable ruszył -> ekwipunek nietknięty -> atomowy claim WSZYSTKICH
     * oryginałów (bez dostępu do ekwipunku; możliwe poza wątkiem głównym). CLAIMED (fachowy błąd może
     * wrócić terminalnie) albo CLAIM_FAILED (COMPENSATION_FAILED + rate-limitowany SEVERE z tx/UUID/liczbą
     * stacków, bez NBT/SQL/sekretów). Genau-einmal przez CAS (drain mógł już przejąć).
     */
    private void rejectedBeforeStart(ReturnRecovery rec, Throwable cause) {
        if (!rec.state.compareAndSet(RecoveryState.PENDING, RecoveryState.RUNNING)) {
            return;   // drain już przejął tę operację
        }
        logger.log(Level.WARNING, "RYNEK: nie można zaplanować zwrotu oferty (plugin wyłączany?) dla "
                + rec.uuid + " - próbuję atomowy claim (" + cause.getClass().getSimpleName() + ")");
        claimAllInOneTx(rec.uuid, rec.stacks).whenComplete((ok, e) ->
                completeRecovery(rec, (e == null && Boolean.TRUE.equals(ok))
                        ? ReturnOutcome.CLAIMED : ReturnOutcome.CLAIM_FAILED));
    }

    /** Terminalne domknięcie recovery: TERMINAL + wyrejestrowanie + domknięcie future (idempotentne). */
    private void completeRecovery(ReturnRecovery rec, ReturnOutcome outcome) {
        rec.state.set(RecoveryState.TERMINAL);
        pendingReturns.remove(rec);
        rec.out.complete(outcome);
    }

    /**
     * Lifecycle przy DISABLE (odzysk): przejmuje NIEROZPOCZĘTE (PENDING) zwroty wystawionych przedmiotów,
     * by żaden zdjęty stack nie zniknął. MUSI być wołane NA WĄTKU GŁÓWNYM, ZANIM wyłączymy plugineigene
     * Infrastruktur i gdy HexCore jest JESZCZE aktywne.
     *  - PENDING (nierozpoczęte) -> bezpieczna próba zwrotu do ekwipunku, a przy przepełnieniu/offline
     *    atomowy claim (wszystko na wątku głównym);
     *  - RUNNING -> już obsługiwane (claim w locie) - NIE dublujemy, tylko czekamy na domknięcie;
     *  - TERMINAL -> nic.
     * Następnie OGRANICZONYM oczekiwaniem czeka na domknięcie trwającej persystencji claim-ów, dopóki
     * HexCore jest aktywne. HexCore NIE jest zamykane. Genau-einmal przez CAS w {@link #tryExecuteReturn}.
     *
     * @return liczba operacji nadal niedomkniętych po upływie limitu; każda otrzymuje osobny wpis SEVERE
     */
    public int drainPendingReturnsOnDisable(long timeoutMs) {
        List<ReturnRecovery> snapshot = new ArrayList<>(pendingReturns);
        if (snapshot.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<ReturnOutcome>> awaited = new ArrayList<>(snapshot.size());
        for (ReturnRecovery rec : snapshot) {
            tryExecuteReturn(rec);   // przejmij TYLKO PENDING; RUNNING/TERMINAL -> CAS nie przejdzie
            awaited.add(rec.out);
        }
        try {
            CompletableFuture.allOf(awaited.toArray(new CompletableFuture[0]))
                    .get(Math.max(0L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.TimeoutException timeout) {
            // Szczegółowa diagnostyka każdej nadal otwartej operacji znajduje się poniżej.
        } catch (java.util.concurrent.ExecutionException failed) {
            logger.log(Level.WARNING, "RYNEK: oczekiwanie na zwroty ofert zakończyło się błędem", failed);
        }

        // Nie kończymy sztucznie ani nie ponawiamy RUNNING: claim może jeszcze zostać zatwierdzony i drugi
        // zwrot stworzyłby duplikat. Zamiast ogólnego ostrzeżenia zapisujemy dane pozwalające administratorowi
        // jednoznacznie odnaleźć KAŻDĄ operację, która nie domknęła się przed końcem limitu.
        int unresolved = 0;
        for (ReturnRecovery rec : new ArrayList<>(pendingReturns)) {
            if (rec.out.isDone()) {
                continue;
            }
            unresolved++;
            logger.severe("RYNEK ZWROT OFERTY: operacja nie zakończyła się w limicie wyłączania"
                    + " - odzysk=" + rec.recoveryId
                    + " gracz=" + rec.uuid
                    + " stan=" + rec.state.get()
                    + " stacki=" + rec.stacks.size()
                    + " - nie uruchamiam drugiego zwrotu; po restarcie wymagana ręczna weryfikacja odbioru"
                    + " (bez danych NBT i danych dostępowych)");
        }
        return unresolved;
    }

    /** Głęboka kopia listy stacków (do próby dodania do ekwipunku), żeby oryginały pozostały do claim-ów. */
    private static List<ItemStack> cloneAll(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>(stacks.size());
        for (ItemStack s : stacks) {
            out.add(s == null ? null : s.clone());
        }
        return out;
    }

    /**
     * ATOMOWY zapis WSZYSTKICH oryginalnych stacków jako item-claim w JEDNEJ transakcji DB (punkt #1).
     * Albo wszystkie zostają utrwalone, albo żaden (rollback) - nigdy część. Serializacja PRZED
     * transakcją: błąd serializacji = brak claim-a (false), bez częściowego zapisu. Zwraca true tylko
     * po ZACOMMITOWANEJ transakcji.
     */
    CompletableFuture<Boolean> claimAllInOneTx(UUID owner, List<ItemStack> stacks) {
        final int count = stacks == null ? 0 : stacks.size();
        final List<byte[]> blobs = new ArrayList<>(count);
        if (stacks != null) {
            for (ItemStack s : stacks) {
                try {
                    blobs.add(ItemSerializer.serialize(s));
                } catch (Throwable t) {
                    // Błąd serializacji -> brak claim-a (false), bez częściowego zapisu.
                    logSevereReturn(owner, count, "serializacja zwrotu nie powiodła się");
                    return CompletableFuture.completedFuture(false);
                }
            }
        }
        final long now = System.currentTimeMillis();
        CompletableFuture<Boolean> future;
        try {
            future = hexCore.async(() -> hexCore.rawDb().tx(tx -> {
                for (byte[] blob : blobs) {
                    AuctionClaimRepository.insertItemTx(tx, owner, blob, "bazaar-order-refund", null, now);
                }
                return Boolean.TRUE;
            }));
        } catch (Throwable t) {
            // Synchroniczne odrzucenie zgłoszenia async -> terminalny false (NIGDY wyjątek na zewnątrz).
            logSevereReturn(owner, count, "atomowy zapis claim-ów nie powiódł się (odrzucenie async)");
            return CompletableFuture.completedFuture(false);
        }
        if (future == null) {
            // Null future -> terminalny false (bez zawieszenia).
            logSevereReturn(owner, count, "atomowy zapis claim-ów nie powiódł się (null future)");
            return CompletableFuture.completedFuture(false);
        }
        return future.handle((ok, ex) -> {
            // Wyjątkowe domknięcie / błąd tx / brak commitu -> false.
            boolean good = ex == null && Boolean.TRUE.equals(ok);
            if (!good) {
                logSevereReturn(owner, count, "atomowy zapis claim-ów nie powiódł się");
            }
            return good;
        });
    }

    /**
     * Zwróć wystawione przedmioty (batchowo, śledzone) i DOPIERO potem domknij PlaceOutcome. Bezpieczny
     * zwrot (ADDED_FULLY/CLAIMED) -> zwykły błąd {@code normalFail}; niepewny/nieudany zwrot
     * (STATE_UNCERTAIN/CLAIM_FAILED) -> {@code COMPENSATION_FAILED} (krytyczne). Busy-guard zwalnia się
     * (przez result.whenComplete) DOPIERO po tym terminalnym domknięciu.
     */
    private void returnStacksThenFail(Player seller, UUID uuid, List<ItemStack> removed,
                                      PlaceResult normalFail, CompletableFuture<PlaceOutcome> result) {
        // returnStacksTracked domyka swój future TERMINALNIE w każdej ścieżce (także gdy nie da się
        // zaplanować zadania). Domykamy PlaceOutcome wprost w jego callbacku (już na wątku głównym) -
        // BEZ dodatkowego onMain, którego odrzucenie zawiesiłoby future i busy-guard.
        returnStacksTracked(seller, uuid, removed).whenComplete((ret, e) -> {
            ReturnOutcome outcome = (e != null || ret == null) ? ReturnOutcome.STATE_UNCERTAIN : ret;
            result.complete(PlaceOutcome.fail(outcome.safe()
                    ? normalFail : PlaceResult.COMPENSATION_FAILED));
        });
    }

    /** Rate-limitowany (60s) SEVERE zwrotu oferty: tx-id + UUID + liczba stacków; BEZ NBT i BEZ sekretów. */
    private void logSevereReturn(UUID uuid, int stackCount, String context) {
        long txId = System.nanoTime();
        long now = System.currentTimeMillis();
        long last = lastReturnSevereLogAt.get();
        if (now - last >= 60_000L && lastReturnSevereLogAt.compareAndSet(last, now)) {
            logger.severe("RYNEK ZWROT OFERTY: " + context + " - tx=" + txId
                    + " gracz=" + uuid + " stacki=" + stackCount
                    + " - bez automatycznego kolejnego zwrotu; wymagana ręczna korekta (bez danych NBT)");
        }
    }
}
