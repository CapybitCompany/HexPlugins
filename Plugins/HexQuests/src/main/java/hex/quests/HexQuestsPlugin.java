package hex.quests;

import hex.core.api.HexApi;
import hex.core.api.trigger.TriggerListener;
import hex.core.api.trigger.TriggerService;
import hex.quests.config.DailyPoolRegistry;
import hex.quests.config.QuestRegistry;
import hex.quests.api.QuestContentResolver;
import hex.quests.api.QuestRuntimeView;
import hex.quests.database.QuestRepository;
import hex.quests.model.QuestDefinition;
import hex.quests.model.QuestObjective;
import hex.quests.model.QuestReward;
import hex.quests.model.TriggerData;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

public final class HexQuestsPlugin extends JavaPlugin implements TabExecutor, Listener, QuestRuntimeView {
    private HexApi hex;
    private TownsApi towns;
    private TriggerService triggers;
    private QuestRepository repository;
    private QuestRegistry quests = QuestRegistry.load(new File("missing-quests.yml"));
    private DailyPoolRegistry pools = DailyPoolRegistry.load(new File("missing-daily-pools.yml"));
    private final Map<String, TriggerListener> subscriptions = new HashMap<>();

    @Override
    public void onEnable() {
        saveResource("quests.yml", false);
        saveResource("daily-pools.yml", false);

        var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        var townsReg = Bukkit.getServicesManager().getRegistration(TownsApi.class);
        if (hexReg == null || townsReg == null) {
            getLogger().severe("HexCore or HexTowns not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hex = hexReg.getProvider();
        this.towns = townsReg.getProvider();
        this.triggers = hex.requireService(TriggerService.class);
        this.repository = new QuestRepository(hex.db().db());

        hex.db().asyncRun(repository::ensureTables);
        towns.dataNamespace(this, "quests", (townId, members) -> hex.db().asyncRun(() -> repository.purgeTown(townId)));

        reloadQuests();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand hexQuestsCommand = getCommand("hexquests");
        if (hexQuestsCommand != null) {
            hexQuestsCommand.setExecutor(this);
            hexQuestsCommand.setTabCompleter(this);
        }
        getLogger().info("HexQuests enabled");
    }

    @Override
    public void onDisable() {
        unsubscribeAll();
        getLogger().info("HexQuests disabled");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        assignDaily(event.getPlayer());
    }

    private void reloadQuests() {
        unsubscribeAll();
        this.quests = QuestRegistry.load(new File(getDataFolder(), "quests.yml"));
        this.pools = DailyPoolRegistry.load(new File(getDataFolder(), "daily-pools.yml"));
        for (String triggerId : quests.triggerIds()) {
            TriggerListener listener = trigger -> handleTrigger(trigger.triggerId(), trigger.data());
            triggers.subscribe(triggerId, listener);
            subscriptions.put(triggerId, listener);
        }
        getLogger().info("Loaded quests=" + quests.all().size() + ", pools=" + pools.all().size() + ", triggers=" + subscriptions.size());
    }

    private void unsubscribeAll() {
        if (triggers != null) {
            subscriptions.forEach((triggerId, listener) -> triggers.unsubscribe(triggerId, listener));
        }
        subscriptions.clear();
    }

    public boolean isTriggerActive(String triggerId) {
        if (triggerId == null || triggerId.isBlank()) {
            return false;
        }
        return subscriptions.keySet().stream().anyMatch(active -> active.equalsIgnoreCase(triggerId));
    }

    public QuestContentResolver contentResolver() {
        var registration = Bukkit.getServicesManager().getRegistration(QuestContentResolver.class);
        return registration == null ? null : registration.getProvider();
    }

    private void assignDaily(Player player) {
        towns.townIdOf(player.getUniqueId()).ifPresent(townId -> hex.db().asyncRun(() ->
                repository.ensureDailyAssigned(townId, player.getUniqueId(), today(), selectDailyQuests(townId, player.getUniqueId(), today()))
        ));
    }

    private List<QuestDefinition> selectDailyQuests(UUID townId, UUID playerUuid, LocalDate date) {
        List<QuestDefinition> selected = new ArrayList<>();
        for (DailyPoolRegistry.DailyPool pool : pools.all()) {
            List<QuestDefinition> candidates = new ArrayList<>(quests.dailyByPool(pool.id()));
            Random random = new Random(Objects.hash(townId, playerUuid, date, pool.id()));
            for (int slot = 0; slot < pool.slots() && !candidates.isEmpty(); slot++) {
                selected.add(removeWeighted(candidates, random));
            }
        }
        return selected;
    }

    private static QuestDefinition removeWeighted(List<QuestDefinition> candidates, Random random) {
        int totalWeight = candidates.stream().mapToInt(QuestDefinition::weight).sum();
        int roll = random.nextInt(Math.max(1, totalWeight));
        int cursor = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cursor += Math.max(1, candidates.get(i).weight());
            if (roll < cursor) {
                return candidates.remove(i);
            }
        }
        return candidates.remove(candidates.size() - 1);
    }

    private void handleTrigger(String triggerId, hex.core.api.messaging.HexMessageData data) {
        UUID townId = TriggerData.townId(data).orElse(null);
        UUID playerUuid = TriggerData.playerUuid(data).orElse(null);
        if (townId == null || playerUuid == null) {
            return;
        }
        hex.db().asyncRun(() -> processTrigger(townId, playerUuid, triggerId, data));
    }

    private void processTrigger(UUID townId, UUID playerUuid, String triggerId, hex.core.api.messaging.HexMessageData data) {
        LocalDate date = today();
        repository.ensureDailyAssigned(townId, playerUuid, date, selectDailyQuests(townId, playerUuid, date));
        List<String> activeQuestIds = repository.activeQuestIds(townId, playerUuid, date);
        long delta = Math.max(1L, TriggerData.longValue(data, "amount", 1L));

        for (String questId : activeQuestIds) {
            QuestDefinition quest = quests.get(questId);
            if (quest == null) {
                continue;
            }
            boolean touched = false;
            for (QuestObjective objective : quest.objectives()) {
                if (objective.triggerId().equalsIgnoreCase(triggerId) && objective.matches(data)) {
                    repository.incrementObjective(townId, playerUuid, date, quest.id(), objective, delta);
                    touched = true;
                }
            }
            if (touched && repository.isComplete(townId, playerUuid, date, quest)) {
                repository.markCompleted(townId, playerUuid, date, quest.id());
                executeRewards(townId, playerUuid, quest);
            }
        }
    }

    private void executeRewards(UUID townId, UUID playerUuid, QuestDefinition quest) {
        for (QuestReward reward : quest.rewards()) {
            if (reward.type().equalsIgnoreCase("town.growth.add") && reward.amount() != 0) {
                towns.addGrowthPoints(townId, reward.amount(), reward.source());
            } else if (reward.type().equalsIgnoreCase("command.console")) {
                Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.source()
                        .replace("<playerUuid>", playerUuid.toString())
                        .replace("<townId>", townId.toString())));
            }
        }
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadQuests();
            sender.sendMessage("HexQuests reloaded. quests=" + quests.all().size());
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("daily") && sender instanceof Player player) {
            assignDaily(player);
            sender.sendMessage("Daily quests assignment requested.");
            return true;
        }
        sender.sendMessage("HexQuests: quests=" + quests.all().size() + ", pools=" + pools.all().size() + ", triggers=" + subscriptions.size());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("info", "reload", "daily") : List.of();
    }
}


