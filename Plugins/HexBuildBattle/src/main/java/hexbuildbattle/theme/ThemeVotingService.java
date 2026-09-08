package hexbuildbattle.theme;

import hexbuildbattle.config.ConfigService;
import hexbuildbattle.game.GameTaskRegistry;
import hexbuildbattle.item.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ThemeVotingService {

    private static final String VOTING_TASK = "theme-voting";

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final ThemeManager themeManager;
    private final GameTaskRegistry taskRegistry;
    private final Random random = new Random();

    private List<Theme> options = List.of();
    private final Map<UUID, String> votes = new HashMap<>();
    private Set<UUID> allowedVoters = Set.of();
    private Consumer<Theme> completion;
    private int remainingSeconds;

    public ThemeVotingService(
            JavaPlugin plugin,
            ConfigService configService,
            ThemeManager themeManager,
            GameTaskRegistry taskRegistry
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.themeManager = themeManager;
        this.taskRegistry = taskRegistry;
    }

    public void start(Collection<Player> players, Consumer<Theme> completion) {
        cancel();
        this.options = themeManager.randomOptions(votingOptionCount());
        this.votes.clear();
        this.allowedVoters = players.stream().map(Player::getUniqueId).collect(Collectors.toUnmodifiableSet());
        this.completion = completion;
        this.remainingSeconds = configService.config().timings().themeVotingSeconds();

        for (Player player : players) {
            player.openInventory(createInventory());
        }

        taskRegistry.runRepeating(VOTING_TASK, this::tick, 20L, 20L);
    }

    public void cancel() {
        taskRegistry.cancel(VOTING_TASK);
        votes.clear();
        allowedVoters = Set.of();
        options = List.of();
        completion = null;
        remainingSeconds = 0;
    }

    public int remainingSeconds() {
        return remainingSeconds;
    }

    public boolean handleClick(Player player, InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ThemeVotingHolder)) {
            return false;
        }
        event.setCancelled(true);
        if (!allowedVoters.contains(player.getUniqueId())) {
            return true;
        }

        int rawSlot = event.getRawSlot();
        List<Integer> slots = votingSlots();
        int optionIndex = slots.indexOf(rawSlot);
        if (optionIndex < 0 || optionIndex >= options.size()) {
            return true;
        }

        Theme selected = options.get(optionIndex);
        votes.put(player.getUniqueId(), selected.id());
        refreshOpenInventories();
        return true;
    }

    public boolean handleClose(Player player, InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ThemeVotingHolder)) {
            return false;
        }
        if (!allowedVoters.contains(player.getUniqueId()) || completion == null || options.isEmpty()) {
            return true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (allowedVoters.contains(player.getUniqueId())
                    && completion != null
                    && !options.isEmpty()
                    && player.isOnline()
                    && !(player.getOpenInventory().getTopInventory().getHolder() instanceof ThemeVotingHolder)) {
                player.openInventory(createInventory());
            }
        });
        return true;
    }

    private void tick() {
        remainingSeconds--;
        if (remainingSeconds <= 0) {
            complete();
        }
    }

    private void complete() {
        taskRegistry.cancel(VOTING_TASK);
        Theme winner = chooseWinner();
        Consumer<Theme> callback = completion;
        cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ThemeVotingHolder) {
                player.closeInventory();
            }
        }

        if (callback != null) {
            callback.accept(winner);
        }
    }

    private Theme chooseWinner() {
        if (options.isEmpty()) {
            return new Theme("missing_themes", "Brak tematow", org.bukkit.Material.BARRIER, 1);
        }
        Map<String, Integer> counts = voteCounts();
        int best = options.stream().mapToInt(theme -> counts.getOrDefault(theme.id(), 0)).max().orElse(0);
        List<Theme> tied = options.stream()
                .filter(theme -> counts.getOrDefault(theme.id(), 0) == best)
                .toList();
        return tied.get(random.nextInt(tied.size()));
    }

    private Inventory createInventory() {
        int size = Math.max(9, Math.min(54, configService.gui().getInt("voting.size", 27)));
        if (size % 9 != 0) {
            size = 27;
        }
        ThemeVotingHolder holder = new ThemeVotingHolder();
        Inventory inventory = Bukkit.createInventory(
                holder,
                size,
                ItemBuilder.component(configService.gui().getString("voting.title", "&6Wybierz temat"))
        );
        holder.attach(inventory);
        List<Integer> slots = votingSlots();
        Map<String, Integer> counts = voteCounts();
        for (int i = 0; i < options.size() && i < slots.size(); i++) {
            Theme theme = options.get(i);
            ItemStack item = ItemBuilder.named(theme.icon(), "&e" + theme.displayName());
            ItemMeta meta = item.getItemMeta();
            if (configService.gui().getBoolean("voting.show-vote-count", true)) {
                meta.lore(List.of(Component.text("Glosy: " + counts.getOrDefault(theme.id(), 0))));
            }
            item.setItemMeta(meta);
            inventory.setItem(slots.get(i), item);
        }
        return inventory;
    }

    private void refreshOpenInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ThemeVotingHolder) {
                player.openInventory(createInventory());
            }
        }
    }

    private Map<String, Integer> voteCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String themeId : votes.values()) {
            counts.merge(themeId, 1, Integer::sum);
        }
        return counts;
    }

    private List<Integer> votingSlots() {
        List<Integer> configured = configService.gui().getIntegerList("voting.slots");
        if (!configured.isEmpty()) {
            return configured;
        }
        return new ArrayList<>(List.of(10, 12, 14, 16));
    }

    private int votingOptionCount() {
        int configured = configService.gui().getInt("voting.option-count", 4);
        int slotCount = votingSlots().size();
        return Math.max(1, Math.min(configured, slotCount));
    }
}
