package hex.towns.gui;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.config.TownsConfig;
import hex.towns.map.TownMapService;
import hex.towns.model.Town;
import hex.towns.model.TownRole;
import hex.towns.service.TownsService;
import hex.towns.visual.VisualCheckService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class NativeTownMenu implements Listener, CommandExecutor, TabCompleter {
    private static final ItemStack EMPTY = null;
    private static final DecimalFormat INTEGER_FORMAT = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pl-PL")));

    private static final int[] COLLECTION_RESOURCE_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private static final int[] COLLECTION_SMALL_SLOTS = {20,21,22,23,24,29,30,31,32,33};
    private static final int[] MINION_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private static final int[] MEMBER_SLOTS = {10,11,12};
    private static final int[] REQUEST_SLOTS = {28,29,30,31,32,33,34};

    private final Plugin plugin;
    private final HexApi api;
    private final TownsService service;
    private final VisualCheckService visualCheckService;
    private volatile TownsConfig config;
    private final TownRenameAnvilListener renameGui;
    private final TownMapService mapService;
    private final TownCoopDecisionMenu coopDecisionMenu;

    public NativeTownMenu(Plugin plugin, HexApi api, TownsService service, VisualCheckService visualCheckService, TownsConfig config,
                          TownRenameAnvilListener renameGui, TownMapService mapService, TownCoopDecisionMenu coopDecisionMenu) {
        this.plugin = plugin;
        this.api = api;
        this.service = service;
        this.visualCheckService = visualCheckService;
        this.config = config;
        this.renameGui = renameGui;
        this.mapService = mapService;
        this.coopDecisionMenu = coopDecisionMenu;
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            api.ui().send(sender, "towns.error.player-only");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "townmenu" -> openMain(player);
            case "townmanage" -> openManage(player);
            case "townclaims", "claimy" -> openClaims(player);
            case "towncoop" -> openCoop(player);
            case "towncollections", "towncollectionsresources" -> openCollections(player, NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES);
            case "towncollectionsfarming" -> openCollections(player, NativeTownMenuHolder.Page.COLLECTIONS_FARMING);
            case "towncollectionsanimals" -> openCollections(player, NativeTownMenuHolder.Page.COLLECTIONS_ANIMALS);
            case "towncollectionsmobs" -> openCollections(player, NativeTownMenuHolder.Page.COLLECTIONS_MOBS);
            case "townminions" -> openMinions(player);
            case "towndanger" -> openDanger(player);
            default -> openMain(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    public void openMain(Player player) {
        Town town = currentTown(player);
        if (town != null) {
            openManage(player);
            return;
        }
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.MAIN, 54, "§8Miasta §7- centrum");
        fill(inv);
        boolean owner = false;
        boolean member = false;

        inv.setItem(4, playerHead(player.getUniqueId(), "§6Profil miasta", List.of(
                "§7Gracz: §f" + player.getName(),
                "§7Status: §f" + (town == null ? "Brak miasta" : owner ? "Właściciel" : "COOP"),
                "§7Miasto: §e" + (town == null ? "-" : town.name()),
                town == null ? "§8Załóż miasto albo poproś o COOP." : "§8Zarządzaj miastem bez DeluxeMenus."
        )));

        if (town == null) {
            inv.setItem(20, item(Material.EMERALD_BLOCK, "§aZałóż miasto", List.of(
                    "§7Wykonuje §f/town create§7.",
                    "§7Startowe chunki: §f" + initialChunks(),
                    "§7Minimalna odległość: §f" + config.minDistanceChunks() + " chunków",
                    "",
                    "§eKliknij, aby rozpocząć zakładanie."
            )));
            inv.setItem(22, item(Material.OAK_DOOR, "§bPoproś o COOP", List.of(
                    "§7Stojąc na terenie cudzego miasta,",
                    "§7wysyłasz prośbę do właściciela.",
                    "",
                    "§eKliknij: /town coop"
            )));
        } else {
            inv.setItem(20, item(Material.BELL, "§eZarządzaj miastem", List.of(
                    "§7Miasto: §f" + town.name(),
                    "§7Rola: §f" + (owner ? "Właściciel" : "COOP"),
                    "",
                    "§eKliknij, aby otworzyć."
            )));
            inv.setItem(22, item(Material.GRASS_BLOCK, "§aTeren i claimy", List.of(
                    "§7Chunki: §f" + service.chunksOf(town).size() + "§7/§f" + config.maxChunks(),
                    "§7Growth points: §a" + town.growthPoints(),
                    "",
                    "§eKliknij, aby otworzyć."
            )));
        }

        inv.setItem(24, item(Material.WRITABLE_BOOK, "§dCOOP i członkowie", List.of(
                town == null ? "§7Nie należysz jeszcze do miasta." : "§7Członkowie: §f" + service.membersOf(town).size() + "§7/§f" + config.maxMembers(),
                "§7Właściciel widzi prośby i członków.",
                "",
                "§eKliknij, aby otworzyć."
        )));
        inv.setItem(30, item(Material.BOOK, "§aKolekcje miasta", collectionSummaryLore(town)));
        inv.setItem(32, item(Material.PLAYER_HEAD, "§bMiniony miasta", minionSummaryLore(town)));
        inv.setItem(34, item(Material.COMPASS, "§eInfo o miejscu", List.of(
                "§7Chunk: §f" + player.getChunk().getX() + ", " + player.getChunk().getZ(),
                "§7Teren: §f" + service.townAt(player.getLocation()).map(Town::name).orElse("Dzicz"),
                "§7Możesz budować: §f" + bool(service.canBuild(player, player.getLocation())),
                "",
                "§eLPM: /town here",
                "§ePPM: /town info"
        )));
        inv.setItem(40, item(Material.LIME_STAINED_GLASS, "§aPodgląd granic", List.of(
                "§7Przełącza wizualizację granic miasta.",
                "",
                "§eKliknij: /town check"
        )));
        inv.setItem(49, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openManage(Player player) {
        Town town = requireTown(player);
        if (town == null) return;
        boolean owner = service.isOwner(player.getUniqueId(), town.id());
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.MANAGE, 54, "§8Miasto §7- zarządzanie");
        fill(inv);
        inv.setItem(4, item(Material.BELL, "§6" + town.name(), List.of(
                "§7Właściciel: §f" + playerName(town.ownerId()),
                "§7Twoja rola: §f" + (owner ? "Właściciel" : "COOP"),
                "§7Członkowie: §f" + service.membersOf(town).size() + "§7/§f" + config.maxMembers(),
                "§7Chunki: §f" + service.chunksOf(town).size() + "§7/§f" + config.maxChunks(),
                "§7Growth points: §a" + town.growthPoints()
        )));
        inv.setItem(19, item(Material.OAK_SIGN, "§eInformacje w czacie", List.of("§7Pokazuje /town info.", "", "§eKliknij.")));
        inv.setItem(20, item(Material.GRASS_BLOCK, "§aTeren i claimy", List.of("§7Claimy, mapa i granice.", "", "§eKliknij.")));
        inv.setItem(21, item(Material.WRITABLE_BOOK, "§dCOOP", List.of("§7Członkowie i prośby.", "", "§eKliknij.")));
        inv.setItem(22, item(Material.NAME_TAG, "§eZmień nazwę miasta", List.of(
                owner ? "§7Otwiera natywne kowadło zmiany nazwy." : "§cTylko właściciel może zmieniać nazwę.",
                "",
                owner ? "§eKliknij." : "§8Brak dostępu."
        )));
        inv.setItem(23, item(Material.BOOK, "§aKolekcje", List.of("§7Podgląd poziomów kolekcji.", "", "§eKliknij.")));
        inv.setItem(24, item(Material.PLAYER_HEAD, "§bMiniony", minionSummaryLore(town)));
        inv.setItem(25, item(Material.LIME_STAINED_GLASS, "§aPodgląd granic", List.of(
                "§7Przełącza wizualizację granic miasta.",
                "§7Ułatwia sprawdzenie zajętego terenu.",
                "",
                "§eKliknij: /town check"
        )));
        inv.setItem(26, item(Material.DIAMOND_PICKAXE, "§bRoboty górnicze", List.of(
                "§7Osobna zakładka twoich robotów.",
                "§7Limit startowy: §f2 roboty na gracza§7.",
                "",
                "§eKliknij: /minion robots"
        )));
        inv.setItem(53, item(owner ? Material.TNT : Material.RED_BED, owner ? "§cStrefa ryzyka" : "§cOpuść COOP", List.of(
                owner ? "§7Akcje właściciela miasta." : "§7Akcje członka COOP.",
                owner ? "§cZniszczenie miasta resetuje dane SMP/COOP." : "§cOdejście z COOP resetuje twoje dane SMP.",
                "",
                "§eKliknij, aby otworzyć."
        )));
        inv.setItem(45, item(Material.BARRIER, "§ePowrót", List.of()));
        inv.setItem(49, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openClaims(Player player) {
        Town town = currentTown(player);
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.CLAIMS, 54, "§8Miasto §7- teren");
        fill(inv);
        inv.setItem(4, item(Material.COMPASS, "§6Aktualny teren", List.of(
                "§7Chunk: §f" + player.getChunk().getX() + ", " + player.getChunk().getZ(),
                "§7Teren: §f" + service.townAt(player.getLocation()).map(Town::name).orElse("Dzicz"),
                "§7To twoje miasto: §f" + bool(town != null && service.townAt(player.getLocation()).map(t -> service.isMember(player.getUniqueId(), t.id())).orElse(false)),
                "§7Możesz budować: §f" + bool(service.canBuild(player, player.getLocation())),
                "",
                "§7Miasto: §e" + (town == null ? "-" : town.name()),
                "§7Chunki: §f" + (town == null ? 0 : service.chunksOf(town).size()) + "§7/§f" + config.maxChunks(),
                "§7Growth points: §a" + (town == null ? 0 : town.growthPoints())
        )));
        inv.setItem(20, item(Material.GRASS_BLOCK, "§aClaimuj aktualny chunk", List.of(
                "§7Wykonuje /town claim.",
                "§7Wymaga przylegania bokiem do miasta.",
                "§7Wymaga pustego bufora: §f" + config.bufferChunks() + " chunk",
                "§7Koszt: §f1 GP",
                "",
                "§eKliknij, aby claimować."
        )));
        inv.setItem(24, item(Material.MAP, "§bMapa lokalna 9x9", List.of("§7Pokazuje tekstową mapę chunków wokół ciebie.", "§8X = ty, O = twoje miasto, T = inne, . = wolne", "", "§eKliknij: /town map")));
        inv.setItem(30, item(Material.LIME_STAINED_GLASS, "§aWizualizacja granic", List.of("§7Przełącza /town check.", "", "§eKliknij.")));
        inv.setItem(32, item(Material.EXPERIENCE_BOTTLE, "§aGrowth points", List.of("§7Aktualnie: §a" + (town == null ? 0 : town.growthPoints()) + " GP", "§7Rozwój miasta jest używany do claimów.", "", "§eKliknij: /town growth")));
        inv.setItem(34, item(Material.PAPER, "§fInfo miasta", List.of("§7Pełna linia informacji w czacie.", "", "§eKliknij: /town info")));
        inv.setItem(45, item(Material.BARRIER, "§ePowrót", List.of()));
        inv.setItem(49, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openCoop(Player player) {
        Town town = currentTown(player);
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.COOP, 54, "§8Miasto §7- COOP");
        fill(inv);
        if (town == null) {
            Optional<Town> target = service.townAt(player.getLocation());
            boolean canRequest = target.isPresent() && !target.get().ownerId().equals(player.getUniqueId()) && !service.isMember(player.getUniqueId(), target.get().id());
            inv.setItem(4, item(Material.WRITABLE_BOOK, "§dCOOP miasta", List.of(
                    "§7Nie należysz jeszcze do miasta.",
                    "§7Aktualny teren: §f" + target.map(Town::name).orElse("Dzicz"),
                    target.isPresent() ? "§7Właściciel: §f" + playerName(target.get().ownerId()) : "§8Stań na terenie cudzego miasta.",
                    "",
                    canRequest ? "§eMożesz wysłać prośbę z tego menu." : "§8Brak miasta pod nogami albo już jesteś członkiem."
            )));
            inv.setItem(31, item(canRequest ? Material.OAK_DOOR : Material.GRAY_STAINED_GLASS_PANE, canRequest ? "§bPoproś o dołączenie" : "§7Brak miasta do zgłoszenia", List.of(
                    canRequest ? "§7Wyśle prośbę do miasta: §f" + target.get().name() : "§7Stań na terenie cudzego miasta,",
                    canRequest ? "§7Właściciel zobaczy ją w tym menu COOP." : "§7żeby aktywować przycisk prośby.",
                    "",
                    canRequest ? "§eKliknij, aby wysłać request." : "§8Brak akcji."
            )));
            inv.setItem(45, item(Material.BARRIER, "§ePowrót", List.of()));
            inv.setItem(53, item(Material.BARRIER, "§cZamknij", List.of()));
            player.openInventory(inv);
            return;
        }
        boolean owner = service.isOwner(player.getUniqueId(), town.id());
        inv.setItem(4, item(Material.WRITABLE_BOOK, "§dCOOP miasta", List.of(
                "§7Miasto: §f" + town.name(),
                "§7Członkowie: §f" + service.membersOf(town).size() + "§7/§f" + config.maxMembers(),
                owner ? "§7Kliknij gracza COOP, aby otworzyć decyzję usunięcia." : "§7Jesteś członkiem COOP.",
                owner ? "§7Kliknij prośbę, aby ją przyjąć/odrzucić." : "§8Prośby widzi właściciel."
        )));
        List<TownsService.MemberInfo> members = service.memberInfos(town);
        for (int i = 0; i < MEMBER_SLOTS.length; i++) {
            if (i < members.size()) {
                TownsService.MemberInfo member = members.get(i);
                inv.setItem(MEMBER_SLOTS[i], playerHead(member.playerId(), "§b" + member.name(), List.of(
                        "§7Rola: §f" + (member.role() == TownRole.OWNER ? "Właściciel" : "COOP"),
                        "§7Status: §f" + (member.online() ? "Online" : "Offline"),
                        "",
                        owner && member.role() != TownRole.OWNER ? "§eKliknij, aby zarządzać." : "§8Brak akcji."
                )));
            } else {
                inv.setItem(MEMBER_SLOTS[i], item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Wolne miejsce członka", List.of("§8Slot COOP jest wolny.")));
            }
        }
        if (owner) {
            List<TownsService.CoopRequestInfo> requests = service.pendingCoopRequests(town, REQUEST_SLOTS.length);
            for (int i = 0; i < REQUEST_SLOTS.length; i++) {
                if (i < requests.size()) {
                    TownsService.CoopRequestInfo request = requests.get(i);
                    inv.setItem(REQUEST_SLOTS[i], playerHead(request.playerId(), "§eProśba: §f" + request.name(), List.of(
                            "§7Gracz prosi o dołączenie do miasta.",
                            "",
                            "§eKliknij, aby przyjąć/odrzucić."
                    )));
                } else {
                    inv.setItem(REQUEST_SLOTS[i], item(Material.WHITE_STAINED_GLASS_PANE, "§fWolne miejsce na prośbę", List.of("§8Brak prośby.")));
                }
            }
        } else {
            inv.setItem(49, item(Material.RED_BED, "§cOpuść COOP", List.of("§cOdejście resetuje twoje dane SMP.", "", "§eKliknij.")));
        }
        inv.setItem(45, item(Material.BARRIER, "§ePowrót", List.of()));
        inv.setItem(53, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openCollections(Player player, NativeTownMenuHolder.Page page) {
        Town town = requireTown(player);
        if (town == null) return;
        if (!isCollectionPage(page)) page = NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES;
        Inventory inv = inventory(player, page, 54, collectionTitle(page));
        fill(inv);
        Object collections = service("hex.collections.api.HexCollectionsApi");
        if (collections != null) {
            invoke(collections, "loadTown", new Class<?>[]{UUID.class}, town.id());
        }
        List<CollectionItem> items = collectionsFor(page);
        int[] slots = page == NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES ? COLLECTION_RESOURCE_SLOTS : COLLECTION_SMALL_SLOTS;
        for (int i = 0; i < Math.min(items.size(), slots.length); i++) {
            CollectionItem def = items.get(i);
            inv.setItem(slots[i], collectionItem(collections, town.id(), def));
        }
        inv.setItem(45, item(Material.BARRIER, "§ePowrót", List.of("§7Wróć do zarządzania miastem.")));
        if (page != NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES) {
            inv.setItem(48, item(Material.ARROW, "§ePoprzednia strona", List.of()));
        }
        if (page != NativeTownMenuHolder.Page.COLLECTIONS_MOBS) {
            inv.setItem(50, item(Material.ARROW, "§eNastępna strona", List.of()));
        }
        inv.setItem(49, item(Material.BOOK, "§aStrona kolekcji", List.of(
                "§7Miasto: §f" + town.name(),
                "§7Strona: §f" + collectionPageName(page),
                "§8Kolekcje są naliczane przez HexCollections."
        )));
        player.openInventory(inv);
    }

    public void openMinions(Player player) {
        Town town = requireTown(player);
        if (town == null) return;
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.MINIONS, 54, "§8Miniony miasta");
        fill(inv);
        Object minionsApi = service("hex.minions.api.MinionsApi");
        Object menuData = minionsApi == null ? null : invoke(minionsApi, "menuData", new Class<?>[]{Player.class}, player);
        int count = intResult(menuData, "minionCount");
        int limit = intResult(menuData, "minionLimit");
        List<?> minions = listResult(menuData, "minions");
        if (menuData == null) {
            count = 0;
            limit = intResult(minionsApi, "maxMinions", new Class<?>[]{UUID.class}, town.id());
            minions = minionsApi == null ? List.of() : listResult(minionsApi, "minionsOfTown", new Class<?>[]{UUID.class}, town.id());
        }
        List<String> headerLore = new ArrayList<>();
        headerLore.add("§7Miasto: §f" + town.name());
        headerLore.add("§7Aktywne miniony: §f" + count + "§7/§f" + limit);
        headerLore.add("§7Wolne sloty: §f" + Math.max(0, limit - count));
        if (limit > MINION_SLOTS.length) {
            headerLore.add("§7Pokazane sloty: §f" + MINION_SLOTS.length + "§7/§f" + limit);
        }
        headerLore.add("");
        headerLore.add("§8LPM: odbierz storage, PPM: otwórz menu,");
        headerLore.add("§8SHIFT+LPM: podnieś, SHIFT+PPM: ulepsz, ŚPM: przenieś.");
        inv.setItem(4, item(Material.PLAYER_HEAD, "§bMiniony miasta", headerLore));

        int visibleSlots = Math.min(Math.max(Math.max(0, limit), minions.size()), MINION_SLOTS.length);
        for (int i = 0; i < visibleSlots; i++) {
            if (i < minions.size()) {
                Object data = minions.get(i);
                inv.setItem(MINION_SLOTS[i], minionItem(minionsApi, player, data, i + 1));
            } else {
                inv.setItem(MINION_SLOTS[i], emptyMinionSlot(i + 1));
            }
        }
        if (visibleSlots == 0) {
            inv.setItem(22, item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Brak dostępnych slotów", List.of("§8Miasto nie ma jeszcze odblokowanej możliwości stawiania minionów.")));
        }
        inv.setItem(45, item(Material.BARRIER, "§ePowrót", List.of()));
        inv.setItem(47, item(Material.BOOK, "§bWiki minionów", List.of("§7Lista typów minionów i receptur.", "", "§eKliknij: /minion wiki")));
        player.openInventory(inv);
    }

    public void openDanger(Player player) {
        Town town = requireTown(player);
        if (town == null) return;
        boolean owner = service.isOwner(player.getUniqueId(), town.id());
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.DANGER, 45, "§4Miasto §8- strefa ryzyka");
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, item(Material.REDSTONE_TORCH, "§cUwaga", List.of(
                "§7Miasto: §f" + town.name(),
                "§cAkcje w tym menu mogą resetować dane SMP/COOP."
        )));
        if (owner) {
            inv.setItem(22, item(Material.TNT, "§4Zniszcz miasto", List.of(
                    "§cUsuwa miasto i resetuje dane członków.",
                    "§7Po kliknięciu dostaniesz potwierdzenie w czacie.",
                    "",
                    "§eKliknij: /town destroy"
            )));
        } else {
            inv.setItem(22, item(Material.RED_BED, "§cOpuść COOP", List.of(
                    "§cOdejście resetuje twoje dane SMP.",
                    "§7Po kliknięciu dostaniesz potwierdzenie w czacie.",
                    "",
                    "§eKliknij: /town endcoop"
            )));
        }
        inv.setItem(36, item(Material.BARRIER, "§ePowrót", List.of()));
        inv.setItem(40, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof NativeTownMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof NativeTownMenuHolder holder)) return;
        event.setCancelled(true);
        if (!holder.viewerId().equals(player.getUniqueId())) return;
        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) return;
        switch (holder.page()) {
            case MAIN -> handleMainClick(player, slot, event.getClick());
            case MANAGE -> handleManageClick(player, slot, event.getClick());
            case CLAIMS -> handleClaimsClick(player, slot, event.getClick());
            case COOP -> handleCoopClick(player, slot, event.getClick());
            case COLLECTIONS_RESOURCES, COLLECTIONS_FARMING, COLLECTIONS_ANIMALS, COLLECTIONS_MOBS -> handleCollectionsClick(player, holder.page(), slot);
            case MINIONS -> handleMinionsClick(player, slot, event.getClick());
            case DANGER -> handleDangerClick(player, slot);
        }
    }

    private void handleMainClick(Player player, int slot, ClickType click) {
        Town town = currentTown(player);
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 20) { if (town == null) run(player, "town create"); else openManage(player); return; }
        if (slot == 22) { if (town == null) run(player, "town coop"); else openClaims(player); return; }
        if (slot == 24) { openCoop(player); return; }
        if (slot == 30) { openCollections(player, NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES); return; }
        if (slot == 32) { openMinions(player); return; }
        if (slot == 34) { run(player, click.isRightClick() ? "town info" : "town here"); return; }
        if (slot == 40) { toggleCheck(player); }
    }

    private void handleManageClick(Player player, int slot, ClickType click) {
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45) { player.closeInventory(); return; }
        if (slot == 19) { run(player, "town info"); return; }
        if (slot == 20) { openClaims(player); return; }
        if (slot == 21) { openCoop(player); return; }
        if (slot == 22) {
            Town town = currentTown(player);
            if (town != null && service.isOwner(player.getUniqueId(), town.id())) {
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> renameGui.open(player));
            } else {
                api.ui().send(player, "towns.error.not-owner");
            }
            return;
        }
        if (slot == 23) { openCollections(player, NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES); return; }
        if (slot == 24) { openMinions(player); return; }
        if (slot == 25) { toggleCheck(player); return; }
        if (slot == 26) { run(player, "minion robots"); return; }
        if (slot == 53) { openDanger(player); }
    }

    private void handleClaimsClick(Player player, int slot, ClickType click) {
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45) { openManageOrMain(player); return; }
        if (slot == 20) { run(player, "town claim"); return; }
        if (slot == 24) { player.closeInventory(); mapService.openMap(player); return; }
        if (slot == 30) { toggleCheck(player); return; }
        if (slot == 32) { run(player, "town growth"); return; }
        if (slot == 34) { run(player, "town info"); }
    }

    private void handleCoopClick(Player player, int slot, ClickType click) {
        Town town = currentTown(player);
        if (slot == 53) { player.closeInventory(); return; }
        if (slot == 45) { openManageOrMain(player); return; }
        if (town == null) {
            if (slot == 31) run(player, "town coop");
            return;
        }
        boolean owner = service.isOwner(player.getUniqueId(), town.id());
        if (owner) {
            List<TownsService.MemberInfo> members = service.memberInfos(town);
            for (int i = 0; i < Math.min(members.size(), MEMBER_SLOTS.length); i++) {
                if (slot == MEMBER_SLOTS[i]) {
                    TownsService.MemberInfo member = members.get(i);
                    if (member.role() != TownRole.OWNER) {
                        player.closeInventory();
                        Bukkit.getScheduler().runTask(plugin, () -> coopDecisionMenu.openMemberKick(player, member.playerId(), member.name()));
                    }
                    return;
                }
            }
            List<TownsService.CoopRequestInfo> requests = service.pendingCoopRequests(town, REQUEST_SLOTS.length);
            for (int i = 0; i < Math.min(requests.size(), REQUEST_SLOTS.length); i++) {
                if (slot == REQUEST_SLOTS[i]) {
                    TownsService.CoopRequestInfo request = requests.get(i);
                    player.closeInventory();
                    Bukkit.getScheduler().runTask(plugin, () -> coopDecisionMenu.openRequestDecision(player, request.playerId(), request.name()));
                    return;
                }
            }
        } else if (slot == 49) {
            openDanger(player);
        }
    }

    private void handleCollectionsClick(Player player, NativeTownMenuHolder.Page page, int slot) {
        if (slot == 45) { openManage(player); return; }
        if (slot == 48) { openCollections(player, previousCollectionPage(page)); return; }
        if (slot == 50) { openCollections(player, nextCollectionPage(page)); }
    }

    private void handleMinionsClick(Player player, int slot, ClickType click) {
        if (slot == 45) { openManage(player); return; }
        if (slot == 47) { run(player, "minion wiki"); return; }
        int index = indexOf(MINION_SLOTS, slot);
        if (index < 0) return;
        Object data = minionMenuDataByIndex(player, index + 1);
        if (data == null) return;
        String id = stringResult(data, "id");
        if (id.isBlank() || id.equals("-")) return;
        if (click == ClickType.SHIFT_LEFT) { run(player, "minion action pickup " + id); return; }
        if (click == ClickType.SHIFT_RIGHT) { run(player, "minion action upgrade " + id); return; }
        if (click == ClickType.MIDDLE || click == ClickType.CREATIVE) { run(player, "minion action move " + id); return; }
        if (click.isRightClick()) { run(player, "minion action open " + id); return; }
        if (click.isLeftClick()) { run(player, "minion action collect " + id); }
    }

    private void handleDangerClick(Player player, int slot) {
        if (slot == 40) { player.closeInventory(); return; }
        if (slot == 36) { openManage(player); return; }
        Town town = currentTown(player);
        if (town == null) return;
        boolean owner = service.isOwner(player.getUniqueId(), town.id());
        if (owner && slot == 22) { run(player, "town destroy"); return; }
        if (!owner && slot == 22) { run(player, "town endcoop"); }
    }

    private void run(Player player, String command) {
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(command));
    }

    private void toggleCheck(Player player) {
        player.closeInventory();
        boolean enabled = visualCheckService.toggle(player);
        api.ui().send(player, enabled ? "towns.check.on" : "towns.check.off");
    }

    private void openManageOrMain(Player player) {
        if (currentTown(player) == null) openMain(player); else openManage(player);
    }

    private Inventory inventory(Player player, NativeTownMenuHolder.Page page, int size, String title) {
        return Bukkit.createInventory(new NativeTownMenuHolder(page, player.getUniqueId()), size, title);
    }

    private void fill(Inventory inv) {
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private void fill(Inventory inv, Material material) {
        ItemStack filler = item(material, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name == null ? " " : name);
            List<String> cleanLore = lore == null ? List.of() : lore.stream().filter(line -> line != null && !line.isBlank()).toList();
            if (!cleanLore.isEmpty()) meta.setLore(cleanLore);
            addItemFlags(meta, "HIDE_ATTRIBUTES", "HIDE_ADDITIONAL_TOOLTIP");
            if ((name == null || name.isBlank()) && cleanLore.isEmpty()) {
                hideTooltip(meta);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack playerHead(UUID owner, String name, List<String> lore) {
        ItemStack stack = item(Material.PLAYER_HEAD, name, lore);
        if (stack.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            stack.setItemMeta(skullMeta);
        }
        return stack;
    }

    private ItemStack collectionItem(Object collections, UUID townId, CollectionItem def) {
        long amount = collections == null ? 0L : longResult(collections, "getAmount", new Class<?>[]{UUID.class, String.class}, townId, def.id());
        int level = collections == null ? 0 : intResult(collections, "getLevel", new Class<?>[]{UUID.class, String.class}, townId, def.id());
        int maxLevel = collections == null ? 7 : Math.max(1, intResult(collections, "getMaxLevel", new Class<?>[]{String.class}, def.id()));
        return item(def.material(), def.name(), List.of(
                "§7ID: §8" + def.id(),
                "§7Poziom: §f" + level + "§7/§f" + maxLevel,
                "§7Ilość: §f" + format(amount),
                "",
                collections == null ? "§cHexCollections nie jest dostępny." : "§8Postęp naliczany automatycznie."
        ));
    }

    private ItemStack minionItem(Object minionsApi, Player player, Object data, int index) {
        String id = stringResult(data, "id");
        String shortId = stringResult(data, "shortId");
        String name = cleanName(stringResult(data, "displayName"));
        if (name.isBlank() || name.equals("-")) name = cleanName(stringResult(data, "typeId"));
        int tier = intResult(data, "tier");
        int maxTier = intResult(data, "maxTier");
        int used = intResult(data, "storageUsed");
        int limit = intResult(data, "storageLimit");
        int percent = intResult(data, "storagePercent");
        String state = stringResult(data, "state");
        String location = stringResult(data, "world") + " " + intResult(data, "x") + ", " + intResult(data, "y") + ", " + intResult(data, "z");
        String head = stringResult(data, "headMaterial");
        Material material = material(head, Material.PLAYER_HEAD);
        ItemStack icon = minionIcon(minionsApi, player, index, id);
        if (icon == null || icon.getType().isAir()) icon = new ItemStack(material);
        return named(icon, "§b#" + index + " §f" + name, List.of(
                "§7Istnieje: §ftrue",
                "§7ID: §f" + (shortId.isBlank() || shortId.equals("-") ? id.substring(0, Math.min(8, id.length())) : shortId),
                "§7Typ: §f" + stringResult(data, "typeId"),
                "§7Tier: §f" + tier + "§7/§f" + maxTier,
                "§7Storage: §f" + used + "§7/§f" + limit + " §8(" + percent + "%%)",
                storageBar(percent),
                "§7Status: §f" + state,
                "§7Lokacja: §f" + location,
                "",
                "§eLPM: odbierz storage",
                "§ePPM: otwórz menu miniona",
                "§eSHIFT+LPM: podnieś miniona",
                "§eSHIFT+PPM: ulepsz",
                "§eŚPM: przenieś na aktualne miejsce"
        ));
    }

    private Object minionMenuDataByIndex(Player player, int index) {
        Object minions = service("hex.minions.api.MinionsApi");
        if (minions == null) return null;
        Object optional = invoke(minions, "menuDataByIndex", new Class<?>[]{Player.class, int.class}, player, index);
        return optionalValue(optional);
    }

    private ItemStack minionIcon(Object minionsApi, Player player, int index, String id) {
        Object byIndex = optionalValue(invoke(minionsApi, "menuIconByIndex", new Class<?>[]{Player.class, int.class}, player, index));
        if (byIndex instanceof ItemStack item && !item.getType().isAir()) return item;
        try {
            UUID uuid = UUID.fromString(id);
            Object byId = optionalValue(invoke(minionsApi, "menuIcon", new Class<?>[]{Player.class, UUID.class}, player, uuid));
            if (byId instanceof ItemStack item && !item.getType().isAir()) return item;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private ItemStack emptyMinionSlot(int index) {
        return item(Material.WHITE_STAINED_GLASS_PANE, "§f#" + index + " §7Wolny slot miniona", List.of(
                "§7Ten slot mieści się w limicie miasta.",
                "§8Postaw miniona w mieście, aby go zająć."
        ));
    }

    private ItemStack named(ItemStack source, String name, List<String> lore) {
        ItemStack stack = source == null || source.getType().isAir() ? new ItemStack(Material.PLAYER_HEAD) : source.clone();
        stack.setAmount(1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name == null ? " " : name);
            List<String> cleanLore = lore == null ? List.of() : lore.stream().filter(line -> line != null && !line.isBlank()).toList();
            meta.setLore(cleanLore.isEmpty() ? null : cleanLore);
            addItemFlags(meta, "HIDE_ATTRIBUTES", "HIDE_ADDITIONAL_TOOLTIP");
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<String> collectionSummaryLore(Town town) {
        if (town == null) return List.of("§7Nie należysz jeszcze do miasta.", "§8Kolekcje są przypisane do miasta.");
        Object collections = service("hex.collections.api.HexCollectionsApi");
        if (collections == null) return List.of("§7Miasto: §f" + town.name(), "§cHexCollections nie jest dostępny.");
        invoke(collections, "loadTown", new Class<?>[]{UUID.class}, town.id());
        Map<?, ?> all = mapResult(collections, "getAllCollections", new Class<?>[]{UUID.class}, town.id());
        int[] reached = new int[8];
        for (Object progress : all.values()) {
            int level = Math.max(0, Math.min(7, intResult(progress, "level")));
            for (int tier = 1; tier <= level; tier++) reached[tier]++;
        }
        List<String> lore = new ArrayList<>();
        lore.add("§7Miasto: §f" + town.name());
        lore.add("§7Kolekcje: §f" + all.size());
        lore.add("");
        for (int tier = 1; tier <= 7; tier++) {
            lore.add("§8• §7Tier " + tier + ": §f" + reached[tier] + "§7/§f" + all.size());
        }
        lore.add("");
        lore.add("§eKliknij, aby otworzyć.");
        return lore;
    }

    private List<String> minionSummaryLore(Town town) {
        if (town == null) return List.of("§7Nie należysz jeszcze do miasta.", "§8Miniony są przypisane do miasta.");
        Object minions = service("hex.minions.api.MinionsApi");
        if (minions == null) return List.of("§7Miasto: §f" + town.name(), "§cHexMinions nie jest dostępny.");
        List<?> views = listResult(minions, "minionsOfTown", new Class<?>[]{UUID.class}, town.id());
        int max = intResult(minions, "maxMinions", new Class<?>[]{UUID.class}, town.id());
        List<String> lore = new ArrayList<>();
        lore.add("§7Aktywne: §f" + views.size() + "§7/§f" + max);
        int storageUsed = views.stream().mapToInt(view -> intResult(view, "storageUsed")).sum();
        int storageLimit = views.stream().mapToInt(view -> intResult(view, "storageLimit")).sum();
        lore.add("§7Storage łącznie: §f" + storageUsed + "§7/§f" + storageLimit);
        lore.add("");
        if (views.isEmpty()) {
            lore.add("§8Brak minionów w mieście.");
        } else {
            lore.add("§7Pierwsze miniony:");
            views.stream().limit(5).forEach(view -> lore.add("§8• §f" + cleanName(stringResult(view, "displayName")) + " §7T" + intResult(view, "tier")));
            if (views.size() > 5) lore.add("§8... oraz " + (views.size() - 5) + " kolejnych.");
        }
        lore.add("");
        lore.add("§eKliknij, aby otworzyć.");
        return lore;
    }

    private Town currentTown(Player player) {
        return service.townIdOf(player.getUniqueId()).flatMap(service::findTown).orElse(null);
    }

    private Town requireTown(Player player) {
        Town town = currentTown(player);
        if (town == null) {
            api.ui().send(player, "towns.error.no-town");
            player.closeInventory();
        }
        return town;
    }

    private String playerName(UUID uuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() == null ? uuid.toString().substring(0, 8) : offline.getName();
    }

    private int initialChunks() {
        int diameter = config.initialRadius() * 2 + 1;
        return diameter * diameter;
    }

    private boolean isCollectionPage(NativeTownMenuHolder.Page page) {
        return page == NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES
                || page == NativeTownMenuHolder.Page.COLLECTIONS_FARMING
                || page == NativeTownMenuHolder.Page.COLLECTIONS_ANIMALS
                || page == NativeTownMenuHolder.Page.COLLECTIONS_MOBS;
    }

    private NativeTownMenuHolder.Page nextCollectionPage(NativeTownMenuHolder.Page page) {
        return switch (page) {
            case COLLECTIONS_RESOURCES -> NativeTownMenuHolder.Page.COLLECTIONS_FARMING;
            case COLLECTIONS_FARMING -> NativeTownMenuHolder.Page.COLLECTIONS_ANIMALS;
            case COLLECTIONS_ANIMALS -> NativeTownMenuHolder.Page.COLLECTIONS_MOBS;
            default -> NativeTownMenuHolder.Page.COLLECTIONS_MOBS;
        };
    }

    private NativeTownMenuHolder.Page previousCollectionPage(NativeTownMenuHolder.Page page) {
        return switch (page) {
            case COLLECTIONS_MOBS -> NativeTownMenuHolder.Page.COLLECTIONS_ANIMALS;
            case COLLECTIONS_ANIMALS -> NativeTownMenuHolder.Page.COLLECTIONS_FARMING;
            case COLLECTIONS_FARMING -> NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES;
            default -> NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES;
        };
    }

    private String collectionTitle(NativeTownMenuHolder.Page page) {
        return switch (page) {
            case COLLECTIONS_FARMING -> "§8Kolekcje miasta §7- farming";
            case COLLECTIONS_ANIMALS -> "§8Kolekcje miasta §7- zwierzęta";
            case COLLECTIONS_MOBS -> "§8Kolekcje miasta §7- moby";
            default -> "§8Kolekcje miasta §7- surowce";
        };
    }

    private String collectionPageName(NativeTownMenuHolder.Page page) {
        return switch (page) {
            case COLLECTIONS_FARMING -> "Farming";
            case COLLECTIONS_ANIMALS -> "Zwierzęta";
            case COLLECTIONS_MOBS -> "Moby";
            default -> "Surowce";
        };
    }

    private List<CollectionItem> collectionsFor(NativeTownMenuHolder.Page page) {
        if (page == NativeTownMenuHolder.Page.COLLECTIONS_FARMING) return List.of(
                new CollectionItem("farming.wheat", Material.WHEAT, "§eKolekcja pszenicy"),
                new CollectionItem("farming.sugar_cane", Material.SUGAR_CANE, "§aKolekcja trzciny cukrowej"),
                new CollectionItem("farming.beetroot", Material.BEETROOT, "§4Kolekcja buraka"),
                new CollectionItem("farming.cactus", Material.CACTUS, "§2Kolekcja kaktusa")
        );
        if (page == NativeTownMenuHolder.Page.COLLECTIONS_ANIMALS) return List.of(
                new CollectionItem("animals.chicken_meat", Material.CHICKEN, "§fKolekcja mięsa z kurczaka"),
                new CollectionItem("animals.beef", Material.BEEF, "§cKolekcja mięsa z krowy"),
                new CollectionItem("animals.leather", Material.LEATHER, "§6Kolekcja skóry"),
                new CollectionItem("animals.pork", Material.PORKCHOP, "§dKolekcja mięsa świni"),
                new CollectionItem("animals.wool", Material.WHITE_WOOL, "§fKolekcja wełny"),
                new CollectionItem("animals.mutton", Material.MUTTON, "§cKolekcja mięsa owcy")
        );
        if (page == NativeTownMenuHolder.Page.COLLECTIONS_MOBS) return List.of(
                new CollectionItem("mob.zombie", Material.ROTTEN_FLESH, "§2Kolekcja zabitych zombie"),
                new CollectionItem("mob.skeleton", Material.BONE, "§fKolekcja zabitych szkieletów"),
                new CollectionItem("mob.spider", Material.SPIDER_EYE, "§4Kolekcja zabitych pająków"),
                new CollectionItem("mob.silverfish", Material.STONE, "§7Kolekcja zabitych silverfishy")
        );
        return List.of(
                new CollectionItem("mining.cobblestone", Material.COBBLESTONE, "§7Kolekcja bruku"),
                new CollectionItem("mining.dirt", Material.DIRT, "§6Kolekcja dirta"),
                new CollectionItem("mining.stone", Material.STONE, "§7Kolekcja kamienia"),
                new CollectionItem("foraging.oak_wood", Material.OAK_LOG, "§6Kolekcja drewna dębowego"),
                new CollectionItem("foraging.spruce_wood", Material.SPRUCE_LOG, "§2Kolekcja drewna sosnowego"),
                new CollectionItem("foraging.spruce_resin", Material.HONEYCOMB, "§bKolekcja żywicy"),
                new CollectionItem("mining.iron", Material.IRON_INGOT, "§fKolekcja żelaza"),
                new CollectionItem("mining.copper", Material.COPPER_ORE, "§6Kolekcja miedzi"),
                new CollectionItem("mining.rare_elements", Material.AMETHYST_SHARD, "§dKolekcja rzadkich pierwiastków"),
                new CollectionItem("mining.coal", Material.COAL, "§8Kolekcja węgla"),
                new CollectionItem("mining.redstone", Material.REDSTONE, "§cKolekcja redstone"),
                new CollectionItem("mining.gold", Material.GOLD_INGOT, "§6Kolekcja złota"),
                new CollectionItem("mining.diamond", Material.DIAMOND, "§bKolekcja diamentów"),
                new CollectionItem("mining.emerald", Material.EMERALD, "§aKolekcja emeraldów"),
                new CollectionItem("mining.uranium", Material.EMERALD, "§aKolekcja uranu"),
                new CollectionItem("mining.obsidian", Material.OBSIDIAN, "§5Kolekcja obsydianu"),
                new CollectionItem("mining.netherrack", Material.NETHERRACK, "§4Kolekcja netherracku"),
                new CollectionItem("mining.netherite", Material.NETHERITE_SCRAP, "§8Kolekcja netheritu"),
                new CollectionItem("industrial.enriched_uranium", Material.EMERALD, "§aKolekcja wzbogaconego uranu"),
                new CollectionItem("mining.tin", Material.IRON_NUGGET, "§fKolekcja cyny"),
                new CollectionItem("industrial.energy", Material.LIGHTNING_ROD, "§eKolekcja energii")
        );
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

    private Object invoke(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method, parameterTypes).invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invoke(Object target, String method) {
        return invoke(target, method, new Class<?>[0]);
    }

    private Object optionalValue(Object optional) {
        if (optional instanceof Optional<?> opt) return opt.orElse(null);
        return optional;
    }

    private Map<?, ?> mapResult(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        Object result = invoke(target, method, parameterTypes, args);
        return result instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<?> listResult(Object target, String method) {
        return listResult(target, method, new Class<?>[0]);
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

    private long longResult(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        Object result = invoke(target, method, parameterTypes, args);
        return result instanceof Number number ? number.longValue() : 0L;
    }

    private String stringResult(Object target, String method) {
        Object result = invoke(target, method);
        return result == null ? "-" : String.valueOf(result);
    }

    private Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return fallback;
        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private String storageBar(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        return "§a" + "|".repeat(filled) + "§7" + "|".repeat(10 - filled);
    }

    private String cleanName(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("<[^>]+>", "").replace("§r", "").strip();
    }

    private String bool(boolean value) {
        return value ? "true" : "false";
    }

    private String format(long value) {
        return INTEGER_FORMAT.format(value).replace('\u00a0', ' ');
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
        try {
            meta.getClass().getMethod("setHideTooltip", boolean.class).invoke(meta, true);
        } catch (Throwable ignored) {
        }
    }

    private record CollectionItem(String id, Material material, String name) {}
}
