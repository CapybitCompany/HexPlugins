package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.auction.model.AuctionClaim;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.ClaimReasonTranslator;
import hex.auctionbazaar.util.ItemSerializer;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * GUI odbioru przedmiotow (claims). Wyswietla przyjazne tlumaczenie
 * technicznego powodu claim-u (z messages.yml claim-reasons).
 * Tytul GUI jest teraz "Odbiór przedmiotów" (nie "Odbiór nagród").
 */
public final class AuctionClaimsGui {

    public static void open(Plugin plugin, Player player, Supplier<AuctionConfig> cfg,
                            AuctionService service, EconomyBridge economy, MessageFactory messages) {
        service.listClaims(player.getUniqueId(), 54).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin, () -> render(plugin, player, list, cfg.get(), service, economy, messages))
        );
    }

    private static void render(Plugin plugin, Player player, List<AuctionClaim> list, AuctionConfig cfg,
                               AuctionService service, EconomyBridge economy, MessageFactory messages) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_CLAIMS);
        Inventory inv = Bukkit.createInventory(holder, 54, LegacyFormat.component(cfg.claimsTitle()));
        holder.bindInventory(inv);

        HexAuctionBazaarPlugin main = plugin instanceof HexAuctionBazaarPlugin p ? p : null;
        ClaimReasonTranslator translator = main == null ? null
                : new ClaimReasonTranslator(() -> main.config().messages());

        int slot = 0;
        for (AuctionClaim c : list) {
            if (slot >= 54) break;
            String friendlyReason = translator == null ? c.reason() : translator.friendly(c.reason());
            ItemStack icon;
            if (c.isMoney()) {
                icon = new ItemStack(Material.GOLD_INGOT);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.displayName(LegacyFormat.component(messages.raw("auction.gui.claim-money-name",
                            MessageFactory.placeholders("amount", economy.format(c.moneyAmount())))));
                    List<Component> lore = new ArrayList<>();
                    lore.add(LegacyFormat.component(messages.raw("auction.gui.claim-reason",
                            MessageFactory.placeholders("reason", friendlyReason))));
                    lore.add(LegacyFormat.component(messages.raw("auction.gui.claim-collect-hint", null)));
                    meta.lore(lore);
                    icon.setItemMeta(meta);
                }
            } else {
                ItemStack item = ItemSerializer.deserialize(c.itemBlob());
                if (item == null) item = new ItemStack(Material.BARRIER);
                icon = item;
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    List<Component> lore = new ArrayList<>();
                    lore.add(LegacyFormat.component(messages.raw("auction.gui.claim-reason",
                            MessageFactory.placeholders("reason", friendlyReason))));
                    lore.add(LegacyFormat.component(messages.raw("auction.gui.claim-collect-hint", null)));
                    meta.lore(lore);
                    icon.setItemMeta(meta);
                }
            }
            inv.setItem(slot, icon);
            final long claimId = c.id();
            final AuctionClaim claim = c;
            holder.setSlotAction(slot, ctx -> service.consumeClaim(ctx.player(), claimId)
                    .thenAccept(outcome -> Bukkit.getScheduler().runTask(plugin, () -> {
                        switch (outcome) {
                            case OK -> {
                                messages.send(ctx.player(), "auction.claim-received",
                                        MessageFactory.placeholders(
                                                "what", claim.isMoney()
                                                        ? economy.format(claim.moneyAmount())
                                                        : messages.raw("auction.gui.claim-item-collected", null)));
                                open(plugin, ctx.player(), () -> cfg, service, economy, messages);
                            }
                            case INVENTORY_FULL -> messages.send(ctx.player(), "auction.inventory-full");
                            case NOT_AVAILABLE -> messages.send(ctx.player(), "auction.no-claims");
                            case ECONOMY_FAILED -> messages.send(ctx.player(), "common.economy-missing");
                            case DB_FAILED -> messages.send(ctx.player(), "common.schema-not-ready");
                        }
                    })));
            slot++;
        }
        player.openInventory(inv);
    }
}
