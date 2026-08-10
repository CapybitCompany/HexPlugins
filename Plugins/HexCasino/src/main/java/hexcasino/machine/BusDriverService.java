package hexcasino.machine;

import hexcasino.CasinoEconomy;
import hexcasino.Text;
import hexcasino.config.CasinoConfig;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class BusDriverService implements Listener {

    private enum Stage {
        COLOR,
        HIGH_LOW,
        BETWEEN_OUTSIDE,
        SUIT
    }

    private final JavaPlugin plugin;
    private final Supplier<CasinoConfig> configSupplier;
    private final Map<MachineKey, CasinoConfig.Machine> machinesByLocation = new LinkedHashMap<>();
    private final Map<UUID, BusDriverSession> sessionsByPlayer = new HashMap<>();
    private final Map<String, UUID> occupiedMachines = new HashMap<>();

    private BukkitTask idleParticleTask;
    private BukkitTask occupiedParticleTask;

    public BusDriverService(JavaPlugin plugin, Supplier<CasinoConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void start() {
        rebuildMachines();
        startParticles();
    }

    public void reload() {
        stop(true);
        start();
    }

    public void stop() {
        stop(false);
    }

    private void stop(boolean closeInventories) {
        if (idleParticleTask != null) {
            idleParticleTask.cancel();
            idleParticleTask = null;
        }
        if (occupiedParticleTask != null) {
            occupiedParticleTask.cancel();
            occupiedParticleTask = null;
        }
        for (BusDriverSession session : new ArrayList<>(sessionsByPlayer.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            endSession(session, player, closeInventories);
        }
        sessionsByPlayer.clear();
        occupiedMachines.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        BusDriverSession activeSession = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (activeSession != null && activeSession.state() == BusDriverSession.State.SHOWING_RESULT) {
            event.setCancelled(true);
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        CasinoConfig.Machine machine = machinesByLocation.get(MachineKey.from(block));
        if (machine == null || block.getType() != machine.activationMaterial()) {
            return;
        }

        event.setCancelled(true);
        openMachine(event.getPlayer(), machine);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof BusDriverGuiHolder holder)) {
            return;
        }
        if (!holder.playerId().equals(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        BusDriverSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= session.inventory().getSize()) {
            return;
        }

        CasinoConfig config = configSupplier.get();
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();
        if (slot == gui.exitSlot()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current != null) {
                    endSession(current, player, true);
                }
            });
            return;
        }

        if (session.actionLocked()) {
            return;
        }

        if (session.state() == BusDriverSession.State.IDLE) {
            if (slot == gui.multiplierSlot() && event.getClick().isLeftClick()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
                    if (current != null && current.state() == BusDriverSession.State.IDLE) {
                        current.multiplierIndex(nextIndex(current.multiplierIndex(), config.busDriver().multiplierOptions().size()));
                        render(current, player);
                    }
                });
                return;
            }
            if (slot == gui.cardSlot()) {
                session.actionLocked(true);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
                    if (current != null && current.state() == BusDriverSession.State.IDLE) {
                        startGame(player, current);
                    } else {
                        session.actionLocked(false);
                    }
                });
            }
            return;
        }

        if (session.state() != BusDriverSession.State.PLAYING) {
            return;
        }

        if (slot == gui.cashoutSlot()) {
            session.actionLocked(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current == session && current.state() == BusDriverSession.State.PLAYING) {
                    cashout(player, current);
                } else {
                    session.actionLocked(false);
                }
            });
            return;
        }

        Stage stage = stage(session);
        if (stage == Stage.SUIT) {
            int suitIndex = gui.suitSlots().indexOf(slot);
            if (suitIndex >= 0 && suitIndex < Suit.values().length) {
                session.actionLocked(true);
                Suit suit = Suit.values()[suitIndex];
                Bukkit.getScheduler().runTask(plugin, () -> {
                    BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
                    if (current == session && current.state() == BusDriverSession.State.PLAYING) {
                        chooseSuit(player, current, suit);
                    } else {
                        session.actionLocked(false);
                    }
                });
            }
            return;
        }

        if (slot == gui.lowerSlot() || slot == gui.higherSlot()) {
            session.actionLocked(true);
            boolean rightChoice = slot == gui.higherSlot();
            Bukkit.getScheduler().runTask(plugin, () -> {
                BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current == session && current.state() == BusDriverSession.State.PLAYING) {
                    choose(player, current, rightChoice);
                } else {
                    session.actionLocked(false);
                }
            });
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof BusDriverGuiHolder)) {
            return;
        }

        BusDriverSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null || session.ending() || session.suppressCloseReopen()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
            if (current != null && !current.ending() && player.isOnline()) {
                player.openInventory(current.inventory());
            }
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        BusDriverSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() != BusDriverSession.State.SHOWING_RESULT) {
            return;
        }

        Location to = event.getTo();
        if (to == null || samePosition(event.getFrom(), to)) {
            return;
        }

        Location locked = session.lockedLocation();
        if (locked == null) {
            locked = event.getFrom().clone();
            session.lockedLocation(locked);
        }
        Location target = locked.clone();
        target.setYaw(to.getYaw());
        target.setPitch(to.getPitch());
        event.setTo(target);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        BusDriverSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session != null) {
            endSession(session, event.getPlayer(), false);
        }
    }

    private void openMachine(Player player, CasinoConfig.Machine machine) {
        CasinoConfig config = configSupplier.get();
        if (sessionsByPlayer.containsKey(player.getUniqueId())) {
            player.sendActionBar(Text.component(config.messages().alreadyPlaying()));
            return;
        }

        UUID occupiedBy = occupiedMachines.get(machine.id());
        if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) {
            player.sendActionBar(Text.component(config.messages().machineBusy()));
            return;
        }

        World world = Bukkit.getWorld(machine.world());
        if (world == null) {
            plugin.getLogger().warning("Bus Driver world is not loaded: " + machine.world());
            return;
        }

        CasinoConfig.PlayerLocation target = machine.playerLocation();
        player.teleport(new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch()));

        BusDriverGuiHolder holder = new BusDriverGuiHolder(player.getUniqueId(), machine.id());
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();
        Inventory inventory = Bukkit.createInventory(holder, gui.size(), Text.legacy(gui.title(), Map.of()));
        holder.setInventory(inventory);

        BusDriverSession session = new BusDriverSession(
                player.getUniqueId(),
                machine,
                inventory,
                optionIndex(config.busDriver().multiplierOptions(), config.busDriver().defaultMultiplier())
        );
        sessionsByPlayer.put(player.getUniqueId(), session);
        occupiedMachines.put(machine.id(), player.getUniqueId());
        render(session, player);
        player.openInventory(inventory);
        play(player, config.sounds().open());
    }

    private void endSession(BusDriverSession session, Player player, boolean closeInventory) {
        session.ending(true);
        sessionsByPlayer.remove(session.playerId());
        occupiedMachines.remove(session.machine().id());

        if (player != null && player.isOnline()) {
            CasinoConfig config = configSupplier.get();
            play(player, config.sounds().close());
            if (config.busDriver().exitVelocity().enabled()) {
                Vector velocity = directionFromYaw(session.machine().playerLocation().yaw())
                        .multiply(-config.busDriver().exitVelocity().backwardsStrength());
                velocity.setY(config.busDriver().exitVelocity().y());
                player.setVelocity(velocity);
            }
            if (closeInventory) {
                player.closeInventory();
            }
        }
    }

    private void startGame(Player player, BusDriverSession session) {
        CasinoConfig config = configSupplier.get();
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        double cost = multiplier(config, session);
        if (balance.isEmpty()) {
            player.sendActionBar(Text.component(config.messages().economyUnavailableActionbar()));
            play(player, config.sounds().noFunds());
            session.actionLocked(false);
            return;
        }
        if (balance.getAsDouble() + 0.0001D < cost) {
            player.sendActionBar(Text.component(config.messages().noFundsActionbar(), placeholders(player, session, balance)));
            play(player, config.sounds().noFunds());
            render(session, player);
            session.actionLocked(false);
            return;
        }
        if (!CasinoEconomy.dispatch(config.economy().removeCommand(), player, cost)) {
            player.sendActionBar(Text.component(config.messages().economyUnavailableActionbar()));
            play(player, config.sounds().noFunds());
            session.actionLocked(false);
            return;
        }

        session.state(BusDriverSession.State.PLAYING);
        session.stake(cost);
        session.clearCards();
        session.completedRounds(0);
        session.currentWin(0.0D);
        session.actionLocked(false);
        play(player, config.sounds().spinStart());
        render(session, player);
    }

    private void choose(Player player, BusDriverSession session, boolean rightChoice) {
        Stage stage = stage(session);
        if (stage == Stage.SUIT) {
            session.actionLocked(false);
            return;
        }
        if (!stageReady(session, stage)) {
            resetRound(session);
            session.actionLocked(false);
            render(session, player);
            return;
        }

        Card next = drawComparableCard(session, stage);
        boolean correct = switch (stage) {
            case COLOR -> next.red() != rightChoice; // left = red, right = black
            case HIGH_LOW -> rightChoice
                    ? next.rank() > session.currentCard().rank()
                    : next.rank() < session.currentCard().rank();
            case BETWEEN_OUTSIDE -> {
                Card first = session.card(0);
                Card second = session.card(1);
                int low = Math.min(first.rank(), second.rank());
                int high = Math.max(first.rank(), second.rank());
                boolean between = next.rank() > low && next.rank() < high;
                yield rightChoice ? !between : between; // left = between, right = outside
            }
            case SUIT -> false;
        };
        resolveChoice(player, session, correct, next);
    }

    private void chooseSuit(Player player, BusDriverSession session, Suit suit) {
        if (!stageReady(session, Stage.SUIT)) {
            resetRound(session);
            session.actionLocked(false);
            render(session, player);
            return;
        }
        Card next = randomCard();
        resolveChoice(player, session, next.suit() == suit, next);
    }

    private Card drawComparableCard(BusDriverSession session, Stage stage) {
        Card next = randomCard();
        if (stage == Stage.HIGH_LOW && session.currentCard() != null) {
            int tries = 0;
            while (next.rank() == session.currentCard().rank() && tries < 12) {
                next = randomCard();
                tries++;
            }
        }
        return next;
    }

    private void resolveChoice(Player player, BusDriverSession session, boolean correct, Card next) {
        CasinoConfig config = configSupplier.get();
        session.addCard(next);
        if (!correct) {
            play(player, config.sounds().lose());
            showDecisionFeedback(session, player, false, next, 0.0D, false);
            return;
        }

        int completed = session.completedRounds() + 1;
        session.completedRounds(completed);
        List<Double> payouts = config.busDriver().roundPayoutMultipliers();
        double win = session.stake() * payouts.get(Math.min(completed - 1, payouts.size() - 1));
        session.currentWin(win);
        int maxRounds = Math.min(4, payouts.size());
        play(player, completed >= maxRounds
                ? config.sounds().winBig()
                : config.sounds().winSmall());
        if (completed >= maxRounds) {
            CasinoEconomy.dispatch(config.economy().addCommand(), player, win);
            showDecisionFeedback(session, player, true, next, win, false);
            return;
        }
        showDecisionFeedback(session, player, true, next, win, true);
    }

    private void cashout(Player player, BusDriverSession session) {
        if (session.currentWin() <= 0.0D) {
            session.actionLocked(false);
            return;
        }
        CasinoConfig config = configSupplier.get();
        CasinoEconomy.dispatch(config.economy().addCommand(), player, session.currentWin());
        play(player, config.sounds().winSmall());
        showResult(session, player, true, session.currentWin());
    }

    private void showDecisionFeedback(BusDriverSession session,
                                      Player player,
                                      boolean correct,
                                      Card revealedCard,
                                      double amount,
                                      boolean continuePlaying) {
        if (player == null || !player.isOnline()) {
            session.actionLocked(false);
            return;
        }
        CasinoConfig config = configSupplier.get();
        session.state(BusDriverSession.State.SHOWING_RESULT);
        session.lockedLocation(player.getLocation().clone());
        session.suppressCloseReopen(true);
        player.closeInventory();
        String title = correct ? "§aDOBRZE" : "§cBLEDNIE";
        String subtitle = "§7Karta: §f" + revealedCard.label()
                + (amount > 0.0D ? " §8| §a" + CasinoEconomy.money(amount) + "$" : "");
        player.sendTitle(title, subtitle, 0, config.busDriver().resultSubtitleTicks(), 0);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BusDriverSession current = sessionsByPlayer.get(session.playerId());
            Player online = Bukkit.getPlayer(session.playerId());
            if (current != session || online == null || !online.isOnline()) {
                return;
            }
            session.suppressCloseReopen(false);
            session.lockedLocation(null);
            if (continuePlaying) {
                session.state(BusDriverSession.State.PLAYING);
                session.actionLocked(false);
                render(session, online);
                online.openInventory(session.inventory());
                return;
            }
            resetRound(session);
            session.actionLocked(false);
            render(session, online);
            online.openInventory(session.inventory());
        }, config.busDriver().resultSubtitleTicks());
    }

    private void showResult(BusDriverSession session, Player player, boolean win, double amount) {
        if (player == null || !player.isOnline()) {
            session.actionLocked(false);
            return;
        }
        CasinoConfig config = configSupplier.get();
        session.state(BusDriverSession.State.SHOWING_RESULT);
        session.lockedLocation(player.getLocation().clone());
        session.suppressCloseReopen(true);
        player.closeInventory();
        player.sendTitle(
                "",
                win ? "§aWygrana: §f" + CasinoEconomy.money(amount) + "$" : "§cPrzegrana",
                0,
                config.busDriver().resultSubtitleTicks(),
                0
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BusDriverSession current = sessionsByPlayer.get(session.playerId());
            Player online = Bukkit.getPlayer(session.playerId());
            if (current != session || online == null || !online.isOnline()) {
                return;
            }
            session.suppressCloseReopen(false);
            session.lockedLocation(null);
            resetRound(session);
            session.actionLocked(false);
            render(session, online);
            online.openInventory(session.inventory());
        }, config.busDriver().resultSubtitleTicks());
    }

    private void resetRound(BusDriverSession session) {
        session.state(BusDriverSession.State.IDLE);
        session.clearCards();
        session.completedRounds(0);
        session.currentWin(0.0D);
        session.stake(0.0D);
    }

    private void render(BusDriverSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        ItemStack filler = item(config.busDriver().gui().filler(), placeholders(player, session, CasinoEconomy.balance(player, config)));
        for (int slot = 0; slot < session.inventory().getSize(); slot++) {
            session.inventory().setItem(slot, filler.clone());
        }
        renderControls(session, player);
    }

    private void renderProgress(BusDriverSession session) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();
        List<Double> payouts = config.busDriver().roundPayoutMultipliers();
        for (int index = 0; index < gui.progressSlots().size() && index < payouts.size(); index++) {
            Map<String, String> values = Map.of(
                    "round", Integer.toString(index + 1),
                    "x", CasinoEconomy.money(payouts.get(index))
            );
            CasinoConfig.GuiItem progress = index < session.completedRounds()
                    ? gui.progressCompleteItem()
                    : gui.progressPendingItem();
            set(session.inventory(), gui.progressSlots().get(index), item(progress, values));
        }
    }

    private void renderControls(BusDriverSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        Map<String, String> placeholders = placeholders(player, session, balance);
        set(session.inventory(), gui.balanceSlot(), item(gui.balanceItem(), placeholders));
        set(session.inventory(), gui.multiplierSlot(), item(session.state() == BusDriverSession.State.IDLE
                ? gui.multiplierItem()
                : gui.multiplierLockedItem(), placeholders));
        set(session.inventory(), gui.exitSlot(), item(gui.exitItem(), placeholders));
        set(session.inventory(), gui.infoSlot(), infoItem(gui.infoItem(), gui.infoRoundLine(), placeholders, session));

        if (session.state() == BusDriverSession.State.PLAYING) {
            renderStageChoices(session, gui, placeholders);
            set(session.inventory(), gui.cashoutSlot(), session.currentWin() > 0.0D
                    ? item(gui.cashoutItem(), placeholders)
                    : item(gui.cashoutUnavailableItem(), placeholders));
        } else if (balance.isPresent() && balance.getAsDouble() + 0.0001D >= multiplier(config, session)) {
            set(session.inventory(), gui.cardSlot(), item(gui.startItem(), placeholders));
        } else {
            set(session.inventory(), gui.cardSlot(), item(gui.noFundsItem(), placeholders));
        }
        player.updateInventory();
    }

    private void renderStageChoices(BusDriverSession session,
                                    CasinoConfig.BusDriverGui gui,
                                    Map<String, String> placeholders) {
        Stage stage = stage(session);
        set(session.inventory(), gui.cardSlot(), stageInfoItem(stage, session, placeholders));
        if (stage == Stage.SUIT) {
            Suit[] suits = Suit.values();
            for (int index = 0; index < gui.suitSlots().size() && index < suits.length; index++) {
                set(session.inventory(), gui.suitSlots().get(index), suitChoiceItem(gui, suits[index]));
            }
            return;
        }
        CasinoConfig.GuiItem left = switch (stage) {
            case COLOR -> withHiddenAdditionalTooltip(gui.redItem());
            case HIGH_LOW -> withHiddenAdditionalTooltip(gui.lowerItem());
            case BETWEEN_OUTSIDE -> withHiddenAdditionalTooltip(gui.betweenItem());
            case SUIT -> gui.lowerItem();
        };
        CasinoConfig.GuiItem right = switch (stage) {
            case COLOR -> withHiddenAdditionalTooltip(gui.blackItem());
            case HIGH_LOW -> withHiddenAdditionalTooltip(gui.higherItem());
            case BETWEEN_OUTSIDE -> withHiddenAdditionalTooltip(gui.outsideItem());
            case SUIT -> gui.higherItem();
        };
        set(session.inventory(), gui.lowerSlot(), item(left, placeholders));
        set(session.inventory(), gui.higherSlot(), item(right, placeholders));
    }

    private ItemStack stageInfoItem(Stage stage, BusDriverSession session, Map<String, String> placeholders) {
        CasinoConfig.GuiItem config = switch (stage) {
            case COLOR -> new CasinoConfig.GuiItem(Material.PAPER, "&fKolor", List.of("&7Wybierz kolor pierwszej karty."), false, true);
            case HIGH_LOW -> new CasinoConfig.GuiItem(Material.PAPER, "&fWyzej / Nizej",
                    List.of("&7Poprzednia karta: &f{current_card}"), false, true);
            case BETWEEN_OUTSIDE -> new CasinoConfig.GuiItem(Material.PAPER, "&fPomiedzy / Poza",
                    List.of("&7Zakres: &f{first_card} &7- &f{second_card}"), false, true);
            case SUIT -> new CasinoConfig.GuiItem(Material.PAPER, "&fZnak",
                    List.of("&7Wybierz znak kolejnej karty."), false, true);
        };
        return item(config, placeholders);
    }

    private ItemStack suitChoiceItem(CasinoConfig.BusDriverGui gui, Suit suit) {
        CasinoConfig.GuiItem config = switch (suit) {
            case HEARTS -> gui.heartsItem();
            case DIAMONDS -> gui.diamondsItem();
            case CLUBS -> gui.clubsItem();
            case SPADES -> gui.spadesItem();
        };
        return item(withHiddenAdditionalTooltip(config), Map.of());
    }

    private CasinoConfig.GuiItem withHiddenAdditionalTooltip(CasinoConfig.GuiItem config) {
        return new CasinoConfig.GuiItem(
                config.material(),
                config.name(),
                config.lore(),
                config.hideTooltip(),
                true,
                config.headId(),
                config.headOwner(),
                config.headTexture()
        );
    }

    private ItemStack infoItem(CasinoConfig.GuiItem config, String roundLine, Map<String, String> placeholders,
                               BusDriverSession session) {
        ItemStack stack = new ItemStack(config.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(config.name(), placeholders));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : config.lore()) {
                if ("{round_payouts}".equals(line)) {
                    lore.addAll(roundPayoutLines(roundLine, session));
                } else {
                    lore.add(Text.component(line, placeholders));
                }
            }
            meta.lore(lore.isEmpty() ? null : lore);
            applyFlags(meta, config);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<net.kyori.adventure.text.Component> roundPayoutLines(String roundLine, BusDriverSession session) {
        CasinoConfig config = configSupplier.get();
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        List<Double> payouts = config.busDriver().roundPayoutMultipliers();
        double stake = session.stake() > 0.0D ? session.stake() : multiplier(config, session);
        for (int index = 0; index < payouts.size(); index++) {
            lines.add(Text.component(roundLine, Map.of(
                    "round", Integer.toString(index + 1),
                    "x", CasinoEconomy.money(stake * payouts.get(index))
            )));
        }
        return lines;
    }

    private ItemStack cardItem(Card card, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String color = card.red() ? "&c" : "&8";
            meta.displayName(Text.component(color + "&l" + card.label()));
            meta.lore(Text.lore(List.of(
                    "&c&m--------------------",
                    "&7Aktualna wygrana: &a{current_win}$",
                    "&7Runda: &f{next_round}"
            ), placeholders));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack item(CasinoConfig.GuiItem config, Map<String, String> placeholders) {
        ItemStack stack = baseItem(config);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            applyHeadProfile(meta, config);
            meta.displayName(Text.component(config.name(), placeholders));
            meta.lore(config.lore().isEmpty() ? null : Text.lore(config.lore(), placeholders));
            applyFlags(meta, config);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack baseItem(CasinoConfig.GuiItem config) {
        if (!isBlank(config.headTexture())) {
            return new ItemStack(config.material());
        }
        ItemStack head = headDatabaseItem(config.headId());
        if (head != null) {
            return head;
        }
        return new ItemStack(config.material());
    }

    private ItemStack headDatabaseItem(String headId) {
        if (isBlank(headId)) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
            Object api = apiClass.getDeclaredConstructor().newInstance();
            Object item = apiClass.getMethod("getItemHead", String.class).invoke(api, headId);
            if (item instanceof ItemStack stack && !stack.getType().isAir()) {
                return stack.clone();
            }
        } catch (Throwable ignored) {
            // HeadDatabase is optional. If it is absent or the id is invalid, fall back to the configured material.
        }
        return null;
    }

    private void applyHeadProfile(ItemMeta meta, CasinoConfig.GuiItem config) {
        if (!(meta instanceof SkullMeta skullMeta)) {
            return;
        }
        URL textureUrl = textureUrl(config.headTexture());
        if (textureUrl != null) {
            PlayerProfile profile = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(config.headTexture().getBytes(StandardCharsets.UTF_8))
            );
            profile.getTextures().setSkin(textureUrl);
            skullMeta.setOwnerProfile(profile);
            return;
        }
        if (!isBlank(config.headOwner())) {
            skullMeta.setOwner(config.headOwner());
        }
    }

    private URL textureUrl(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String value = raw.trim();
        String decoded = decodedTextureUrl(value);
        if (decoded != null) {
            value = decoded;
        } else if (value.contains("textures.minecraft.net/texture/") && !value.startsWith("http")) {
            value = "https://" + value;
        } else if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://textures.minecraft.net/texture/" + value;
        }
        try {
            return URI.create(value).toURL();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String decodedTextureUrl(String value) {
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            String marker = "\"url\"";
            int markerIndex = decoded.indexOf(marker);
            if (markerIndex < 0) {
                return null;
            }
            int colon = decoded.indexOf(':', markerIndex + marker.length());
            int firstQuote = decoded.indexOf('"', colon + 1);
            int secondQuote = decoded.indexOf('"', firstQuote + 1);
            if (colon < 0 || firstQuote < 0 || secondQuote < 0) {
                return null;
            }
            return decoded.substring(firstQuote + 1, secondQuote).replace("\\/", "/");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void applyFlags(ItemMeta meta, CasinoConfig.GuiItem config) {
        if (config.hideAdditionalTooltip()) {
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        }
        if (config.hideTooltip()) {
            meta.addItemFlags(ItemFlag.values());
            try {
                meta.setHideTooltip(true);
            } catch (Throwable ignored) {
                // Keeps compatibility if a different 1.21 server API is used during local tests.
            }
        }
    }

    private Card randomCard() {
        return new Card(ThreadLocalRandom.current().nextInt(2, 15), Suit.values()[ThreadLocalRandom.current().nextInt(Suit.values().length)]);
    }

    private void rebuildMachines() {
        machinesByLocation.clear();
        CasinoConfig config = configSupplier.get();
        for (CasinoConfig.Machine machine : config.busDriver().machines().values()) {
            machinesByLocation.put(MachineKey.from(machine.world(), machine.activationBlock()), machine);
        }
    }

    private void startParticles() {
        CasinoConfig config = configSupplier.get();
        if (config.idleParticles().enabled() && config.idleParticles().count() > 0) {
            idleParticleTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> spawnMachineParticles(false),
                    0L,
                    config.idleParticles().intervalTicks());
        }
        if (config.occupiedParticles().enabled() && config.occupiedParticles().count() > 0) {
            occupiedParticleTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> spawnMachineParticles(true),
                    0L,
                    config.occupiedParticles().intervalTicks());
        }
    }

    private void spawnMachineParticles(boolean occupied) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.ParticleSetting setting = occupied ? config.occupiedParticles() : config.idleParticles();
        for (CasinoConfig.Machine machine : config.busDriver().machines().values()) {
            if (occupiedMachines.containsKey(machine.id()) != occupied) {
                continue;
            }
            World world = Bukkit.getWorld(machine.world());
            if (world == null) {
                continue;
            }
            CasinoConfig.BlockLocation block = machine.activationBlock();
            Location location = new Location(world, block.x() + 0.5D, block.y() + setting.yOffset(), block.z() + 0.5D);
            spawnParticles(world, location, setting);
        }
    }

    private void spawnParticles(World world, Location location, CasinoConfig.ParticleSetting setting) {
        if (setting.particle() == Particle.DUST) {
            Particle.DustOptions dust = new Particle.DustOptions(
                    Color.fromRGB(setting.red(), setting.green(), setting.blue()),
                    setting.size()
            );
            world.spawnParticle(setting.particle(), location, setting.count(), setting.offsetX(), setting.offsetY(),
                    setting.offsetZ(), setting.speed(), dust);
            return;
        }
        world.spawnParticle(setting.particle(), location, setting.count(), setting.offsetX(), setting.offsetY(),
                setting.offsetZ(), setting.speed());
    }

    private Map<String, String> placeholders(Player player, BusDriverSession session, OptionalDouble balance) {
        CasinoConfig config = configSupplier.get();
        double multiplier = multiplier(config, session);
        int nextRound = Math.min(session.completedRounds() + 1, config.busDriver().roundPayoutMultipliers().size());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        values.put("balance", balance.isPresent() ? CasinoEconomy.money(balance.getAsDouble()) : "0");
        values.put("balance_display", balance.isPresent() ? CasinoEconomy.money(balance.getAsDouble()) : "-");
        values.put("multiplier", CasinoEconomy.money(multiplier));
        values.put("bet_per_line", CasinoEconomy.money(multiplier));
        values.put("total_cost", CasinoEconomy.money(multiplier));
        values.put("current_win", CasinoEconomy.money(session.currentWin()));
        values.put("completed_rounds", Integer.toString(session.completedRounds()));
        values.put("next_round", Integer.toString(nextRound));
        values.put("stage", stage(session).name().toLowerCase(Locale.ROOT));
        values.put("current_card", label(session.currentCard()));
        values.put("first_card", label(session.card(0)));
        values.put("second_card", label(session.card(1)));
        return values;
    }

    private double multiplier(CasinoConfig config, BusDriverSession session) {
        return config.busDriver().multiplierOptions()
                .get(Math.min(session.multiplierIndex(), config.busDriver().multiplierOptions().size() - 1));
    }

    private Stage stage(BusDriverSession session) {
        int completed = session.completedRounds();
        if (completed <= 0) {
            return Stage.COLOR;
        }
        if (completed == 1) {
            return Stage.HIGH_LOW;
        }
        if (completed == 2) {
            return Stage.BETWEEN_OUTSIDE;
        }
        return Stage.SUIT;
    }

    private boolean stageReady(BusDriverSession session, Stage stage) {
        return switch (stage) {
            case COLOR -> true;
            case HIGH_LOW -> session.currentCard() != null;
            case BETWEEN_OUTSIDE -> session.card(0) != null && session.card(1) != null;
            case SUIT -> session.currentCard() != null;
        };
    }

    private String label(Card card) {
        return card == null ? "-" : card.label();
    }

    private Vector directionFromYaw(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0.0D, Math.cos(radians)).normalize();
    }

    private int optionIndex(List<Double> options, double preferred) {
        for (int index = 0; index < options.size(); index++) {
            if (Math.abs(options.get(index) - preferred) < 0.0001D) {
                return index;
            }
        }
        return 0;
    }

    private int nextIndex(int current, int size) {
        if (size <= 1) {
            return 0;
        }
        return (current + 1) % size;
    }

    private void set(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private boolean samePosition(Location first, Location second) {
        return Objects.equals(first.getWorld(), second.getWorld())
                && Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    private void play(Player player, List<CasinoConfig.SoundSetting> settings) {
        for (CasinoConfig.SoundSetting setting : settings) {
            if (!setting.enabled()) {
                continue;
            }
            if (setting.delayTicks() <= 0) {
                playOne(player, setting);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        playOne(player, setting);
                    }
                }, setting.delayTicks());
            }
        }
    }

    private void playOne(Player player, CasinoConfig.SoundSetting setting) {
        try {
            Sound sound = Sound.valueOf(setting.name().trim().toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, setting.volume(), setting.pitch());
        } catch (RuntimeException ex) {
            player.playSound(player.getLocation(), setting.name(), setting.volume(), setting.pitch());
        }
    }

    public record Card(int rank, Suit suit) {

        public boolean red() {
            return suit == Suit.HEARTS || suit == Suit.DIAMONDS;
        }

        public String label() {
            return rankLabel(rank) + " " + suit.label();
        }
    }

    public enum Suit {
        HEARTS("Kier"),
        DIAMONDS("Karo"),
        CLUBS("Trefl"),
        SPADES("Pik");

        private final String label;

        Suit(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static String rankLabel(int rank) {
        return switch (rank) {
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            case 14 -> "A";
            default -> Integer.toString(rank);
        };
    }
}
