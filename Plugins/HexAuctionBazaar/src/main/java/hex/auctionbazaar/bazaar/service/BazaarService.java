package hex.auctionbazaar.bazaar.service;

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
    public enum SellResult { OK, UNKNOWN_ITEM, SELL_DISABLED, NOT_ENOUGH_ITEMS,
                             DB_FAILED, ECONOMY_UNAVAILABLE, INVALID_QTY }

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
        public static SellOutcome fail(SellResult r) { return new SellOutcome(r, null, null); }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final EconomyBridge economy;
    private final BazaarStockRepository stocks;
    private final AuctionClaimRepository claims;
    private final Supplier<BazaarConfig> configSupplier;
    private final Supplier<Boolean> requirePlainItem;

    public BazaarService(Plugin plugin,
                         HexCoreBridge hexCore,
                         EconomyBridge economy,
                         BazaarStockRepository stocks,
                         AuctionClaimRepository claims,
                         Supplier<BazaarConfig> configSupplier,
                         Supplier<Boolean> requirePlainItem) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.stocks = Objects.requireNonNull(stocks, "stocks");
        this.claims = Objects.requireNonNull(claims, "claims");
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

    // ---------------------------------------------------------------- buy

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

        hexCore.async(() -> stocks.find(itemKey))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        logger.log(Level.WARNING, "bazaar buy find failed", err);
                        onMain(() -> result.complete(BuyOutcome.fail(BuyResult.DB_FAILED)));
                        return;
                    }
                    BazaarStock stock = opt.orElseGet(() -> new BazaarStock(
                            itemKey, item.initialStock(), null, null, System.currentTimeMillis()));
                    if (stock.stock() < amount) {
                        onMain(() -> result.complete(BuyOutcome.fail(BuyResult.NOT_ENOUGH_STOCK)));
                        return;
                    }
                    BazaarPrice price = BazaarPricer.compute(item, cfg.pricing(),
                            stock.stock(), stock.lastBuyPrice(), stock.lastSellPrice());
                    BigDecimal total = price.buyPrice().multiply(new BigDecimal(amount));

                    economy.withdraw(buyerId, buyerName, total, "bazaar-buy-" + itemKey)
                            .whenComplete((wd, wdErr) -> {
                                if (wdErr != null || wd == null || !wd.success()) {
                                    onMain(() -> result.complete(BuyOutcome.fail(BuyResult.NOT_ENOUGH_MONEY)));
                                    return;
                                }
                                hexCore.async(() -> {
                                            long now = System.currentTimeMillis();
                                            return stocks.applyBuyWithLogTx(itemKey, amount,
                                                    price.buyPrice(), buyerId, buyerName, total, now);
                                        })
                                        .whenComplete((stockOk, stockErr) -> {
                                            if (stockErr != null || stockOk == null || !stockOk) {
                                                // Refund the money we already withdrew.
                                                economy.deposit(buyerId, buyerName, total,
                                                                "bazaar-buy-rollback-" + itemKey)
                                                        .exceptionally(ex -> {
                                                            logger.log(Level.SEVERE,
                                                                    "buy rollback deposit failed for " + buyerId, ex);
                                                            // Create money claim instead so funds are not lost.
                                                            hexCore.async(() -> claims.insertMoney(buyerId, total,
                                                                            "bazaar-buy-rollback-claim-" + itemKey,
                                                                            null, System.currentTimeMillis()))
                                                                    .exceptionally(e2 -> {
                                                                        logger.log(Level.SEVERE,
                                                                                "claim insert for buy rollback failed", e2);
                                                                        return -1L;
                                                                    });
                                                            return null;
                                                        });
                                                onMain(() -> result.complete(
                                                        BuyOutcome.fail(BuyResult.NOT_ENOUGH_STOCK)));
                                                return;
                                            }
                                            onMain(() -> giveOrClaim(buyer, item, amount, price, total, result));
                                        });
                            });
                });
        return result;
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

        // Inventory work must run on the main thread.
        boolean plainOnly = Boolean.TRUE.equals(requirePlainItem.get());
        if (!removeFromInventory(seller, item.material(), amount, plainOnly)) {
            return CompletableFuture.completedFuture(SellOutcome.fail(SellResult.NOT_ENOUGH_ITEMS));
        }

        hexCore.async(() -> stocks.find(itemKey))
                .whenComplete((opt, err) -> {
                    if (err != null) {
                        logger.log(Level.WARNING, "bazaar sell find failed", err);
                        onMain(() -> {
                            giveBack(seller, sellerId, item.material(), amount);
                            result.complete(SellOutcome.fail(SellResult.DB_FAILED));
                        });
                        return;
                    }
                    BazaarStock stock = opt.orElseGet(() -> new BazaarStock(
                            itemKey, item.initialStock(), null, null, System.currentTimeMillis()));
                    BazaarPrice price = BazaarPricer.compute(item, cfg.pricing(),
                            stock.stock(), stock.lastBuyPrice(), stock.lastSellPrice());
                    BigDecimal total = price.sellPrice().multiply(new BigDecimal(amount));

                    hexCore.async(() -> {
                                long now = System.currentTimeMillis();
                                return stocks.applySellWithLogTx(itemKey, amount, price.sellPrice(),
                                        sellerId, sellerName, total, now);
                            })
                            .whenComplete((stockOk, stockErr) -> {
                                if (stockErr != null || stockOk == null || !stockOk) {
                                    onMain(() -> {
                                        giveBack(seller, sellerId, item.material(), amount);
                                        result.complete(SellOutcome.fail(SellResult.DB_FAILED));
                                    });
                                    return;
                                }
                                economy.deposit(sellerId, sellerName, total, "bazaar-sell-" + itemKey)
                                        .whenComplete((depo, dErr) -> {
                                            if (dErr != null || depo == null || !depo.success()) {
                                                // Roll back the stock first, then return the items / money claim.
                                                hexCore.asyncRun(() -> stocks.compensateSellRollback(
                                                                itemKey, amount, System.currentTimeMillis()))
                                                        .exceptionally(ex -> {
                                                            logger.log(Level.SEVERE,
                                                                    "sell rollback failed for " + itemKey, ex);
                                                            return null;
                                                        });
                                                onMain(() -> {
                                                    giveBack(seller, sellerId, item.material(), amount);
                                                    result.complete(SellOutcome.fail(SellResult.ECONOMY_UNAVAILABLE));
                                                });
                                                return;
                                            }
                                            onMain(() -> result.complete(SellOutcome.ok(price, total)));
                                        });
                            });
                });
        return result;
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
