package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.ItemSerializer;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * GUI potwierdzenia zakupu aukcji. Wszystkie widoczne teksty pochodza
 * z messages.yml aby zachowac konfigurowalnosc i Polski jezyk.
 */
public final class AuctionConfirmGui {

    public static void open(Plugin plugin, Player player, long listingId,
                            Supplier<AuctionConfig> cfg, AuctionService service,
                            EconomyBridge economy, MessageFactory messages) {
        service.findByIdAsync(listingId).thenAccept(opt ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    AuctionListing target = opt.orElse(null);
                    if (target == null) {
                        messages.send(player, "auction.listing-not-found",
                                placeholders("id", String.valueOf(listingId)));
                        return;
                    }
                    // Nie otwieramy okna zakupu dla własnej aukcji.
                    if (target.sellerUuid().equals(player.getUniqueId())) {
                        messages.send(player, "auction.own-listing");
                        return;
                    }
                    render(plugin, player, target, cfg.get(), service, economy, messages);
                })
        );
    }

    private static void render(Plugin plugin, Player player, AuctionListing l, AuctionConfig cfg,
                               AuctionService service, EconomyBridge economy, MessageFactory messages) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_CONFIRM_BUY);
        Inventory inv = Bukkit.createInventory(holder, 27, LegacyFormat.component(cfg.confirmTitle()));
        holder.bindInventory(inv);
        holder.putState("listingId", l.id());

        ItemStack item = ItemSerializer.deserialize(l.itemBlob());
        if (item == null) {
            item = new ItemStack(Material.BARRIER);
        }
        inv.setItem(13, item);

        ItemStack confirm = withName(Material.LIME_WOOL,
                messages.raw("auction.gui.confirm-buy-button",
                        placeholders("price", economy.format(l.price()))));
        ItemStack cancel = withName(Material.RED_WOOL,
                messages.raw("auction.gui.confirm-cancel-button", null));
        inv.setItem(11, confirm);
        inv.setItem(15, cancel);

        holder.setSlotAction(11, ctx -> {
            Long id = holder.state("listingId");
            ctx.player().closeInventory();
            if (id == null) return;
            service.buy(ctx.player(), id).thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                switch (res.outcome()) {
                    // Sukces WYŁĄCZNIE dla faktycznie dostarczonego/odłożonego przedmiotu.
                    case OK_DELIVERED -> messages.send(ctx.player(), "auction.bought",
                            placeholders("price", economy.format(res.pricePaid())));
                    case OK_ITEM_CLAIMED -> messages.send(ctx.player(), "auction.bought-claimed",
                            placeholders("price", economy.format(res.pricePaid())));
                    case REFUNDED -> messages.send(ctx.player(), "auction.buy-refunded");
                    case REFUND_PENDING -> messages.send(ctx.player(), "auction.buy-refund-pending");
                    case COMPENSATION_FAILED -> messages.send(ctx.player(), "auction.buy-compensation-failed");
                    case NOT_ENOUGH_MONEY -> messages.send(ctx.player(), "auction.not-enough-money");
                    case OWN_LISTING -> messages.send(ctx.player(), "auction.own-listing");
                    case ECONOMY_UNAVAILABLE -> messages.send(ctx.player(), "common.economy-missing");
                    case ECONOMY_ERROR -> messages.send(ctx.player(), "common.economy-error");
                    case FEATURE_DISABLED -> messages.send(ctx.player(), "common.feature-disabled");
                    case NO_PERMISSION -> messages.send(ctx.player(), "common.no-permission");
                    case NOT_ACTIVE -> messages.send(ctx.player(), "auction.listing-not-active");
                    case DB_FAILED -> messages.send(ctx.player(), "common.db-error");
                }
            }));
        });
        holder.setSlotAction(15, ctx -> ctx.player().closeInventory());

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.BLACK_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static ItemStack withName(Material material, String name) {
        ItemStack s = new ItemStack(material);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(name));
            s.setItemMeta(meta);
        }
        return s;
    }
}
