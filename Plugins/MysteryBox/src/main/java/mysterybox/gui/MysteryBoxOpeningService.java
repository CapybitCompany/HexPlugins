package mysterybox.gui;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.model.ResolvedReward;
import mysterybox.service.AuditLogService;
import mysterybox.service.ItemFactoryService;
import mysterybox.service.MessageService;
import mysterybox.service.RewardService;
import mysterybox.util.LegacyTextUtil;
import mysterybox.util.PlaceholderUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class MysteryBoxOpeningService {

    private final JavaPlugin plugin;
    private final Supplier<MysteryBoxConfig> configSupplier;
    private final ItemFactoryService itemFactoryService;
    private final RewardService rewardService;
    private final AuditLogService auditLogService;
    private final MessageService messageService;
    private final Map<UUID, OpeningSession> activeSessions = new ConcurrentHashMap<>();

    public MysteryBoxOpeningService(
            JavaPlugin plugin,
            Supplier<MysteryBoxConfig> configSupplier,
            ItemFactoryService itemFactoryService,
            RewardService rewardService,
            AuditLogService auditLogService,
            MessageService messageService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.itemFactoryService = Objects.requireNonNull(itemFactoryService, "itemFactoryService");
        this.rewardService = Objects.requireNonNull(rewardService, "rewardService");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    public void openBox(Player player, EquipmentSlot hand) {
        if (activeSessions.containsKey(player.getUniqueId())) {
            messageService.sendAlreadyOpening(player);
            return;
        }

        boolean consumed = itemFactoryService.consumeOneFromHand(player, hand, itemFactoryService::isMysteryBoxItem);
        if (!consumed) {
            return;
        }

        MysteryBoxConfig.OpeningSettings opening = configSupplier.get().opening();
        ResolvedReward finalReward = rewardService.rollReward();
        Inventory inventory = Bukkit.createInventory(
                player,
                opening.guiSize(),
                LegacyTextUtil.toComponent(opening.guiTitle())
        );

        fillDecorations(inventory, opening);

        List<ItemStack> row = new ArrayList<>();
        for (int i = 0; i < opening.rowSlots().size(); i++) {
            row.add(rewardService.rollReward().previewItem().clone());
        }
        renderRow(inventory, opening, row);
        player.openInventory(inventory);
        messageService.sendOpenStarted(player);

        OpeningSession session = new OpeningSession(UUID.randomUUID().toString(), inventory, row, finalReward);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> tickSession(player, session),
                0L,
                opening.updatePeriodTicks()
        );
        session.task = task;
        activeSessions.put(player.getUniqueId(), session);
    }

    public void handleInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        OpeningSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (event.getView().getTopInventory().equals(session.inventory)) {
            event.setCancelled(true);
        }
    }

    public void handleInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        OpeningSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (event.getView().getTopInventory().equals(session.inventory)) {
            event.setCancelled(true);
        }
    }

    public void cancelAndRefund(Player player) {
        OpeningSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        cancelTask(session);
        Map<Integer, ItemStack> left = player.getInventory().addItem(itemFactoryService.createMysteryBoxItem(1));
        if (!left.isEmpty()) {
            for (ItemStack item : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }
    }

    public void cancelAllAndRefund() {
        for (UUID playerId : List.copyOf(activeSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                cancelAndRefund(player);
            }
        }
    }

    private void tickSession(Player player, OpeningSession session) {
        MysteryBoxConfig.OpeningSettings opening = configSupplier.get().opening();
        if (!player.isOnline()) {
            activeSessions.remove(player.getUniqueId());
            cancelTask(session);
            return;
        }

        if (session.step < opening.spinSteps()) {
            rotateRow(session, rewardService.rollReward().previewItem().clone());
            renderRow(session.inventory, opening, session.row);
            playConfiguredSound(player, opening.tickSound());
            session.step++;
            return;
        }

        if (!session.finalShown) {
            session.row.set(session.row.size() / 2, session.finalReward.previewItem().clone());
            renderRow(session.inventory, opening, session.row);
            playConfiguredSound(player, opening.finalSound());
            session.finalShown = true;
            return;
        }

        session.rewardDelayCounter++;
        if (session.rewardDelayCounter < opening.rewardDelayTicks()) {
            return;
        }

        grantReward(player, session.openingId, session.finalReward);
        activeSessions.remove(player.getUniqueId());
        cancelTask(session);
        clearInventory(session.inventory);
        player.closeInventory();
    }

    private void grantReward(Player player, String openingId, ResolvedReward reward) {
        MysteryBoxConfig.RewardSettings settings = reward.settings();
        MysteryBoxConfig.RewardGrantItemSettings rewardItem = settings.grant().item();

        if (rewardItem.enabled()) {
            ItemStack grantedItem = rewardService.createGrantedItem(rewardItem);
            Map<Integer, ItemStack> left = player.getInventory().addItem(grantedItem);
            if (!left.isEmpty()) {
                for (ItemStack item : left.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
                messageService.sendInventoryFull(player);
            }
        }

        for (String rawCommand : settings.grant().commands()) {
            if (rawCommand == null || rawCommand.isBlank()) {
                continue;
            }
            String command = PlaceholderUtil.apply(rawCommand, Map.of("player", player.getName()));
            Bukkit.dispatchCommand(plugin.getServer().getConsoleSender(), command);
        }

        auditLogService.logOpeningResult(player, openingId, settings);
        messageService.sendOpenWon(player, settings.winMessage());
    }

    private void rotateRow(OpeningSession session, ItemStack newItem) {
        session.row.remove(0);
        session.row.add(newItem);
    }

    private void renderRow(Inventory inventory, MysteryBoxConfig.OpeningSettings opening, List<ItemStack> row) {
        for (int i = 0; i < opening.rowSlots().size(); i++) {
            inventory.setItem(opening.rowSlots().get(i), row.get(i));
        }
    }

    private void fillDecorations(Inventory inventory, MysteryBoxConfig.OpeningSettings opening) {
        ItemStack filler = createFillerPane();
        for (int slot = 0; slot < opening.guiSize(); slot++) {
            if (!opening.rowSlots().contains(slot)) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private ItemStack createFillerPane() {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE, 1);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(LegacyTextUtil.colorize("&8"));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    private void playConfiguredSound(Player player, MysteryBoxConfig.SoundSettings soundSettings) {
        if (soundSettings.name().isBlank()) {
            return;
        }
        player.playSound(player.getLocation(), soundSettings.name(), soundSettings.volume(), soundSettings.pitch());
    }

    private void cancelTask(OpeningSession session) {
        if (session.task != null) {
            session.task.cancel();
        }
    }

    private void clearInventory(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
    }

    private static final class OpeningSession {
        private final String openingId;
        private final Inventory inventory;
        private final List<ItemStack> row;
        private final ResolvedReward finalReward;
        private int step;
        private boolean finalShown;
        private int rewardDelayCounter;
        private BukkitTask task;

        private OpeningSession(String openingId, Inventory inventory, List<ItemStack> row, ResolvedReward finalReward) {
            this.openingId = openingId;
            this.inventory = inventory;
            this.row = row;
            this.finalReward = finalReward;
        }
    }
}
