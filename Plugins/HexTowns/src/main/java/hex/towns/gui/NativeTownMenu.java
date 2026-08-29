package hex.towns.gui;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.config.TownsConfig;
import hex.towns.api.TownPermission;
import hex.towns.bank.TownBankRepository;
import hex.towns.map.TownMapService;
import hex.towns.guide.TownGuideService;
import hex.towns.model.Town;
import hex.towns.model.TownRole;
import hex.towns.service.TownsService;
import hex.towns.visual.VisualCheckService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeTownMenu implements Listener, CommandExecutor, TabCompleter {
    private static final ItemStack EMPTY = null;
    private static final DecimalFormat INTEGER_FORMAT = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pl-PL")));

    private static final int[] COLLECTION_RESOURCE_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private static final int[] COLLECTION_SMALL_SLOTS = {20,21,22,23,24,29,30,31,32,33};
    private static final int[] MINION_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private static final int[] MEMBER_SLOTS = {10,11,12,13,14,19,20,21,22,23};
    private static final int[] REQUEST_SLOTS = {28,29,30,31,32,33,34};
    private static final int[] BANK_DEPOSIT_SLOTS = {10,11,12,13,14,15};
    private static final int[] BANK_WITHDRAW_SLOTS = {19,20,21,22,23,24};
    private static final long[] BANK_AMOUNTS = {100L,500L,1_000L,2_500L,5_000L,10_000L};
    private static final int[] BANK_MEMBER_SLOTS = {28,29,30,31,32,33,34,37,38,39};

    private final Plugin plugin;
    private final HexApi api;
    private final TownsService service;
    private final VisualCheckService visualCheckService;
    private volatile TownsConfig config;
    private final TownRenameAnvilListener renameGui;
    private final TownMapService mapService;
    private final TownCoopDecisionMenu coopDecisionMenu;
    private final TownGuideService guideService;
    /** Runtime-only admin preview state. This never touches membership or any progression/statistics table. */
    private final Map<UUID, DeveloperViewState> developerViews = new ConcurrentHashMap<>();
    /** One synthetic member per administrator. Dummies are runtime-only and never enter town membership/statistics. */
    private final Map<UUID, DeveloperDummyState> developerDummies = new ConcurrentHashMap<>();

    public NativeTownMenu(Plugin plugin, HexApi api, TownsService service, VisualCheckService visualCheckService, TownsConfig config,
                          TownRenameAnvilListener renameGui, TownMapService mapService, TownCoopDecisionMenu coopDecisionMenu,
                          TownGuideService guideService) {
        this.plugin = plugin;
        this.api = api;
        this.service = service;
        this.visualCheckService = visualCheckService;
        this.config = config;
        this.renameGui = renameGui;
        this.mapService = mapService;
        this.coopDecisionMenu = coopDecisionMenu;
        this.guideService = guideService;
    }

    public enum DeveloperViewMode { GUEST, MEMBER, OWNER }

    public record DeveloperViewState(DeveloperViewMode mode, UUID townId, UUID dummyId) {
        public DeveloperViewState(DeveloperViewMode mode, UUID townId) { this(mode, townId, null); }
        public boolean dummy() { return dummyId != null; }
    }

    public static final class DeveloperDummyState {
        private final UUID id;
        private final UUID townId;
        private final String name;
        private final EnumMap<TownPermission, Boolean> permissions = new EnumMap<>(TownPermission.class);

        private DeveloperDummyState(UUID id, UUID townId, String name) {
            this.id = id;
            this.townId = townId;
            this.name = name;
            for (TownPermission permission : TownPermission.values()) {
                boolean allowed = switch (permission) {
                    case CONTAINERS, MINION_PICKUP, MACHINE_BREAK, BANK_WITHDRAW -> false;
                    default -> true;
                };
                permissions.put(permission, allowed);
            }
        }

        public UUID id() { return id; }
        public UUID townId() { return townId; }
        public String name() { return name; }
        public Map<TownPermission, Boolean> permissions() { return Map.copyOf(permissions); }
        public boolean can(TownPermission permission) { return permissions.getOrDefault(permission, false); }
        private void set(TownPermission permission, boolean allowed) { permissions.put(permission, allowed); }
    }

    public void setDeveloperView(Player player, DeveloperViewMode mode, Town town) {
        if (player == null || mode == null || town == null || !player.hasPermission("hextowns.admin")) return;
        developerViews.put(player.getUniqueId(), new DeveloperViewState(mode, town.id()));
    }

    public DeveloperDummyState addDeveloperDummy(Player admin, Town town) {
        if (admin == null || town == null || !admin.hasPermission("hextowns.admin")) return null;
        UUID id = UUID.nameUUIDFromBytes(("hextowns:dev-dummy:" + admin.getUniqueId() + ":" + town.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DeveloperDummyState state = new DeveloperDummyState(id, town.id(), "DEV Dummy " + admin.getName());
        developerDummies.put(admin.getUniqueId(), state);
        return state;
    }

    public boolean removeDeveloperDummy(Player admin) {
        if (admin == null || !admin.hasPermission("hextowns.admin")) return false;
        DeveloperDummyState removed = developerDummies.remove(admin.getUniqueId());
        DeveloperViewState view = developerViews.get(admin.getUniqueId());
        if (view != null && view.dummy()) developerViews.remove(admin.getUniqueId());
        return removed != null;
    }

    public Optional<DeveloperDummyState> developerDummy(Player admin) {
        if (admin == null || !admin.hasPermission("hextowns.admin")) return Optional.empty();
        DeveloperDummyState state = developerDummies.get(admin.getUniqueId());
        if (state == null || service.findTown(state.townId()).isEmpty()) {
            if (state != null) developerDummies.remove(admin.getUniqueId());
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public boolean useDeveloperDummy(Player admin) {
        DeveloperDummyState dummy = developerDummy(admin).orElse(null);
        if (dummy == null) return false;
        developerViews.put(admin.getUniqueId(), new DeveloperViewState(DeveloperViewMode.MEMBER, dummy.townId(), dummy.id()));
        return true;
    }

    public boolean toggleDeveloperDummyPermission(Player admin, TownPermission permission) {
        DeveloperDummyState dummy = developerDummy(admin).orElse(null);
        if (dummy == null || permission == null) return false;
        dummy.set(permission, !dummy.can(permission));
        return true;
    }

    public void clearDeveloperView(Player player) {
        if (player != null) developerViews.remove(player.getUniqueId());
    }

    public Optional<DeveloperViewState> developerView(Player player) {
        if (player == null || !player.hasPermission("hextowns.admin")) return Optional.empty();
        DeveloperViewState state = developerViews.get(player.getUniqueId());
        if (state == null || service.findTown(state.townId()).isEmpty()) {
            if (state != null) developerViews.remove(player.getUniqueId());
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public boolean isDeveloperPreview(Player player) {
        return developerView(player).isPresent();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        developerViews.remove(event.getPlayer().getUniqueId());
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }

    public void openGuide(Player player) {
        guideService.open(player);
    }

    public void openGuideGrowth(Player player) {
        guideService.openGrowth(player);
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
            case "townbank" -> openBank(player);
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
        Town previewTarget = developerTargetTown(player);
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.MAIN, 54, "§8Miasta §7- centrum");
        fill(inv);
        boolean devGuest = isDeveloperGuestView(player);
        boolean owner = false; // MAIN is only rendered when currentTown(...) == null.

        inv.setItem(4, playerHead(player.getUniqueId(), "§6Profil miasta", List.of(
                "§7Gracz: §f" + player.getName(),
                "§7Rola: §f" + (devGuest ? "Gość §8(DEV)" : "Brak miasta"),
                "§7Miasto: §e" + (devGuest && previewTarget != null ? previewTarget.name() : "-"),
                devGuest ? "§dPodgląd developerski — bez zmiany członkostwa/statystyk." : "§8Stań na terenie miasta, aby poprosić o dołączenie."
        )));

        if (town == null) {
            inv.setItem(22, item(Material.OAK_DOOR, "§bPoproś o dołączenie", List.of(
                    "§7Stojąc na terenie cudzego miasta,",
                    "§7wysyłasz prośbę do właściciela.",
                    "",
                    "§eKliknij, aby wysłać prośbę."
            )));
        } else {
            inv.setItem(20, item(Material.BELL, "§eZarządzaj miastem", List.of(
                    "§7Miasto: §f" + town.name(),
                    "§7Rola: §f" + (owner ? "Właściciel" : "Członek"),
                    "",
                    "§eKliknij, aby otworzyć."
            )));
            inv.setItem(22, item(Material.GRASS_BLOCK, "§aTeren i claimy", List.of(
                    "§7Chunki: §6" + service.chunksOf(town).size() + "§7/§6" + service.maxChunks(town),
                    "§7Punkty Miasta: §6" + town.growthPoints(),
                    "",
                    "§eKliknij, aby otworzyć."
            )));
        }

        inv.setItem(24, item(Material.WRITABLE_BOOK, "§dGracze w mieście", List.of(
                town == null ? "§7Nie należysz jeszcze do miasta." : "§7Członkowie: §f" + service.membersOf(town).size() + "§7/§f" + service.maxMembers(town),
                "§7Właściciel widzi prośby i członków.",
                "",
                "§eKliknij, aby otworzyć."
        )));
        if (town != null) {
            inv.setItem(30, item(Material.BOOK, "§aKolekcje miasta", collectionSummaryLore(town)));
            inv.setItem(32, item(Material.PLAYER_HEAD, "§bMiniony miasta", minionSummaryLore(town)));
        }
        inv.setItem(34, item(Material.COMPASS, "§eInfo o miejscu", List.of(
                "§7Teren: §f" + service.townAt(player.getLocation()).map(Town::name).orElse("Dzicz"),
                "§7Możesz budować: §f" + bool(service.canBuild(player, player.getLocation())),
                "",
                "§eLPM: /town here",
                "§ePPM: /town info"
        )));
        inv.setItem(38, item(Material.KNOWLEDGE_BOOK, "§ePrzewodnik miasta", List.of(
                "§7Poznaj zasady miast, Punktów Miasta,",
                "§7kolekcji, minionów i wspólnej progresji.",
                "",
                "§eKliknij, aby otworzyć."
        )));
        inv.setItem(40, item(Material.LIME_STAINED_GLASS, "§aPodgląd granic", List.of(
                "§7Pokazuje w świecie granice claimów twojego miasta.",
                "",
                "§eKliknij, aby przełączyć."
        )));
        inv.setItem(49, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openManage(Player player) {
        Town town = requireTown(player);
        if (town == null) return;
        // Display cached/base member limits immediately; rank refresh is background-only.
        openManageResolved(player, town, service.maxMembers(town));
    }

    private void openManageResolved(Player player, Town town, int maxMembers) {
        boolean owner = isOwnerForView(player, town);
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.MANAGE, 54, "§8Miasto §7- zarządzanie");
        fill(inv);
        inv.setItem(4, item(Material.BELL, "§6" + town.name(), List.of(
                "§7Właściciel: §f" + playerName(town.ownerId()),
                "§7Twoja rola: §f" + roleForView(player, town),
                "§7Członkowie: §6" + service.membersOf(town).size() + "§7/§6" + maxMembers,
                "§7Chunki: §6" + service.chunksOf(town).size() + "§7/§6" + service.maxChunks(town),
                "§7Punkty Miasta: §6" + town.growthPoints(),
                developerStatusLine(player)
        )));
        inv.setItem(20, item(Material.GRASS_BLOCK, "§aTeren i claimy", List.of("§7Claimy, mapa i granice.", "", "§eKliknij.")));
        inv.setItem(21, item(Material.WRITABLE_BOOK, "§dGracze w mieście", List.of("§7Członkowie i prośby.", "", "§eKliknij.")));
        boolean devPreview = isDeveloperPreview(player);
        inv.setItem(22, item(Material.NAME_TAG, "§eZmień nazwę miasta", List.of(
                devPreview ? "§8Tryb DEV: zmiana nazwy pozostaje zablokowana."
                        : owner ? "§7Otwiera natywne kowadło zmiany nazwy." : "§cTylko właściciel może zmieniać nazwę.",
                "",
                devPreview ? "§8Brak akcji w trybie DEV." : owner ? "§eKliknij." : "§8Brak dostępu."
        )));
        inv.setItem(23, item(Material.BOOK, "§aKolekcje", List.of("§7Podgląd poziomów kolekcji.", "", "§eKliknij.")));
        inv.setItem(24, item(Material.PLAYER_HEAD, "§bMiniony", minionSummaryLore(town)));
        inv.setItem(31, manageBankIcon(town, null));

        Object minionsApi = service("hex.minions.api.MinionsApi");
        int usedMachines = minionsApi == null ? 0 : intResult(minionsApi, "countEnergyMachines", new Class<?>[]{UUID.class}, town.id());
        int maxMachines = minionsApi == null ? 0 : intResult(minionsApi, "maxEnergyMachines", new Class<?>[]{UUID.class}, town.id());
        int usedGenerators = minionsApi == null ? 0 : intResult(minionsApi, "countEnergyGenerators", new Class<?>[]{UUID.class}, town.id());
        int maxGenerators = minionsApi == null ? 0 : intResult(minionsApi, "maxEnergyGenerators", new Class<?>[]{UUID.class}, town.id());
        int usedDevices = minionsApi == null ? 0 : intResult(minionsApi, "countEnergyDevices", new Class<?>[]{UUID.class}, town.id());
        int maxDevices = minionsApi == null ? 0 : intResult(minionsApi, "maxEnergyDevices", new Class<?>[]{UUID.class}, town.id());
        inv.setItem(39, item(Material.REDSTONE, "§bWiki maszyn", List.of(
                "§7Generatory: §f" + usedGenerators + "§7/§f" + maxGenerators,
                "§7Maszyny przetwarzające: §f" + usedMachines + "§7/§f" + maxMachines,
                "§7Urządzenia EU łącznie: §f" + usedDevices + "§7/§f" + maxDevices,
                "",
                "§eKliknij, aby otworzyć wiki maszyn."
        )));
        inv.setItem(40, item(Material.KNOWLEDGE_BOOK, "§ePrzewodnik miasta", List.of(
                "§7Zasady wspólnej progresji, Punktów Miasta,",
                "§7kolekcji, graczy i kosztów ulepszeń.",
                "",
                "§eKliknij, aby otworzyć."
        )));
        inv.setItem(41, item(Material.BOOK, "§aWiki minionów", List.of(
                "§7Receptury, rozwój i przedmioty minionów.",
                "",
                "§eKliknij, aby otworzyć wiki minionów."
        )));
        inv.setItem(53, item(devPreview ? Material.GRAY_DYE : owner ? Material.TNT : Material.RED_BED,
                devPreview ? "§8Operacje destrukcyjne (DEV)" : owner ? "§cZniszczenie miasta" : "§cOpuść miasto", List.of(
                devPreview ? "§8Tryb DEV nie pozwala niszczyć miasta ani opuszczać go w imieniu gracza."
                        : owner ? "§7Akcje właściciela miasta." : "§7Akcje członka miasta.",
                devPreview ? "§8Możesz bezpiecznie diagnozować GUI i uprawnienia."
                        : owner ? "§cZniszczenie miasta resetuje dane progresji miasta." : "§cOdejście z miasta resetuje twoje dane SMP.",
                "",
                devPreview ? "§8Brak akcji." : "§eKliknij, aby otworzyć."
        )));
        inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of()));
        inv.setItem(49, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
        refreshManageBankIcon(player, town, inv);
    }

    public void openClaims(Player player) {
        Town town = currentTown(player);
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.CLAIMS, 54, "§8Miasto §7- teren");
        fill(inv);
        inv.setItem(4, item(Material.COMPASS, "§6Aktualny teren", List.of(
                "§7Teren: §f" + service.townAt(player.getLocation()).map(Town::name).orElse("Dzicz"),
                "§7Możesz budować: §f" + bool(service.canBuild(player, player.getLocation())),
                "§7Claimy miasta: §6" + (town == null ? 0 : service.chunksOf(town).size()) + "§7/§6" + (town == null ? config.maxChunks() : service.maxChunks(town)),
                "§7Punkty Miasta: §6" + (town == null ? 0 : town.growthPoints())
        )));
        inv.setItem(19, item(Material.GRASS_BLOCK, "§aJak claimować chunk", List.of(
                "§7Stań na wybranym chunku i użyj:",
                "§f/town claim",
                "§7Chunk musi przylegać bokiem do miasta.",
                "§7Koszt: §f1 Punkt Miasta§7. Outsider nie może budować w buforze §f" + config.outsiderBuildBufferChunks() + " chunków§7.",
                "",
                "§8Ten przycisk jest wyłącznie instrukcją."
        )));
        inv.setItem(21, item(Material.MAP, "§bMapa lokalna 9x9", List.of("§7Pokazuje mapę chunków wokół ciebie.", "§8X = ty, O = twoje miasto, T = inne, . = wolne", "", "§eKliknij: /town map")));
        inv.setItem(23, item(Material.LIME_STAINED_GLASS, "§aWizualizacja granic", List.of("§7Pokazuje w świecie granice claimów twojego miasta.", "§eKliknij, aby przełączyć.")));
        inv.setItem(25, item(Material.EXPERIENCE_BOTTLE, "§aPunkty Miasta", List.of(
                "§7Aktualnie: §a" + (town == null ? 0 : town.growthPoints()) + " Punktów Miasta",
                "§7Pozwalają rozwijać teren miasta.",
                "§7Zdobywasz je poprzez rozwijanie kolekcji i minionów.",
                "",
                "§eKliknij, aby zobaczyć możliwe źródła."
        )));
        inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of()));
        inv.setItem(49, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openCoop(Player player) {
        Town town = currentTown(player);
        Optional<Town> target = contextualTown(player);
        if (town == null) {
            // The COOP menu must never be blocked by LuckPerms/storage. maxMembers(...)
            // is cache/base based and starts any expensive refresh only in the background.
            int displayLimit = target.map(service::maxMembers).orElse(config.maxMembers());
            openCoopResolved(player, null, displayLimit);
            return;
        }

        // Gracz stojący na terenie obcego miasta nie może użyć tego widoku do
        // wysłania requestu COOP. Zamiast przycisku prośby widzi jasną informację,
        // że jest już członkiem innego miasta.
        if (target.isPresent() && !target.get().id().equals(town.id())) {
            openCoopAlreadyMember(player, town, target.get());
            return;
        }

        openCoopResolved(player, town, service.maxMembers(town));
    }

    private void openCoopAlreadyMember(Player player, Town ownTown, Town targetTown) {
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.COOP, 54, "§8Miasto §7- gracze");
        fill(inv);
        inv.setItem(4, item(Material.WRITABLE_BOOK, "§dGracze w mieście", List.of(
                "§7Aktualny teren: §f" + targetTown.name(),
                "§7Twoje miasto: §e" + ownTown.name(),
                "",
                "§cNie możesz poprosić o członkostwo,",
                "§cponieważ należysz już do innego miasta."
        )));
        inv.setItem(31, item(Material.BARRIER, "§cNależysz już do innego miasta", List.of(
                "§7Twoje miasto: §f" + ownTown.name(),
                "§7Odwiedzane miasto: §f" + targetTown.name(),
                "",
                "§8Aby dołączyć do innego miasta,",
                "§8najpierw musisz opuścić obecne."
        )));
        inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of()));
        inv.setItem(53, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    private void openCoopResolved(Player player, Town town, int maxMembers) {
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.COOP, 54, "§8Miasto §7- gracze");
        fill(inv);
        if (town == null) {
            Optional<Town> target = contextualTown(player);
            boolean canRequest = target.isPresent()
                    && service.membersOf(target.get()).size() < service.maxMembers(target.get())
                    && (isDeveloperGuestView(player)
                        || (!target.get().ownerId().equals(player.getUniqueId())
                            && !service.isMember(player.getUniqueId(), target.get().id())));
            inv.setItem(4, item(Material.WRITABLE_BOOK, "§dGracze w mieście", List.of(
                    "§7Nie należysz jeszcze do miasta.",
                    "§7Aktualny teren: §f" + target.map(Town::name).orElse("Dzicz"),
                    target.isPresent() ? "§7Właściciel: §f" + playerName(target.get().ownerId()) : "§8Stań na terenie cudzego miasta.",
                    "",
                    canRequest ? "§eMożesz wysłać prośbę z tego menu." : "§8Brak miasta pod nogami albo już jesteś członkiem."
            )));
            inv.setItem(31, item(canRequest ? Material.OAK_DOOR : Material.BLACK_STAINED_GLASS_PANE, canRequest ? "§bPoproś o dołączenie" : "§7Brak miasta do zgłoszenia", List.of(
                    canRequest ? "§7Wyśle prośbę do miasta: §f" + target.get().name() : "§7Stań na terenie cudzego miasta,",
                    canRequest ? "§7Właściciel zobaczy ją w menu graczy." : "§7żeby aktywować przycisk prośby.",
                    "",
                    canRequest ? "§eKliknij, aby wysłać prośbę." : "§8Brak akcji."
            )));
            inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of()));
            inv.setItem(53, item(Material.BARRIER, "§cZamknij", List.of()));
            player.openInventory(inv);
            return;
        }
        boolean owner = isOwnerForView(player, town);
        boolean devOwner = isDeveloperPreview(player) && owner;
        inv.setItem(4, item(Material.WRITABLE_BOOK, "§dGracze w mieście", List.of(
                "§7Miasto: §f" + town.name(),
                "§7Członkowie: §6" + service.membersOf(town).size() + "§7/§6" + maxMembers,
                devOwner ? "§dDEV właściciel: kliknij członka, aby naprawić jego uprawnienia."
                        : owner ? "§7Kliknij członka, aby otworzyć zarządzanie." : "§7Jesteś członkiem tego miasta.",
                devOwner ? "§8Prośby COOP są w trybie DEV tylko do odczytu."
                        : owner ? "§7Kliknij prośbę, aby ją przyjąć/odrzucić." : "§8Prośby widzi właściciel."
        )));
        List<TownsService.MemberInfo> members = service.memberInfos(town);
        for (int i = 0; i < MEMBER_SLOTS.length; i++) {
            if (i < members.size()) {
                TownsService.MemberInfo member = members.get(i);
                List<String> memberLore = new ArrayList<>();
                if (member.role() == TownRole.OWNER) memberLore.add("§6Właściciel miasta");
                memberLore.add("§7Ranga: " + service.playerRankDisplay(member.playerId()));
                memberLore.add(member.online() ? "§7Status: §aOnline" : "§7Status: §8Offline");
                if (owner && member.role() != TownRole.OWNER) {
                    memberLore.add("");
                    memberLore.add("§eKliknij, aby zarządzać.");
                }
                inv.setItem(MEMBER_SLOTS[i], playerHead(member.playerId(), "§b" + member.name(), memberLore));
            } else if (i < maxMembers) {
                inv.setItem(MEMBER_SLOTS[i], item(Material.LIME_STAINED_GLASS_PANE, "§aWolne miejsce", List.of("§7Do miasta może dołączyć kolejny gracz.")));
            } else {
                inv.setItem(MEMBER_SLOTS[i], item(Material.RED_STAINED_GLASS_PANE, "§cMiejsce zablokowane", List.of(
                        "§7Dodatkowe sloty odblokowują rangi członków miasta."
                )));
            }
        }
        DeveloperDummyState dummy = developerDummy(player).filter(value -> value.townId().equals(town.id())).orElse(null);
        if (dummy != null) {
            inv.setItem(42, playerHead(dummy.id(), "§d" + dummy.name(), List.of(
                    "§dDummy developerski",
                    "§7Nie jest liczony jako członek miasta",
                    "§7i nie wpływa na kolekcje, ceny ani statystyki.",
                    "",
                    owner ? "§eLPM: przełącz się na dummy" : "§eKliknij: przełącz się na dummy",
                    owner ? "§ePPM: ustaw uprawnienia dummy" : "§8Uprawnienia dummy ustawisz w widoku właściciela."
            )));
        }
        if (owner) {
            List<TownsService.CoopRequestInfo> requests = service.pendingCoopRequests(town, REQUEST_SLOTS.length);
            for (int i = 0; i < REQUEST_SLOTS.length; i++) {
                if (i < requests.size()) {
                    TownsService.CoopRequestInfo request = requests.get(i);
                    inv.setItem(REQUEST_SLOTS[i], playerHead(request.playerId(), "§eProśba: §f" + request.name(), List.of(
                            "§7Gracz prosi o dołączenie do miasta.",
                            "§7Ranga: " + service.playerRankDisplay(request.playerId()),
                            "§7Wygasa za: §f" + coopRequestRemaining(request.createdAt()),
                            "",
                            "§eKliknij, aby przyjąć/odrzucić."
                    )));
                } else {
                    inv.setItem(REQUEST_SLOTS[i], item(Material.WHITE_STAINED_GLASS_PANE, "§fWolne miejsce na prośbę", List.of("§8Brak prośby.")));
                }
            }
        } else {
            inv.setItem(49, item(Material.RED_BED, "§cOpuść miasto", List.of("§cOdejście z miasta resetuje twoje dane SMP.", "", "§eKliknij.")));
        }
        inv.setItem(40, item(Material.SCAFFOLDING, "§eWpływ liczby graczy na progresję", List.of(
                "§7Więcej graczy zwiększa wymagania kolekcji.",
                "§7Większe wymagania wpływają również na",
                "§7koszty kolejnych ulepszeń minionów.",
                "§7Odejście gracza nie obniża już zwiększonego celu.",
                "",
                "§eKliknij, aby przeczytać więcej."
        )));
        inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of()));
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
        inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of("§7Wróć do zarządzania miastem.")));
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
        Object menuData = isDeveloperPreview(player) ? null : (minionsApi == null ? null : invoke(minionsApi, "menuData", new Class<?>[]{Player.class}, player));
        int count = intResult(menuData, "minionCount");
        int limit = intResult(menuData, "minionLimit");
        List<?> minions = listResult(menuData, "minions");
        if (menuData == null) {
            limit = intResult(minionsApi, "maxMinions", new Class<?>[]{UUID.class}, town.id());
            minions = minionsApi == null ? List.of() : listResult(minionsApi, "minionsOfTown", new Class<?>[]{UUID.class}, town.id());
            count = minions.size();
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
        inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of()));
        inv.setItem(47, item(Material.BOOK, "§bWiki minionów", List.of("§7Lista typów minionów i receptur.", "", "§eKliknij: /minion wiki")));
        player.openInventory(inv);
    }

    public void openBank(Player player) {
        Town town = requireTown(player);
        if (town == null) return;
        UUID viewerId = player.getUniqueId();
        CompletableFuture<TownsService.BankSnapshot> snapshotFuture = isDeveloperPreview(player)
                ? service.bankSnapshotForTown(town.id())
                : service.bankSnapshot(viewerId);
        snapshotFuture.thenAccept(snapshot -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            Town current = currentTown(player);
            if (current == null || !snapshot.available() || snapshot.townId() == null || !snapshot.townId().equals(current.id())) {
                player.closeInventory();
                player.sendMessage("§c" + (snapshot.error() == null || snapshot.error().isBlank() ? "Bank Miasta jest niedostępny." : snapshot.error()));
                return;
            }
            openBankResolved(player, current, snapshot);
        })).exceptionally(error -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.sendMessage("§cNie udało się otworzyć Banku Miasta.");
            });
            plugin.getLogger().warning("Town bank menu load failed for " + viewerId + ": " + error.getMessage());
            return null;
        });
    }

    private void openBankResolved(Player player, Town town, TownsService.BankSnapshot snapshot) {
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.BANK, 54, "§8Miasto §7- bank");
        fill(inv);
        boolean canWithdraw = isDeveloperPreview(player) ? canBankWithdrawForView(player, town) : snapshot.canWithdraw();
        inv.setItem(4, item(Material.GOLD_BLOCK, "§6Bank Miasta", List.of(
                "§7Miasto: §f" + town.name(),
                "§7Podatek od wpłat: §f3%",
                "",
                "§7Saldo miasta: §6" + formatMoney(snapshot.balance()) + "$"
        )));

        for (int i = 0; i < BANK_AMOUNTS.length; i++) {
            long amount = BANK_AMOUNTS[i];
            BigDecimal gross = BigDecimal.valueOf(amount);
            BigDecimal tax = gross.multiply(new BigDecimal("0.03"));
            BigDecimal net = gross.subtract(tax);
            boolean canDeposit = !isDeveloperPreview(player) && snapshot.viewerBalance().compareTo(gross) >= 0;
            Material depositMaterial = canDeposit ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
            String depositName = (canDeposit ? "§a" : "§8") + "Wpłać " + compactMoney(amount) + "$";
            List<String> depositLore = new ArrayList<>();
            depositLore.add("§7Z konta zostanie pobrane: §f" + compactMoney(amount) + "$");
            depositLore.add("§7Podatek 3%: §c" + formatMoney(tax) + "$");
            depositLore.add("§7Do banku trafi: §a" + formatMoney(net) + "$");
            depositLore.add("");
            if (isDeveloperPreview(player)) depositLore.add("§8Tryb DEV: transakcje są wyłączone.");
            else if (canDeposit) depositLore.add("§eKliknij, aby wpłacić.");
            else depositLore.add("§cNie masz wystarczającej ilości pieniędzy.");
            inv.setItem(BANK_DEPOSIT_SLOTS[i], item(depositMaterial, depositName, depositLore));

            boolean bankHasAmount = snapshot.balance().compareTo(gross) >= 0;
            boolean canUseWithdraw = canWithdraw && bankHasAmount && !isDeveloperPreview(player);
            Material withdrawMaterial = canUseWithdraw ? Material.ORANGE_CONCRETE : Material.GRAY_CONCRETE;
            String withdrawName = (canUseWithdraw ? "§6" : "§8") + "Wypłać " + compactMoney(amount) + "$";
            List<String> withdrawLore = new ArrayList<>();
            if (!canWithdraw) {
                withdrawLore.add("§cNie masz uprawnienia do wypłat z Banku Miasta.");
                withdrawLore.add("§7Właściciel miasta musi nadać Ci uprawnienie");
                withdrawLore.add("§fWypłaty z Banku Miasta §7w ustawieniach członka.");
            } else if (!bankHasAmount) {
                withdrawLore.add("§cW Banku Miasta nie ma wystarczającej ilości pieniędzy.");
            } else if (isDeveloperPreview(player)) {
                withdrawLore.add("§8Tryb DEV: transakcje są wyłączone.");
            } else {
                withdrawLore.add("§7Środki trafią na twoje konto.");
            }
            withdrawLore.add("§7Saldo banku: §f" + formatMoney(snapshot.balance()) + "$");
            if (canUseWithdraw) {
                withdrawLore.add("");
                withdrawLore.add("§eKliknij, aby wypłacić.");
            }
            inv.setItem(BANK_WITHDRAW_SLOTS[i], item(withdrawMaterial, withdrawName, withdrawLore));
        }

        List<TownsService.MemberInfo> members = service.memberInfos(town);
        for (int i = 0; i < Math.min(members.size(), BANK_MEMBER_SLOTS.length); i++) {
            TownsService.MemberInfo member = members.get(i);
            TownBankRepository.MemberAccount account = snapshot.accounts().getOrDefault(member.playerId(), TownBankRepository.MemberAccount.empty(member.playerId()));
            BigDecimal balance = account.netBalance();
            String balanceLine = balance.signum() < 0 ? "§7Saldo: §c" + formatMoney(balance) + "$" : "§7Saldo: §a" + formatMoney(balance) + "$";
            inv.setItem(BANK_MEMBER_SLOTS[i], playerHead(member.playerId(), "§f" + member.name(), List.of(
                    "§7Wpłacono: §a" + formatMoney(account.creditedNet()) + "$",
                    "§7Wypłacono: §6" + formatMoney(account.withdrawn()) + "$",
                    balanceLine
            )));
        }

        inv.setItem(45, item(Material.ARROW, "§ePowrót", List.of("§7Wróć do zarządzania miastem.")));
        inv.setItem(49, item(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openDeveloperDummyPermissions(Player admin) {
        DeveloperDummyState dummy = developerDummy(admin).orElse(null);
        if (dummy == null) {
            admin.sendMessage("§cNie masz aktywnego dummy członka. Użyj /townadmin dummy add <miasto>.");
            return;
        }
        Town town = service.findTown(dummy.townId()).orElse(null);
        if (town == null) {
            admin.sendMessage("§cMiasto przypisane do dummy już nie istnieje.");
            return;
        }
        Inventory inv = inventory(admin, NativeTownMenuHolder.Page.DUMMY_PERMISSIONS, 45, "§8DEV Dummy §7- uprawnienia");
        fill(inv);
        inv.setItem(4, playerHead(dummy.id(), "§d" + dummy.name(), List.of(
                "§7Miasto: §f" + town.name(),
                "§7To syntetyczny członek developerski.",
                "§8Nie jest zapisywany w bazie, nie zwiększa liczby",
                "§8członków i nie wpływa na kolekcje ani ceny."
        )));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        TownPermission[] permissions = TownPermission.values();
        for (int i = 0; i < permissions.length && i < slots.length; i++) {
            TownPermission permission = permissions[i];
            boolean allowed = dummy.can(permission);
            inv.setItem(slots[i], item(allowed ? Material.LIME_DYE : Material.GRAY_DYE,
                    (allowed ? "§a" : "§c") + permissionLabel(permission),
                    List.of("§7Status: " + (allowed ? "§aDozwolone" : "§cZablokowane"), "§eKliknij, aby przełączyć dla dummy.")));
        }
        inv.setItem(31, item(Material.ENDER_EYE, "§dPrzełącz się na dummy", List.of(
                "§7Otwiera miasto jako ten syntetyczny członek.",
                "§8Nie tworzy prawdziwego członkostwa.",
                "", "§eKliknij."
        )));
        inv.setItem(36, item(Material.ARROW, "§ePowrót", List.of("§7Wróć do menu graczy.")));
        inv.setItem(40, item(Material.BARRIER, "§cZamknij", List.of()));
        admin.openInventory(inv);
    }

    private void handleDummyPermissionsClick(Player player, int slot) {
        if (slot == 40) { player.closeInventory(); return; }
        if (slot == 36) { openCoop(player); return; }
        if (slot == 31) {
            if (useDeveloperDummy(player)) openManage(player);
            return;
        }
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        TownPermission[] permissions = TownPermission.values();
        for (int i = 0; i < permissions.length && i < slots.length; i++) {
            if (slot != slots[i]) continue;
            if (toggleDeveloperDummyPermission(player, permissions[i])) openDeveloperDummyPermissions(player);
            return;
        }
    }

    private String permissionLabel(TownPermission permission) {
        return switch (permission) {
            case BUILD -> "Budowanie";
            case BREAK -> "Niszczenie bloków";
            case CONTAINERS -> "Skrzynie i magazyny";
            case MINION_USE -> "Obsługa minionów";
            case MINION_PICKUP -> "Podnoszenie minionów";
            case MACHINE_USE -> "Obsługa maszyn";
            case MACHINE_BREAK -> "Niszczenie maszyn";
            case BANK_WITHDRAW -> "Wypłaty z Banku Miasta";
        };
    }

    public void openDanger(Player player) {
        Town town = requireTown(player);
        if (town == null) return;
        boolean owner = isOwnerForView(player, town);
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.DANGER, 45, "§4Zniszczenie miasta");
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, item(Material.REDSTONE_TORCH, "§cUwaga", List.of(
                "§7Miasto: §f" + town.name(),
                "§cAkcje w tym menu mogą resetować dane progresji miasta."
        )));
        if (owner) {
            inv.setItem(22, item(Material.TNT, "§4Zniszcz miasto", List.of(
                    "§cUsuwa miasto i resetuje dane członków.",
                    "§7Po kliknięciu dostaniesz potwierdzenie w czacie.",
                    "",
                    "§eKliknij: /town destroy"
            )));
        } else {
            inv.setItem(22, item(Material.RED_BED, "§cOpuść miasto", List.of(
                    "§cOdejście z miasta resetuje twoje dane SMP.",
                    "§7Po kliknięciu dostaniesz potwierdzenie w czacie.",
                    "",
                    "§eKliknij, aby opuścić miasto."
            )));
        }
        inv.setItem(36, item(Material.ARROW, "§ePowrót", List.of()));
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
            case BANK -> handleBankClick(player, slot);
            case GUIDE, GUIDE_GROWTH, GUIDE_PLAYERS -> guideService.handleClick(player, holder.page(), slot);
            case DUMMY_PERMISSIONS -> handleDummyPermissionsClick(player, slot);
            case DANGER -> handleDangerClick(player, slot);
        }
    }

    private void handleMainClick(Player player, int slot, ClickType click) {
        Town town = currentTown(player);
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 20) { if (town != null) openManage(player); return; }
        if (slot == 22) {
            if (town == null) {
                if (isDeveloperPreview(player)) openCoop(player); else run(player, "town coop");
            } else openClaims(player);
            return;
        }
        if (slot == 24) { openCoop(player); return; }
        if (slot == 30) { if (town != null) openCollections(player, NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES); return; }
        if (slot == 32) { if (town != null) openMinions(player); return; }
        if (slot == 34) { run(player, click.isRightClick() ? "town info" : "town here"); return; }
        if (slot == 38) { guideService.open(player); return; }
        if (slot == 40) { toggleCheck(player); }
    }

    private void handleManageClick(Player player, int slot, ClickType click) {
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45) { player.closeInventory(); return; }
        if (slot == 20) { openClaims(player); return; }
        if (slot == 21) { openCoop(player); return; }
        if (slot == 22) {
            if (isDeveloperPreview(player)) {
                player.sendMessage("§dPodgląd DEV jest tylko do odczytu — zmiana nazwy została zablokowana.");
                return;
            }
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
        if (slot == 31) { openBank(player); return; }
        if (slot == 39) { run(player, "minion wiki electronics"); return; }
        if (slot == 40) { guideService.open(player); return; }
        if (slot == 41) { run(player, "minion wiki"); return; }
        if (slot == 53) { openDanger(player); }
    }

    private void handleClaimsClick(Player player, int slot, ClickType click) {
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45) { openManageOrMain(player); return; }
        if (slot == 19) { return; }
        if (slot == 21) { player.closeInventory(); mapService.openMap(player); return; }
        if (slot == 23) { toggleCheck(player); return; }
        if (slot == 25) { guideService.openGrowth(player); return; }
    }

    private void handleCoopClick(Player player, int slot, ClickType click) {
        Town town = currentTown(player);
        if (slot == 53) { player.closeInventory(); return; }
        if (slot == 45) { openManageOrMain(player); return; }
        if (slot == 40) { guideService.openPlayers(player); return; }
        if (town == null) {
            if (slot == 31) {
                if (isDeveloperPreview(player)) player.sendMessage("§dPodgląd DEV nie wysyła próśb COOP.");
                else run(player, "town coop");
            }
            return;
        }
        DeveloperDummyState dummy = developerDummy(player).filter(value -> value.townId().equals(town.id())).orElse(null);
        if (slot == 42 && dummy != null) {
            if (click.isRightClick() && isOwnerForView(player, town)) {
                openDeveloperDummyPermissions(player);
            } else if (useDeveloperDummy(player)) {
                openManage(player);
            }
            return;
        }
        boolean owner = isOwnerForView(player, town);
        if (owner) {
            List<TownsService.MemberInfo> members = service.memberInfos(town);
            for (int i = 0; i < Math.min(members.size(), MEMBER_SLOTS.length); i++) {
                if (slot == MEMBER_SLOTS[i]) {
                    TownsService.MemberInfo member = members.get(i);
                    if (member.role() != TownRole.OWNER) {
                        player.closeInventory();
                        if (isDeveloperPreview(player) && developerView(player).map(state -> state.mode() == DeveloperViewMode.OWNER).orElse(false)) {
                            Bukkit.getScheduler().runTask(plugin, () -> coopDecisionMenu.openMemberKickAsAdmin(player, town, member.playerId(), member.name()));
                        } else {
                            Bukkit.getScheduler().runTask(plugin, () -> coopDecisionMenu.openMemberKick(player, member.playerId(), member.name()));
                        }
                    }
                    return;
                }
            }
            List<TownsService.CoopRequestInfo> requests = service.pendingCoopRequests(town, REQUEST_SLOTS.length);
            for (int i = 0; i < Math.min(requests.size(), REQUEST_SLOTS.length); i++) {
                if (slot == REQUEST_SLOTS[i]) {
                    if (isDeveloperPreview(player)) {
                        player.sendMessage("§dTryb DEV właściciela nie przyjmuje ani nie odrzuca próśb COOP.");
                        return;
                    }
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
        if (isDeveloperPreview(player)) {
            if (indexOf(MINION_SLOTS, slot) >= 0) player.sendMessage("§dPodgląd DEV miasta jest tylko do odczytu — akcja na minionie została zablokowana.");
            return;
        }
        int index = indexOf(MINION_SLOTS, slot);
        if (index < 0) return;
        Object data = minionMenuDataByIndex(player, index + 1);
        if (data == null) return;
        String rawId = stringResult(data, "id");
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException ignored) {
            return;
        }

        Object minions = service("hex.minions.api.MinionsApi");
        if (minions == null) return;
        player.closeInventory();

        if (click == ClickType.SHIFT_LEFT) {
            handleMinionOperation(player, invoke(minions, "pickup", new Class<?>[]{Player.class, UUID.class}, player, id));
            return;
        }
        if (click == ClickType.SHIFT_RIGHT) {
            handleMinionOperation(player, invoke(minions, "upgrade", new Class<?>[]{Player.class, UUID.class}, player, id));
            return;
        }
        if (click == ClickType.MIDDLE || click == ClickType.CREATIVE) {
            handleMinionOperation(player, invoke(minions, "move", new Class<?>[]{Player.class, UUID.class, Location.class}, player, id, player.getLocation()));
            return;
        }
        if (click.isRightClick()) {
            invoke(minions, "openMenu", new Class<?>[]{Player.class, UUID.class}, player, id);
            return;
        }
        if (click.isLeftClick()) {
            handleMinionOperation(player, invoke(minions, "collect", new Class<?>[]{Player.class, UUID.class}, player, id));
        }
    }

    private void handleMinionOperation(Player player, Object result) {
        if (!(result instanceof CompletableFuture<?> future)) return;
        future.thenAccept(operation -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (operation == null) return;
            String messageKey = stringResult(operation, "messageKey");
            if (messageKey.isBlank() || messageKey.equals("-")) return;
            Object rawTokens = invoke(operation, "tokens");
            if (rawTokens instanceof UiTokens tokens) api.ui().send(player, messageKey, tokens);
            else api.ui().send(player, messageKey);
        }));
    }

    private void handleBankClick(Player player, int slot) {
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45) { openManageOrMain(player); return; }
        if (isDeveloperPreview(player)) {
            if (indexOf(BANK_DEPOSIT_SLOTS, slot) >= 0 || indexOf(BANK_WITHDRAW_SLOTS, slot) >= 0)
                player.sendMessage("§dPodgląd DEV Banku Miasta jest tylko do odczytu — transakcja została zablokowana.");
            return;
        }
        for (int i = 0; i < BANK_AMOUNTS.length; i++) {
            if (slot == BANK_DEPOSIT_SLOTS[i]) {
                executeBankOperation(player, service.bankDeposit(player, BANK_AMOUNTS[i]));
                return;
            }
            if (slot == BANK_WITHDRAW_SLOTS[i]) {
                executeBankOperation(player, service.bankWithdraw(player, BANK_AMOUNTS[i]));
                return;
            }
        }
    }

    private void executeBankOperation(Player player, CompletableFuture<TownsService.BankOperationResult> future) {
        UUID playerId = player.getUniqueId();
        future.thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(result.message());
            if (result.success() && service.townIdOf(playerId).isPresent()) openBank(player);
        })).exceptionally(error -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.sendMessage("§cOperacja Banku Miasta nie powiodła się.");
            });
            plugin.getLogger().warning("Town bank operation failed for " + playerId + ": " + error.getMessage());
            return null;
        });
    }

    private void handleDangerClick(Player player, int slot) {
        if (slot == 40) { player.closeInventory(); return; }
        if (slot == 36) { openManage(player); return; }
        if (isDeveloperPreview(player)) {
            if (slot == 22) player.sendMessage("§dPodgląd DEV jest tylko do odczytu — opuszczenie/zniszczenie miasta zostało zablokowane.");
            return;
        }
        Town town = currentTown(player);
        if (town == null) return;
        boolean owner = isOwnerForView(player, town);
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
            List<String> cleanLore = sanitizeLore(lore);
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
        long required = collections == null ? 0L : longResult(collections, "getNextLevelRequirement", new Class<?>[]{UUID.class, String.class}, townId, def.id());
        long remaining = collections == null ? 0L : longResult(collections, "getRemainingToNextLevel", new Class<?>[]{UUID.class, String.class}, townId, def.id());
        List<String> lore = new ArrayList<>();
        lore.add("§7Poziom: §6" + level + "§7/§6" + maxLevel);
        lore.add("§7Zebrano: §6" + format(amount));
        if (level >= maxLevel || required <= 0L) {
            lore.add("§aMaksymalny poziom osiągnięty");
        } else {
            int percent = (int) Math.max(0L, Math.min(100L, amount * 100L / Math.max(1L, required)));
            lore.add(storageBar(percent) + " §6" + percent + "%");
            lore.add("§7Do następnego: §6" + format(remaining));
        }
        lore.add("");
        lore.add("§6Top 5 miast:");
        if (collections == null) {
            lore.add("§cRanking jest chwilowo niedostępny.");
        } else {
            List<?> top = listResult(collections, "top", new Class<?>[]{String.class, int.class}, def.id(), 5);
            for (int i = 0; i < 5; i++) {
                if (i >= top.size()) break;
                Object entry = top.get(i);
                Object rawTownId = invoke(entry, "townId");
                UUID rankedTownId = rawTownId instanceof UUID uuid ? uuid : null;
                long rankedAmount = longResult(entry, "amount", new Class<?>[0]);
                String townName = rankedTownId == null ? "-" : service.findTown(rankedTownId).map(Town::name).orElse("-");
                lore.add("§e" + (i + 1) + ". §f" + townName + " §8— §a" + format(rankedAmount));
            }
        }
        ItemStack icon = new ItemStack(def.material());
        int customModelData = switch (def.id()) {
            case "foraging.spruce_resin" -> 14010;
            case "mining.uranium" -> 12001;
            case "industrial.enriched_uranium" -> 12003;
            case "mining.tin" -> 14002;
            default -> 0;
        };
        if (customModelData > 0) {
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(customModelData);
                icon.setItemMeta(meta);
            }
        }
        return named(icon, def.name(), lore);
    }

    private ItemStack minionItem(Object minionsApi, Player player, Object data, int index) {
        String id = stringResult(data, "id");
        String name = cleanName(stringResult(data, "displayName"));
        if (name.isBlank() || name.equals("-")) name = cleanName(stringResult(data, "typeId"));
        int tier = intResult(data, "tier");
        int maxTier = intResult(data, "maxTier");
        int used = intResult(data, "storageUsed");
        int limit = intResult(data, "storageLimit");
        int percent = intResult(data, "storagePercent");
        String head = stringResult(data, "headMaterial");
        Material material = material(head, Material.PLAYER_HEAD);
        ItemStack icon = isDeveloperPreview(player) ? null : minionIcon(minionsApi, player, index, id);
        if (icon == null || icon.getType().isAir()) icon = new ItemStack(material);
        return named(icon, "§b#" + index + " §f" + name, List.of(
                "§7Tier: §6" + tier + "§7/§6" + maxTier,
                "§7Sloty: §6" + used + "§7/§6" + limit,
                "§a" + storageBar(percent),
                "",
                "§eLPM §7Odbierz  §8•  §ePPM §7Otwórz",
                "§eShift+LPM §7Podnieś  §8•  §eShift+PPM §7Ulepsz",
                "§eŚPM §7Przenieś"
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
            List<String> cleanLore = sanitizeLore(lore);
            meta.setLore(cleanLore.isEmpty() ? null : cleanLore);
            addItemFlags(meta, "HIDE_ATTRIBUTES", "HIDE_ADDITIONAL_TOOLTIP");
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static List<String> sanitizeLore(List<String> lore) {
        if (lore == null || lore.isEmpty()) return List.of();
        ArrayList<String> clean = new ArrayList<>();
        boolean previousBlank = true;
        for (String line : lore) {
            if (line == null) continue;
            boolean blank = line.isBlank();
            if (blank && previousBlank) continue;
            clean.add(blank ? "" : line);
            previousBlank = blank;
        }
        while (!clean.isEmpty() && clean.get(clean.size() - 1).isBlank()) clean.remove(clean.size() - 1);
        return List.copyOf(clean);
    }

    private List<String> collectionSummaryLore(Town town) {
        if (town == null) return List.of("§7Nie należysz jeszcze do miasta.", "§8Kolekcje są przypisane do miasta.");
        Object collections = service("hex.collections.api.HexCollectionsApi");
        if (collections == null) return List.of("§7Miasto: §f" + town.name(), "§cKolekcje są chwilowo niedostępne.");
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
        if (minions == null) return List.of("§7Miasto: §f" + town.name(), "§cMiniony są chwilowo niedostępne.");
        List<?> views = listResult(minions, "minionsOfTown", new Class<?>[]{UUID.class}, town.id());
        int max = intResult(minions, "maxMinions", new Class<?>[]{UUID.class}, town.id());
        List<String> lore = new ArrayList<>();
        lore.add("§7Aktywne: §6" + views.size() + "§7/§6" + max);
        int storageUsed = views.stream().mapToInt(view -> intResult(view, "storageUsed")).sum();
        int storageLimit = views.stream().mapToInt(view -> intResult(view, "storageLimit")).sum();
        lore.add("§7Sloty miniona: §6" + storageUsed + "§7/§6" + storageLimit);
        lore.add("");
        if (views.isEmpty()) {
            lore.add("§8Brak minionów w mieście.");
        } else {
            lore.add("§7Postawione miniony:");
            views.stream().limit(5).forEach(view -> lore.add("§8• §f" + cleanName(stringResult(view, "displayName")) + " §7T" + intResult(view, "tier")));
            if (views.size() > 5) lore.add("§8... oraz " + (views.size() - 5) + " kolejnych.");
        }
        lore.add("");
        lore.add("§eKliknij, aby otworzyć.");
        return lore;
    }

    private ItemStack manageBankIcon(Town town, BigDecimal balance) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Wspólne środki członków miasta.");
        lore.add("§7Wpłaty są objęte podatkiem §f3%§7.");
        lore.add("");
        lore.add(balance == null ? "§7Saldo miasta: §8ładowanie..." : "§7Saldo miasta: §6" + formatMoney(balance) + "$");
        lore.add("");
        lore.add("§eKliknij, aby otworzyć.");
        return item(Material.GOLD_INGOT, "§6Bank Miasta", lore);
    }

    private void refreshManageBankIcon(Player player, Town town, Inventory inventory) {
        service.bankSnapshotForTown(town.id()).thenAccept(snapshot -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !snapshot.available()) return;
            if (player.getOpenInventory().getTopInventory() != inventory) return;
            Town current = currentTown(player);
            if (current == null || !current.id().equals(town.id())) return;
            inventory.setItem(31, manageBankIcon(town, snapshot.balance()));
        })).exceptionally(error -> null);
    }

    private String formatMoney(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal normalized = value.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }

    private String compactMoney(long amount) {
        if (amount == 1_000L) return "1k";
        if (amount == 2_500L) return "2.5k";
        if (amount == 5_000L) return "5k";
        if (amount == 10_000L) return "10k";
        return Long.toString(amount);
    }

    private Town actualTown(Player player) {
        return player == null ? null : service.townIdOf(player.getUniqueId()).flatMap(service::findTown).orElse(null);
    }

    private Town developerTargetTown(Player player) {
        return developerView(player).flatMap(state -> service.findTown(state.townId())).orElse(null);
    }

    private boolean isDeveloperGuestView(Player player) {
        return developerView(player).map(state -> state.mode() == DeveloperViewMode.GUEST).orElse(false);
    }

    private Town currentTown(Player player) {
        Optional<DeveloperViewState> state = developerView(player);
        if (state.isPresent()) {
            if (state.get().mode() == DeveloperViewMode.GUEST) return null;
            return service.findTown(state.get().townId()).orElse(null);
        }
        return actualTown(player);
    }

    private Optional<Town> contextualTown(Player player) {
        if (isDeveloperPreview(player)) return Optional.ofNullable(developerTargetTown(player));
        return service.townAt(player.getLocation());
    }

    private boolean isOwnerForView(Player player, Town town) {
        if (player == null || town == null) return false;
        Optional<DeveloperViewState> state = developerView(player);
        if (state.isPresent()) return state.get().mode() == DeveloperViewMode.OWNER;
        return service.isOwner(player.getUniqueId(), town.id());
    }

    private boolean isDeveloperDummyView(Player player) {
        return developerView(player).map(DeveloperViewState::dummy).orElse(false);
    }

    private boolean canBankWithdrawForView(Player player, Town town) {
        if (player == null || town == null) return false;
        Optional<DeveloperViewState> state = developerView(player);
        if (state.isPresent()) {
            if (state.get().mode() == DeveloperViewMode.OWNER) return true;
            if (state.get().dummy()) {
                DeveloperDummyState dummy = developerDummy(player).orElse(null);
                return dummy != null && dummy.townId().equals(town.id()) && dummy.can(TownPermission.BANK_WITHDRAW);
            }
            return false;
        }
        return service.can(player.getUniqueId(), town.id(), TownPermission.BANK_WITHDRAW);
    }

    private String roleForView(Player player, Town town) {
        Optional<DeveloperViewState> state = developerView(player);
        if (state.isPresent()) {
            if (state.get().mode() == DeveloperViewMode.OWNER) return "Właściciel §d(DEV)";
            if (state.get().dummy()) return "Członek Dummy §d(DEV)";
            if (state.get().mode() == DeveloperViewMode.MEMBER) return "Członek §d(DEV)";
            return "Gość §d(DEV)";
        }
        return service.isOwner(player.getUniqueId(), town.id()) ? "Właściciel" : "Członek";
    }

    private String developerStatusLine(Player player) {
        Optional<DeveloperViewState> state = developerView(player);
        if (state.isEmpty()) return "";
        if (state.get().mode() == DeveloperViewMode.OWNER) {
            return "§dTryb DEV właściciela — członkostwo/statystyki bez zmian; operacje ekonomiczne i destrukcyjne są chronione.";
        }
        if (state.get().dummy()) return "§dTryb DEV Dummy — syntetyczny członek, nie jest liczony do limitów ani progresji.";
        return "§dPodgląd DEV — nie zmienia członkostwa, kolekcji, cen ani statystyk.";
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
                new CollectionItem("animals.eggs", Material.EGG, "§eKolekcja jajek"),
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
                new CollectionItem("mining.copper", Material.COPPER_INGOT, "§6Kolekcja miedzi"),
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
                new CollectionItem("mining.tin", Material.IRON_INGOT, "§fKolekcja cyny"),
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

    private String coopRequestRemaining(long createdAtMillis) {
        long expiresAt = createdAtMillis + config.requestTtlSeconds() * 1000L;
        long remainingSeconds = Math.max(0L, (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
        long minutes = remainingSeconds / 60L;
        long seconds = remainingSeconds % 60L;
        return minutes > 0L ? minutes + "m " + seconds + "s" : seconds + "s";
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
        if (meta != null) meta.setHideTooltip(true);
    }

    private record CollectionItem(String id, Material material, String name) {}
}
