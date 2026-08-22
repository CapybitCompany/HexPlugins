package hex.quests.ui;

import hex.quests.HexQuestsPlugin;
import hex.quests.api.QuestContentResolver;
import hex.quests.model.ItemDefinition;
import hex.quests.model.QuestDefinition;
import hex.quests.model.QuestObjective;
import hex.quests.model.QuestProgressSnapshot;
import hex.quests.model.QuestReward;
import hex.quests.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DailyQuestMenu implements Listener {
    private final HexQuestsPlugin plugin;

    public DailyQuestMenu(HexQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, LocalDate date, List<String> questIds,
                     Map<String, QuestProgressSnapshot> progress) {
        DailyHolder holder = new DailyHolder(player.getUniqueId().toString(), date);
        Inventory inventory = Bukkit.createInventory(holder, 27, plugin.settings().menuTitle());
        holder.inventory = inventory;
        render(inventory, questIds, progress);
        player.openInventory(inventory);
    }

    public void refreshIfOpen(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof DailyHolder holder)) return;
        LocalDate currentDate = plugin.today();
        if (!holder.date.equals(currentDate)) {
            open(player, currentDate, plugin.currentQuestIds(),
                    plugin.progressCache().snapshot(player.getUniqueId(), currentDate));
            return;
        }
        render(holder.inventory, plugin.currentQuestIds(),
                plugin.progressCache().snapshot(player.getUniqueId(), holder.date));
    }

    private void render(Inventory inventory, List<String> questIds, Map<String, QuestProgressSnapshot> progress) {
        inventory.clear();
        ItemStack filler = fillerItem();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(plugin.settings().infoSlot(), infoItem());
        for (int i = 0; i < questIds.size() && i < plugin.settings().questSlots().size(); i++) {
            QuestDefinition quest = plugin.questRegistry().get(questIds.get(i));
            if (quest == null) continue;
            inventory.setItem(plugin.settings().questSlots().get(i), questItem(quest, progress.get(quest.id())));
        }
    }

    private ItemStack fillerItem() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(plugin.settings().infoName());
        meta.setLore(plugin.settings().infoLore());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack questItem(QuestDefinition quest, QuestProgressSnapshot progress) {
        boolean completed = progress != null && progress.completed();
        ItemStack item = completed ? new ItemStack(Material.LIME_STAINED_GLASS_PANE) : createIcon(quest.icon());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ColorUtil.color("&e" + quest.title()));

        List<String> lore = new ArrayList<>();
        for (String line : quest.description()) lore.add(ColorUtil.color("&7" + line));
        lore.add("");
        long current = 0;
        long target = 0;
        for (QuestObjective objective : quest.objectives()) {
            target += objective.target();
            current += progress == null ? 0L : Math.min(objective.target(),
                    progress.objectiveProgress().getOrDefault(objective.id(), 0L));
        }
        lore.add(ColorUtil.color("&7Postęp: &f" + current + "/" + target));
        lore.add("");
        lore.add(ColorUtil.color("&6Nagroda"));
        QuestReward reward = quest.reward();
        if (reward.money().signum() > 0) {
            lore.add(ColorUtil.color("&7HexEconomy: &a" + plugin.economy().format(reward.money())));
        }
        if (reward.experience() > 0) lore.add(ColorUtil.color("&7XP: &b" + reward.experience()));
        for (ItemDefinition rewardItem : reward.items()) {
            String name = rewardItem.customId().isBlank() ? rewardItem.material().name() : rewardItem.customId();
            lore.add(ColorUtil.color("&7Przedmiot: &f" + rewardItem.amount() + "x " + name));
        }
        if (completed) {
            lore.add("");
            lore.add(ColorUtil.color("&aWykonano"));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createIcon(ItemDefinition definition) {
        if (!definition.customId().isBlank()) {
            QuestContentResolver resolver = plugin.contentResolver();
            if (resolver != null) {
                ItemStack custom = resolver.createCustomItem(definition.customId(), 1);
                if (custom != null) return custom;
            }
        }
        ItemStack item = definition.createVanillaStack();
        item.setAmount(1);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DailyHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DailyHolder) event.setCancelled(true);
    }

    private static final class DailyHolder implements InventoryHolder {
        private final String playerUuid;
        private final LocalDate date;
        private Inventory inventory;

        private DailyHolder(String playerUuid, LocalDate date) {
            this.playerUuid = playerUuid;
            this.date = date;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
