package hex.auctionbazaar.auction.service;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.model.AuctionClaim;
import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.model.ListingState;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.auction.repository.AuctionListingRepository;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.util.CustomItemTradePolicy;
import hex.auctionbazaar.util.InventoryFit;
import hex.auctionbazaar.util.ItemSerializer;
import hex.auctionbazaar.util.ListingLimitResolver;
import hex.auctionbazaar.util.SafeTime;
import hex.auctionbazaar.util.RefundCompensation;
import hex.auctionbazaar.util.SaleFeeResolver;
import hex.auctionbazaar.util.SaleTax;
import hex.economy.api.EconomyResult;
import hex.auctionbazaar.util.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High level AuctionHouse logic.
 *
 * Rules:
 *  - All DB writes run through {@link HexCoreBridge#async(Supplier)} or
 *    {@link HexCoreBridge#asyncRun(Runnable)}; never on the Bukkit main thread.
 *  - All Bukkit API calls (inventories, item stacks, scheduler) happen on the
 *    main thread.
 *  - Our own multi-step DB transitions go through {@code db.tx(...)} so they
 *    are atomic against each other; economy is the only external service.
 *
 * Listing state machine:
 *   ACTIVE --buy--&gt; RESERVED --withdraw ok--&gt; SOLD
 *                       |
 *                       +-- withdraw fail -&gt; ACTIVE
 *   ACTIVE --cancel--&gt; CANCELLED (item claim for seller, atomic)
 *   ACTIVE --expire--&gt; EXPIRED   (item claim for seller, atomic)
 */
public final class AuctionService {

    public enum SellResult {
        OK, NO_ITEM, INVALID_PRICE, TOO_MANY, TAX_CHANGED, BUSY,
        ECONOMY_UNAVAILABLE, NOT_ENOUGH_MONEY, ECONOMY_ERROR, DB_FAILED,
        /** Wystawienie się nie powiodło i nie udało się bezpiecznie zwrócić przedmiotu/środków. */
        COMPENSATION_FAILED,
        /** Dom Aukcyjny wyłączony (auction.enabled:false) lub tryb konserwacji - egzekwowane serwerowo. */
        FEATURE_DISABLED,
        /** Brak uprawnień (auction.permissions.sell) - sprawdzane też serwerowo, nie tylko w komendzie/GUI. */
        NO_PERMISSION,
        ITEM_NOT_ALLOWED
    }
    /**
     * Wynik kupna. Rozróżnia: dostawę bezpośrednią, dostawę jako claim, zwrot środków
     * (bezpośredni / jako claim), krytyczny błąd kompensacji oraz zwykłe odmowy.
     * Stany NIE zlewają się - sukces oznacza WYŁĄCZNIE OK_DELIVERED / OK_ITEM_CLAIMED.
     */
    public enum BuyOutcome {
        OK_DELIVERED, OK_ITEM_CLAIMED,
        REFUNDED, REFUND_PENDING, COMPENSATION_FAILED,
        NOT_ACTIVE, NOT_ENOUGH_MONEY, DB_FAILED, ECONOMY_UNAVAILABLE, OWN_LISTING,
        /** Techniczny błąd systemu ekonomii (wyjątek/nieudana operacja, nie brak środków). */
        ECONOMY_ERROR,
        /** Dom Aukcyjny wyłączony (auction.enabled:false) - egzekwowane serwerowo. */
        FEATURE_DISABLED,
        /** Brak uprawnień - sprawdzane też serwerowo. */
        NO_PERMISSION,
        ITEM_NOT_ALLOWED
    }
    public enum CancelOutcome {
        OK, NOT_FOUND, NOT_OWNER, NOT_ACTIVE,
        /** Brak uprawnień (auction.permissions.cancel-own) - sprawdzane też serwerowo. */
        NO_PERMISSION
    }
    public enum ClaimOutcome { OK, NOT_AVAILABLE, ECONOMY_FAILED, DB_FAILED, INVENTORY_FULL,
            /** Niepewny stan ekwipunku lub nieudany rollback - claim pozostaje w CLAIMING, wymagana ręczna korekta. */
            COMPENSATION_FAILED }
    /** Wynik śledzonej rekompensaty przedmiotu: dostarczony do ekwipunku / zapisany jako claim / niepowodzenie. */
    public enum ItemRefundStatus { DELIVERED, CLAIMED, FAILED }

    /**
     * Wynik wystawienia. Niesie rozbicie: brutto, podatek, {@code listingFee}
     * oraz {@code net} = ekonomiczne netto (brutto - podatek - opłata). Podatek i
     * opłata są pobierane z góry przy wystawieniu. {@link #required()} = łączna
     * kwota pobierana z góry (do komunikatu o braku środków).
     */
    public record SellOutcome(SellResult result, Long listingId, int limit,
                              BigDecimal gross, BigDecimal tax, BigDecimal net,
                              BigDecimal taxPercent, BigDecimal listingFee, String error) {
        private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

        public BigDecimal required() {
            return nz(listingFee).add(nz(tax));
        }

        public static SellOutcome ok(long id, SaleTax.Breakdown b, BigDecimal fee, BigDecimal net) {
            return new SellOutcome(SellResult.OK, id, 0, b.gross(), b.tax(), net, b.percent(), fee, null);
        }
        public static SellOutcome fail(SellResult r, String err) {
            return new SellOutcome(r, null, 0, null, null, null, null, null, err);
        }
        public static SellOutcome tooMany(int limit) {
            return new SellOutcome(SellResult.TOO_MANY, null, limit, null, null, null, null, null,
                    "limit " + limit);
        }
        /** Dla TAX_CHANGED / NOT_ENOUGH_MONEY: niesie aktualne rozbicie do wyświetlenia. */
        public static SellOutcome withBreakdown(SellResult r, SaleTax.Breakdown b,
                                                BigDecimal fee, BigDecimal net, String err) {
            return new SellOutcome(r, null, 0, b.gross(), b.tax(), net, b.percent(), fee, err);
        }
    }

    public record BuyResult(BuyOutcome outcome, AuctionListing listing, BigDecimal pricePaid) {
        public static BuyResult delivered(AuctionListing l) {
            return new BuyResult(BuyOutcome.OK_DELIVERED, l, l.price());
        }
        public static BuyResult itemClaimed(AuctionListing l) {
            return new BuyResult(BuyOutcome.OK_ITEM_CLAIMED, l, l.price());
        }
        /** Zwrot/kompensacja po nieudanej transakcji - niesie cenę zapłaconą (do komunikatu). */
        public static BuyResult compensated(BuyOutcome o, AuctionListing l) {
            return new BuyResult(o, l, l == null ? null : l.price());
        }
        public static BuyResult fail(BuyOutcome o) { return new BuyResult(o, null, null); }
    }

    /** Czysta mapa statusu kompensacji pieniężnej na wynik kupna (zwrot). */
    public static BuyOutcome refundOutcome(RefundCompensation.Status s) {
        return switch (s) {
            case REFUNDED -> BuyOutcome.REFUNDED;
            case PENDING_CLAIM -> BuyOutcome.REFUND_PENDING;
            case FAILED -> BuyOutcome.COMPENSATION_FAILED;
        };
    }

    /** Czysta mapa statusu zwrotu pieniędzy na status audytu. */
    public static String refundAuditResult(RefundCompensation.Status s) {
        return switch (s) {
            case REFUNDED -> AuditAction.RESULT_ROLLBACK;
            case PENDING_CLAIM -> AuditAction.RESULT_REFUND_PENDING;
            case FAILED -> AuditAction.RESULT_FAILED;
        };
    }

    /** Czysta mapa statusu dostawy przedmiotu na wynik kupna (sukces WYŁĄCZNIE dla dostarczenia/claim). */
    public static BuyOutcome deliveryOutcome(ItemRefundStatus st) {
        return switch (st) {
            case DELIVERED -> BuyOutcome.OK_DELIVERED;
            case CLAIMED -> BuyOutcome.OK_ITEM_CLAIMED;
            case FAILED -> BuyOutcome.COMPENSATION_FAILED;
        };
    }

    /**
     * Czysta decyzja wyniku, gdy withdraw opłaty/podatku się nie powiódł.
     * Niepowodzenie zwrotu przedmiotu ma PIERWSZEŃSTWO (błąd techniczny) nad
     * NOT_ENOUGH_MONEY / ECONOMY_ERROR (punkt #3).
     */
    public static SellResult listingWithdrawFailResult(boolean itemRefundFailed,
                                                       boolean chargeError, boolean notEnough) {
        if (itemRefundFailed) return SellResult.COMPENSATION_FAILED;
        if (chargeError) return SellResult.ECONOMY_ERROR;
        if (notEnough) return SellResult.NOT_ENOUGH_MONEY;
        return SellResult.ECONOMY_ERROR;
    }

    private final Plugin plugin;
    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final EconomyBridge economy;
    private final AuctionListingRepository listings;
    private final AuctionClaimRepository claims;
    private final AuditService audit;
    private final Supplier<AuctionConfig> configSupplier;
    /** Globalny przełącznik pluginu (enabled). false = tryb konserwacji: brak nowych komercyjnych mutacji. */
    private final java.util.function.BooleanSupplier pluginEnabled;

    /** Busy-guard: blokuje równoległe wystawianie tego samego gracza aż do końca kompensacji. */
    private final java.util.Set<UUID> selling = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Rate-limit dla SEVERE logu nieodwracalnej kompensacji. */
    private final java.util.concurrent.atomic.AtomicLong lastSevereLogAt =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public AuctionService(Plugin plugin,
                          HexCoreBridge hexCore,
                          EconomyBridge economy,
                          AuctionListingRepository listings,
                          AuctionClaimRepository claims,
                          AuditService audit,
                          Supplier<AuctionConfig> configSupplier,
                          java.util.function.BooleanSupplier pluginEnabled) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.listings = Objects.requireNonNull(listings, "listings");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Nowe komercyjne mutacje (wystawianie/kupno) są dozwolone tylko gdy plugin NIE jest w trybie
     * konserwacji (global enabled) ORAZ Dom Aukcyjny jest włączony (auction.enabled). Sprawdzane
     * SERWEROWO tuż przed mutacją - także dla GUI otwartego przed reloadem. Akcje odzysku
     * (anulowanie, odbiór) świadomie NIE przechodzą przez tę bramkę.
     */
    private boolean commerceEnabled() {
        return pluginEnabled.getAsBoolean() && configSupplier.get().enabled();
    }

    /**
     * Punkt #6: mapowanie wyniku zwolnienia rezerwacji po nieudanym pobraniu środków przy kupnie.
     * Wynik {@code false}/{@code null}/wyjątek oznacza, że aukcja mogła zostać uwięziona w RESERVED ->
     * {@link BuyOutcome#COMPENSATION_FAILED} (stan krytyczny). Tylko potwierdzone {@code true} pozwala
     * zwrócić pierwotny wynik ekonomii ({@code econFail}).
     */
    public static BuyOutcome reservationReleaseBuyOutcome(Boolean released, Throwable err, BuyOutcome econFail) {
        if (err != null || !Boolean.TRUE.equals(released)) {
            return BuyOutcome.COMPENSATION_FAILED;
        }
        return econFail;
    }

    private void onMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private <T> CompletableFuture<T> failOnMain(String label, Throwable err) {
        logger.log(Level.WARNING, "Operacja aukcji '" + label + "' nie powiodła się", err);
        return CompletableFuture.failedFuture(err);
    }

    public CompletableFuture<Optional<AuctionListing>> findByIdAsync(long id) {
        return hexCore.async(() -> listings.findById(id))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Odczyt aukcji o ID " + id + " nie powiódł się", ex);
                    return Optional.empty();
                });
    }

    // ---------------------------------------------------------------- sell

    /**
     * Main-thread entry. Snapshots the held stack, charges the listing fee
     * async, removes the item, and inserts the listing. Every failure path
     * either returns the item to the seller's inventory or stores it as a
     * pending claim - the item is never silently lost.
     */
    /** Wejście z komendy - bez wiązania procentu z podsumowania. */
    public CompletableFuture<SellOutcome> sellItemInHand(Player seller, BigDecimal price) {
        return sellItemInHand(seller, price, null);
    }

    /**
     * Wystawienie z ręki. {@code expectedPercent} (może być null) wiąże procent
     * pokazany w podsumowaniu z faktycznie pobieranym: jeśli między podsumowaniem a
     * potwierdzeniem zmienią się permisje, zwracamy TAX_CHANGED (GUI pokaże ponownie),
     * nigdy nie pobierając innej kwoty niż wyświetlona.
     *
     * Bezpieczeństwo: item serializowany PRZED jakąkolwiek mutacją; podatek+opłata
     * z góry; snapshot podatkowy zapisany na aukcji; każde niepowodzenie po pobraniu
     * środków uruchamia śledzoną kompensację (deposit -> money-claim -> terminal),
     * a przedmiot wraca do gracza. Busy-guard blokuje równoległe wystawianie.
     */
    public CompletableFuture<SellOutcome> sellItemInHand(Player seller, BigDecimal price, BigDecimal expectedPercent) {
        AuctionConfig cfg = configSupplier.get();
        // SERWEROWA autoryzacja/gate PRZED jakąkolwiek mutacją (GUI mogło zostać otwarte przed
        // disable/utratą praw). Wyłączona funkcja to FEATURE_DISABLED, NIE mylące NO_ITEM (punkt #4).
        if (!commerceEnabled()) {
            return CompletableFuture.completedFuture(
                    SellOutcome.fail(SellResult.FEATURE_DISABLED, "auction disabled"));
        }
        if (!seller.hasPermission(cfg.permSell())) {
            return CompletableFuture.completedFuture(
                    SellOutcome.fail(SellResult.NO_PERMISSION, "no permission"));
        }
        if (!cfg.priceInRange(price)) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.INVALID_PRICE, "price out of range"));
        }
        ItemStack inHand = seller.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == Material.AIR || inHand.getAmount() <= 0) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.NO_ITEM, "no item in hand"));
        }
        CustomItemTradePolicy.Decision tradeDecision = CustomItemTradePolicy.evaluate(inHand);
        if (!tradeDecision.allowed()) {
            return CompletableFuture.completedFuture(
                    SellOutcome.fail(SellResult.ITEM_NOT_ALLOWED, tradeDecision.reason()));
        }
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(
                    SellOutcome.fail(SellResult.ECONOMY_UNAVAILABLE, "economy unavailable"));
        }

        UUID sellerId = seller.getUniqueId();
        String sellerName = seller.getName();
        ItemStack snapshot = inHand.clone();

        // Serializacja PRZED jakąkolwiek mutacją (Economy/ekwipunek).
        final byte[] blob;
        try {
            blob = ItemSerializer.serialize(snapshot);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "AUKCJA: serializacja przedmiotu nie powiodła się "
                    + "przed pobraniem środków", t);
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.DB_FAILED, "serialize"));
        }

        BigDecimal pct = SaleFeeResolver.resolve(seller::hasPermission, cfg.saleFeePercent(), cfg.saleFeeTiers());
        SaleTax.Breakdown tax = SaleTax.compute(price, pct);
        BigDecimal fee = cfg.listingFee() == null ? BigDecimal.ZERO : cfg.listingFee();
        BigDecimal economicNet = economicNet(tax, fee);
        // Wiązanie podsumowania z abbuchung: zmiana rangi -> pokaż ponownie.
        if (expectedPercent != null && expectedPercent.compareTo(pct) != 0) {
            return CompletableFuture.completedFuture(
                    SellOutcome.withBreakdown(SellResult.TAX_CHANGED, tax, fee, economicNet, "tax changed"));
        }
        int limit = ListingLimitResolver.resolve(seller::hasPermission,
                cfg.listingLimitDefault(), cfg.listingLimitTiers());

        // Busy-guard: jeden aktywny proces wystawiania na gracza (do końca kompensacji).
        if (!selling.add(sellerId)) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.BUSY, "already selling"));
        }
        CompletableFuture<SellOutcome> result = new CompletableFuture<>();
        result.whenComplete((r, e) -> selling.remove(sellerId));

        BigDecimal upfront = fee.add(tax.tax());
        hexCore.async(() -> listings.countActiveBySeller(sellerId))
                .whenComplete((count, err) -> {
                    if (err != null) {
                        onMain(() -> result.complete(SellOutcome.fail(SellResult.DB_FAILED, err.getMessage())));
                        return;
                    }
                    if (count >= limit) {
                        onMain(() -> result.complete(SellOutcome.tooMany(limit)));
                        return;
                    }
                    onMain(() -> {
                        ItemStack current = seller.getInventory().getItemInMainHand();
                        if (current == null || !current.isSimilar(snapshot)
                                || current.getAmount() < snapshot.getAmount()) {
                            result.complete(SellOutcome.fail(SellResult.NO_ITEM, "item changed"));
                            return;
                        }
                        if (!cfg.priceInRange(price)) {
                            result.complete(SellOutcome.fail(SellResult.INVALID_PRICE, "price out of range"));
                            return;
                        }
                        seller.getInventory().removeItem(snapshot.clone());
                        chargeAndInsert(seller, sellerId, sellerName, snapshot, blob, price, tax, fee,
                                upfront, economicNet, cfg, result);
                    });
                });
        return result;
    }

    /** Ekonomiczne netto = brutto - podatek - opłata (nie mniej niż 0). */
    private static BigDecimal economicNet(SaleTax.Breakdown tax, BigDecimal fee) {
        BigDecimal net = tax.net().subtract(fee == null ? BigDecimal.ZERO : fee);
        return net.signum() < 0
                ? BigDecimal.ZERO.setScale(SaleTax.MONEY_SCALE, RoundingMode.HALF_UP)
                : net;
    }

    private void chargeAndInsert(Player seller, UUID sellerId, String sellerName,
                                 ItemStack snapshot, byte[] blob, BigDecimal price, SaleTax.Breakdown tax,
                                 BigDecimal fee, BigDecimal upfront, BigDecimal economicNet,
                                 AuctionConfig cfg, CompletableFuture<SellOutcome> result) {
        CompletableFuture<EconomyResult> chargeChain = upfront.signum() <= 0
                ? CompletableFuture.completedFuture(EconomyResult.ok(BigDecimal.ZERO))
                : economy.withdraw(sellerId, sellerName, upfront, "auction-listing-tax");

        chargeChain.whenComplete((chargeRes, chargeErr) -> {
            if (chargeErr != null || chargeRes == null || !chargeRes.success()) {
                // Środki NIE zostały pobrane - zwróć przedmiot (śledzone) i DOPIERO potem zakończ.
                boolean notEnough = chargeRes != null && "NOT_ENOUGH_FUNDS".equals(chargeRes.reason());
                long txId = System.nanoTime();
                returnItemTracked(seller, sellerId, snapshot, "listing-refund")
                        .whenComplete((is, e) -> onMain(() -> {
                            boolean itemFailed = e != null || is == ItemRefundStatus.FAILED;
                            if (itemFailed) {
                                logSevereCompensationFailure(txId, sellerId, BigDecimal.ZERO,
                                        ItemRefundStatus.FAILED, RefundCompensation.Status.REFUNDED);
                            }
                            // Niepowodzenie zwrotu przedmiotu ma PIERWSZEŃSTWO nad NOT_ENOUGH_MONEY (punkt #3).
                            SellResult r = listingWithdrawFailResult(itemFailed, chargeErr != null, notEnough);
                            result.complete(r == SellResult.NOT_ENOUGH_MONEY
                                    ? SellOutcome.withBreakdown(r, tax, fee, economicNet, "brak środków")
                                    : SellOutcome.fail(r, r == SellResult.COMPENSATION_FAILED
                                            ? "zwrot przedmiotu nie powiódł się" : "błąd pobrania środków"));
                        }));
                return;
            }
            // Środki pobrane. Wstaw aukcję ze snapshotem; przy błędzie -> pełna kompensacja.
            long now = System.currentTimeMillis();
            // SafeTime chroni przed przepełnieniem (nigdy ujemny expiresAt - punkt #8).
            long expiresAt = SafeTime.deadlineMillis(now, cfg.defaultDurationSeconds());
            hexCore.async(() -> listings.insert(sellerId, sellerName, blob,
                            snapshot.getType().name(), snapshot.getAmount(), price, now, expiresAt,
                            tax.percent(), tax.tax(), fee, economicNet))
                    .whenComplete((id, insertErr) -> {
                        if (insertErr != null || id == null) {
                            logger.log(Level.WARNING, "AUKCJA: zapis aukcji nie powiódł się "
                                    + "- uruchamiam kompensację", insertErr);
                            compensateListingFailure(seller, sellerId, sellerName, snapshot, upfront, result);
                            return;
                        }
                        audit.log(audit.builder()
                                .actor(sellerId, sellerName)
                                .action(AuditAction.AUCTION_LISTING_CREATED)
                                .market(AuditAction.MARKET_AUCTION)
                                .listingId(id)
                                .unitPrice(price)
                                .total(price)
                                .result(AuditAction.RESULT_OK)
                                .reason("brutto=" + tax.gross().toPlainString()
                                        + " podatek=" + tax.tax().toPlainString()
                                        + " (" + tax.percent().toPlainString() + "%)"
                                        + " opłata=" + fee.toPlainString()
                                        + " netto=" + economicNet.toPlainString()));
                        onMain(() -> result.complete(SellOutcome.ok(id, tax, fee, economicNet)));
                    });
        });
    }

    /**
     * Kompensacja po pobraniu opłaty+podatku, gdy insert aukcji zawiódł.
     * Przedmiot wraca do gracza; kwota zwracana przez deposit, a przy jego
     * niepowodzeniu przez trwały money-claim. Wynik terminalny dopiero po ustaleniu
     * statusu (żadnego fire-and-forget). Rate-limitowany SEVERE, gdy ani deposit ani
     * claim się nie powiodą.
     */
    private record CompensationResult(ItemRefundStatus item, RefundCompensation.Status money) {}

    private void compensateListingFailure(Player seller, UUID sellerId, String sellerName,
                                          ItemStack snapshot, BigDecimal upfront,
                                          CompletableFuture<SellOutcome> result) {
        long txId = System.nanoTime();
        // Kompensacja przedmiotu I środków jest w pełni śledzona; wynik terminalny dopiero po OBU.
        CompletableFuture<ItemRefundStatus> itemFuture =
                returnItemTracked(seller, sellerId, snapshot, "listing-refund");
        CompletableFuture<RefundCompensation.Status> moneyFuture =
                (upfront == null || upfront.signum() <= 0)
                        ? CompletableFuture.completedFuture(RefundCompensation.Status.REFUNDED)
                        : RefundCompensation.compensate(
                        () -> economy.deposit(sellerId, sellerName, upfront, "auction-listing-fee-refund")
                                .thenApply(r -> r != null && r.success()),
                        () -> hexCore.async(() -> claims.insertMoney(sellerId, upfront,
                                        "auction-listing-fee-refund-claim", null, System.currentTimeMillis()))
                                .thenApply(cid -> cid != null && cid >= 0));

        itemFuture.thenCombine(moneyFuture, CompensationResult::new)
                .whenComplete((res, err) -> onMain(() -> {
                    ItemRefundStatus is = (err != null || res == null) ? ItemRefundStatus.FAILED : res.item();
                    RefundCompensation.Status ms = (err != null || res == null)
                            ? RefundCompensation.Status.FAILED : res.money();
                    auditRefund(sellerId, sellerName, upfront, is, ms);
                    boolean compFailed = is == ItemRefundStatus.FAILED
                            || ms == RefundCompensation.Status.FAILED;
                    if (compFailed) {
                        logSevereCompensationFailure(txId, sellerId, upfront, is, ms);
                    }
                    // Dopiero teraz zwalniamy busy-guard (przez result.whenComplete) i kończymy.
                    // Nieudana kompensacja -> błąd techniczny; udana -> DB_FAILED (środki+przedmiot zwrócone).
                    result.complete(SellOutcome.fail(
                            compFailed ? SellResult.COMPENSATION_FAILED : SellResult.DB_FAILED,
                            "zapis aukcji nie powiódł się"));
                }));
    }

    private void auditRefund(UUID sellerId, String sellerName, BigDecimal amount,
                             ItemRefundStatus item, RefundCompensation.Status money) {
        String res = compensationAuditResult(item, money);
        audit.log(audit.builder()
                .actor(sellerId, sellerName)
                .action(AuditAction.AUCTION_LISTING_CREATED)
                .market(AuditAction.MARKET_AUCTION)
                .total(amount)
                .result(res)
                .reason("zapis aukcji nieudany - zwrot przedmiotu="
                        + item.name().toLowerCase(java.util.Locale.ROOT)
                        + " zwrot środków=" + money.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private void logSevereCompensationFailure(long txId, UUID uuid, BigDecimal amount,
                                              ItemRefundStatus item, RefundCompensation.Status money) {
        long now = System.currentTimeMillis();
        long last = lastSevereLogAt.get();
        if (now - last >= 60_000L && lastSevereLogAt.compareAndSet(last, now)) {
            logger.severe("KOMPENSACJA AUKCJI NIEODWRACALNA tx=" + txId
                    + " gracz=" + uuid
                    + " kwota=" + (amount == null ? "0" : amount.toPlainString())
                    + " przedmiot=" + item + " środki=" + money
                    + " - wymagana ręczna korekta (bez danych dostępowych do bazy)");
        }
    }

    /**
     * Śledzona, ALL-OR-NOTHING rekompensata przedmiotu (punkty #2/#3). Dostęp do
     * ekwipunku WYŁĄCZNIE na wątku głównym; wyjątki nie wydostają się z zadania.
     * Mapowanie ({@link #resolveItemReturn}):
     *  - ADDED_FULLY -> DELIVERED (meta/NBT/PDC zachowane - dodajemy klon oryginału),
     *  - NOT_FIT_REVERTED -> PEŁNY przedmiot jako POJEDYNCZY trwały claim (CLAIMED po
     *    potwierdzonym insertcie; FAILED gdy insert padł),
     *  - STATE_UNCERTAIN -> FAILED, BEZ zapisu claim (w ekwipunku może już leżeć część -
     *    unikamy duplikacji),
     *  - gracz offline -> od razu claim pełnego przedmiotu.
     * Future domykany jest ZAWSZE (także gdy nie da się zaplanować zadania po disable).
     */
    private CompletableFuture<ItemRefundStatus> returnItemTracked(Player player, UUID owner,
                                                                  ItemStack item, String reason) {
        CompletableFuture<ItemRefundStatus> out = new CompletableFuture<>();
        try {
            onMain(() -> {
                try {
                    if (owner == null || item == null) {
                        out.complete(ItemRefundStatus.FAILED);
                        return;
                    }
                    boolean online = player != null && player.isOnline();
                    InventoryFit.Result addResult = online
                            ? InventoryFit.tryAddFull(player, item.clone())
                            : InventoryFit.Result.NOT_FIT_REVERTED;   // offline: pomiń ekwipunek, od razu claim
                    resolveItemReturn(online, addResult, item,
                            it -> insertItemClaimTracked(owner, it, reason))
                            .whenComplete((st, e) -> out.complete(
                                    (e != null || st == null) ? ItemRefundStatus.FAILED : st));
                } catch (Throwable t) {
            logger.log(Level.SEVERE, "AUKCJA: zwrot przedmiotu - nieoczekiwany błąd "
                            + "na wątku głównym", t);
                    out.complete(ItemRefundStatus.FAILED);
                }
            });
        } catch (Throwable t) {
            // Nie udało się zaplanować zadania (np. plugin wyłączany) -> domknij terminalnie.
            logger.log(Level.SEVERE, "AUKCJA: nie można zaplanować zwrotu przedmiotu "
                    + "(plugin wyłączany?) - zwracam FAILED", t);
            out.complete(ItemRefundStatus.FAILED);
        }
        return out;
    }

    /**
     * Czyste mapowanie wyniku dodawania do ekwipunku na śledzoną rekompensatę (testowalne):
     * ADDED_FULLY -> DELIVERED; STATE_UNCERTAIN -> FAILED (bez claim); NOT_FIT_REVERTED lub
     * gracz offline -> claim pełnego przedmiotu.
     */
    public static <T> CompletableFuture<ItemRefundStatus> resolveItemReturn(
            boolean attemptedInventory, InventoryFit.Result addResult, T item,
            java.util.function.Function<T, CompletableFuture<Boolean>> claimInsert) {
        if (attemptedInventory) {
            if (addResult == InventoryFit.Result.ADDED_FULLY) {
                return CompletableFuture.completedFuture(ItemRefundStatus.DELIVERED);
            }
            if (addResult == InventoryFit.Result.STATE_UNCERTAIN) {
                // Niepewny stan ekwipunku -> NIE zapisujemy claim (możliwa częściowa dostawa).
                return CompletableFuture.completedFuture(ItemRefundStatus.FAILED);
            }
            // NOT_FIT_REVERTED -> pełny przedmiot jako jeden claim.
        }
        return trackItemReturn(false, java.util.List.of(item), claimInsert);
    }

    /**
     * Śledzona część rekompensaty przedmiotu (bez Bukkit, testowalna). Przy pełnej
     * dostawie -> DELIVERED; inaczej wstawia claim-y i czeka na WSZYSTKIE inserty:
     * wszystkie OK -> CLAIMED, jakikolwiek błąd -> FAILED. Future kończy się dopiero
     * po potwierdzeniu wszystkich insertów (żadnego fire-and-forget).
     */
    public static <T> CompletableFuture<ItemRefundStatus> trackItemReturn(
            boolean fullyDelivered, java.util.Collection<T> toClaim,
            java.util.function.Function<T, CompletableFuture<Boolean>> claimInsert) {
        if (fullyDelivered || toClaim == null || toClaim.isEmpty()) {
            return CompletableFuture.completedFuture(ItemRefundStatus.DELIVERED);
        }
        java.util.List<CompletableFuture<Boolean>> inserts = new java.util.ArrayList<>();
        for (T it : toClaim) {
            CompletableFuture<Boolean> f;
            try {
                f = claimInsert.apply(it);
                if (f == null) f = CompletableFuture.completedFuture(false);
            } catch (Throwable t) {
                f = CompletableFuture.completedFuture(false);
            }
            inserts.add(f.exceptionally(ex -> false));
        }
        return CompletableFuture.allOf(inserts.toArray(new CompletableFuture[0]))
                .thenApply(v -> combineClaimResults(
                        inserts.stream().map(cf -> cf.getNow(false)).toList()));
    }

    /** Czysta reguła: brak claim-ów -> DELIVERED; wszystkie OK -> CLAIMED; jakikolwiek błąd -> FAILED. */
    public static ItemRefundStatus combineClaimResults(java.util.List<Boolean> results) {
        if (results == null || results.isEmpty()) {
            return ItemRefundStatus.DELIVERED;
        }
        return results.stream().allMatch(Boolean.TRUE::equals)
                ? ItemRefundStatus.CLAIMED : ItemRefundStatus.FAILED;
    }

    /**
     * Czysty status audytu połączonej kompensacji: dowolny FAILED -> FAILED,
     * inaczej dowolny "pending" (CLAIMED / PENDING_CLAIM) -> REFUND_PENDING, inaczej ROLLBACK.
     */
    public static String compensationAuditResult(ItemRefundStatus item, RefundCompensation.Status money) {
        if (item == ItemRefundStatus.FAILED || money == RefundCompensation.Status.FAILED) {
            return AuditAction.RESULT_FAILED;
        }
        if (item == ItemRefundStatus.CLAIMED || money == RefundCompensation.Status.PENDING_CLAIM) {
            return AuditAction.RESULT_REFUND_PENDING;
        }
        return AuditAction.RESULT_ROLLBACK;
    }

    /** Wstawia item-claim i zwraca true dopiero po potwierdzonym insertcie (błąd -> false, logowany). */
    private CompletableFuture<Boolean> insertItemClaimTracked(UUID owner, ItemStack item, String reason) {
        final byte[] blob;
        try {
            blob = ItemSerializer.serialize(item);
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "nie udało się zserializować przedmiotu do odbioru (claim), powód="
                    + reason, t);
            return CompletableFuture.completedFuture(false);
        }
        return hexCore.async(() -> claims.insertItem(owner, blob, reason, null, System.currentTimeMillis()))
                .handle((id, ex) -> {
                    boolean ok = ex == null && id != null && id >= 0;
                    if (!ok) {
                        logger.log(Level.SEVERE, "zapis odbioru (claim) nie powiódł się dla " + owner
                                + " powód=" + reason, ex);
                    }
                    return ok;
                });
    }

    // ---------------------------------------------------------------- buy

    /**
     * Czysta ocena warunków wstępnych kupna. Zwraca {@code null} gdy można
     * kontynuować (rezerwacja + pobranie środków), albo powód odmowy. Kupno
     * WŁASNEJ aukcji zwraca {@link BuyOutcome#OWN_LISTING} - zawsze zanim
     * cokolwiek zostanie zarezerwowane lub pobrane.
     */
    public static BuyOutcome checkPreBuy(AuctionListing l, UUID buyerId) {
        if (l == null || l.state() != ListingState.ACTIVE) {
            return BuyOutcome.NOT_ACTIVE;
        }
        if (l.sellerUuid().equals(buyerId)) {
            return BuyOutcome.OWN_LISTING;
        }
        return null;
    }

    public CompletableFuture<BuyResult> buy(Player buyer, long listingId) {
        AuctionConfig cfg0 = configSupplier.get();
        // SERWEROWA autoryzacja tuż przed mutacją (GUI mogło zostać otwarte przed disable/utratą praw).
        // commerceEnabled() = global enabled (tryb konserwacji) ORAZ auction.enabled.
        if (!commerceEnabled()) {
            return CompletableFuture.completedFuture(BuyResult.fail(BuyOutcome.FEATURE_DISABLED));
        }
        if (!buyer.hasPermission(cfg0.permOpen())) {
            return CompletableFuture.completedFuture(BuyResult.fail(BuyOutcome.NO_PERMISSION));
        }
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(BuyResult.fail(BuyOutcome.ECONOMY_UNAVAILABLE));
        }
        AuctionConfig cfg = configSupplier.get();
        UUID buyerId = buyer.getUniqueId();
        String buyerName = buyer.getName();
        long now = System.currentTimeMillis();
        // SafeTime chroni przed przepełnieniem (nigdy ujemny reservedUntil - punkt #8).
        long reservedUntil = SafeTime.deadlineMillis(now, cfg.reservationTtlSeconds());

        CompletableFuture<BuyResult> result = new CompletableFuture<>();
        hexCore.async(() -> listings.findById(listingId))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        onMain(() -> result.complete(BuyResult.fail(BuyOutcome.DB_FAILED)));
                        return;
                    }
                    AuctionListing l = opt.orElse(null);
                    // Guard oceniany PRZED rezerwacją i pobraniem środków - kupno
                    // własnej aukcji jest blokowane bez żadnej mutacji DB/Economy.
                    BuyOutcome pre = checkPreBuy(l, buyerId);
                    if (pre != null) {
                        onMain(() -> result.complete(BuyResult.fail(pre)));
                        return;
                    }
                    onMain(() -> {
                        BuyOutcome itemGate = checkListingItemTradeable(l);
                        if (itemGate != null) {
                            result.complete(BuyResult.fail(itemGate));
                            return;
                        }
                        hexCore.async(() -> listings.tryReserve(listingId, buyerId, reservedUntil))
                            .whenComplete((reserved, reserveErr) -> {
                                if (reserveErr != null || reserved == null || !reserved) {
                                    onMain(() -> result.complete(BuyResult.fail(BuyOutcome.NOT_ACTIVE)));
                                    return;
                                }
                                economy.withdraw(buyerId, buyerName, l.price(), "auction-buy-" + listingId)
                                        .whenComplete((wd, wdErr) -> {
                                            if (wdErr != null || wd == null || !wd.success()) {
                                                // Rozdziel brak środków od technicznego błędu ekonomii.
                                                boolean notEnough = wd != null && !wd.success()
                                                        && "NOT_ENOUGH_FUNDS".equals(wd.reason());
                                                BuyOutcome econFail = notEnough
                                                        ? BuyOutcome.NOT_ENOUGH_MONEY : BuyOutcome.ECONOMY_ERROR;
                                                // Zwolnienie rezerwacji MUSI być POTWIERDZONE booleanem: false/null/
                                                // wyjątek -> aukcja mogła utknąć w RESERVED -> stan krytyczny
                                                // (COMPENSATION_FAILED). Tylko potwierdzone true daje econFail (punkt #6).
                                                hexCore.async(() ->
                                                                listings.releaseReservation(listingId, buyerId))
                                                        .whenComplete((released, relErr) -> {
                                                            BuyOutcome relOut = reservationReleaseBuyOutcome(
                                                                    released, relErr, econFail);
                                                            if (relOut == BuyOutcome.COMPENSATION_FAILED) {
                                                                logger.log(Level.SEVERE, "AUKCJA: zwolnienie "
                                                                        + "rezerwacji po nieudanym pobraniu środków "
                                                                        + "nie zostało potwierdzone (aukcja "
                                                                        + listingId + ", wynik=" + released
                                                                        + ") - wymagana ręczna korekta", relErr);
                                                            }
                                                            onMain(() -> result.complete(BuyResult.fail(relOut)));
                                                        });
                                                return;
                                            }
                                            finalizePurchase(buyer, buyerId, l, cfg, result);
                                        });
                            });
                    });
                });
        return result;
    }

    private BuyOutcome checkListingItemTradeable(AuctionListing listing) {
        ItemStack item;
        try {
            item = ItemSerializer.deserialize(listing.itemBlob());
        } catch (Throwable t) {
            logger.log(Level.WARNING, "AUKCJA: nie mozna odczytac przedmiotu aukcji "
                    + listing.id() + " przed kupnem", t);
            return BuyOutcome.DB_FAILED;
        }
        if (item == null) {
            return BuyOutcome.DB_FAILED;
        }
        CustomItemTradePolicy.Decision decision = CustomItemTradePolicy.evaluate(item);
        return decision.allowed() ? null : BuyOutcome.ITEM_NOT_ALLOWED;
    }

    private void finalizePurchase(Player buyer, UUID buyerId, AuctionListing l,
                                  AuctionConfig cfg, CompletableFuture<BuyResult> result) {
        long now = System.currentTimeMillis();
        // Podatek jest pobierany przy WYSTAWIENIU, więc sprzedawca dostaje pełną
        // cenę (kupujący płaci brutto, netto sprzedawcy = brutto - podatek z góry).
        BigDecimal price = l.price();
        BigDecimal sellerPayout = price;
        String buyerName = buyer.getName();
        UUID sellerId = l.sellerUuid();
        long listingId = l.id();

        hexCore.async(() -> listings.markSoldWithSellerClaimTx(
                        listingId, buyerId, now, sellerId, sellerPayout, "auction-sold-" + listingId))
                .whenComplete((claimIdOpt, txErr) -> {
                    if (txErr != null || claimIdOpt == null || claimIdOpt.isEmpty()) {
                        // SOLD nie doszło do skutku (sprzedawca NIE dostał claim) -> zwrot kupującego.
                        refundBuyerAfterSoldFailure(buyer, buyerId, buyerName, l, price, result);
                        return;
                    }
                    onMain(() -> {
                        notifySellerSold(l, price);
                        deliverPurchasedItem(buyer, buyerId, l, price, result);
                    });
                });
    }

    private void notifySellerSold(AuctionListing listing, BigDecimal price) {
        Player seller = Bukkit.getPlayer(listing.sellerUuid());
        if (seller == null || !seller.isOnline()) {
            return;
        }
        seller.playSound(seller.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 0.85f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (seller.isOnline()) {
                seller.playSound(seller.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.15f);
            }
        }, 8L);
        seller.sendActionBar(LegacyFormat.component(
                "&0[&4HEX &6AUCTIONHOUSE&0] &fSprzedano przedmiot za &e" + economy.format(price) + "&f!"));
    }

    /**
     * Punkt #1: nieudana transakcja SOLD po pobraniu środków. Zwrot jest w PEŁNI
     * śledzony (deposit -> money-claim -> terminal), rezerwacja zwalniana i logowana,
     * a wynik kupna kończy się DOPIERO po ustaleniu statusu zwrotu. Bez podwójnej wypłaty.
     */
    private void refundBuyerAfterSoldFailure(Player buyer, UUID buyerId, String buyerName,
                                             AuctionListing l, BigDecimal price,
                                             CompletableFuture<BuyResult> result) {
        long txId = System.nanoTime();
        long listingId = l.id();
        // Zwolnienie rezerwacji jest ŚLEDZONE (logowane przy błędzie) - niezależne od zwrotu,
        // więc jego niepowodzenie nie powoduje podwójnej wypłaty.
        hexCore.asyncRun(() -> listings.releaseReservation(listingId, buyerId))
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "AUKCJA: zwolnienie rezerwacji po nieudanej "
                            + "transakcji sprzedaży nie powiodło się (aukcja " + listingId + ")", ex);
                    return null;
                });
        RefundCompensation.compensate(
                () -> economy.deposit(buyerId, buyerName, price, "auction-buy-refund-" + listingId)
                        .thenApply(r -> r != null && r.success()),
                () -> hexCore.async(() -> claims.insertMoney(buyerId, price,
                                "auction-buy-refund-claim-" + listingId, listingId, System.currentTimeMillis()))
                        .thenApply(cid -> cid != null && cid >= 0))
                .whenComplete((status, cErr) -> onMain(() -> {
                    RefundCompensation.Status s = (cErr != null || status == null)
                            ? RefundCompensation.Status.FAILED : status;
                    if (s == RefundCompensation.Status.FAILED) {
                        logSevereCompensationFailure(txId, buyerId, price,
                                ItemRefundStatus.FAILED, RefundCompensation.Status.FAILED);
                    }
                    auditBuy(buyerId, buyerName, listingId, price, refundAuditResult(s),
                            "transakcja sprzedaży nieudana - zwrot środków="
                                    + s.name().toLowerCase(java.util.Locale.ROOT));
                    result.complete(BuyResult.compensated(refundOutcome(s), l));
                }));
    }

    /**
     * Punkt #2/#3: dostawa przedmiotu po udanej transakcji SOLD (wątek główny).
     *  - przedmiot nieodczytywalny -> ŚLEDZONY zwrot pieniędzy (REFUNDED/REFUND_PENDING/COMPENSATION_FAILED),
     *  - przedmiot odczytywalny -> dostawa ALL-OR-NOTHING (OK_DELIVERED / OK_ITEM_CLAIMED / COMPENSATION_FAILED).
     * Nigdy nie zwracamy fałszywego OK; audyt nie ma RESULT_OK gdy dostawa/kompensacja padła.
     */
    private void deliverPurchasedItem(Player buyer, UUID buyerId, AuctionListing l,
                                      BigDecimal price, CompletableFuture<BuyResult> result) {
        long txId = System.nanoTime();
        long listingId = l.id();
        String buyerName = buyer.getName();
        ItemStack item = null;
        try {
            item = ItemSerializer.deserialize(l.itemBlob());
        } catch (Throwable t) {
            logger.log(Level.WARNING, "AUKCJA: nie można odczytać przedmiotu aukcji " + listingId, t);
        }
        if (item == null) {
            RefundCompensation.compensate(
                    () -> economy.deposit(buyerId, buyerName, price, "auction-item-recovery-" + listingId)
                            .thenApply(r -> r != null && r.success()),
                    () -> hexCore.async(() -> claims.insertMoney(buyerId, price,
                                    "auction-item-recovery-claim-" + listingId, listingId,
                                    System.currentTimeMillis()))
                            .thenApply(cid -> cid != null && cid >= 0))
                    .whenComplete((status, cErr) -> onMain(() -> {
                        RefundCompensation.Status s = (cErr != null || status == null)
                                ? RefundCompensation.Status.FAILED : status;
                        if (s == RefundCompensation.Status.FAILED) {
                            logSevereCompensationFailure(txId, buyerId, price,
                                    ItemRefundStatus.FAILED, RefundCompensation.Status.FAILED);
                        }
                        auditBuy(buyerId, buyerName, listingId, price, refundAuditResult(s),
                                "przedmiot nieodczytywalny - odzysk środków="
                                        + s.name().toLowerCase(java.util.Locale.ROOT));
                        result.complete(BuyResult.compensated(refundOutcome(s), l));
                    }));
            return;
        }
        returnItemTracked(buyer, buyerId, item, "auction-buy-overflow-" + listingId)
                .whenComplete((is, e) -> onMain(() -> {
                    ItemRefundStatus st = (e != null || is == null) ? ItemRefundStatus.FAILED : is;
                    BuyOutcome outcome = deliveryOutcome(st);
                    boolean failed = outcome == BuyOutcome.COMPENSATION_FAILED;
                    if (failed) {
                        logSevereCompensationFailure(txId, buyerId, price,
                                ItemRefundStatus.FAILED, RefundCompensation.Status.REFUNDED);
                    }
                    auditBuy(buyerId, buyerName, listingId, price,
                            failed ? AuditAction.RESULT_FAILED : AuditAction.RESULT_OK,
                            "dostawa=" + st.name().toLowerCase(java.util.Locale.ROOT));
                    BuyResult br = switch (outcome) {
                        case OK_DELIVERED -> BuyResult.delivered(l);
                        case OK_ITEM_CLAIMED -> BuyResult.itemClaimed(l);
                        default -> BuyResult.compensated(BuyOutcome.COMPENSATION_FAILED, l);
                    };
                    result.complete(br);
                }));
    }

    private void auditBuy(UUID uuid, String name, long listingId, BigDecimal price,
                          String result, String reason) {
        audit.log(audit.builder()
                .actor(uuid, name)
                .action(AuditAction.AUCTION_LISTING_BOUGHT)
                .market(AuditAction.MARKET_AUCTION)
                .listingId(listingId)
                .unitPrice(price)
                .total(price)
                .result(result)
                .reason(reason));
    }

    // ---------------------------------------------------------------- cancel

    public CompletableFuture<CancelOutcome> cancel(Player seller, long listingId) {
        // SERWEROWA autoryzacja. Anulowanie to akcja ODZYSKU (zwrot przedmiotu) - świadomie NIE
        // przechodzi przez bramkę trybu konserwacji, ale WYMAGA uprawnienia cancel-own (punkt #5).
        if (!seller.hasPermission(configSupplier.get().permCancelOwn())) {
            return CompletableFuture.completedFuture(CancelOutcome.NO_PERMISSION);
        }
        UUID sellerId = seller.getUniqueId();
        CompletableFuture<CancelOutcome> result = new CompletableFuture<>();

        hexCore.async(() -> listings.findById(listingId))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        logger.log(Level.WARNING, "Odczyt aukcji przed anulowaniem nie powiódł się", err);
                        onMain(() -> result.complete(CancelOutcome.NOT_FOUND));
                        return;
                    }
                    AuctionListing l = opt.orElse(null);
                    if (l == null) {
                        onMain(() -> result.complete(CancelOutcome.NOT_FOUND));
                        return;
                    }
                    if (!l.sellerUuid().equals(sellerId)) {
                        onMain(() -> result.complete(CancelOutcome.NOT_OWNER));
                        return;
                    }
                    if (l.state() != ListingState.ACTIVE) {
                        onMain(() -> result.complete(CancelOutcome.NOT_ACTIVE));
                        return;
                    }
                    hexCore.async(() -> listings.cancelActiveWithClaimTx(
                                    listingId, sellerId, l.itemBlob(),
                                    "auction-cancelled-" + listingId, System.currentTimeMillis()))
                            .whenComplete((claimOpt, txErr) -> {
                                if (txErr != null) {
                                    logger.log(Level.WARNING, "Transakcja anulowania aukcji nie powiodła się", txErr);
                                    onMain(() -> result.complete(CancelOutcome.NOT_ACTIVE));
                                    return;
                                }
                                if (claimOpt.isPresent()) {
                                    audit.log(audit.builder()
                                            .actor(sellerId, seller.getName())
                                            .action(AuditAction.AUCTION_LISTING_CANCELLED)
                                            .market(AuditAction.MARKET_AUCTION)
                                            .listingId(listingId)
                                            .result(AuditAction.RESULT_OK));
                                }
                                onMain(() -> result.complete(claimOpt.isPresent()
                                        ? CancelOutcome.OK : CancelOutcome.NOT_ACTIVE));
                            });
                });
        return result;
    }

    // ---------------------------------------------------------------- listings / claims views

    public CompletableFuture<List<AuctionListing>> listActive(int limit, int offset) {
        return hexCore.async(() -> listings.findActive(limit, offset))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie aktywnych aukcji nie powiodło się", ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<AuctionListing>> listActive(int limit, int offset, SortMode sort) {
        return hexCore.async(() -> listings.findActiveSorted(limit, offset, sort))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie posortowanych aukcji nie powiodło się", ex);
                    return List.of();
                });
    }

    public CompletableFuture<Integer> countActive() {
        return hexCore.async(listings::countActive)
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Zliczenie aktywnych aukcji nie powiodło się", ex);
                    return 0;
                });
    }

    public enum SortMode { NEWEST, PRICE_ASC, PRICE_DESC }

    public CompletableFuture<List<AuctionListing>> listMine(UUID seller) {
        return hexCore.async(() -> listings.findActiveBySeller(seller))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie własnych aukcji nie powiodło się", ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<AuctionListing>> listMine(UUID seller, int limit, int offset) {
        return hexCore.async(() -> listings.findActiveBySeller(seller, limit, offset))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie strony własnych aukcji nie powiodło się", ex);
                    return List.of();
                });
    }

    public CompletableFuture<Integer> countMine(UUID seller) {
        return hexCore.async(() -> listings.countActiveBySeller(seller))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Zliczenie własnych aukcji nie powiodło się", ex);
                    return 0;
                });
    }

    public CompletableFuture<List<AuctionClaim>> listClaims(UUID owner, int limit) {
        return hexCore.async(() -> claims.findPendingByOwner(owner, limit))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie odbiorów nie powiodło się", ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<AuctionClaim>> listClaims(UUID owner, int limit, int offset) {
        return hexCore.async(() -> claims.findPendingByOwner(owner, limit, offset))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Pobranie strony odbiorów nie powiodło się", ex);
                    return List.of();
                });
    }

    public CompletableFuture<Integer> countClaims(UUID owner) {
        return hexCore.async(() -> claims.countPendingByOwner(owner))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Zliczenie odbiorów nie powiodło się", ex);
                    return 0;
                });
    }

    /**
     * Safe claim payout:
     *  1. atomic PENDING -&gt; CLAIMING (lock)
     *  2. deposit / give item
     *  3. on success DELETE the row
     *  4. on any failure UPDATE state back to PENDING so the claim survives
     */
    public CompletableFuture<ClaimOutcome> consumeClaim(Player player, long claimId) {
        UUID owner = player.getUniqueId();
        String name = player.getName();
        CompletableFuture<ClaimOutcome> result = new CompletableFuture<>();

        hexCore.async(() -> claims.tryReserve(claimId, owner))
                .whenComplete((reserved, reserveErr) -> {
                    if (reserveErr != null) {
                        onMain(() -> result.complete(ClaimOutcome.DB_FAILED));
                        return;
                    }
                    AuctionClaim claim = reserved == null ? null : reserved.orElse(null);
                    if (claim == null) {
                        onMain(() -> result.complete(ClaimOutcome.NOT_AVAILABLE));
                        return;
                    }
                    if (claim.isMoney()) {
                        payoutMoney(claimId, owner, name, claim, result);
                    } else if (claim.isItem()) {
                        payoutItem(claimId, owner, player, claim, result);
                    } else {
                        // Zdegenerowany claim (nic do wypłaty): usuń, by nie wracał. Przy błędzie delete
                        // CZEKAMY na rollback -> PENDING (retry); nieudany rollback -> krytyczne.
                        hexCore.async(() -> claims.delete(claimId, owner))
                                .whenComplete((ok, err) -> {
                                    if (err == null && ok != null && ok) {
                                        onMain(() -> result.complete(ClaimOutcome.OK));
                                        return;
                                    }
                                    rollbackClaim(claimId, owner).whenComplete((rolled, rbErr) -> onMain(() -> {
                                        if (rbErr == null && Boolean.TRUE.equals(rolled)) {
                                            result.complete(ClaimOutcome.DB_FAILED);   // retry możliwy
                                        } else {
                                            logger.severe("ODBIÓR: rollback zdegenerowanego claim-a nie powiódł "
                                                    + "się, claim id " + claimId + " - wymagana ręczna korekta");
                                            result.complete(ClaimOutcome.COMPENSATION_FAILED);
                                        }
                                    }));
                                });
                    }
                });
        return result;
    }

    private void payoutMoney(long claimId, UUID owner, String name,
                              AuctionClaim claim, CompletableFuture<ClaimOutcome> result) {
        economy.deposit(owner, name, claim.moneyAmount(), "auction-claim-" + claimId)
                .whenComplete((depo, err) -> {
                    if (err != null || depo == null || !depo.success()) {
                        logger.warning("ODBIÓR: wypłata środków z claim-a nie powiodła się dla " + owner
                                + " kwota=" + claim.moneyAmount());
                        // Czekamy na potwierdzony rollback CLAIMING->PENDING. Dopiero wtedy ECONOMY_FAILED
                        // (gracz może spróbować ponownie). Gdy rollback padnie -> claim zostaje w CLAIMING
                        // (bez ryzyka podwójnej wypłaty) -> COMPENSATION_FAILED + SEVERE do ręcznej korekty.
                        rollbackClaim(claimId, owner).whenComplete((rolled, rbErr) -> onMain(() -> {
                            if (rbErr == null && Boolean.TRUE.equals(rolled)) {
                                result.complete(ClaimOutcome.ECONOMY_FAILED);
                            } else {
                                logger.severe("ODBIÓR: rollback claim-a pieniężnego po nieudanej wypłacie "
                                        + "nie powiódł się, claim id " + claimId
                                        + " - wynik techniczny (wymagana ręczna korekta)");
                                result.complete(ClaimOutcome.COMPENSATION_FAILED);
                            }
                        }));
                        return;
                    }
                    hexCore.async(() -> claims.delete(claimId, owner))
                            .whenComplete((ok, dErr) -> {
                                if (dErr != null || ok == null || !ok) {
                                    // Could not delete - economy paid out though. To avoid double pay
                                    // we DO NOT roll back. Operator will see the leftover CLAIMING row.
                                    logger.severe("ODBIÓR: usunięcie claim-a po udanej wypłacie środków nie "
                                            + "powiodło się, claim id " + claimId
                                            + " pozostaje w CLAIMING (wymagana ręczna korekta)");
                                }
                                onMain(() -> result.complete(ClaimOutcome.OK));
                            });
                });
    }

    /**
     * Wypłata przedmiotu jest all-or-nothing po stronie ekwipunku - przez {@link InventoryFit#tryAddFull}
     * (pełny tri-state, punkt przeglądu). Mapowanie:
     *  - {@code ADDED_FULLY}     -> całość dodana; USUŃ claim. Gdy delete padnie, claim zostaje w CLAIMING
     *    (NIGDY z powrotem na PENDING po wydaniu przedmiotu) + SEVERE do ręcznej korekty;
     *  - {@code NOT_FIT_REVERTED}-> ekwipunek bezpiecznie przywrócony; śledzony rollback CLAIMING->PENDING,
     *    z oczekiwaniem. Dopiero po UDANYM rollbacku zwróć {@code INVENTORY_FULL}. Gdy rollback padnie,
     *    NIE meldujemy fałszywie "ekwipunek pełny" - wynik {@code COMPENSATION_FAILED} + log admina;
     *  - {@code STATE_UNCERTAIN} -> część przedmiotu mogła już leżeć w ekwipunku. NIE usuwamy i NIE cofamy
     *    claim-a (zostaje w CLAIMING -> brak ponownej wypłaty -> brak duplikacji). Wynik
     *    {@code COMPENSATION_FAILED}, rate-limitowany SEVERE, krytyczny komunikat - ręczna korekta.
     */
    private void payoutItem(long claimId, UUID owner, Player player,
                             AuctionClaim claim, CompletableFuture<ClaimOutcome> result) {
        onMain(() -> {
            ItemStack item;
            try {
                item = ItemSerializer.deserialize(claim.itemBlob());
            } catch (Throwable t) {
                logger.log(Level.WARNING, "ODBIÓR: nie można odczytać przedmiotu claim-a " + claimId, t);
                rollbackThenComplete(claimId, owner, ClaimOutcome.DB_FAILED,
                        "przedmiot nieodczytywalny", result);
                return;
            }
            if (item == null) {
                rollbackThenComplete(claimId, owner, ClaimOutcome.DB_FAILED,
                        "przedmiot null", result);
                return;
            }
            InventoryFit.Result addResult = InventoryFit.tryAddFull(player, item);
            resolveItemPayout(addResult,
                    () -> hexCore.async(() -> claims.delete(claimId, owner)),
                    () -> rollbackClaim(claimId, owner),
                    () -> logger.severe("ODBIÓR: usunięcie claim-a po wydaniu przedmiotu nie powiodło się, "
                            + "claim id " + claimId + " pozostaje w CLAIMING (wymagana ręczna korekta)"),
                    () -> logger.severe("ODBIÓR: rollback claim-a po pełnym ekwipunku nie powiódł się, "
                            + "claim id " + claimId + " - wynik techniczny (wymagana ręczna korekta)"),
                    () -> logSevereClaimUncertain(claimId, owner))
                    .whenComplete((outcome, e) -> onMain(() -> result.complete(
                            (e != null || outcome == null) ? ClaimOutcome.COMPENSATION_FAILED : outcome)));
        });
    }

    /**
     * Czyste/asynchroniczne mapowanie tri-state ekwipunku na terminalny wynik odbioru claim-a (testowalne):
     *  - {@code ADDED_FULLY}      -> deleteClaim(); ZAWSZE OK (delete-fail -> onDeleteFailed, claim zostaje
     *    CLAIMING - NIGDY z powrotem na PENDING po wydaniu przedmiotu);
     *  - {@code NOT_FIT_REVERTED} -> rollbackClaim() i OCZEKANIE; ok -> INVENTORY_FULL, błąd ->
     *    onRollbackFailed + COMPENSATION_FAILED (nie fałszywe "ekwipunek pełny");
     *  - {@code STATE_UNCERTAIN}  -> onUncertain; COMPENSATION_FAILED; NIE woła deleteClaim ani rollbackClaim
     *    (claim zostaje w CLAIMING -> brak ponownej wypłaty -> brak duplikacji).
     * Future zawsze kończy się terminalnie.
     */
    public static CompletableFuture<ClaimOutcome> resolveItemPayout(
            InventoryFit.Result addResult,
            java.util.function.Supplier<CompletableFuture<Boolean>> deleteClaim,
            java.util.function.Supplier<CompletableFuture<Boolean>> rollbackClaim,
            Runnable onDeleteFailed,
            Runnable onRollbackFailed,
            Runnable onUncertain) {
        switch (addResult) {
            case ADDED_FULLY: {
                CompletableFuture<Boolean> del;
                try {
                    CompletableFuture<Boolean> f = deleteClaim.get();
                    del = f == null ? CompletableFuture.completedFuture(false) : f.exceptionally(ex -> false);
                } catch (Throwable t) {
                    del = CompletableFuture.completedFuture(false);
                }
                return del.thenApply(ok -> {
                    if (!Boolean.TRUE.equals(ok)) {
                        onDeleteFailed.run();          // przedmiot już wydany, delete padł -> zostaje CLAIMING
                    }
                    return ClaimOutcome.OK;
                });
            }
            case NOT_FIT_REVERTED: {
                CompletableFuture<Boolean> rb;
                try {
                    CompletableFuture<Boolean> f = rollbackClaim.get();
                    rb = f == null ? CompletableFuture.completedFuture(false) : f.exceptionally(ex -> false);
                } catch (Throwable t) {
                    rb = CompletableFuture.completedFuture(false);
                }
                return rb.thenApply(ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        return ClaimOutcome.INVENTORY_FULL;
                    }
                    onRollbackFailed.run();
                    return ClaimOutcome.COMPENSATION_FAILED;
                });
            }
            case STATE_UNCERTAIN:
            default:
                onUncertain.run();
                return CompletableFuture.completedFuture(ClaimOutcome.COMPENSATION_FAILED);
        }
    }

    /** Śledzony rollback CLAIMING -&gt; PENDING. Zwraca true tylko przy potwierdzonym cofnięciu. */
    private CompletableFuture<Boolean> rollbackClaim(long claimId, UUID owner) {
        return hexCore.async(() -> claims.rollback(claimId, owner))
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "ODBIÓR: rollback claim-a nie powiódł się dla " + claimId, ex);
                    return false;
                });
    }

    /**
     * Cofnij claim CLAIMING-&gt;PENDING i domknij wynik dopiero po POTWIERDZONYM rollbacku:
     * rollback OK -&gt; {@code retryFail} (claim wrócił, można spróbować ponownie); rollback nieudany -&gt;
     * claim zostaje w CLAIMING, {@code COMPENSATION_FAILED} + SEVERE do ręcznej korekty.
     */
    private void rollbackThenComplete(long claimId, UUID owner, ClaimOutcome retryFail,
                                      String failContext, CompletableFuture<ClaimOutcome> result) {
        rollbackClaim(claimId, owner).whenComplete((rolled, rbErr) -> onMain(() -> {
            if (rbErr == null && Boolean.TRUE.equals(rolled)) {
                result.complete(retryFail);
            } else {
                logger.severe("ODBIÓR: rollback claim-a nie powiódł się (" + failContext + "), claim id "
                        + claimId + " - wymagana ręczna korekta");
                result.complete(ClaimOutcome.COMPENSATION_FAILED);
            }
        }));
    }

    private final java.util.concurrent.atomic.AtomicLong lastClaimUncertainLogAt =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /**
     * Rate-limitowany (60s) SEVERE dla niepewnego stanu ekwipunku przy odbiorze claim-a.
     * Zawiera claim-id i UUID gracza, ale BEZ sekretów i BEZ pełnych danych NBT.
     */
    private void logSevereClaimUncertain(long claimId, UUID owner) {
        long now = System.currentTimeMillis();
        long last = lastClaimUncertainLogAt.get();
        if (now - last >= 60_000L && lastClaimUncertainLogAt.compareAndSet(last, now)) {
            logger.severe("ODBIÓR CLAIM: niepewny stan ekwipunku - claim=" + claimId
                    + " gracz=" + owner
                    + " - claim pozostaje w stanie CLAIMING; wymagana ręczna kontrola (bez danych NBT)");
        }
    }

    // ---------------------------------------------------------------- expiry

    /**
     * Forcibly expire all overdue ACTIVE listings and atomically create
     * item claims for the sellers.
     */
    public CompletableFuture<Integer> expireDueListings(int batchSize) {
        return hexCore.async(() -> {
            long now = System.currentTimeMillis();
            listings.releaseStaleReservations(now);
            List<AuctionListing> due = listings.findExpired(now, batchSize);
            if (due.isEmpty()) return 0;
            // Audytujemy WYŁĄCZNIE aukcje faktycznie przestawione na EXPIRED (nie te w międzyczasie
            // kupione/anulowane) - repo zwraca rzeczywiście przetworzone obiekty.
            List<AuctionListing> expired = listings.expireBatchWithClaimsTx(due, now);
            int processed = expired.size();
            if (processed > 0) {
                audit.log(audit.builder()
                        .action(AuditAction.AUCTION_CLEANUP)
                        .market(AuditAction.MARKET_AUCTION)
                        .amount((long) processed)
                        .result(AuditAction.RESULT_OK));
                for (AuctionListing l : expired) {
                    audit.log(audit.builder()
                            .actor(l.sellerUuid(), l.sellerName())
                            .action(AuditAction.AUCTION_LISTING_EXPIRED)
                            .market(AuditAction.MARKET_AUCTION)
                            .listingId(l.id())
                            .result(AuditAction.RESULT_OK));
                }
            }
            return processed;
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Skanowanie wygasłych aukcji nie powiodło się", ex);
            return 0;
        });
    }
}
