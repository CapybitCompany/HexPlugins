package hex.towns.heart;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.api.event.TownRenamedEvent;
import hex.towns.api.event.TownDestroyedEvent;
import hex.towns.config.TownsConfig;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import hex.towns.service.OperationResult;
import hex.towns.gui.NativeTownMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class TownHeartListener implements Listener {
    private static final int CONFIRM_SLOT = 11;
    private static final int NAME_SLOT = 13;
    private static final int CANCEL_SLOT = 15;
    private static final int BASE_TOWN_STATS_SLOT = 10;
    private static final int BASE_COLLECTIONS_SLOT = 12;
    private static final int BASE_MINIONS_SLOT = 14;
    private static final int BASE_TOWN_CHECK_SLOT = 16;
    private static final int BASE_MEMBER_PANEL_SLOT = 31;
    private static final int BASE_GUIDE_SLOT = 40;
    private static final int BASE_CLOSE_SLOT = 49;
    private static final int ANVIL_INPUT_SLOT = 0;
    private static final int ANVIL_RESULT_SLOT = 2;

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final HexApi api;
    private final TownsService townsService;
    private volatile TownsConfig config;
    private final TownHeartItem itemFactory;
    private final TownHeartService heartService;
    private final NativeTownMenu nativeTownMenu;
    private final NamespacedKey heartVisualTownKey;
    private final Map<UUID, PendingHeartPlacement> pending = new ConcurrentHashMap<>();
    private final Set<UUID> activeNameAnvils = ConcurrentHashMap.newKeySet();

    public TownHeartListener(Plugin plugin, HexApi api, TownsService townsService, TownsConfig config, TownHeartItem itemFactory, TownHeartService heartService, NativeTownMenu nativeTownMenu) {
        this.plugin = plugin;
        this.api = api;
        this.townsService = townsService;
        this.config = config;
        this.itemFactory = itemFactory;
        this.heartService = heartService;
        this.nativeTownMenu = nativeTownMenu;
        this.heartVisualTownKey = new NamespacedKey(plugin, "town_heart_visual_town");
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }


    @EventHandler
    public void onTownRenamed(TownRenamedEvent event) {
        heartService.updateName(event.town());
    }

    @EventHandler
    public void onTownDestroyed(TownDestroyedEvent event) {
        heartService.removeHeartCompletely(event.town());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!itemFactory.isHeart(item)) {
            if (heartService.protectedHeartAt(event.getBlock().getLocation()).isPresent()) {
                event.setCancelled(true);
                api.ui().send(event.getPlayer(), "towns.heart.protected-zone");
            }
            return;
        }
        event.setCancelled(true);
        openPlacementMenu(event.getPlayer(), event.getBlockPlaced().getLocation(), item.clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Optional<TownHeartLocation> protectedHeart = heartService.protectedHeartAt(event.getBlock().getLocation());
        if (protectedHeart.isEmpty()) return;
        // Migration escape hatch: older HexMinions builds could bypass BlockPlaceEvent and put a
        // machine inside the protected heart chunk. A member may remove exactly such a tagged
        // HexMinions special block, while the heart/foundation and arbitrary blocks remain protected.
        if (townsService.canActAsMember(event.getPlayer().getUniqueId(), protectedHeart.get().townId())
                && isTaggedHexMinionsSpecialBlock(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        api.ui().send(event.getPlayer(), "towns.heart.indestructible");
    }

    private boolean isTaggedHexMinionsSpecialBlock(Block block) {
        if (block == null || !(block.getState() instanceof TileState state)) return false;
        return state.getPersistentDataContainer().getKeys().stream()
                .anyMatch(key -> "hexminions".equalsIgnoreCase(key.getNamespace())
                        && "special_block_id".equalsIgnoreCase(key.getKey()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        Optional<TownHeartLocation> heart = heartService.heartAt(block.getLocation());
        if (heart.isPresent()) {
            event.setCancelled(true);
            openBaseMenu(event.getPlayer(), heart.get().townId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeartInteractionEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        String rawTownId = entity.getPersistentDataContainer().get(heartVisualTownKey, PersistentDataType.STRING);
        if (rawTownId == null || rawTownId.isBlank()) return;
        try {
            UUID townId = UUID.fromString(rawTownId);
            if (heartService.heartOf(townId).isPresent()) {
                event.setCancelled(true);
                openBaseMenu(event.getPlayer(), townId);
            }
        } catch (IllegalArgumentException ignored) {
            // Obca/uszkodzona encja z takim PDC nie powinna blokować gry.
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeartVisualDamage(EntityDamageByEntityEvent event) {
        String rawTownId = event.getEntity().getPersistentDataContainer().get(heartVisualTownKey, PersistentDataType.STRING);
        if (rawTownId != null && !rawTownId.isBlank()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        if (heartService.protectedHeartAt(event.getBlock().getLocation()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (heartService.protectedHeartAt(event.getToBlock().getLocation()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> heartService.protectedHeartAt(block.getLocation()).isPresent()
                || heartService.protectedHeartAt(block.getRelative(event.getDirection()).getLocation()).isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> heartService.protectedHeartAt(block.getLocation()).isPresent()
                || heartService.protectedHeartAt(block.getRelative(event.getDirection()).getLocation()).isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> heartService.protectedHeartAt(block.getLocation()).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> heartService.protectedHeartAt(block.getLocation()).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        if (isTownHeartRecipe(matrix)) {
            event.getInventory().setResult(itemFactory.create(1));
            return;
        }
        if (itemFactory.isHeart(event.getInventory().getResult()) || looksLikeTownHeartPattern(matrix)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!itemFactory.isHeart(event.getCurrentItem())) return;
        if (!isTownHeartRecipe(event.getInventory().getMatrix())) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                api.ui().send(player, "towns.heart.craft.no-shift");
            }
            return;
        }
        consumeExtraIngredients(event.getInventory().getMatrix());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof TownHeartMenuHolder holder)) return;
        event.setCancelled(true);
        if (!holder.playerId().equals(player.getUniqueId())) return;
        if (holder.kind() == TownHeartMenuHolder.Kind.BASE) {
            handleBaseMenuClick(player, holder, event.getRawSlot());
            return;
        }
        PendingHeartPlacement placement = pending.get(player.getUniqueId());
        if (placement == null || placement.expired()) {
            pending.remove(player.getUniqueId());
            player.closeInventory();
            api.ui().send(player, "towns.confirm.expired");
            return;
        }
        if (holder.kind() == TownHeartMenuHolder.Kind.CONFIRM) {
            if (event.getRawSlot() == NAME_SLOT) {
                openNameAnvil(player, placement.name());
            } else if (event.getRawSlot() == CANCEL_SLOT) {
                pending.remove(player.getUniqueId());
                player.closeInventory();
            } else if (event.getRawSlot() == CONFIRM_SLOT) {
                confirmPlacement(player, placement);
            }
            return;
        }
        if (holder.kind() == TownHeartMenuHolder.Kind.NAME_ANVIL && event.getRawSlot() == ANVIL_RESULT_SLOT) {
            String name = anvilRenameText(event.getInventory(), event.getView());
            String normalized = townsService.normalizeTownNameForInput(name);
            if (normalized.isBlank()) {
                api.ui().send(player, "towns.rename.invalid", UiTokens.of("max", String.valueOf(config.maxNameLength())));
                return;
            }
            pending.put(player.getUniqueId(), placement.withName(normalized));
            activeNameAnvils.remove(player.getUniqueId());
            openConfirmMenu(player, normalized);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof TownHeartMenuHolder holder)) return;
        if (holder.kind() != TownHeartMenuHolder.Kind.NAME_ANVIL) return;
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!activeNameAnvils.contains(player.getUniqueId())) return;
        setAnvilRepairCost(event.getView(), 0);
        String raw = anvilRenameText(event.getInventory(), event.getView());
        event.setResult(named(Material.NAME_TAG, raw == null || raw.isBlank() ? "Nazwa miasta" : raw, List.of("§7Kliknij, aby wrócić do menu potwierdzenia.")));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof TownHeartMenuHolder holder && holder.kind() == TownHeartMenuHolder.Kind.NAME_ANVIL) {
            activeNameAnvils.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        activeNameAnvils.remove(event.getPlayer().getUniqueId());
    }

    public void giveHeart(Player target, int amount) {
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(itemFactory.create(amount));
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
    }

    private void openPlacementMenu(Player player, Location clickedLocation, ItemStack sourceItem) {
        OperationResult placementCheck = townsService.validateHeartPlacement(clickedLocation);
        if (!placementCheck.success()) {
            api.ui().send(player, placementCheck.templateKey(), placementCheck.tokens());
            return;
        }
        Optional<UUID> currentTownId = townsService.townIdOf(player.getUniqueId());
        UUID attachTownId = null;
        String name = townsService.defaultTownNameForInput();
        if (currentTownId.isPresent()) {
            Town town = townsService.findTown(currentTownId.get()).orElse(null);
            if (town == null) {
                api.ui().send(player, "towns.error.no-town");
                return;
            }
            if (!config.heartAllowExistingTownPlacementForTests()) {
                api.ui().send(player, "towns.heart.already-has-town");
                return;
            }
            if (heartService.heartOf(town.id()).isPresent()) {
                api.ui().send(player, "towns.heart.already-placed");
                return;
            }
            attachTownId = town.id();
            name = town.name();
        }
        pending.put(player.getUniqueId(), new PendingHeartPlacement(player.getUniqueId(), clickedLocation.clone(), name, attachTownId, sourceItem, System.currentTimeMillis() + 60_000L));
        openConfirmMenu(player, name);
    }

    private void openConfirmMenu(Player player, String townName) {
        Inventory inv = Bukkit.createInventory(new TownHeartMenuHolder(TownHeartMenuHolder.Kind.CONFIRM, player.getUniqueId(), null), 27, "Serce miasta");
        ItemStack glass = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, glass);
        inv.setItem(CONFIRM_SLOT, named(Material.LIME_CONCRETE, "§aPostaw serce miasta", List.of("§7Potwierdź założenie/podpięcie bazy.")));
        inv.setItem(NAME_SLOT, named(Material.NAME_TAG, "§eNazwa miasta: §f" + townName, List.of("§7Kliknij, aby ustawić nazwę startową.")));
        inv.setItem(CANCEL_SLOT, named(Material.RED_CONCRETE, "§cAnuluj", List.of("§7Serce nie zostanie zużyte.")));
        player.openInventory(inv);
    }

    private void openNameAnvil(Player player, String currentName) {
        Inventory inv = Bukkit.createInventory(new TownHeartMenuHolder(TownHeartMenuHolder.Kind.NAME_ANVIL, player.getUniqueId(), null), InventoryType.ANVIL, "Nazwa miasta");
        inv.setItem(ANVIL_INPUT_SLOT, named(Material.NAME_TAG, currentName, List.of("§7Wpisz nazwę miasta.")));
        activeNameAnvils.add(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openBaseMenu(Player player, UUID townId) {
        Town town = townsService.findTown(townId).orElse(null);
        if (town == null) {
            api.ui().send(player, "towns.error.no-town");
            return;
        }
        boolean member = townsService.isMember(player.getUniqueId(), town.id());
        boolean owner = townsService.isOwner(player.getUniqueId(), town.id());
        if (member) {
            // Członkowie miasta nie muszą przechodzić przez publiczny podgląd serca.
            // Kliknięcie serca otwiera od razu główne menu właściciela/COOP.
            openFullTownMenu(player);
            return;
        }
        Inventory inv = Bukkit.createInventory(new TownHeartMenuHolder(TownHeartMenuHolder.Kind.BASE, player.getUniqueId(), townId), 54, "Baza miasta: " + town.name());
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, glass);

        inv.setItem(4, named(Material.RED_CONCRETE, "§cSerce miasta §e" + town.name(), List.of(
                "§7Właściciel: §f" + playerName(town.ownerId()),
                "§7Tryb: §f" + (member ? (owner ? "Właściciel" : "Członek miasta") : "Publiczny podgląd"),
                "§8Serce jest niezniszczalne."
        )));
        inv.setItem(BASE_TOWN_STATS_SLOT, townStatsItem(town));
        inv.setItem(BASE_COLLECTIONS_SLOT, collectionsSummaryItem(town));
        inv.setItem(BASE_MINIONS_SLOT, minionsSummaryItem(town));
        inv.setItem(BASE_TOWN_CHECK_SLOT, named(Material.COMPASS, "§eTown check / granice", List.of(
                "§7Pozwala podejrzeć granice miasta z miejsca,",
                "§7w którym aktualnie stoisz.",
                "",
                "§eKliknij, aby przełączyć podgląd granic."
        )));
        if (member) {
            inv.setItem(BASE_MEMBER_PANEL_SLOT, named(owner ? Material.COMMAND_BLOCK : Material.BELL, owner ? "§6Panel właściciela" : "§aPanel członka", List.of(
                    "§7To miejsce jest widoczne tylko dla członków miasta.",
                    "§7Osoby spoza miasta widzą wyłącznie statystyki.",
                    "",
                    "§eKliknij, aby otworzyć główne menu miasta."
            )));
        } else {
            Town currentTown = townsService.townIdOf(player.getUniqueId()).flatMap(townsService::findTown).orElse(null);
            if (currentTown == null) {
                inv.setItem(BASE_MEMBER_PANEL_SLOT, named(Material.WRITABLE_BOOK, "§dPoproś o dołączenie", List.of(
                        "§7Nie należysz obecnie do żadnego miasta.",
                        "§7Możesz wysłać prośbę o dołączenie do:",
                        "§f" + town.name(),
                        "",
                        "§eKliknij, aby otworzyć menu dołączania."
                )));
            } else {
                inv.setItem(BASE_MEMBER_PANEL_SLOT, named(Material.BARRIER, "§cNależysz już do innego miasta", List.of(
                        "§7Twoje obecne miasto: §f" + currentTown.name(),
                        "§7Aby poprosić o członkostwo tutaj,",
                        "§7musisz najpierw opuścić obecne miasto."
                )));
            }
        }
        inv.setItem(BASE_GUIDE_SLOT, named(Material.KNOWLEDGE_BOOK, "§eJak działają miasta?", List.of(
                "§7Poznaj zasady wspólnej progresji,",
                "§7Punktów Miasta, kolekcji i minionów.",
                "",
                "§eKliknij, aby otworzyć przewodnik."
        )));
        inv.setItem(BASE_CLOSE_SLOT, named(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    private void handleBaseMenuClick(Player player, TownHeartMenuHolder holder, int rawSlot) {
        Town town = townsService.findTown(holder.townId()).orElse(null);
        if (town == null) {
            player.closeInventory();
            return;
        }
        if (rawSlot == BASE_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (rawSlot == BASE_TOWN_CHECK_SLOT) {
            player.closeInventory();
            player.performCommand("town check");
            return;
        }
        if (rawSlot == BASE_GUIDE_SLOT) {
            player.closeInventory();
            nativeTownMenu.openGuide(player);
            return;
        }
        if (rawSlot == BASE_MEMBER_PANEL_SLOT) {
            if (townsService.isMember(player.getUniqueId(), town.id())) {
                player.closeInventory();
                openFullTownMenu(player);
                return;
            }
            if (townsService.townIdOf(player.getUniqueId()).isEmpty()) {
                player.closeInventory();
                nativeTownMenu.openCoop(player);
            }
        }
    }

    private void openFullTownMenu(Player player) {
        nativeTownMenu.openMain(player);
    }

    private ItemStack townStatsItem(Town town) {
        return named(Material.PAPER, "§aStatystyki miasta", List.of(
                "§7Nazwa: §f" + town.name(),
                "§7Właściciel: §f" + playerName(town.ownerId()),
                "§7Członkowie: §6" + townsService.membersOf(town).size() + "§7/§6" + townsService.maxMembers(town),
                "§7Claimy: §6" + townsService.chunksOf(town).size() + "§7/§6" + townsService.maxChunks(town),
                "§7Punkty Miasta: §6" + town.growthPoints()
        ));
    }

    private ItemStack collectionsSummaryItem(Town town) {
        Object collections = service("hex.collections.api.HexCollectionsApi");
        if (collections == null) {
            return named(Material.BOOK, "§aKolekcje miasta", List.of("§cKolekcje są chwilowo niedostępne."));
        }
        invoke(collections, "loadTown", new Class<?>[]{UUID.class}, town.id());
        Map<?, ?> all = mapResult(collections, "getAllCollections", new Class<?>[]{UUID.class}, town.id());
        int[] reached = new int[8];
        for (Object progress : all.values()) {
            int level = Math.max(0, Math.min(7, intResult(progress, "level")));
            for (int tier = 1; tier <= level; tier++) reached[tier]++;
        }
        int total = all.size();
        List<String> lore = new ArrayList<>();
        lore.add("§7Ile kolekcji osiągnęło dany poziom:");
        lore.add("");
        for (int tier = 1; tier <= 7; tier++) {
            lore.add("§8• §7Poziom §6" + tier + "§7: §6" + reached[tier] + "§7/§6" + total);
        }
        lore.add("");
        return named(Material.BOOK, "§aKolekcje miasta", lore);
    }

    private ItemStack minionsSummaryItem(Town town) {
        Object minions = service("hex.minions.api.MinionsApi");
        if (minions == null) {
            return named(Material.PLAYER_HEAD, "§bMiniony miasta", List.of("§cMiniony są chwilowo niedostępne."));
        }
        List<?> views = listResult(minions, "minionsOfTown", new Class<?>[]{UUID.class}, town.id());
        List<String> lore = new ArrayList<>();
        lore.add("§7Aktywne: §6" + views.size() + "§7/§6" + intResult(minions, "maxMinions", new Class<?>[]{UUID.class}, town.id()));
        int storageUsed = views.stream().mapToInt(view -> intResult(view, "storageUsed")).sum();
        int storageLimit = views.stream().mapToInt(view -> intResult(view, "storageLimit")).sum();
        lore.add("§7Magazyny minionów: §6" + storageUsed + "§7/§6" + storageLimit);
        lore.add("");
        if (views.isEmpty()) {
            lore.add("§8Brak minionów w mieście.");
        } else {
            lore.add("§7Postawione miniony:");
            views.stream().limit(8).forEach(view -> lore.add("§8• " + asLegacyDisplayName(stringResult(view, "displayName")) + " §7Tier §6" + intResult(view, "tier") + " §8• §7Magazyn §6" + intResult(view, "storageUsed") + "§7/§6" + intResult(view, "storageLimit")));
            if (views.size() > 8) lore.add("§8... oraz " + (views.size() - 8) + " kolejnych.");
        }
        return named(Material.PLAYER_HEAD, "§bMiniony i statystyki minionów", lore);
    }

    private Object service(String className) {
        try {
            Class<?> type = Class.forName(className);
            var registration = Bukkit.getServicesManager().getRegistration(type);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<?, ?> mapResult(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        Object result = invoke(target, method, parameterTypes, args);
        return result instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<?> listResult(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        Object result = invoke(target, method, parameterTypes, args);
        return result instanceof List<?> list ? list : List.of();
    }

    private int intResult(Object target, String method) {
        return intResult(target, method, new Class<?>[0]);
    }

    private int intResult(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        Object result = invoke(target, method, parameterTypes, args);
        return result instanceof Number number ? number.intValue() : 0;
    }

    private String stringResult(Object target, String method) {
        Object result = invoke(target, method, new Class<?>[0]);
        return result == null ? "-" : String.valueOf(result);
    }

    private String asLegacyDisplayName(String text) {
        if (text == null || text.isBlank()) return "§f-";
        try {
            if (text.indexOf('§') >= 0) return text;
            if (text.contains("<") && text.contains(">")) return LEGACY.serialize(MINI.deserialize(text));
        } catch (Throwable ignored) {
        }
        return "§f" + text;
    }

    private Object invoke(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method, parameterTypes).invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String anvilRenameText(Inventory inventory, InventoryView view) {
        String viewText = anvilRenameText(view);
        if (!viewText.isBlank()) {
            return viewText;
        }
        String resultName = itemDisplayName(inventory.getItem(ANVIL_RESULT_SLOT));
        if (!resultName.isBlank()) {
            return resultName;
        }
        String inputName = itemDisplayName(inventory.getItem(ANVIL_INPUT_SLOT));
        if (!inputName.isBlank()) {
            return inputName;
        }
        return "";
    }

    private String itemDisplayName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
    }

    private String anvilRenameText(InventoryView view) {
        Object result = invoke(view, "getRenameText", new Class<?>[0]);
        return result == null ? "" : String.valueOf(result);
    }

    private void setAnvilRepairCost(InventoryView view, int cost) {
        invoke(view, "setRepairCost", new Class<?>[]{int.class}, cost);
    }

    private String playerName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }

    private void confirmPlacement(Player player, PendingHeartPlacement placement) {
        player.closeInventory();
        OperationResult placementCheck = townsService.validateHeartPlacement(placement.clickedLocation());
        if (!placementCheck.success()) {
            pending.remove(player.getUniqueId());
            api.ui().send(player, placementCheck.templateKey(), placementCheck.tokens());
            return;
        }
        if (!itemFactory.isHeart(player.getInventory().getItemInMainHand()) && !itemFactory.isHeart(player.getInventory().getItemInOffHand())) {
            api.ui().send(player, "towns.heart.item-missing");
            return;
        }
        pending.remove(player.getUniqueId());
        if (placement.attachTownId() != null) {
            Town town = townsService.findTown(placement.attachTownId()).orElse(null);
            if (town == null) {
                api.ui().send(player, "towns.error.no-town");
                return;
            }
            try {
                heartService.installHeart(town, placement.clickedLocation());
                consumeOneHeart(player);
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
                api.ui().send(player, "towns.heart.placed", UiTokens.of("town", town.name()));
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "HexTowns heart installation failed for existing town " + town.id(), error);
                api.ui().send(player, "towns.error.db", UiTokens.of("error", rootMessage(error)));
            }
            return;
        }
        townsService.createTownAt(player, placement.name(), placement.clickedLocation()).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "HexTowns heart placement failed for " + player.getName(), cause);
                api.ui().send(player, "towns.error.db", UiTokens.of("error", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                return;
            }
            if (!result.success()) {
                api.ui().send(player, result.templateKey(), result.tokens());
                return;
            }
            Town town = townsService.townIdOf(player.getUniqueId()).flatMap(townsService::findTown).orElse(null);
            if (town == null) {
                api.ui().send(player, "towns.error.no-town");
                return;
            }
            try {
                heartService.installHeart(town, placement.clickedLocation());
            } catch (Throwable installError) {
                plugin.getLogger().log(Level.SEVERE, "HexTowns heart installation failed; rolling back new town " + town.id(), installError);
                townsService.rollbackNewTown(town.id()).whenComplete((ignored, rollbackError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (rollbackError != null) {
                        plugin.getLogger().log(Level.SEVERE, "HexTowns new-town rollback failed for " + town.id(), rollbackError);
                    }
                    api.ui().send(player, "towns.error.db", UiTokens.of("error", rootMessage(installError)));
                }));
                return;
            }
            consumeOneHeart(player);
            Bukkit.broadcast(Component.text("Powstało nowe miasto ", NamedTextColor.GREEN)
                    .append(Component.text(town.name(), NamedTextColor.GOLD))
                    .append(Component.text(" założone przez ", NamedTextColor.GREEN))
                    .append(Component.text(player.getName(), NamedTextColor.AQUA)));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
            api.ui().send(player, result.templateKey(), result.tokens());
        }));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) current = current.getCause();
        if (current == null) return "unknown error";
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private boolean looksLikeTownHeartPattern(ItemStack[] matrix) {
        if (matrix == null || matrix.length < 9) return false;
        return material(matrix[0]) == Material.IRON_INGOT
                && material(matrix[1]) == Material.GOLD_INGOT
                && material(matrix[2]) == Material.IRON_INGOT
                && isAnyMeat(matrix[3])
                && material(matrix[4]) == Material.DIAMOND
                && isAnyMeat(matrix[5])
                && material(matrix[6]) == Material.COBBLESTONE
                && material(matrix[7]) == Material.COBBLESTONE
                && material(matrix[8]) == Material.COBBLESTONE;
    }

    private Material material(ItemStack item) {
        return item == null ? Material.AIR : item.getType();
    }

    private boolean isTownHeartRecipe(ItemStack[] matrix) {
        if (matrix == null || matrix.length < 9) return false;
        return has(matrix[0], Material.IRON_INGOT, 4)
                && has(matrix[1], Material.GOLD_INGOT, 4)
                && has(matrix[2], Material.IRON_INGOT, 4)
                && isMeat(matrix[3], 4)
                && has(matrix[4], Material.DIAMOND, 1)
                && isMeat(matrix[5], 4)
                && has(matrix[6], Material.COBBLESTONE, 32)
                && has(matrix[7], Material.COBBLESTONE, 32)
                && has(matrix[8], Material.COBBLESTONE, 32);
    }

    private boolean has(ItemStack item, Material material, int amount) {
        return item != null && item.getType() == material && item.getAmount() >= amount;
    }

    private boolean isMeat(ItemStack item, int amount) {
        return item != null && item.getAmount() >= amount && isAnyMeat(item);
    }

    private boolean isAnyMeat(ItemStack item) {
        if (item == null) return false;
        return switch (item.getType()) {
            case BEEF, COOKED_BEEF, PORKCHOP, COOKED_PORKCHOP, CHICKEN, COOKED_CHICKEN, MUTTON, COOKED_MUTTON, RABBIT, COOKED_RABBIT -> true;
            default -> false;
        };
    }

    private void consumeExtraIngredients(ItemStack[] matrix) {
        int[] amounts = {4, 4, 4, 4, 1, 4, 32, 32, 32};
        for (int i = 0; i < Math.min(matrix.length, amounts.length); i++) {
            ItemStack item = matrix[i];
            if (item == null) continue;
            item.setAmount(Math.max(0, item.getAmount() - (amounts[i] - 1)));
        }
    }

    private void consumeOneHeart(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (itemFactory.isHeart(main)) {
            main.setAmount(main.getAmount() - 1);
            return;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (itemFactory.isHeart(off)) {
            off.setAmount(off.getAmount() - 1);
        }
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            addItemFlags(meta, "HIDE_ATTRIBUTES", "HIDE_ADDITIONAL_TOOLTIP");
            if ((name == null || name.isBlank()) && (lore == null || lore.stream().allMatch(line -> line == null || line.isBlank()))) {
                hideTooltip(meta);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void addItemFlags(ItemMeta meta, String... flagNames) {
        try {
            Class<?> itemFlagClass = Class.forName("org.bukkit.inventory.ItemFlag");
            List<Object> flags = new ArrayList<>();
            for (String flagName : flagNames) {
                try {
                    flags.add(java.lang.Enum.valueOf((Class) itemFlagClass, flagName));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (flags.isEmpty()) return;
            Object flagsArray = java.lang.reflect.Array.newInstance(itemFlagClass, flags.size());
            for (int i = 0; i < flags.size(); i++) java.lang.reflect.Array.set(flagsArray, i, flags.get(i));
            meta.getClass().getMethod("addItemFlags", flagsArray.getClass()).invoke(meta, flagsArray);
        } catch (Throwable ignored) {
        }
    }

    private void hideTooltip(ItemMeta meta) {
        if (meta != null) meta.setHideTooltip(true);
    }
}
