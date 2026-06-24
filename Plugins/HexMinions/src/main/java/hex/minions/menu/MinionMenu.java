package hex.minions.menu;

import hex.core.api.HexApi;
import hex.minions.api.MinionMenuData;
import hex.minions.config.ItemRequirement;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.ResourceDefinition;
import hex.minions.config.ResourceDrop;
import hex.minions.config.TierDefinition;
import hex.minions.crafting.SpecialIngredient;
import hex.minions.crafting.SpecialItemDefinition;
import hex.minions.crafting.SpecialRecipeDefinition;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineEnergyDefinition;
import hex.minions.machine.MachineRecipe;
import hex.minions.service.MinionItemFactory;
import hex.minions.service.MinionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MinionMenu {
    public static final int[] STORAGE_SLOTS = {19, 20, 21, 28, 29, 30, 37, 38, 39};
    public static final int ADDON_SLOT_1 = 24;
    public static final int ADDON_SLOT_2 = 25;
    public static final int STORAGE_CHEST_SLOT = 43;
    private static final int[] WIKI_INDEX_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int[] WIKI_TIER_SLOTS = {19, 20, 21, 22, 23, 24, 25};
    private static final int[] WIKI_SPECIAL_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    private static final int[] RECIPE_GRID_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int[] WIKI_MACHINE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] WIKI_MACHINE_RECIPE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final HexApi hex;
    private final MinionService service;
    private final MinionItemFactory itemFactory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MinionMenu(HexApi hex, MinionService service, MinionItemFactory itemFactory) {
        this.hex = hex;
        this.service = service;
        this.itemFactory = itemFactory;
    }

    public void open(Player player, UUID minionId) {
        Optional<MinionMenuData> data = service.minionData(player, minionId);
        if (data.isEmpty()) {
            hex.ui().send(player, "minions.error.not-found");
            return;
        }
        MinionMenuData d = data.get();
        service.select(player, minionId);
        Inventory inv = Bukkit.createInventory(new MinionMenuHolder(minionId), 54, miniMessage.deserialize("<dark_gray>Minion: " + d.displayName()));
        fill(inv);
        renderStaticMinionMenuItems(inv, d);
        inv.setItem(ADDON_SLOT_1, boosterSlotItem(d));
        inv.setItem(ADDON_SLOT_2, addonItem(d, "addon_2", Material.CHEST, "<green>Slot update / rozszerzenia</green>"));
        player.openInventory(inv);
    }

    public void refreshMinionInventory(Player player, UUID minionId, Inventory inv) {
        Optional<MinionMenuData> data = service.minionData(player, minionId);
        if (data.isEmpty()) return;
        renderStaticMinionMenuItems(inv, data.get());
    }

    private void renderStaticMinionMenuItems(Inventory inv, MinionMenuData d) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(d.typeId());
        inv.setItem(4, type == null ? item(Material.PLAYER_HEAD, d.displayName(), List.of(
                "<gray>Tier: <white>" + d.tier() + "/" + d.maxTier(),
                "<gray>Storage: <white>" + d.storageUsed() + "/" + d.storageLimit(),
                "<gray>Lokacja: <white>" + d.world() + " " + d.x() + "," + d.y() + "," + d.z()
        )) : minionHead(type, d.tier(), d.displayName(), List.of(
                "<gray>Typ: <white>" + d.typeId() + "</white></gray>",
                "<gray>Tier: <white>" + d.tier() + "/" + d.maxTier() + "</white></gray>",
                "<gray>Storage: <white>" + d.storageUsed() + "/" + d.storageLimit() + "</white></gray>",
                "<gray>Lokacja: <white>" + d.world() + " " + d.x() + "," + d.y() + "," + d.z() + "</white></gray>",
                "",
                "<yellow>Booster:</yellow>",
                boosterSummaryLine(d),
                boosterQueueLine(d),
                "",
                "<yellow>PPM w menu miasta otwiera to menu.</yellow>"
        )));
        renderStorage(inv, d);
        inv.setItem(STORAGE_CHEST_SLOT, storageChestStatus(d));
        inv.setItem(45, item(Material.ENDER_PEARL, "<aqua>Przenieś tutaj</aqua>", List.of("<gray>Przenieś do pozycji, w której stoisz.</gray>")));
        inv.setItem(47, item(Material.BOOK, "<aqua>Wiki minionów</aqua>", List.of("<gray>Zobacz wszystkie skonfigurowane typy minionów.</gray>")));
        inv.setItem(48, item(Material.CHEST, "<green>Odbierz wszystko</green>", List.of(
                "<gray>Przenosi całe storage do ekwipunku jako zwykłe itemy.</gray>",
                "<yellow>Kliknij pojedynczy surowiec w storage, aby odebrać tylko ten stack.</yellow>"
        )));
        inv.setItem(50, item(Material.ANVIL, "<gold>Ulepsz</gold>", List.of("<gray>Wymagania: <white>" + d.nextUpgradeRequirementsText())));
        inv.setItem(53, item(Material.BARRIER, "<red>Podnieś miniona</red>", List.of("<gray>Zwraca item miniona.</gray>")));
    }

    public void openWiki(Player player) {
        openWikiPage(player, 0);
    }

    public void openWikiPage(Player player, int page) {
        List<MinionTypeDefinition> types = sortedWikiTypes();
        int totalPages = Math.max(1, (int) Math.ceil(types.size() / (double) WIKI_INDEX_SLOTS.length));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        Inventory inv = Bukkit.createInventory(new MinionWikiHolder("", safePage), 54, miniMessage.deserialize("<dark_gray>Wiki minionów " + (safePage + 1) + "/" + totalPages));
        fill(inv);
        inv.setItem(4, item(Material.BOOK, "<aqua>Wiki minionów</aqua>", List.of(
                "<gray>Lista jest generowana automatycznie z pliku</gray>",
                "<white>minion-types.yml</white><gray>.</gray>",
                "<gray>Strona: <white>" + (safePage + 1) + "/" + totalPages + "</white></gray>",
                "",
                "<yellow>Kliknij główkę, aby zobaczyć poziomy, dropy i wymagania.</yellow>"
        )));
        int offset = safePage * WIKI_INDEX_SLOTS.length;
        for (int i = 0; i < WIKI_INDEX_SLOTS.length && offset + i < types.size(); i++) {
            MinionTypeDefinition type = types.get(offset + i);
            inv.setItem(WIKI_INDEX_SLOTS[i], minionHead(type, 1, type.displayName(), wikiIndexLore(type)));
        }
        if (safePage > 0) {
            inv.setItem(48, item(Material.ARROW, "<yellow>Poprzednia strona</yellow>", List.of("<gray>Przejdź do strony <white>" + safePage + "</white>.</gray>")));
        }
        if (safePage + 1 < totalPages) {
            inv.setItem(50, item(Material.ARROW, "<yellow>Następna strona</yellow>", List.of("<gray>Przejdź do strony <white>" + (safePage + 2) + "</white>.</gray>")));
        }
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Zamknij i wróć do menu miasta.</gray>")));
        player.openInventory(inv);
    }

    public void openWikiType(Player player, String typeId) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(typeId);
        if (type == null || !type.enabled()) {
            hex.ui().send(player, "minions.error.unknown-type");
            return;
        }
        Inventory inv = Bukkit.createInventory(new MinionWikiHolder(type.id()), 54, miniMessage.deserialize("<dark_gray>Wiki: " + type.displayName()));
        fill(inv);
        inv.setItem(4, minionHead(type, 1, type.displayName(), wikiHeaderLore(type)));
        for (int tier = 1; tier <= WIKI_TIER_SLOTS.length; tier++) {
            inv.setItem(WIKI_TIER_SLOTS[tier - 1], tierGlass(type, tier));
        }
        renderWikiSpecialItems(inv, type);
        inv.setItem(37, item(Material.CHEST, "<green>Dropy miniona</green>", dropsLore(type)));
        inv.setItem(39, item(Material.CLOCK, "<aqua>Efekty poziomów</aqua>", tierSummaryLore(type)));
        inv.setItem(41, item(Material.WRITABLE_BOOK, "<yellow>Konfiguracja</yellow>", List.of(
                "<gray>Wszystkie dane tej strony są pobierane z:</gray>",
                "<white>minion-types.yml</white>",
                "",
                "<gray>Dodanie nowego typu miniona do konfiguracji</gray>",
                "<gray>automatycznie doda go do wiki.</gray>"
        )));
        List<MachineDefinition> machines = wikiMachinesFor(type);
        if (!machines.isEmpty()) {
            inv.setItem(43, item(Material.REDSTONE, "<aqua>Urządzenia i maszyny</aqua>", List.of(
                    "<gray>Ten minion odblokowuje albo opisuje maszyny.</gray>",
                    "<gray>Liczba urządzeń: <white>" + machines.size() + "</white></gray>",
                    "",
                    "<yellow>Kliknij, aby zobaczyć procesy, EU i receptury.</yellow>"
            )));
        }
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do listy</yellow>", List.of("<gray>Kliknij, aby wrócić do wiki minionów.</gray>")));
        player.openInventory(inv);
    }


    public void openRecipe(Player player, String recipeId, String returnTypeId) {
        SpecialRecipeDefinition recipe = service.specialItems().recipe(recipeId).orElse(null);
        if (recipe == null) { openWikiType(player, returnTypeId); return; }
        Inventory inv = Bukkit.createInventory(new SpecialRecipeMenuHolder(recipe.id(), returnTypeId == null ? "" : returnTypeId), 54, miniMessage.deserialize("<dark_gray>Receptura: " + recipe.id()));
        fill(inv);
        for (int row = 0; row < 3; row++) {
            String line = recipe.shape().get(row);
            for (int col = 0; col < 3; col++) {
                char key = line.charAt(col);
                SpecialIngredient ingredient = recipe.ingredients().get(key);
                inv.setItem(RECIPE_GRID_SLOTS[row * 3 + col], ingredient == null || key == ' ' ? item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()) : ingredientIcon(ingredient));
            }
        }
        inv.setItem(16, item(Material.ARROW, "<yellow>Wynik</yellow>", List.of("<gray>Przedmiot po prawej to output receptury.</gray>")));
        inv.setItem(25, service.recipeOutput(recipe));
        inv.setItem(43, stationIcon(recipe));
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do wiki miniona.</gray>")));
        player.openInventory(inv);
    }

    public void openWikiMachines(Player player, String returnTypeId) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(returnTypeId);
        if (type == null || !type.enabled()) {
            openWiki(player);
            return;
        }
        List<MachineDefinition> machines = wikiMachinesFor(type);
        Inventory inv = Bukkit.createInventory(MinionWikiHolder.machineIndex(type.id()), 54, miniMessage.deserialize("<dark_gray>Wiki maszyn: " + type.displayName()));
        fill(inv);
        inv.setItem(4, item(Material.REDSTONE, "<aqua>Urządzenia powiązane z minionem</aqua>", List.of(
                "<gray>Pokazuje maszyny odblokowywane przez ten typ miniona</gray>",
                "<gray>oraz urządzenia opisane w jego wiki.</gray>",
                "",
                "<yellow>Kliknij maszynę, aby zobaczyć procesy.</yellow>"
        )));
        for (int i = 0; i < Math.min(machines.size(), WIKI_MACHINE_SLOTS.length); i++) {
            MachineDefinition machine = machines.get(i);
            inv.setItem(WIKI_MACHINE_SLOTS[i], machineIcon(machine, List.of(
                    "<gray>Typ: <white>" + machine.type() + "</white></gray>",
                    "<gray>Procesy: <white>" + machineProcessCount(machine) + "</white></gray>",
                    energySummaryLine(machine),
                    "",
                    "<yellow>Kliknij, aby wejść w wiki urządzenia.</yellow>"
            )));
        }
        if (machines.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "<red>Brak urządzeń</red>", List.of(
                    "<gray>Nie znaleziono maszyny odblokowywanej przez ten typ miniona.</gray>",
                    "<dark_gray>Sprawdź special-items.yml → recipes → unlock.</dark_gray>"
            )));
        }
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do miniona</yellow>", List.of("<gray>Wróć do wiki tego miniona.</gray>")));
        player.openInventory(inv);
    }

    public void openWikiMachine(Player player, String returnTypeId, String machineId) {
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        if (machine == null) {
            openWikiMachines(player, returnTypeId);
            return;
        }
        Inventory inv = Bukkit.createInventory(MinionWikiHolder.machine(returnTypeId, machine.id()), 54, miniMessage.deserialize("<dark_gray>Wiki: " + stripMini(machine.displayName())));
        fill(inv);
        inv.setItem(4, machineIcon(machine, machineHeaderLore(machine)));
        List<String> processIds = wikiMachineProcessIds(machine);
        for (int i = 0; i < Math.min(processIds.size(), WIKI_MACHINE_RECIPE_SLOTS.length); i++) {
            String processId = processIds.get(i);
            inv.setItem(WIKI_MACHINE_RECIPE_SLOTS[i], machineProcessIcon(machine, processId));
        }
        if (processIds.isEmpty()) {
            inv.setItem(22, item(Material.PAPER, "<gray>Brak procesów</gray>", List.of(
                    "<gray>Ta maszyna nie ma skonfigurowanych receptur ani paliw.</gray>",
                    "<dark_gray>Dodaj wpisy w machines.yml.</dark_gray>"
            )));
        }
        inv.setItem(40, machineCraftingRecipeIcon(machine));
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do urządzeń</yellow>", List.of("<gray>Wróć do listy maszyn tego miniona.</gray>")));
        player.openInventory(inv);
    }

    public void openWikiMachineRecipe(Player player, String returnTypeId, String machineId, String recipeId) {
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        if (machine == null) {
            openWikiMachines(player, returnTypeId);
            return;
        }
        Inventory inv = Bukkit.createInventory(MinionWikiHolder.machineRecipe(returnTypeId, machine.id(), recipeId), 54, miniMessage.deserialize("<dark_gray>Proces: " + stripMini(machine.displayName())));
        fill(inv);
        inv.setItem(4, machineIcon(machine, machineHeaderLore(machine)));
        if (recipeId != null && recipeId.startsWith("fuel:")) {
            renderGeneratorFuelRecipe(inv, machine, recipeId.substring("fuel:".length()));
        } else {
            MachineRecipe recipe = machine.recipes().stream().filter(r -> r.id().equalsIgnoreCase(recipeId)).findFirst().orElse(null);
            if (recipe == null) {
                openWikiMachine(player, returnTypeId, machine.id());
                return;
            }
            renderMachineRecipe(inv, machine, recipe);
        }
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do maszyny</yellow>", List.of("<gray>Wróć do listy procesów tej maszyny.</gray>")));
        player.openInventory(inv);
    }

    public void openEnchantedCrafting(Player player, String stationId) {
        Inventory inv = Bukkit.createInventory(new EnchantedCraftingMenuHolder(stationId), 54, miniMessage.deserialize("<dark_gray>Enchanted Crafting"));
        fill(inv);
        for (int slot : RECIPE_GRID_SLOTS) inv.setItem(slot, null);
        inv.setItem(4, item(Material.ENCHANTING_TABLE, "<aqua>Enchanted Crafting Table</aqua>", List.of(
                "<gray>Włóż itemy w grid 3x3.</gray>",
                "<gray>Wynik aktualizuje się automatycznie.</gray>",
                "<yellow>Kliknij wynik, aby stworzyć 1 sztukę.</yellow>",
                "<yellow>Shift+klik wyniku tworzy maksymalną możliwą liczbę.</yellow>"
        )));
        inv.setItem(16, item(Material.ARROW, "<yellow>Craft</yellow>", List.of("<gray>Kliknij item wyniku po prawej.</gray>")));
        inv.setItem(24, item(Material.GRAY_STAINED_GLASS_PANE, "<gray>Brak dopasowanej receptury</gray>", List.of("<dark_gray>Ułóż składniki w gridzie 3x3.</dark_gray>")));
        inv.setItem(33, item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
        player.openInventory(inv);
    }

    public String wikiTypeAtSlot(int slot) {
        return wikiTypeAtSlot(slot, 0);
    }

    public String wikiTypeAtSlot(int slot, int page) {
        List<MinionTypeDefinition> types = sortedWikiTypes();
        int offset = Math.max(0, page) * WIKI_INDEX_SLOTS.length;
        for (int i = 0; i < WIKI_INDEX_SLOTS.length && offset + i < types.size(); i++) {
            if (WIKI_INDEX_SLOTS[i] == slot) return types.get(offset + i).id();
        }
        return "";
    }

    public boolean wikiHasPage(int page) {
        return page >= 0 && page * WIKI_INDEX_SLOTS.length < sortedWikiTypes().size();
    }

    private List<MinionTypeDefinition> sortedWikiTypes() {
        Map<String, Integer> preferredOrder = Map.ofEntries(
                Map.entry("cobblestone", 10),
                Map.entry("dirt", 20),
                Map.entry("stone", 30),
                Map.entry("oak_plank", 40),
                Map.entry("oak_wood", 40),
                Map.entry("spruce_wood", 50),
                Map.entry("coal", 60),
                Map.entry("iron", 70),
                Map.entry("copper", 80),
                Map.entry("redstone", 90),
                Map.entry("zombie", 900),
                Map.entry("spider", 910),
                Map.entry("skeleton", 920),
                Map.entry("silverfish", 930),
                Map.entry("sheep", 940),
                Map.entry("pig", 950),
                Map.entry("cow", 960),
                Map.entry("chicken", 970)
        );
        return service.definitions().minionTypes().values().stream()
                .filter(MinionTypeDefinition::enabled)
                .sorted(Comparator
                        .comparingInt((MinionTypeDefinition type) -> preferredOrder.getOrDefault(type.id(), 500))
                        .thenComparing(MinionTypeDefinition::category)
                        .thenComparing(MinionTypeDefinition::id))
                .toList();
    }

    public String wikiMachineAtSlot(String returnTypeId, int slot) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(returnTypeId);
        if (type == null) return "";
        List<MachineDefinition> machines = wikiMachinesFor(type);
        for (int i = 0; i < Math.min(machines.size(), WIKI_MACHINE_SLOTS.length); i++) {
            if (WIKI_MACHINE_SLOTS[i] == slot) return machines.get(i).id();
        }
        return "";
    }

    public String wikiMachineProcessAtSlot(String machineId, int slot) {
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        if (machine == null) return "";
        List<String> processIds = wikiMachineProcessIds(machine);
        for (int i = 0; i < Math.min(processIds.size(), WIKI_MACHINE_RECIPE_SLOTS.length); i++) {
            if (WIKI_MACHINE_RECIPE_SLOTS[i] == slot) return processIds.get(i);
        }
        return "";
    }

    private List<MachineDefinition> wikiMachinesFor(MinionTypeDefinition type) {
        if (type == null) return List.of();
        ArrayList<MachineDefinition> result = new ArrayList<>();
        for (MachineDefinition machine : service.machines().machines().values()) {
            if (!machine.enabled()) continue;
            boolean related = false;
            if (machine.specialItemId() != null && !machine.specialItemId().isBlank()) {
                related = service.specialItems().recipes().values().stream().anyMatch(recipe ->
                        machine.specialItemId().equalsIgnoreCase(recipe.outputSpecialItem())
                                && recipe.unlock().townMinionLevels().containsKey(type.id().toLowerCase(java.util.Locale.ROOT)));
                if (!related) {
                    related = type.wikiSpecialItems().stream().anyMatch(id -> id.equalsIgnoreCase(machine.specialItemId()) || id.equalsIgnoreCase(machine.id()));
                }
            }
            if (related) result.add(machine);
        }
        result.sort(Comparator.comparing(MachineDefinition::displayName));
        return result;
    }

    private List<String> wikiMachineProcessIds(MachineDefinition machine) {
        ArrayList<String> ids = new ArrayList<>();
        if (machine.energy().enabled() && machine.energy().generator()) {
            java.util.LinkedHashSet<String> fuels = new java.util.LinkedHashSet<>(machine.energy().fuelEu().keySet());
            for (String fuel : fuels) ids.add("fuel:" + fuel);
        }
        for (MachineRecipe recipe : machine.recipes()) ids.add(recipe.id());
        return ids;
    }

    private int machineProcessCount(MachineDefinition machine) {
        return wikiMachineProcessIds(machine).size();
    }

    private ItemStack machineIcon(MachineDefinition machine, List<String> lore) {
        if (machine.specialItemId() != null && !machine.specialItemId().isBlank()) {
            ItemStack special = service.specialItems().createItem(machine.specialItemId(), 1);
            if (!special.getType().isAir()) {
                ItemMeta meta = special.getItemMeta();
                if (meta != null) {
                    meta.displayName(component(machine.displayName()));
                    applyLore(meta, lore);
                    hideAttributes(meta);
                    special.setItemMeta(meta);
                }
                return special;
            }
        }
        return item(machine.baseBlock(), machine.displayName(), lore);
    }

    private List<String> machineHeaderLore(MachineDefinition machine) {
        ArrayList<String> lore = new ArrayList<>();
        lore.add("<gray>ID: <white>" + machine.id() + "</white></gray>");
        lore.add("<gray>Typ: <white>" + machine.type() + "</white></gray>");
        lore.add("<gray>Procesy: <white>" + machineProcessCount(machine) + "</white></gray>");
        lore.add(energySummaryLine(machine));
        if (machine.energy().enabled()) {
            if (machine.energy().generator()) {
                lore.add("<gray>Transfer: <white>" + machine.energy().transferPerSecond() + " EU/s</white></gray>");
                lore.add("<gray>Zasilanie: <white>lewa strona, potem prawa</white></gray>");
            } else {
                lore.add("<gray>Zużycie: <white>" + machine.energy().euPerSecond() + " EU/s</white></gray>");
                lore.add("<gray>Bufor: <white>" + machine.energy().bufferCapacity() + " EU</white></gray>");
            }
        }
        lore.add("");
        lore.add("<yellow>Kliknij proces, aby zobaczyć szczegóły.</yellow>");
        return trimLore(lore, 14);
    }

    private String energySummaryLine(MachineDefinition machine) {
        MachineEnergyDefinition energy = machine.energy();
        if (!energy.enabled()) return "<gray>Energia: <dark_gray>nie wymaga EU</dark_gray></gray>";
        if (energy.generator()) return "<gray>Energia: <green>generator</green>, bufor <white>" + energy.bufferCapacity() + " EU</white></gray>";
        return "<gray>Energia: <aqua>odbiornik</aqua>, <white>" + energy.euPerSecond() + " EU/s</white></gray>";
    }

    private ItemStack machineProcessIcon(MachineDefinition machine, String processId) {
        if (processId.startsWith("fuel:")) {
            String fuel = processId.substring("fuel:".length());
            int eu = machine.energy().fuelEu(fuel);
            int burn = machine.energy().fuelBurnSeconds(fuel, 8);
            return fuelIcon(fuel, 1, "<gold>Paliwo: " + prettyId(fuel) + "</gold>", List.of(
                    "<gray>Dostarcza: <yellow>" + eu + " EU</yellow></gray>",
                    "<gray>Czas spalania: <white>" + burn + "s</white></gray>",
                    "<gray>Maszyna: <white>" + stripMini(machine.displayName()) + "</white></gray>",
                    "",
                    "<yellow>Kliknij, aby zobaczyć schemat.</yellow>"
            ));
        }
        MachineRecipe recipe = machine.recipes().stream().filter(r -> r.id().equalsIgnoreCase(processId)).findFirst().orElse(null);
        if (recipe == null) return item(Material.BARRIER, "<red>Nieznany proces</red>", List.of("<gray>Brak receptury w machines.yml.</gray>"));
        ItemStack icon = machineRecipeOutputIcon(recipe);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(component("<aqua>" + prettyId(recipe.id()) + "</aqua>"));
            applyLore(meta, List.of(
                    "<gray>Wynik: <white>" + itemLabel(recipe.outputSpecialItem(), recipe.outputMaterial()) + " x" + recipe.outputAmount() + "</white></gray>",
                    "<gray>Czas: <white>" + recipe.timeSeconds() + "s</white></gray>",
                    machineRecipeEnergyLine(machine, recipe),
                    "<gray>Szansa: <green>" + chance(recipe.successChance()) + "%</green></gray>",
                    "",
                    "<yellow>Kliknij, aby zobaczyć schemat.</yellow>"
            ));
            hideAttributes(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void renderMachineRecipe(Inventory inv, MachineDefinition machine, MachineRecipe recipe) {
        inv.setItem(20, machineRecipeInputIcon(recipe));
        if (hasSecondary(recipe)) inv.setItem(21, machineRecipeSecondaryIcon(recipe));
        if (hasFuel(recipe)) inv.setItem(22, machineRecipeFuelIcon(recipe));
        inv.setItem(16, item(Material.ARROW, "<yellow>Proces</yellow>", List.of(
                "<gray>Czas: <white>" + recipe.timeSeconds() + "s</white></gray>",
                machineRecipeEnergyLine(machine, recipe),
                "<gray>Szansa powodzenia: <green>" + chance(recipe.successChance()) + "%</green></gray>"
        )));
        inv.setItem(24, machineRecipeOutputIcon(recipe));
        inv.setItem(31, machineIcon(machine, List.of(
                "<gray>Proces wykonywany w tej maszynie.</gray>",
                "<gray>Station ID: <white>" + machine.stationId() + "</white></gray>",
                energySummaryLine(machine)
        )));
        inv.setItem(43, item(Material.PAPER, "<aqua>Szczegóły procesu</aqua>", List.of(
                "<gray>ID receptury: <white>" + recipe.id() + "</white></gray>",
                "<gray>Konfiguracja: <white>machines.yml → " + machine.id() + " → recipes</white></gray>",
                "<gray>Wejście główne: <white>" + recipe.inputAmount() + "x " + itemLabel(recipe.inputSpecialItem(), recipe.inputMaterial()) + "</white></gray>",
                hasSecondary(recipe) ? "<gray>Drugie wejście: <white>" + recipe.secondaryAmount() + "x " + itemLabel(recipe.secondarySpecialItem(), recipe.secondaryMaterial()) + "</white></gray>" : "<dark_gray>Drugie wejście: brak</dark_gray>",
                hasFuel(recipe) ? "<gray>Paliwo: <white>" + recipe.fuelAmount() + "x " + itemLabel(recipe.fuelSpecialItem(), recipe.fuelMaterial()) + "</white></gray>" : "<dark_gray>Paliwo: brak</dark_gray>"
        )));
    }

    private void renderGeneratorFuelRecipe(Inventory inv, MachineDefinition machine, String fuel) {
        int eu = machine.energy().fuelEu(fuel);
        int burn = machine.energy().fuelBurnSeconds(fuel, 8);
        inv.setItem(20, fuelIcon(fuel, 1, "<gold>Wejście paliwa</gold>", List.of("<gray>Włóż do slotu paliwa generatora.</gray>")));
        inv.setItem(16, item(Material.ARROW, "<yellow>Spalanie</yellow>", List.of(
                "<gray>Czas: <white>" + burn + "s</white></gray>",
                "<gray>Produkcja: <yellow>" + eu + " EU</yellow></gray>",
                "<gray>Średnio: <white>" + (burn <= 0 ? eu : eu / Math.max(1, burn)) + " EU/s</white></gray>"
        )));
        inv.setItem(24, item(Material.REDSTONE_BLOCK, "<yellow>Energia</yellow>", List.of(
                "<gray>Generator otrzymuje: <yellow>" + eu + " EU</yellow></gray>",
                "<gray>Bufor generatora: <white>" + machine.energy().bufferCapacity() + " EU</white></gray>",
                "<gray>Transfer: <white>" + machine.energy().transferPerSecond() + " EU/s</white></gray>"
        )));
        inv.setItem(31, machineIcon(machine, List.of(
                "<gray>Proces paliwowy generatora.</gray>",
                "<gray>Kolejność zasilania: <white>lewa, potem prawa</white></gray>"
        )));
        inv.setItem(43, item(Material.PAPER, "<aqua>Szczegóły spalania</aqua>", List.of(
                "<gray>Paliwo: <white>" + prettyId(fuel) + "</white></gray>",
                "<gray>EU: <yellow>" + eu + "</yellow></gray>",
                "<gray>Czas spalania: <white>" + burn + "s</white></gray>",
                "<gray>Konfiguracja: <white>machines.yml → " + machine.id() + " → energy</white></gray>"
        )));
    }

    private ItemStack machineCraftingRecipeIcon(MachineDefinition machine) {
        if (machine.specialItemId() == null || machine.specialItemId().isBlank()) {
            return item(Material.CRAFTING_TABLE, "<gray>Brak receptury bloku</gray>", List.of("<gray>Maszyna nie ma special-item.</gray>"));
        }
        Optional<String> recipeId = recipeIdForSpecialOutput(machine.specialItemId());
        if (recipeId.isEmpty()) {
            return item(Material.CRAFTING_TABLE, "<gray>Brak receptury bloku</gray>", List.of(
                    "<gray>Nie znaleziono receptury tworzącej item maszyny.</gray>",
                    "<dark_gray>special-item: " + machine.specialItemId() + "</dark_gray>"
            ));
        }
        SpecialRecipeDefinition recipe = service.specialItems().recipe(recipeId.get()).orElse(null);
        if (recipe == null) return item(Material.CRAFTING_TABLE, "<gray>Brak receptury bloku</gray>", List.of());
        ItemStack icon = service.recipeOutput(recipe);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(component("<yellow>Receptura stworzenia maszyny</yellow>"));
            applyLore(meta, List.of(
                    "<gray>Kliknij item maszyny w sekcji specjalnej wiki</gray>",
                    "<gray>albo użyj receptury: <white>" + recipe.id() + "</white></gray>",
                    "<gray>Crafting w: <white>" + recipe.station() + "</white></gray>",
                    "<gray>Wymagania: <yellow>" + service.recipeUnlockText(recipe) + "</yellow></gray>"
            ));
            hideAttributes(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private String machineRecipeEnergyLine(MachineDefinition machine, MachineRecipe recipe) {
        if (!machine.energy().enabled()) return "<gray>EU: <dark_gray>nie wymaga</dark_gray></gray>";
        if (machine.energy().generator()) return "<gray>EU: <green>proces generatora</green></gray>";
        long total = (long) machine.energy().euPerSecond() * Math.max(1, recipe.timeSeconds());
        return "<gray>Zużycie: <aqua>" + machine.energy().euPerSecond() + " EU/s</aqua> = <white>" + total + " EU</white></gray>";
    }

    private ItemStack machineRecipeInputIcon(MachineRecipe recipe) {
        return machineIngredientIcon(recipe.inputSpecialItem(), recipe.inputMaterial(), recipe.inputCustomModelData(), recipe.inputAmount(), "<white>Wejście</white>");
    }

    private ItemStack machineRecipeSecondaryIcon(MachineRecipe recipe) {
        return machineIngredientIcon(recipe.secondarySpecialItem(), recipe.secondaryMaterial(), 0, recipe.secondaryAmount(), "<white>Drugie wejście</white>");
    }

    private ItemStack machineRecipeFuelIcon(MachineRecipe recipe) {
        return machineIngredientIcon(recipe.fuelSpecialItem(), recipe.fuelMaterial(), 0, recipe.fuelAmount(), "<gold>Paliwo</gold>");
    }

    private ItemStack machineRecipeOutputIcon(MachineRecipe recipe) {
        return machineIngredientIcon(recipe.outputSpecialItem(), recipe.outputMaterial(), recipe.outputCustomModelData(), recipe.outputAmount(), "<green>Wynik</green>");
    }

    private ItemStack machineIngredientIcon(String specialItemId, Material material, int customModelData, int amount, String role) {
        ItemStack icon;
        if (specialItemId != null && !specialItemId.isBlank()) icon = service.specialItems().createItem(specialItemId, amount);
        else icon = item(material == Material.AIR ? Material.PAPER : material, "<white>" + (material == Material.AIR ? "Item" : material.name()) + "</white>", List.of(), amount, customModelData);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(component(role + ": <white>" + itemLabel(specialItemId, material) + " x" + amount + "</white>"));
            applyLore(meta, List.of("<gray>Element receptury maszyny.</gray>"));
            hideAttributes(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack fuelIcon(String fuel, int amount, String name, List<String> lore) {
        if (fuel == null || fuel.isBlank()) return item(Material.COAL, name, lore, amount, 0);
        if (service.specialItems().item(fuel).isPresent()) {
            ItemStack icon = service.specialItems().createItem(fuel, amount);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(component(name));
                applyLore(meta, lore);
                hideAttributes(meta);
                icon.setItemMeta(meta);
            }
            return icon;
        }
        Material material = Material.matchMaterial(fuel.toUpperCase(java.util.Locale.ROOT));
        return item(material == null ? Material.COAL : material, name, lore, amount, 0);
    }

    private boolean hasSecondary(MachineRecipe recipe) {
        return (recipe.secondarySpecialItem() != null && !recipe.secondarySpecialItem().isBlank()) || recipe.secondaryMaterial() != Material.AIR;
    }

    private boolean hasFuel(MachineRecipe recipe) {
        return (recipe.fuelSpecialItem() != null && !recipe.fuelSpecialItem().isBlank()) || recipe.fuelMaterial() != Material.AIR;
    }

    private String itemLabel(String specialItemId, Material material) {
        if (specialItemId != null && !specialItemId.isBlank()) {
            return service.specialItems().item(specialItemId).map(def -> stripMini(def.displayName())).orElse(prettyId(specialItemId));
        }
        return material == null || material == Material.AIR ? "brak" : prettyId(material.name());
    }

    private static String prettyId(String raw) {
        if (raw == null || raw.isBlank()) return "brak";
        String normalized = raw.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + normalized.substring(1);
    }

    private static String stripMini(String input) {
        if (input == null) return "";
        return input.replaceAll("<[^>]+>", "").trim();
    }

    private List<String> wikiIndexLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>ID: <white>" + type.id() + "</white></gray>");
        lore.add("<gray>Kategoria: <white>" + type.category() + "</white></gray>");
        lore.add("<gray>Max tier: <white>" + type.maxTier() + "</white></gray>");
        lore.addAll(craftingUnlockLore(type));
        lore.addAll(boosterWikiLore(type));
        lore.addAll(autoSmelterWikiLore(type));
        lore.add("");
        lore.add("<yellow>Co zdobywa:</yellow>");
        lore.addAll(dropsLore(type));
        lore.add("");
        lore.add("<green>Kliknij, aby zobaczyć poziomy.</green>");
        return trimLore(lore, 18);
    }

    private List<String> craftingUnlockLore(MinionTypeDefinition type) {
        Optional<SpecialRecipeDefinition> recipe = service.specialItems().recipes().values().stream()
                .filter(r -> type.id().equalsIgnoreCase(r.outputMinionType()))
                .findFirst();
        if (recipe.isEmpty()) return List.of("<dark_gray>Crafting: brak receptury miniona w special-items.yml.</dark_gray>");
        return List.of(
                "<yellow>Crafting miniona:</yellow>",
                "<gray>Receptura: <white>" + recipe.get().id() + "</white></gray>",
                "<gray>Stół: <white>" + recipe.get().station() + "</white></gray>",
                "<gray>Odblokowanie: <gold>" + service.recipeUnlockText(recipe.get()) + "</gold></gray>"
        );
    }

    private List<String> wikiHeaderLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>ID: <white>" + type.id() + "</white></gray>");
        lore.add("<gray>Kategoria: <white>" + type.category() + "</white></gray>");
        lore.add("<gray>Max tier: <white>" + type.maxTier() + "</white></gray>");
        lore.addAll(craftingUnlockLore(type));
        lore.addAll(boosterWikiLore(type));
        lore.addAll(autoSmelterWikiLore(type));
        lore.add("");
        lore.add("<yellow>Wymagania zdobycia / ulepszania są opisane</yellow>");
        lore.add("<yellow>na szybach poziomów poniżej.</yellow>");
        return lore;
    }

    private ItemStack tierGlass(MinionTypeDefinition type, int tier) {
        TierDefinition def = type.tiers().get(tier);
        Material material = tier <= 5 ? Material.LIME_STAINED_GLASS_PANE : tier == 6 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        String name = (def == null ? "<gray>Poziom " : "<white>Poziom ") + tier + (def == null ? " - nieskonfigurowany</gray>" : "</white>");
        List<String> lore = new ArrayList<>();
        if (def == null) {
            lore.add("<gray>Ten poziom nie istnieje jeszcze w konfiguracji.</gray>");
            lore.add("<dark_gray>Dodaj sekcję tiers." + tier + " w minion-types.yml.</dark_gray>");
            return item(material, name, lore);
        }
        lore.add("<yellow>Wymagania:</yellow>");
        lore.addAll(requirementsLore(def));
        lore.add("");
        lore.add("<yellow>Efekt poziomu:</yellow>");
        lore.add("<gray>Czas akcji: <white>" + def.actionTimeSeconds() + "s</white></gray>");
        lore.add("<gray>Limit storage: <white>" + def.storage() + "</white></gray>");
        lore.add("<gray>Sloty storage: <white>" + def.storageSlots() + "</white></gray>");
        lore.add("");
        lore.add("<yellow>Dropy:</yellow>");
        lore.addAll(dropsLore(type));
        return item(material, name, trimLore(lore, 24));
    }

    private List<String> tierSummaryLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        for (int tier = 1; tier <= Math.max(type.maxTier(), WIKI_TIER_SLOTS.length); tier++) {
            TierDefinition def = type.tiers().get(tier);
            if (def == null) continue;
            lore.add("<gray>Tier <white>" + tier + "</white>: <aqua>" + def.actionTimeSeconds() + "s</aqua>, storage <green>" + def.storage() + "</green>, sloty <yellow>" + def.storageSlots() + "</yellow></gray>");
        }
        return lore.isEmpty() ? List.of("<gray>Brak skonfigurowanych poziomów.</gray>") : trimLore(lore, 18);
    }

    private List<String> requirementsLore(TierDefinition tier) {
        List<String> lore = new ArrayList<>();
        if (tier.upgradeRequirements().emptyRequirements()) {
            lore.add("<gray>Brak - poziom dostępny od razu.</gray>");
            return lore;
        }
        for (Map.Entry<String, Long> entry : tier.upgradeRequirements().collectionAmounts().entrySet()) {
            lore.add("<gray>Kolekcja <white>" + entry.getKey() + "</white>: <green>" + entry.getValue() + "</green></gray>");
        }
        for (ItemRequirement item : tier.upgradeRequirements().items()) {
            lore.add("<gray>Item <white>" + item.displayName() + "</white>: <green>" + item.amount() + "x</green> <dark_gray>(" + (item.consume() ? "zużywa" : "nie zużywa") + ")</dark_gray></gray>");
        }
        return lore;
    }

    private List<String> boosterWikiLore(MinionTypeDefinition type) {
        if (type.supportedBoosterTiers().isEmpty() || service.specialItems() == null || service.specialItems().boosters().isEmpty()) {
            return List.of("<gray>Boostery: <dark_gray>brak obsługiwanych</dark_gray></gray>");
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Boostery: <green>obsługiwane</green></gray>");
        service.specialItems().boosters().values().stream()
                .filter(booster -> type.supportedBoosterTiers().contains(booster.tier()))
                .sorted(Comparator.comparingInt(booster -> booster.tier()))
                .forEach(booster -> lore.add("<gray>- <gold>Minion Booster Tier " + booster.tier() + "</gold>: <green>+" + formatPercent(booster.speedBoostPercent()) + "%</green> przez <white>" + booster.durationSeconds() + "s</white></gray>"));
        return lore;
    }

    private List<String> autoSmelterWikiLore(MinionTypeDefinition type) {
        if (type.autoSmelter() == null || !type.autoSmelter().enabled() || type.autoSmelter().replacements().isEmpty()) {
            return List.of();
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Auto Smelter: <green>obsługiwany</green></gray>");
        for (Map.Entry<String, String> entry : type.autoSmelter().replacements().entrySet()) {
            ResourceDefinition input = service.definitions().resources().get(entry.getKey());
            ResourceDefinition output = service.definitions().resources().get(entry.getValue());
            String inputName = input == null ? entry.getKey() : input.displayName();
            String outputName = output == null ? entry.getValue() : output.displayName();
            lore.add("<gray>- <white>" + inputName + "</white> → <gold>" + outputName + "</gold></gray>");
        }
        lore.add("<dark_gray>Wymaga itemu Auto Smelter w slocie update'u.</dark_gray>");
        return trimLore(lore, 8);
    }

    private List<String> dropsLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        if (type.resourceTable().isEmpty()) return List.of("<gray>Brak dropów w konfiguracji.</gray>");
        for (ResourceDrop drop : type.resourceTable()) {
            ResourceDefinition resource = service.definitions().resources().get(drop.resourceId());
            String name = resource == null ? drop.resourceId() : resource.displayName();
            String amount = drop.amountMin() == drop.amountMax() ? String.valueOf(drop.amountMin()) : drop.amountMin() + "-" + drop.amountMax();
            String special = drop.specialDrop() ? " <light_purple>★ specjalny drop</light_purple>" : "";
            lore.add("<gray>- <white>" + name + "</white> x" + amount + " <green>" + chance(drop.chance()) + "%</green>" + special + "</gray>");
            if (drop.specialDrop() && drop.specialDropPerTierBonus() > 0.0D) {
                lore.add("<dark_gray>  +" + chance(drop.specialDropPerTierBonus()) + "% za każdy tier powyżej I</dark_gray>");
            }
        }
        return lore;
    }

    private void fill(Inventory inv) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    public void openStorageChest(Player player, UUID minionId) {
        Optional<MinionMenuData> data = service.minionData(player, minionId);
        if (data.isEmpty()) {
            hex.ui().send(player, "minions.error.not-found");
            return;
        }
        Optional<org.bukkit.block.Chest> chest = service.storageChestForMenu(minionId);
        if (chest.isEmpty()) {
            open(player, minionId);
            return;
        }
        int usableSlots = service.storageChestSlotCapacity(minionId);
        int storageRows = Math.max(1, (Math.max(1, usableSlots) + 8) / 9);
        int size = Math.max(18, Math.min(54, (storageRows + 1) * 9));
        Inventory inv = Bukkit.createInventory(new MinionStorageChestMenuHolder(minionId), size, miniMessage.deserialize("<dark_gray>Storage skrzynki miniona"));
        fill(inv);
        org.bukkit.inventory.Inventory chestInv = chest.get().getBlockInventory();
        for (int i = 0; i < Math.min(usableSlots, Math.min(size, chestInv.getSize())); i++) {
            inv.setItem(i, chestInv.getItem(i));
        }
        for (int i = usableSlots; i < size; i++) {
            inv.setItem(i, item(Material.RED_STAINED_GLASS_PANE, "<red>Zablokowany slot</red>", List.of("<gray>Ta skrzynka ma pojemność: <white>" + usableSlots + "</white> sloty.</gray>")));
        }
        inv.setItem(size - 5, item(Material.ARROW, "<yellow>Powrót do miniona</yellow>", List.of("<gray>Zapisuje podgląd skrzynki i wraca do menu miniona.</gray>")));
        player.openInventory(inv);
    }

    public void saveStorageChestMenu(UUID minionId, Inventory inv) {
        service.saveStorageChestMenu(minionId, inv);
    }

    public void saveMinionMenu(UUID minionId, Inventory inv) {
        service.saveMinionMenu(minionId, inv);
    }

    public boolean isStorageSlot(int slot) {
        for (int storageSlot : STORAGE_SLOTS) if (storageSlot == slot) return true;
        return false;
    }

    public boolean isAddonSlot(int slot) {
        return slot == ADDON_SLOT_1 || slot == ADDON_SLOT_2;
    }

    public boolean isAllowedInStorage(ItemStack item) {
        return service.isResourceItem(item);
    }

    public boolean isAllowedInAddonSlot(UUID minionId, ItemStack item) {
        return service.isAllowedAddonItem(minionId, item);
    }


    public String wikiRecipeAtSlot(String typeId, int slot) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(typeId);
        if (type == null) return "";
        List<String> recipeIds = wikiRecipeIds(type);
        for (int i = 0; i < Math.min(recipeIds.size(), WIKI_SPECIAL_SLOTS.length); i++) if (WIKI_SPECIAL_SLOTS[i] == slot) return recipeIds.get(i);
        return "";
    }

    private void renderWikiSpecialItems(Inventory inv, MinionTypeDefinition type) {
        List<String> recipes = wikiRecipeIds(type);
        for (int i = 0; i < Math.min(recipes.size(), WIKI_SPECIAL_SLOTS.length); i++) {
            String id = recipes.get(i);
            SpecialRecipeDefinition recipe = service.specialItems().recipe(id).orElse(null);
            ItemStack icon;
            if (recipe == null) {
                if (service.specialItems().item(id).isEmpty()) continue;
                icon = service.specialItems().createItem(id, 1);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.lore(List.of(
                            component("<gray>Specjalny item/drop powiązany z tym minionem.</gray>"),
                            component("<dark_gray>Brak osobnej receptury w special-items.yml.</dark_gray>")
                    ));
                    icon.setItemMeta(meta);
                }
                inv.setItem(WIKI_SPECIAL_SLOTS[i], icon);
                continue;
            }
            icon = service.recipeOutput(recipe);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.lore(List.of(
                        component("<gray>Kliknij, aby zobaczyć recepturę.</gray>"),
                        component("<gray>Crafting w: <white>" + recipe.station() + "</white></gray>"),
                        component("<gray>Wymagania: <yellow>" + service.recipeUnlockText(recipe) + "</yellow></gray>")
                ));
                icon.setItemMeta(meta);
            }
            inv.setItem(WIKI_SPECIAL_SLOTS[i], icon);
        }
    }

    private List<String> wikiRecipeIds(MinionTypeDefinition type) {
        ArrayList<String> ids = new ArrayList<>();
        ids.addAll(type.wikiSpecialItems());
        // Boostery są elementem opisu miniona: jeśli dany minion obsługuje Tier I/II/etc.,
        // pokazujemy w wiki odpowiadający item i jego recepturę przed mniej ważnymi recepturami storage.
        service.specialItems().boosters().values().stream()
                .filter(booster -> type.supportedBoosterTiers().contains(booster.tier()))
                .sorted(Comparator.comparingInt(booster -> booster.tier()))
                .forEach(booster -> recipeIdForSpecialOutput(booster.specialItemId()).ifPresent(recipeId -> {
                    if (!ids.contains(recipeId)) ids.add(recipeId);
                }));
        service.specialItems().recipes().values().forEach(recipe -> {
            String lower = recipe.id().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains(type.id().toLowerCase(java.util.Locale.ROOT)) && !ids.contains(recipe.id())) ids.add(recipe.id());
        });
        service.specialItems().recipes().values().forEach(recipe -> {
            if (recipe.id().contains("storage") && !ids.contains(recipe.id())) ids.add(recipe.id());
        });
        return ids;
    }

    private Optional<String> recipeIdForSpecialOutput(String specialItemId) {
        if (specialItemId == null || specialItemId.isBlank()) return Optional.empty();
        return service.specialItems().recipes().values().stream()
                .filter(recipe -> specialItemId.equalsIgnoreCase(recipe.outputSpecialItem()))
                .map(SpecialRecipeDefinition::id)
                .findFirst();
    }

    private ItemStack ingredientIcon(SpecialIngredient ingredient) {
        if (ingredient.specialItemId() != null && !ingredient.specialItemId().isBlank()) {
            return service.specialItems().createItem(ingredient.specialItemId(), ingredient.amount());
        }
        return item(ingredient.material(), "<white>" + ingredient.material().name() + " x" + ingredient.amount() + "</white>", List.of(), ingredient.amount(), ingredient.customModelData());
    }

    private ItemStack stationIcon(SpecialRecipeDefinition recipe) {
        Material material = "VANILLA_CRAFTING_TABLE".equalsIgnoreCase(recipe.station()) ? Material.CRAFTING_TABLE : Material.ENCHANTING_TABLE;
        return item(material, "<aqua>Wykonaj w: " + recipe.station() + "</aqua>", List.of(
                "<gray>Wymagania: <yellow>" + service.recipeUnlockText(recipe) + "</yellow></gray>",
                "<dark_gray>Konfigurowalne w special-items.yml</dark_gray>"
        ));
    }

    private void renderStorage(Inventory inv, MinionMenuData data) {
        List<ItemStack> stacks = storageStacks(data.storage());
        for (int i = 0; i < STORAGE_SLOTS.length; i++) {
            int slot = STORAGE_SLOTS[i];
            int storageSlot = i + 1;
            if (storageSlot > data.storageSlotsUnlocked()) {
                inv.setItem(slot, item(Material.RED_STAINED_GLASS_PANE, "<red>Zablokowany slot storage</red>", List.of(
                        "<gray>Odblokowanie: <white>Tier " + unlockTier(data.typeId(), storageSlot) + "</white></gray>",
                        "<dark_gray>Konfiguracja: minion-types.yml → tiers → storage-slots</dark_gray>"
                )));
                continue;
            }
            inv.setItem(slot, i < stacks.size() ? stacks.get(i) : null);
        }
    }

    private List<ItemStack> storageStacks(Map<String, Long> storage) {
        ArrayList<ItemStack> result = new ArrayList<>();
        storage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ResourceDefinition def = service.definitions().resources().get(entry.getKey());
                    Material material = def == null ? Material.CHEST : def.material();
                    long remaining = entry.getValue();
                    int stackSize = def == null ? 64 : Math.max(1, Math.min(64, def.stackSize()));
                    while (remaining > 0 && result.size() < STORAGE_SLOTS.length) {
                        int amount = (int) Math.max(1, Math.min(stackSize, remaining));
                        result.add(storageStack(material, amount, def == null ? 0 : def.customModelData()));
                        remaining -= amount;
                    }
                });
        return result;
    }

    private ItemStack storageStack(Material material, int amount, int customModelData) {
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        if (customModelData > 0) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(customModelData);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private int unlockTier(String typeId, int storageSlot) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(typeId);
        if (type == null) return storageSlot;
        return type.tiers().values().stream()
                .filter(tier -> tier.storageSlots() >= storageSlot)
                .mapToInt(TierDefinition::tier)
                .min()
                .orElse(type.maxTier());
    }

    private ItemStack boosterSlotItem(MinionMenuData data) {
        ItemStack saved = service.addonItem(data.id(), "addon_1");
        if (saved != null && !saved.getType().isAir()) {
            ItemStack copy = saved.clone();
            ItemMeta meta = copy.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Slot boostera miniona.</gray>");
                lore.add(boosterSummaryLine(data));
                lore.add(boosterQueueLine(data));
                lore.add("<dark_gray>Wyjmij item, aby zatrzymać kolejkowanie kolejnych boosterów.</dark_gray>");
                applyLore(meta, lore);
                hideAttributes(meta);
                copy.setItemMeta(meta);
            }
            return copy;
        }
        return item(Material.BLAZE_POWDER, "<gold>Slot boostera miniona</gold>", List.of(
                "<gray>Włóż tutaj <white>Minion Booster Tier I</white>.</gray>",
                "<gray>Tier I: <green>+10%</green> szybkości przez <white>30s</white>.</gray>",
                "<gray>Boostery można stackować.</gray>",
                "<dark_gray>Obsługiwane tiery zależą od minion-types.yml.</dark_gray>"
        ));
    }

    private String boosterSummaryLine(MinionMenuData data) {
        if (data.activeBoosterTier() <= 0 || data.boosterSecondsRemaining() <= 0) {
            return "<gray>Brak aktywnego boostera.</gray>";
        }
        String total = data.boosterDurationSeconds() > 0 ? " z " + data.boosterDurationSeconds() + "s" : "";
        return "<gray>Aktywny: <gold>Tier " + data.activeBoosterTier() + "</gold>, pozostało <white>" + data.boosterSecondsRemaining() + "s" + total + "</white>, efekt <green>+" + formatPercent(data.boosterSpeedBoostPercent()) + "%</green>.</gray>";
    }

    private String boosterQueueLine(MinionMenuData data) {
        int queued = Math.max(0, data.boosterItemsQueued());
        int total = queued + (data.activeBoosterTier() > 0 && data.boosterSecondsRemaining() > 0 ? 1 : 0);
        return "<gray>Boostery: <white>" + total + "</white> aktywny/zapas, w slocie <yellow>" + queued + "</yellow>.</gray>";
    }

    private ItemStack addonItem(MinionMenuData data, String slotId, Material placeholder, String name) {
        ItemStack saved = service.addonItem(data.id(), slotId);
        if (saved != null && !saved.getType().isAir()) return saved;
        return item(placeholder, name, List.of(
                "<gray>Włóż tutaj specjalny item update'u.</gray>",
                "<gray>np. Auto Smelter albo inny dodatek produkcji.</gray>",
                "<dark_gray>Skrzynkę storage wkłada się w osobny slot na dole menu.</dark_gray>"
        ));
    }

    private ItemStack storageChestStatus(MinionMenuData data) {
        if (service.hasStorageChest(data.id())) {
            return item(Material.CHEST, "<green>Podpięta skrzynka storage</green>", List.of(
                    "<gray>Kliknij, aby otworzyć menu fizycznej skrzynki.</gray>",
                    "<gray>Minion najpierw próbuje wkładać dropy do tej skrzynki,</gray>",
                    "<gray>a dopiero potem do internal storage.</gray>",
                    "<dark_gray>Skrzynka jest chroniona przed ręcznym zniszczeniem.</dark_gray>"
            ));
        }
        return item(Material.ENDER_CHEST, "<yellow>Slot Minion Storage</yellow>", List.of(
                "<gray>Włóż tu <gold>Rozszerzenie storage miniona</gold>.</gray>",
                "<gray>Po kliknięciu itemem skrzynka pojawi się po lewej stronie miniona.</gray>",
                "<gray>Podstawowa skrzynka ma <white>3</white> sloty.</gray>",
                "<red>Jeśli po lewej nie ma miejsca, dostaniesz komunikat.</red>"
        ));
    }

    private ItemStack minionHead(MinionTypeDefinition type, int tier, String name, List<String> lore) {
        ItemStack item = itemFactory.createMinionItem(type, Math.max(1, Math.min(tier, type.maxTier())), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name));
            applyLore(meta, lore);
            hideAttributes(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return item(material, name, lore, 1, 0);
    }

    private ItemStack item(Material material, String name, List<String> lore, int amount, int customModelData) {
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (customModelData > 0) meta.setCustomModelData(customModelData);
            meta.displayName(component(name));
            applyLore(meta, lore);
            hideAttributes(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyLore(ItemMeta meta, List<String> lore) {
        List<Component> components = lore == null ? List.of() : lore.stream()
                .filter(line -> line != null && !line.isBlank())
                .map(this::component)
                .toList();
        meta.lore(components.isEmpty() ? null : components);
    }

    private void hideAttributes(ItemMeta meta) {
        try {
            Class<?> itemFlagClass = Class.forName("org.bukkit.inventory.ItemFlag");
            Object hideAttributes = java.lang.Enum.valueOf((Class) itemFlagClass, "HIDE_ATTRIBUTES");
            Object flagsArray = java.lang.reflect.Array.newInstance(itemFlagClass, 1);
            java.lang.reflect.Array.set(flagsArray, 0, hideAttributes);
            meta.getClass().getMethod("addItemFlags", flagsArray.getClass()).invoke(meta, flagsArray);
        } catch (Throwable ignored) {
            // Older stubs/runtime without ItemFlag support: harmless fallback.
        }
    }

    private Component component(String text) {
        if (text == null || text.isBlank()) return Component.empty();
        return miniMessage.deserialize(text);
    }

    private static String formatPercent(double percent) {
        if (Math.abs(percent - Math.rint(percent)) < 0.0001D) return String.valueOf((long) Math.rint(percent));
        return String.format(java.util.Locale.US, "%.2f", percent);
    }

    private static String chance(double chance) {
        double percent = chance * 100.0D;
        if (Math.abs(percent - Math.rint(percent)) < 0.0001D) return String.valueOf((long) Math.rint(percent));
        if (percent > 0.0D && percent < 0.01D) return String.format(java.util.Locale.US, "%.3f", percent);
        return String.format(java.util.Locale.US, "%.2f", percent);
    }

    private static List<String> trimLore(List<String> lore, int max) {
        if (lore.size() <= max) return lore;
        ArrayList<String> trimmed = new ArrayList<>(lore.subList(0, Math.max(0, max - 1)));
        trimmed.add("<dark_gray>... i więcej w konfiguracji.</dark_gray>");
        return trimmed;
    }
}
