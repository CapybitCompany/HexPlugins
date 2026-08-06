package hex.quests.service;

import hex.economy.api.EconomyResult;
import hex.economy.api.HexEconomyApi;
import hex.quests.HexQuestsPlugin;
import hex.quests.api.QuestContentResolver;
import hex.quests.model.ItemDefinition;
import hex.quests.model.QuestDefinition;
import hex.quests.model.QuestReward;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.Map;

public final class RewardService {
    private final HexQuestsPlugin plugin;
    private final HexEconomyApi economy;

    public RewardService(HexQuestsPlugin plugin, HexEconomyApi economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    public boolean grant(Player player, LocalDate date, QuestDefinition quest) {
        QuestReward reward = quest.reward();
        if (reward.money().signum() > 0) {
            EconomyResult result = economy.deposit(player.getUniqueId(), player.getName(), reward.money(),
                    "daily-quest:" + date + ":" + quest.id());
            if (!result.success()) {
                plugin.getLogger().severe("Nie udało się przyznać nagrody HexEconomy dla " + player.getName()
                        + ", quest=" + quest.id() + ", reason=" + result.reason());
                return false;
            }
        }

        try {
            if (reward.experience() > 0) player.giveExp(reward.experience());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Nie udało się przyznać XP za " + quest.id() + ": " + ex.getMessage());
        }
        for (ItemDefinition item : reward.items()) {
            try {
                giveOrDrop(player, createItem(item));
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("Nie udało się przyznać przedmiotu za " + quest.id() + ": " + ex.getMessage());
            }
        }
        for (String rawCommand : reward.consoleCommands()) {
            try {
                String command = rawCommand
                        .replace("<player>", player.getName())
                        .replace("<playerUuid>", player.getUniqueId().toString())
                        .replace("<quest>", quest.id());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("Nie udało się wykonać komendy nagrody za " + quest.id() + ": " + ex.getMessage());
            }
        }

        player.playSound(player.getLocation(), plugin.settings().completionSound(),
                plugin.settings().completionVolume(), plugin.settings().completionPitch());
        player.sendMessage(plugin.settings().prefix() + plugin.settings().completedMessage()
                .replace("<quest>", quest.title()));
        return true;
    }

    private ItemStack createItem(ItemDefinition definition) {
        if (!definition.customId().isBlank()) {
            QuestContentResolver resolver = plugin.contentResolver();
            if (resolver != null) {
                ItemStack resolved = resolver.createCustomItem(definition.customId(), definition.amount());
                if (resolved != null) return resolved;
            }
            plugin.getLogger().warning("Brak resolvera custom itemu '" + definition.customId()
                    + "'; używam materiału zastępczego " + definition.material());
        }
        return definition.createVanillaStack();
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (leftovers.isEmpty()) return;
        Location location = player.getLocation();
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(location, leftover);
        }
    }
}
