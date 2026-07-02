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
import hex.auctionbazaar.util.InventoryFit;
import hex.auctionbazaar.util.ItemSerializer;
import hex.economy.api.EconomyResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

    public enum SellResult { OK, NO_ITEM, INVALID_PRICE, TOO_MANY, ECONOMY_FAILED, DB_FAILED }
    public enum BuyOutcome { OK, NOT_ACTIVE, NOT_ENOUGH_MONEY, DB_FAILED, ECONOMY_UNAVAILABLE, OWN_LISTING }
    public enum CancelOutcome { OK, NOT_FOUND, NOT_OWNER, NOT_ACTIVE }
    public enum ClaimOutcome { OK, NOT_AVAILABLE, ECONOMY_FAILED, DB_FAILED, INVENTORY_FULL }

    public record SellOutcome(SellResult result, Long listingId, String error) {
        public static SellOutcome ok(long id) { return new SellOutcome(SellResult.OK, id, null); }
        public static SellOutcome fail(SellResult r, String err) { return new SellOutcome(r, null, err); }
    }

    public record BuyResult(BuyOutcome outcome, AuctionListing listing, BigDecimal pricePaid) {
        public static BuyResult ok(AuctionListing l) { return new BuyResult(BuyOutcome.OK, l, l.price()); }
        public static BuyResult fail(BuyOutcome o) { return new BuyResult(o, null, null); }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final EconomyBridge economy;
    private final AuctionListingRepository listings;
    private final AuctionClaimRepository claims;
    private final AuditService audit;
    private final Supplier<AuctionConfig> configSupplier;

    public AuctionService(Plugin plugin,
                          HexCoreBridge hexCore,
                          EconomyBridge economy,
                          AuctionListingRepository listings,
                          AuctionClaimRepository claims,
                          AuditService audit,
                          Supplier<AuctionConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.listings = Objects.requireNonNull(listings, "listings");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    // ---------------------------------------------------------------- helpers

    private void onMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private <T> CompletableFuture<T> failOnMain(String label, Throwable err) {
        logger.log(Level.WARNING, "auction " + label + " failed", err);
        return CompletableFuture.failedFuture(err);
    }

    public CompletableFuture<Optional<AuctionListing>> findByIdAsync(long id) {
        return hexCore.async(() -> listings.findById(id))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "findById failed for " + id, ex);
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
    public CompletableFuture<SellOutcome> sellItemInHand(Player seller, BigDecimal price) {
        AuctionConfig cfg = configSupplier.get();
        if (!cfg.enabled()) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.NO_ITEM, "auction disabled"));
        }
        if (!cfg.priceInRange(price)) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.INVALID_PRICE, "price out of range"));
        }
        ItemStack inHand = seller.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == Material.AIR || inHand.getAmount() <= 0) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.NO_ITEM, "no item in hand"));
        }
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.ECONOMY_FAILED, "economy unavailable"));
        }

        UUID sellerId = seller.getUniqueId();
        String sellerName = seller.getName();
        ItemStack snapshot = inHand.clone();

        CompletableFuture<SellOutcome> result = new CompletableFuture<>();
        hexCore.async(() -> listings.countActiveBySeller(sellerId))
                .whenComplete((count, err) -> {
                    if (err != null) {
                        onMain(() -> result.complete(SellOutcome.fail(SellResult.DB_FAILED, err.getMessage())));
                        return;
                    }
                    if (count >= cfg.maxActiveListingsPerPlayer()) {
                        onMain(() -> result.complete(SellOutcome.fail(SellResult.TOO_MANY,
                                "limit " + cfg.maxActiveListingsPerPlayer())));
                        return;
                    }
                    onMain(() -> {
                        ItemStack current = seller.getInventory().getItemInMainHand();
                        if (current == null || !current.isSimilar(snapshot)
                                || current.getAmount() < snapshot.getAmount()) {
                            result.complete(SellOutcome.fail(SellResult.NO_ITEM, "item changed"));
                            return;
                        }
                        ItemStack remove = snapshot.clone();
                        seller.getInventory().removeItem(remove);
                        chargeListingFeeThenInsert(seller, sellerId, sellerName, snapshot, price, cfg, result);
                    });
                });
        return result;
    }

    private void chargeListingFeeThenInsert(Player seller, UUID sellerId, String sellerName,
                                            ItemStack snapshot, BigDecimal price, AuctionConfig cfg,
                                            CompletableFuture<SellOutcome> result) {
        BigDecimal fee = cfg.listingFee() == null ? BigDecimal.ZERO : cfg.listingFee();
        CompletableFuture<EconomyResult> feeChain = fee.signum() <= 0
                ? CompletableFuture.completedFuture(EconomyResult.ok(BigDecimal.ZERO))
                : economy.withdraw(sellerId, sellerName, fee, "auction-listing-fee");

        feeChain.whenComplete((feeRes, feeErr) -> {
            if (feeErr != null || feeRes == null || !feeRes.success()) {
                onMain(() -> {
                    refundItemOrClaim(seller, sellerId, snapshot, "listing-refund");
                    result.complete(SellOutcome.fail(SellResult.ECONOMY_FAILED, "listing fee declined"));
                });
                return;
            }
            byte[] blob;
            try {
                blob = ItemSerializer.serialize(snapshot);
            } catch (Throwable t) {
                onMain(() -> {
                    refundItemOrClaim(seller, sellerId, snapshot, "listing-refund");
                    refundFee(sellerId, sellerName, fee, "auction-listing-fee-refund");
                    result.complete(SellOutcome.fail(SellResult.DB_FAILED, "serialize"));
                });
                return;
            }
            long now = System.currentTimeMillis();
            long expiresAt = now + cfg.defaultDurationSeconds() * 1000L;
            hexCore.async(() -> listings.insert(sellerId, sellerName, blob,
                            snapshot.getType().name(), snapshot.getAmount(), price, now, expiresAt))
                    .whenComplete((id, insertErr) -> {
                        if (insertErr != null || id == null) {
                            logger.log(Level.WARNING, "auction insert failed", insertErr);
                            onMain(() -> {
                                refundItemOrClaim(seller, sellerId, snapshot, "listing-refund");
                                refundFee(sellerId, sellerName, fee, "auction-listing-fee-refund");
                                result.complete(SellOutcome.fail(SellResult.DB_FAILED,
                                        insertErr == null ? "no id" : insertErr.getMessage()));
                            });
                            return;
                        }
                        audit.log(audit.builder()
                                .actor(sellerId, sellerName)
                                .action(AuditAction.AUCTION_LISTING_CREATED)
                                .market(AuditAction.MARKET_AUCTION)
                                .listingId(id)
                                .unitPrice(price)
                                .total(price)
                                .result(AuditAction.RESULT_OK));
                        onMain(() -> result.complete(SellOutcome.ok(id)));
                    });
        });
    }

    /**
     * Main-thread refund: adds back to inventory or, if offline / overflow,
     * asynchronously creates an item claim. Never drops in the world.
     */
    private void refundItemOrClaim(Player seller, UUID sellerId, ItemStack item, String reason) {
        if (seller != null && seller.isOnline()) {
            var leftover = seller.getInventory().addItem(item.clone());
            for (ItemStack rest : leftover.values()) {
                claimItemAsync(sellerId, rest, reason, null);
            }
            return;
        }
        claimItemAsync(sellerId, item, reason, null);
    }

    private void claimItemAsync(UUID owner, ItemStack item, String reason, Long listingId) {
        if (owner == null || item == null) return;
        final byte[] blob;
        try {
            blob = ItemSerializer.serialize(item);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not serialize for claim", t);
            return;
        }
        hexCore.async(() -> claims.insertItem(owner, blob, reason, listingId, System.currentTimeMillis()))
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "claim insert failed for " + owner + " reason=" + reason, ex);
                    return -1L;
                });
    }

    private void refundFee(UUID uuid, String name, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) return;
        economy.deposit(uuid, name, amount, reason).exceptionally(ex -> {
            logger.log(Level.WARNING, "fee refund failed for " + uuid, ex);
            return null;
        });
    }

    // ---------------------------------------------------------------- buy

    public CompletableFuture<BuyResult> buy(Player buyer, long listingId) {
        if (!economy.isAvailable()) {
            return CompletableFuture.completedFuture(BuyResult.fail(BuyOutcome.ECONOMY_UNAVAILABLE));
        }
        AuctionConfig cfg = configSupplier.get();
        UUID buyerId = buyer.getUniqueId();
        String buyerName = buyer.getName();
        long now = System.currentTimeMillis();
        long reservedUntil = now + cfg.reservationTtlSeconds() * 1000L;

        CompletableFuture<BuyResult> result = new CompletableFuture<>();
        hexCore.async(() -> listings.findById(listingId))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        onMain(() -> result.complete(BuyResult.fail(BuyOutcome.DB_FAILED)));
                        return;
                    }
                    AuctionListing l = opt.orElse(null);
                    if (l == null || l.state() != ListingState.ACTIVE) {
                        onMain(() -> result.complete(BuyResult.fail(BuyOutcome.NOT_ACTIVE)));
                        return;
                    }
                    if (l.sellerUuid().equals(buyerId)) {
                        onMain(() -> result.complete(BuyResult.fail(BuyOutcome.OWN_LISTING)));
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
                                                hexCore.asyncRun(() -> listings.releaseReservation(listingId, buyerId))
                                                        .exceptionally(ex -> {
                                                            logger.log(Level.SEVERE,
                                                                    "release after withdraw fail failed", ex);
                                                            return null;
                                                        });
                                                onMain(() -> result.complete(
                                                        BuyResult.fail(BuyOutcome.NOT_ENOUGH_MONEY)));
                                                return;
                                            }
                                            finalizePurchase(buyer, buyerId, l, cfg, result);
                                        });
                            });
                });
        return result;
    }

    private void finalizePurchase(Player buyer, UUID buyerId, AuctionListing l,
                                  AuctionConfig cfg, CompletableFuture<BuyResult> result) {
        long now = System.currentTimeMillis();
        BigDecimal feePct = cfg.saleFeePercent() == null ? BigDecimal.ZERO : cfg.saleFeePercent();
        BigDecimal net = l.price()
                .multiply(BigDecimal.ONE.subtract(feePct.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal price = l.price();
        String buyerName = buyer.getName();
        UUID sellerId = l.sellerUuid();
        long listingId = l.id();

        hexCore.async(() -> listings.markSoldWithSellerClaimTx(
                        listingId, buyerId, now, sellerId, net, "auction-sold-" + listingId))
                .whenComplete((claimIdOpt, txErr) -> {
                    if (txErr != null || claimIdOpt == null || claimIdOpt.isEmpty()) {
                        // Refund the buyer (full price) - reservation is gone because tx failed.
                        economy.deposit(buyerId, buyerName, price, "auction-buy-refund-" + listingId)
                                .exceptionally(ex -> {
                                    logger.log(Level.SEVERE,
                                            "buyer refund failed after sold-tx failure for listing "
                                                    + listingId, ex);
                                    return null;
                                });
                        hexCore.asyncRun(() -> listings.releaseReservation(listingId, buyerId));
                        onMain(() -> result.complete(BuyResult.fail(BuyOutcome.DB_FAILED)));
                        return;
                    }
                    onMain(() -> {
                        ItemStack item = null;
                        try {
                            item = ItemSerializer.deserialize(l.itemBlob());
                        } catch (Throwable t) {
                            logger.log(Level.WARNING, "could not deserialize listing item " + listingId, t);
                        }
                        if (item == null) {
                            // Buyer paid but item is unreadable: store the money as a recovery claim.
                            hexCore.async(() -> claims.insertMoney(buyerId, price,
                                    "auction-item-recovery-" + listingId, listingId,
                                    System.currentTimeMillis()))
                                    .exceptionally(ex -> {
                                        logger.log(Level.SEVERE,
                                                "recovery money claim insert failed for buyer "
                                                        + buyerId, ex);
                                        return -1L;
                                    });
                            result.complete(BuyResult.ok(l));
                            return;
                        }
                        var leftover = buyer.getInventory().addItem(item);
                        for (ItemStack rest : leftover.values()) {
                            claimItemAsync(buyerId, rest,
                                    "auction-buy-overflow-" + listingId, listingId);
                        }
                        audit.log(audit.builder()
                                .actor(buyerId, buyer.getName())
                                .action(AuditAction.AUCTION_LISTING_BOUGHT)
                                .market(AuditAction.MARKET_AUCTION)
                                .listingId(listingId)
                                .unitPrice(price)
                                .total(price)
                                .result(AuditAction.RESULT_OK));
                        result.complete(BuyResult.ok(l));
                    });
                });
    }

    // ---------------------------------------------------------------- cancel

    public CompletableFuture<CancelOutcome> cancel(Player seller, long listingId) {
        UUID sellerId = seller.getUniqueId();
        CompletableFuture<CancelOutcome> result = new CompletableFuture<>();

        hexCore.async(() -> listings.findById(listingId))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        logger.log(Level.WARNING, "cancel findById failed", err);
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
                                    logger.log(Level.WARNING, "cancel tx failed", txErr);
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
                    logger.log(Level.WARNING, "listActive failed", ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<AuctionListing>> listActive(int limit, int offset, SortMode sort) {
        return hexCore.async(() -> listings.findActiveSorted(limit, offset, sort))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "listActiveSorted failed", ex);
                    return List.of();
                });
    }

    public CompletableFuture<Integer> countActive() {
        return hexCore.async(listings::countActive)
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "countActive failed", ex);
                    return 0;
                });
    }

    public enum SortMode { NEWEST, PRICE_ASC, PRICE_DESC }

    public CompletableFuture<List<AuctionListing>> listMine(UUID seller) {
        return hexCore.async(() -> listings.findActiveBySeller(seller))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "listMine failed", ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<AuctionClaim>> listClaims(UUID owner, int limit) {
        return hexCore.async(() -> claims.findPendingByOwner(owner, limit))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "listClaims failed", ex);
                    return List.of();
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
                        // Degenerate: nothing to pay. Just delete so it stops re-appearing.
                        hexCore.async(() -> claims.delete(claimId, owner))
                                .whenComplete((ok, err) -> {
                                    if (err != null || ok == null || !ok) {
                                        rollbackClaim(claimId, owner);
                                    }
                                    onMain(() -> result.complete(ClaimOutcome.OK));
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
                        logger.warning("money claim payout failed for " + owner + " amount=" + claim.moneyAmount());
                        rollbackClaim(claimId, owner);
                        onMain(() -> result.complete(ClaimOutcome.ECONOMY_FAILED));
                        return;
                    }
                    hexCore.async(() -> claims.delete(claimId, owner))
                            .whenComplete((ok, dErr) -> {
                                if (dErr != null || ok == null || !ok) {
                                    // Could not delete - economy paid out though. To avoid double pay
                                    // we DO NOT roll back. Operator will see the leftover CLAIMING row.
                                    logger.severe("claim delete failed after successful deposit, claim id "
                                            + claimId + " stays in CLAIMING (manual cleanup needed)");
                                }
                                onMain(() -> result.complete(ClaimOutcome.OK));
                            });
                });
    }

    /**
     * Item payout is all-or-nothing on the inventory side.
     *
     * Why: the previous behaviour added items first, then tried to spill the
     * remainder into a new claim. If the remainder insert failed it tried to
     * give the items back, which could duplicate them and leaves an
     * inconsistent claim row. Here we use {@link InventoryFit#tryAddFullOrRevert}
     * to either place every unit of the stack or none at all. Only after the
     * inventory has actually accepted the stack do we delete the claim row.
     */
    private void payoutItem(long claimId, UUID owner, Player player,
                             AuctionClaim claim, CompletableFuture<ClaimOutcome> result) {
        onMain(() -> {
            ItemStack item;
            try {
                item = ItemSerializer.deserialize(claim.itemBlob());
            } catch (Throwable t) {
                logger.log(Level.WARNING, "could not deserialize claim item " + claimId, t);
                rollbackClaim(claimId, owner);
                result.complete(ClaimOutcome.DB_FAILED);
                return;
            }
            if (item == null) {
                rollbackClaim(claimId, owner);
                result.complete(ClaimOutcome.DB_FAILED);
                return;
            }
            boolean placed = InventoryFit.tryAddFullOrRevert(player, item);
            if (!placed) {
                // Nothing was placed in the inventory. Put the claim back to
                // PENDING and let the player free up space and try again.
                rollbackClaim(claimId, owner);
                result.complete(ClaimOutcome.INVENTORY_FULL);
                return;
            }
            hexCore.async(() -> claims.delete(claimId, owner))
                    .whenComplete((ok, dErr) -> {
                        if (dErr != null || ok == null || !ok) {
                            // Items already sit in the inventory; we cannot
                            // safely take them back. Log and leave the row
                            // (it stays in CLAIMING for manual cleanup).
                            logger.severe("claim delete failed after item payout, claim id "
                                    + claimId + " stays in CLAIMING (manual cleanup needed)");
                        }
                        onMain(() -> result.complete(ClaimOutcome.OK));
                    });
        });
    }

    private void rollbackClaim(long claimId, UUID owner) {
        hexCore.async(() -> claims.rollback(claimId, owner))
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "claim rollback failed for " + claimId, ex);
                    return false;
                });
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
            int processed = listings.expireBatchWithClaimsTx(due, now);
            if (processed > 0) {
                audit.log(audit.builder()
                        .action(AuditAction.AUCTION_CLEANUP)
                        .market(AuditAction.MARKET_AUCTION)
                        .amount((long) processed)
                        .result(AuditAction.RESULT_OK));
                for (AuctionListing l : due) {
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
            logger.log(Level.WARNING, "expire sweep failed", ex);
            return 0;
        });
    }
}
