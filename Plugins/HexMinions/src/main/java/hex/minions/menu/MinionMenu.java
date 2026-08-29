package hex.minions.menu;

import hex.core.api.HexApi;
import hex.minions.api.MinionMenuData;
import hex.minions.config.ItemRequirement;
import hex.minions.config.DynamicCollectionCost;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.ResourceDefinition;
import hex.minions.config.ResourceDrop;
import hex.minions.config.TierDefinition;
import hex.minions.crafting.SpecialIngredient;
import hex.minions.crafting.SpecialItemDefinition;
import hex.minions.crafting.SpecialRecipeDefinition;
import hex.minions.crafting.RecipeUnlockRequirement;
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
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MinionMenu {
    public static final int[] STORAGE_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32};
    public static final int ADDON_SLOT_1 = 10;
    public static final int ADDON_SLOT_2 = 19;
    public static final int STORAGE_CHEST_SLOT = 28;
    public static final int UPGRADE_SLOT = 37;
    public static final int MOVE_SLOT = 45;
    public static final int COLLECT_SLOT = 48;
    public static final int MINION_WIKI_SLOT = 49;
    public static final int ELECTRONICS_WIKI_SLOT = 50;
    public static final int PICKUP_SLOT = 53;
    private static final int[] WIKI_INDEX_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int[] WIKI_TIER_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] WIKI_SPECIAL_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int[] RECIPE_GRID_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int[] WIKI_MACHINE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] WIKI_MACHINE_RECIPE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final String ELECTRONICS_RETURN_ID = MinionWikiHolder.ELECTRONICS_RETURN_ID;

    private final HexApi hex;
    private final MinionService service;
    private final MinionItemFactory itemFactory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<UUID> wikiShowAll = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record ElectronicsWikiEntry(String id, boolean machine, String category, int order) { }
    private record VanillaFurnaceProcess(String id, ItemStack input, ItemStack output, int timeSeconds) { }
    private static final String MACHINE_WIKI_PREFIX = "machine@";
    private static final String USES_PREFIX = "__uses__:";

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
        renderStaticMinionMenuItems(inv, d, player);
        renderDynamicAddonSlots(inv, d);
        player.openInventory(inv);
    }

    public void refreshMinionInventory(Player player, UUID minionId, Inventory inv) {
        Optional<MinionMenuData> data = service.minionData(player, minionId);
        if (data.isEmpty()) return;
        MinionMenuData d = data.get();
        renderStaticMinionMenuItems(inv, d, player);
        renderDynamicAddonSlots(inv, d);
    }

    private void renderDynamicAddonSlots(Inventory inv, MinionMenuData d) {
        inv.setItem(ADDON_SLOT_1, boosterSlotItem(d));
        inv.setItem(ADDON_SLOT_2, addonItem(d, "addon_2", Material.ORANGE_STAINED_GLASS_PANE, "<gold>Ulepszenie</gold>"));
    }

    private void renderStaticMinionMenuItems(Inventory inv, MinionMenuData d, Player player) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(d.typeId());
        List<String> headerLore = new ArrayList<>();
        headerLore.add("<gray>Tier: <gold>" + d.tier() + "/" + d.maxTier() + "</gold></gray>");
        headerLore.add("<gray>Magazyn: <gold>" + d.storageUsed() + "/" + d.storageLimit() + "</gold> <gray>przedmiotów</gray>");
        if (type != null && type.tier(d.tier()) != null) {
            headerLore.add("<gray>Czas generowania: <gold>" + type.tier(d.tier()).actionTimeText() + "s</gold></gray>");
        }
        inv.setItem(4, type == null
                ? item(Material.PLAYER_HEAD, d.displayName(), headerLore)
                : minionHead(type, d.tier(), d.displayName(), headerLore));
        renderStorage(inv, d);
        inv.setItem(STORAGE_CHEST_SLOT, storageChestStatus(d));
        inv.setItem(UPGRADE_SLOT, item(Material.LIME_STAINED_GLASS_PANE, "<green>Ulepsz miniona</green>", upgradeButtonLore(d, type, player)));
        inv.setItem(MOVE_SLOT, item(Material.ENDER_PEARL, "<yellow>Przenieś tutaj</yellow>", List.of("<gray>Przenieś miniona do pozycji, w której stoisz.</gray>")));
        inv.setItem(ELECTRONICS_WIKI_SLOT, item(Material.REDSTONE, "<green>Wiki elektroniki</green>", List.of(
                "<gray>Generatory, maszyny, kable i akumulatory EU.</gray>",
                "",
                "<yellow>Kliknij, aby otworzyć.</yellow>"
        )));
        inv.setItem(MINION_WIKI_SLOT, item(Material.BOOK, "<aqua>Wiki minionów</aqua>", List.of(
                "<gray>Sprawdź typy minionów, ich surowce i rozwój.</gray>",
                "",
                "<yellow>Kliknij, aby otworzyć.</yellow>"
        )));
        inv.setItem(COLLECT_SLOT, item(Material.CHEST, "<green>Odbierz wszystko</green>", List.of(
                "<gray>Przenosi zawartość magazynu do ekwipunku.</gray>",
                "<yellow>Kliknij surowiec, aby odebrać tylko ten stack.</yellow>"
        )));
        inv.setItem(PICKUP_SLOT, item(Material.BARRIER, "<red>Podnieś miniona</red>", List.of("<gray>Zwraca miniona jako przedmiot.</gray>")));
    }

    private List<String> upgradeButtonLore(MinionMenuData data, MinionTypeDefinition type, Player player) {
        List<String> lore = new ArrayList<>();
        if (type == null || !data.canUpgrade() || data.tier() >= data.maxTier()) {
            lore.add("<gray>Minion jest już na maksymalnym poziomie.</gray>");
            return lore;
        }
        int nextTier = data.tier() + 1;
        TierDefinition current = type.tier(data.tier());
        TierDefinition next = type.tier(nextTier);
        lore.add("<yellow>Koszt / wymagania:</yellow>");
        lore.addAll(requirementsLore(next, player, nextTier));
        lore.add("<gray>Więcej graczy w mieście zwiększa wymagania kolekcji</gray>");
        lore.add("<gray>i koszt ulepszenia.</gray>");
        lore.add("");
        lore.add("<yellow>Co da ulepszenie:</yellow>");
        lore.add("<gray>Tier: <gold>" + data.tier() + "</gold> → <green>" + nextTier + "</green></gray>");
        if (current != null && next != null) {
            lore.add("<gray>Czas generowania: <gold>" + current.actionTimeText() + "s</gold> → <green>" + next.actionTimeText() + "s</green></gray>");
            lore.add("<gray>Sloty magazynu: <gold>" + current.storageSlots() + "</gold> → <green>" + next.storageSlots() + "</green></gray>");
        }
        lore.add("");
        lore.add("<yellow>Kliknij, aby ulepszyć.</yellow>");
        return trimLore(lore, 18);
    }

    public void openWiki(Player player) {
        openWikiPage(player, 0);
    }

    public void openElectronicsWiki(Player player) {
        List<ElectronicsWikiEntry> entries = electronicsEntries(player);
        Inventory inv = Bukkit.createInventory(MinionWikiHolder.electronicsIndex(), 54, miniMessage.deserialize("<dark_gray>Wiki elektroniki"));
        fill(inv);
        inv.setItem(4, item(Material.REDSTONE, "<aqua>Wiki elektroniki</aqua>", List.of(
                "<gray>Znajdziesz tu maszyny, generatory, akumulatory, kable i pozostałą elektronikę EU.</gray>",
                "",
                "<yellow>LPM: jak stworzyć • PPM: zastosowania i procesy.</yellow>"
        )));
        for (int i = 0; i < Math.min(entries.size(), WIKI_INDEX_SLOTS.length); i++) {
            ElectronicsWikiEntry entry = entries.get(i);
            inv.setItem(WIKI_INDEX_SLOTS[i], electronicsEntryIcon(player, entry));
        }
        if (entries.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "<red>Brak odblokowanej elektroniki</red>", List.of(
                    "<gray>Przełącz widok na wszystko albo odblokuj pierwsze receptury EU.</gray>"
            )));
        }
        inv.setItem(45, item(Material.BARRIER, "<yellow>Zamknij</yellow>", List.of("<gray>Wróć do poprzedniego menu.</gray>")));
        inv.setItem(53, wikiViewToggleItem(player));
        player.openInventory(inv);
    }

    public boolean isElectronicsMachineAtSlot(Player player, int slot) {
        List<ElectronicsWikiEntry> entries = electronicsEntries(player);
        for (int i = 0; i < Math.min(entries.size(), WIKI_INDEX_SLOTS.length); i++) {
            if (WIKI_INDEX_SLOTS[i] == slot) return entries.get(i).machine();
        }
        return false;
    }

    public boolean openElectronicsEntryAtSlot(Player player, int slot) {
        List<ElectronicsWikiEntry> entries = electronicsEntries(player);
        for (int i = 0; i < Math.min(entries.size(), WIKI_INDEX_SLOTS.length); i++) {
            if (WIKI_INDEX_SLOTS[i] != slot) continue;
            ElectronicsWikiEntry entry = entries.get(i);
            if (entry.machine()) {
                openWikiMachine(player, ELECTRONICS_RETURN_ID, entry.id());
            } else {
                Optional<String> recipeId = recipeIdForSpecialOutput(entry.id());
                if (recipeId.isPresent()) openRecipe(player, recipeId.get(), ELECTRONICS_RETURN_ID);
                else openElectronicsWiki(player);
            }
            return true;
        }
        return false;
    }

    public void openWikiPage(Player player, int page) {
        int totalPages = wikiCategoryPageCount();
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        List<MinionTypeDefinition> types = sortedWikiTypes(player, safePage);
        Inventory inv = Bukkit.createInventory(new MinionWikiHolder("", safePage), 54, miniMessage.deserialize("<dark_gray>Wiki minionów: " + wikiCategoryName(safePage)));
        fill(inv);
        inv.setItem(4, item(Material.BOOK, "<aqua>Wiki minionów — " + wikiCategoryName(safePage) + "</aqua>", List.of(
                "<gray>Miniony są podzielone na karty: surowce, farming i zwierzęta.</gray>",
                "<gray>Karta: <white>" + (safePage + 1) + "/" + totalPages + "</white></gray>",
                "",
                "<yellow>Kliknij główkę, aby zobaczyć poziomy, dropy i wymagania.</yellow>"
        )));
        for (int i = 0; i < WIKI_INDEX_SLOTS.length && i < types.size(); i++) {
            MinionTypeDefinition type = types.get(i);
            inv.setItem(WIKI_INDEX_SLOTS[i], minionHead(type, 1, type.displayName(), wikiIndexLore(type)));
        }
        if (safePage > 0) {
            inv.setItem(48, item(Material.ARROW, "<yellow>Poprzednia karta</yellow>", List.of("<gray>Przejdź do: <white>" + wikiCategoryName(safePage - 1) + "</white>.</gray>")));
        }
        if (safePage + 1 < totalPages) {
            inv.setItem(50, item(Material.ARROW, "<yellow>Następna karta</yellow>", List.of("<gray>Przejdź do: <white>" + wikiCategoryName(safePage + 1) + "</white>.</gray>")));
        }
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Zamknij i wróć do menu miasta.</gray>")));
        inv.setItem(53, wikiViewToggleItem(player));
        player.openInventory(inv);
    }

    public void openWikiType(Player player, String typeId) {
        openWikiTypePage(player, typeId, 0);
    }

    public void openWikiTypePage(Player player, String typeId, int page) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(typeId);
        if (type == null || !type.enabled()) {
            hex.ui().send(player, "minions.error.unknown-type");
            return;
        }
        int totalPages = wikiSpecialPageCount(player, type);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        Inventory inv = Bukkit.createInventory(new MinionWikiHolder(type.id(), safePage), 54, miniMessage.deserialize("<dark_gray>Wiki: " + type.displayName()));
        fill(inv);
        inv.setItem(4, minionHead(type, 1, type.displayName(), wikiHeaderLore(type)));
        for (int tier = 1; tier <= WIKI_TIER_SLOTS.length; tier++) {
            inv.setItem(WIKI_TIER_SLOTS[tier - 1], tierGlass(type, tier, player));
        }
        renderWikiSpecialItems(inv, player, type, safePage);
        inv.setItem(37, item(Material.CHEST, "<green>Dropy miniona</green>", dropsLore(type)));
        inv.setItem(39, item(Material.BOOK, "<aqua>Efekty poziomów</aqua>", tierSummaryLore(type)));
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do listy</yellow>", List.of("<gray>Kliknij, aby wrócić do wiki minionów.</gray>")));
        if (safePage > 0) {
            inv.setItem(48, item(Material.ARROW, "<yellow>Poprzednia strona itemów</yellow>", List.of("<gray>Przejdź do strony <white>" + safePage + "</white>.</gray>")));
        }
        if (safePage + 1 < totalPages) {
            inv.setItem(50, item(Material.ARROW, "<yellow>Następna strona itemów</yellow>", List.of("<gray>Przejdź do strony <white>" + (safePage + 2) + "/" + totalPages + "</white>.</gray>")));
        }
        inv.setItem(53, wikiViewToggleItem(player));
        player.openInventory(inv);
    }


    public void openRecipe(Player player, String recipeId, String returnTypeId) {
        SpecialRecipeDefinition canonical = service.specialItems().recipe(recipeId).orElse(null);
        if (canonical == null) {
            if (isElectronicsReturn(returnTypeId)) openElectronicsWiki(player);
            else openWikiType(player, returnTypeId);
            return;
        }
        Inventory inv = Bukkit.createInventory(new SpecialRecipeMenuHolder(canonical.id(), returnTypeId == null ? "" : returnTypeId), 54, miniMessage.deserialize("<dark_gray>Jak stworzyć: " + recipeOutputName(canonical)));
        fill(inv);
        renderSpecialRecipe(inv, canonical.id());
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do wiki miniona.</gray>")));
        inv.setItem(53, wikiViewToggleItem(player));
        player.openInventory(inv);
    }

    public void refreshSpecialRecipe(Inventory inv, String recipeId) {
        if (inv == null || recipeId == null || recipeId.isBlank()) return;
        renderSpecialRecipe(inv, recipeId);
    }

    private void renderSpecialRecipe(Inventory inv, String canonicalRecipeId) {
        SpecialRecipeDefinition recipe = activeSpecialRecipeVariant(canonicalRecipeId);
        if (recipe == null) return;
        for (int slot : RECIPE_GRID_SLOTS) inv.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        for (int row = 0; row < 3; row++) {
            String line = recipe.shape().get(row);
            for (int col = 0; col < 3; col++) {
                char key = line.charAt(col);
                SpecialIngredient ingredient = recipe.ingredients().get(key);
                inv.setItem(RECIPE_GRID_SLOTS[row * 3 + col], ingredient == null || key == ' ' ? item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()) : ingredientIcon(ingredient));
            }
        }
        inv.setItem(25, service.recipeOutput(recipe));
    }

    private SpecialRecipeDefinition activeSpecialRecipeVariant(String canonicalRecipeId) {
        SpecialRecipeDefinition base = service.specialItems().recipe(canonicalRecipeId).orElse(null);
        if (base == null) return null;
        String output = base.outputSpecialItem() == null ? "" : base.outputSpecialItem();
        if (!"compressor_update".equalsIgnoreCase(output)) return base;
        List<SpecialRecipeDefinition> variants = service.specialItems().recipes().values().stream()
                .filter(SpecialRecipeDefinition::enabled)
                .filter(recipe -> output.equalsIgnoreCase(recipe.outputSpecialItem()))
                .sorted(Comparator.comparingInt((SpecialRecipeDefinition recipe) -> recipe.id().equalsIgnoreCase("compressor_update") ? 0 : 1).thenComparing(SpecialRecipeDefinition::id))
                .toList();
        if (variants.isEmpty()) return base;
        return variants.get((int) ((System.currentTimeMillis() / 1000L) % variants.size()));
    }

    public void openWikiMachines(Player player, String returnTypeId) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(returnTypeId);
        if (type == null || !type.enabled()) {
            openWiki(player);
            return;
        }
        List<MachineDefinition> machines = wikiMachinesFor(player, type);
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
            ArrayList<String> machineLore = new ArrayList<>(machineDescriptionLore(machine));
            String energy = energySummaryLine(machine);
            if (energy != null && !energy.isBlank()) machineLore.add(energy);
            machineLore.add("");
            machineLore.add("<yellow>Kliknij, aby zobaczyć szczegóły.</yellow>");
            inv.setItem(WIKI_MACHINE_SLOTS[i], machineIcon(machine, machineLore));
        }
        if (machines.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "<gray>Brak urządzeń</gray>", List.of(
                    "<gray>Dla tego miniona nie ma obecnie dostępnych urządzeń.</gray>"
            )));
        }
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do miniona</yellow>", List.of("<gray>Wróć do wiki tego miniona.</gray>")));
        inv.setItem(53, wikiViewToggleItem(player));
        player.openInventory(inv);
    }

    public void openWikiMachine(Player player, String returnTypeId, String machineId) {
        openWikiMachinePage(player, returnTypeId, machineId, 0);
    }

    public void openWikiMachinePage(Player player, String returnTypeId, String machineId, int page) {
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        if (machine == null) {
            if (isElectronicsReturn(returnTypeId)) openElectronicsWiki(player);
            else openWikiMachines(player, returnTypeId);
            return;
        }
        List<String> processIds = wikiMachineProcessIds(machine);
        int pages = Math.max(1, (int) Math.ceil(processIds.size() / (double) WIKI_MACHINE_RECIPE_SLOTS.length));
        int safePage = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(MinionWikiHolder.machine(returnTypeId, machine.id(), safePage), 54, miniMessage.deserialize("<dark_gray>Wiki: " + stripMini(machine.displayName())));
        fill(inv);
        inv.setItem(4, machineIcon(machine, machineHeaderLore(machine)));
        int offset = safePage * WIKI_MACHINE_RECIPE_SLOTS.length;
        for (int i = 0; i < WIKI_MACHINE_RECIPE_SLOTS.length && offset + i < processIds.size(); i++) {
            String processId = processIds.get(offset + i);
            inv.setItem(WIKI_MACHINE_RECIPE_SLOTS[i], machineProcessIcon(machine, processId));
        }
        if (processIds.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "<gray>Brak procesów</gray>", List.of(
                    "<gray>To urządzenie nie ma obecnie dostępnych procesów.</gray>"
            )));
        }
        inv.setItem(40, machineCraftingRecipeIcon(machine));
        if (safePage > 0) inv.setItem(48, item(Material.ARROW, "<yellow>Poprzednia strona</yellow>", List.of()));
        if (safePage + 1 < pages) inv.setItem(50, item(Material.ARROW, "<yellow>Następna strona</yellow>", List.of()));
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do urządzeń</yellow>", List.of("<gray>Wróć do listy maszyn.</gray>")));
        inv.setItem(53, wikiViewToggleItem(player));
        player.openInventory(inv);
    }

    public void openWikiMachineRecipe(Player player, String returnTypeId, String machineId, String recipeId) {
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        if (machine == null) {
            if (isElectronicsReturn(returnTypeId)) openElectronicsWiki(player);
            else openWikiMachines(player, returnTypeId);
            return;
        }
        Inventory inv = Bukkit.createInventory(MinionWikiHolder.machineRecipe(returnTypeId, machine.id(), recipeId), 54, miniMessage.deserialize("<dark_gray>Proces: " + stripMini(machine.displayName())));
        fill(inv);
        renderActiveMachineRecipe(inv, machine, recipeId);
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót do maszyny</yellow>", List.of("<gray>Wróć do listy procesów tej maszyny.</gray>")));
        inv.setItem(53, wikiViewToggleItem(player));
        player.openInventory(inv);
    }


    public void refreshWikiMachineRecipe(Inventory inv, String machineId, String representativeId) {
        if (inv == null) return;
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        if (machine == null) return;
        renderActiveMachineRecipe(inv, machine, representativeId);
    }

    private void renderActiveMachineRecipe(Inventory inv, MachineDefinition machine, String representativeId) {
        for (int slot : new int[]{20, 21, 22, 24, 31}) inv.setItem(slot, null);
        inv.setItem(4, machineIcon(machine, machineHeaderLore(machine)));
        String recipeId = activeMachineProcessVariant(machine, representativeId);
        if (recipeId != null && recipeId.startsWith("fuel:")) {
            renderGeneratorFuelRecipe(inv, machine, recipeId.substring("fuel:".length()));
        } else if (recipeId != null && (recipeId.startsWith("vanilla:") || recipeId.equals("electric_steel"))) {
            VanillaFurnaceProcess process = recipeId.equals("electric_steel") ? electricSteelWikiProcess()
                    : pluginFurnaceProcesses().stream().filter(v -> v.id().equalsIgnoreCase(recipeId)).findFirst().orElse(null);
            if (process != null) renderVanillaFurnaceRecipe(inv, machine, process);
        } else {
            MachineRecipe recipe = machine.recipes().stream().filter(r -> r.id().equalsIgnoreCase(recipeId)).findFirst().orElse(null);
            if (recipe != null) renderMachineRecipe(inv, machine, recipe);
        }
        List<String> variants = machineProcessVariants(machine, representativeId);
        if (variants.size() > 1) {
            inv.setItem(40, item(Material.CLOCK, "<yellow>Wariant " + (variants.indexOf(recipeId) + 1) + "/" + variants.size() + "</yellow>", List.of(
                    "<gray>Receptura zmienia się automatycznie co sekundę.</gray>"
            )));
        }
    }

    public void openEnchantedCrafting(Player player, String stationId) {
        Inventory inv = Bukkit.createInventory(new EnchantedCraftingMenuHolder(stationId), 54, miniMessage.deserialize("<dark_gray>Zaawansowany crafting"));
        fill(inv);
        for (int slot : RECIPE_GRID_SLOTS) inv.setItem(slot, null);
        inv.setItem(4, item(Material.ENCHANTING_TABLE, "<aqua>Zaawansowany stół rzemieślniczy</aqua>", List.of(
                "<gray>Włóż itemy w grid 3x3.</gray>",
                "<gray>Wynik aktualizuje się automatycznie.</gray>",
                "<yellow>Kliknij wynik, aby stworzyć 1 sztukę.</yellow>",
                "<yellow>Shift+klik wyniku tworzy maksymalną możliwą liczbę.</yellow>"
        )));
        inv.setItem(16, item(Material.ARROW, "<yellow>Wytwórz</yellow>", List.of("<gray>Kliknij item wyniku po prawej.</gray>")));
        inv.setItem(24, item(Material.GRAY_STAINED_GLASS_PANE, "<gray>Brak dopasowanej receptury</gray>", List.of("<dark_gray>Ułóż składniki w gridzie 3x3.</dark_gray>")));
        inv.setItem(33, item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
        player.openInventory(inv);
    }

    public String wikiTypeAtSlot(int slot) {
        return wikiTypeAtSlot(null, slot, 0);
    }

    public String wikiTypeAtSlot(Player player, int slot, int page) {
        List<MinionTypeDefinition> types = sortedWikiTypes(player, Math.max(0, page));
        for (int i = 0; i < WIKI_INDEX_SLOTS.length && i < types.size(); i++) {
            if (WIKI_INDEX_SLOTS[i] == slot) return types.get(i).id();
        }
        return "";
    }

    public boolean wikiHasPage(int page) {
        return page >= 0 && page < wikiCategoryPageCount();
    }

    private int wikiCategoryPageCount() {
        return 4;
    }

    private String wikiCategoryName(int page) {
        if (page == 1) return "Farming";
        if (page == 2) return "Zwierzęta";
        if (page == 3) return "Moby";
        return "Surowce";
    }

    private List<MinionTypeDefinition> sortedWikiTypes(Player player, int page) {
        List<String> order = page == 1 ? List.of("wheat", "sugar_cane", "beetroot", "cactus")
                : page == 2 ? List.of("chicken", "cow", "pig", "sheep")
                : page == 3 ? List.of("zombie", "skeleton", "spider", "silverfish")
                : List.of("cobblestone", "dirt", "stone", "oak_plank", "spruce_wood", "iron", "copper", "coal", "redstone", "gold", "diamond", "emerald", "uranium", "obsidian", "netherrack", "netherite", "tin");
        Map<String, Integer> preferredOrder = new java.util.HashMap<>();
        for (int i = 0; i < order.size(); i++) preferredOrder.put(order.get(i), i);
        return service.definitions().minionTypes().values().stream()
                .filter(MinionTypeDefinition::enabled)
                .filter(type -> wikiCategory(type, page))
                .filter(type -> wikiShowAll(player) || isMinionUnlockedFor(player, type))
                .sorted(Comparator
                        .comparingLong(this::minionUnlockScore)
                        .thenComparingInt((MinionTypeDefinition type) -> preferredOrder.getOrDefault(type.id(), 1000))
                        .thenComparing(MinionTypeDefinition::id))
                .toList();
    }

    private long minionUnlockScore(MinionTypeDefinition type) {
        if (type == null) return Long.MAX_VALUE;
        SpecialRecipeDefinition recipe = service.specialItems().recipes().values().stream()
                .filter(r -> type.id().equalsIgnoreCase(r.outputMinionType()) && r.outputMinionTier() <= 1)
                .findFirst().orElse(null);
        if (recipe == null || recipe.unlock().isEmpty()) return 0L;

        long score = 0L;
        for (RecipeUnlockRequirement.CollectionTierCount req : recipe.unlock().prerequisiteCollectionTierCounts()) {
            score = Math.max(score, (long) req.tier() * 1_000_000_000L + (long) req.count() * 10_000_000L);
        }
        for (RecipeUnlockRequirement.CollectionTierCount req : recipe.unlock().collectionTierCounts()) {
            score = Math.max(score, (long) req.tier() * 1_000_000_000L + (long) req.count() * 10_000_000L + 1_000_000L);
        }
        if (!recipe.unlock().townMinionLevels().isEmpty()) {
            int max = recipe.unlock().townMinionLevels().values().stream().mapToInt(Integer::intValue).max().orElse(1);
            score = Math.max(score, 500_000_000L + (long) max * 10_000_000L);
        }
        if (!recipe.unlock().collectionLevels().isEmpty()) {
            int max = recipe.unlock().collectionLevels().values().stream().mapToInt(Integer::intValue).max().orElse(1);
            score = Math.max(score, 400_000_000L + (long) max * 10_000_000L);
        }
        if (!recipe.unlock().collections().isEmpty()) {
            long max = recipe.unlock().collections().values().stream().mapToLong(Long::longValue).max().orElse(1L);
            score = Math.max(score, 300_000_000L + Math.min(99_000_000L, max));
        }
        return score == 0L ? 1L : score;
    }

    private boolean wikiCategory(MinionTypeDefinition type, int page) {
        String id = type.id().toLowerCase(java.util.Locale.ROOT);
        if (page == 1) return id.equals("wheat") || id.equals("sugar_cane") || id.equals("beetroot") || id.equals("cactus");
        if (page == 2) return "animals".equalsIgnoreCase(type.category()) || id.equals("sheep") || id.equals("pig") || id.equals("cow") || id.equals("chicken");
        if (page == 3) return id.equals("zombie") || id.equals("skeleton") || id.equals("spider") || id.equals("silverfish")
                || "mobs".equalsIgnoreCase(type.category()) || "mob".equalsIgnoreCase(type.category()) || "combat".equalsIgnoreCase(type.category());
        return !wikiCategory(type, 1) && !wikiCategory(type, 2) && !wikiCategory(type, 3);
    }

    public String wikiMachineAtSlot(String returnTypeId, int slot) {
        return wikiMachineAtSlot(null, returnTypeId, slot);
    }

    public String wikiMachineAtSlot(Player player, String returnTypeId, int slot) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(returnTypeId);
        if (type == null) return "";
        List<MachineDefinition> machines = wikiMachinesFor(player, type);
        for (int i = 0; i < Math.min(machines.size(), WIKI_MACHINE_SLOTS.length); i++) {
            if (WIKI_MACHINE_SLOTS[i] == slot) return machines.get(i).id();
        }
        return "";
    }

    public String wikiMachineProcessAtSlot(String machineId, int slot) {
        return wikiMachineProcessAtSlot(machineId, slot, 0);
    }

    public String wikiMachineProcessAtSlot(String machineId, int slot, int page) {
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        if (machine == null) return "";
        List<String> processIds = wikiMachineProcessIds(machine);
        int offset = Math.max(0, page) * WIKI_MACHINE_RECIPE_SLOTS.length;
        for (int i = 0; i < WIKI_MACHINE_RECIPE_SLOTS.length && offset + i < processIds.size(); i++) {
            if (WIKI_MACHINE_RECIPE_SLOTS[i] == slot) return processIds.get(offset + i);
        }
        return "";
    }

    public boolean wikiMachineHasPage(String machineId, int page) {
        MachineDefinition machine = service.machines().machines().get(machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT));
        return machine != null && page >= 0 && page * WIKI_MACHINE_RECIPE_SLOTS.length < wikiMachineProcessIds(machine).size();
    }

    /**
     * Finds a HexMinions custom crafting recipe for an item shown anywhere in wiki.
     * Used by wiki/recepture screens so clicking a custom item always replaces the
     * current detail view with that item's recipe instead of creating a deep back-stack.
     */
    public String wikiRecipeForItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";
        Optional<String> specialId = service.specialItems().readSpecialItemId(item);
        if (specialId.isPresent()) {
            return recipeIdForSpecialOutput(specialId.get()).orElse("");
        }
        Optional<MinionItemFactory.MinionItemData> minion = itemFactory.read(item);
        if (minion.isPresent()) {
            String typeId = minion.get().typeId();
            int tier = minion.get().tier();
            return service.specialItems().recipes().values().stream()
                    .filter(recipe -> typeId.equalsIgnoreCase(recipe.outputMinionType()) && tier == recipe.outputMinionTier())
                    .map(SpecialRecipeDefinition::id)
                    .findFirst()
                    .orElse("");
        }
        int customModelData = 0;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) customModelData = meta.getCustomModelData();
        int finalCustomModelData = customModelData;
        if (finalCustomModelData > 0) {
            return service.specialItems().recipes().values().stream()
                    .filter(recipe -> recipe.outputSpecialItem() == null || recipe.outputSpecialItem().isBlank())
                    .filter(recipe -> recipe.outputMaterial() == item.getType() && recipe.outputCustomModelData() == finalCustomModelData)
                    .map(SpecialRecipeDefinition::id)
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    private List<MachineDefinition> wikiMachinesFor(Player player, MinionTypeDefinition type) {
        if (type == null) return List.of();
        ArrayList<MachineDefinition> result = new ArrayList<>();
        for (MachineDefinition machine : service.machines().machines().values()) {
            if (!machine.enabled() || isHiddenRobotId(machine.id()) || isHiddenRobotId(machine.specialItemId())) continue;
            if (isElectronicsWikiOnlySpecialItem(machine.specialItemId())) continue;
            boolean related = false;
            if (machine.specialItemId() != null && !machine.specialItemId().isBlank()) {
                related = service.specialItems().recipes().values().stream().anyMatch(recipe ->
                        machine.specialItemId().equalsIgnoreCase(recipe.outputSpecialItem())
                                && recipe.unlock().townMinionLevels().containsKey(type.id().toLowerCase(java.util.Locale.ROOT)));
                if (!related) {
                    related = type.wikiSpecialItems().stream().anyMatch(id -> id.equalsIgnoreCase(machine.specialItemId()) || id.equalsIgnoreCase(machine.id()));
                }
            }
            if (related && (wikiShowAll(player) || isMachineUnlockedFor(player, machine, type))) result.add(machine);
        }
        result.sort(Comparator.comparing(MachineDefinition::displayName));
        return result;
    }

    private List<ElectronicsWikiEntry> electronicsEntries(Player player) {
        ArrayList<ElectronicsWikiEntry> entries = new ArrayList<>();
        service.machines().machines().values().stream()
                .filter(MachineDefinition::enabled)
                .filter(machine -> !isHiddenRobotId(machine.id()) && !isHiddenRobotId(machine.specialItemId()))
                .filter(machine -> machine.energy().enabled() || "SMELTING_FURNACE".equalsIgnoreCase(machine.type()))
                .filter(machine -> wikiShowAll(player) || isMachineUnlockedFor(player, machine, null))
                .forEach(machine -> entries.add(new ElectronicsWikiEntry(machine.id(), true, electronicsMachineCategory(machine), electronicsMachineOrder(machine))));

        service.specialItems().items().keySet().stream()
                .filter(id -> !isHiddenRobotId(id))
                .filter(this::isElectronicsStandaloneSpecialItem)
                .filter(id -> wikiShowAll(player) || isRecipeOrItemUnlockedFor(player, id))
                .forEach(id -> entries.add(new ElectronicsWikiEntry(id, false, electronicsItemCategory(id), electronicsItemOrder(id))));

        entries.sort(Comparator
                .comparingInt(ElectronicsWikiEntry::order)
                .thenComparing(entry -> stripMini(electronicsEntryDisplayName(entry))));
        return entries;
    }

    private ItemStack electronicsEntryIcon(Player player, ElectronicsWikiEntry entry) {
        if (entry.machine()) {
            MachineDefinition machine = service.machines().machines().get(entry.id());
            if (machine == null) return item(Material.BARRIER, "<red>Niedostępne urządzenie</red>", List.of("<gray>Ten element jest obecnie niedostępny.</gray>"));
            ArrayList<String> lore = new ArrayList<>(machineDescriptionLore(machine));
            String energy = energySummaryLine(machine);
            if (energy != null && !energy.isBlank()) lore.add(energy);
            lore.add("");
            lore.add("<yellow>LPM: jak stworzyć • PPM: procesy urządzenia.</yellow>");
            if (player != null && !isMachineUnlockedFor(player, machine, null)) {
                lore.add("");
                lore.add("<gray>Odblokowywanie: <gold>" + machineUnlockText(machine) + "</gold></gray>");
            }
            return machineIcon(machine, lore);
        }
        ItemStack icon = service.specialItems().createItem(entry.id(), 1);
        if (icon.getType().isAir()) icon = item(Material.PAPER, "<white>" + prettyId(entry.id()) + "</white>", List.of());
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            SpecialRecipeDefinition recipe = recipeIdForSpecialOutput(entry.id()).flatMap(id -> service.specialItems().recipe(id)).orElse(null);
            meta.displayName(component(electronicsEntryDisplayName(entry)));
            SpecialItemDefinition definition = service.specialItems().item(entry.id()).orElse(null);
            ArrayList<String> lore = new ArrayList<>();
            if (definition != null) lore.addAll(withoutStaticUnlockLore(definition.lore()));
            if (recipe != null) {
                lore.add("");
                lore.add("<yellow>LPM: jak stworzyć • PPM: zastosowania.</yellow>");
                if (player != null && !isRecipeUnlockedFor(player, recipe) && !recipe.unlock().isEmpty()) {
                    lore.add("");
                    lore.add("<gray>Odblokowywanie: <gold>" + service.recipeUnlockText(recipe) + "</gold></gray>");
                }
            }
            applyLore(meta, lore);
            hideAttributes(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private String electronicsEntryDisplayName(ElectronicsWikiEntry entry) {
        if (entry.machine()) {
            MachineDefinition machine = service.machines().machines().get(entry.id());
            return machine == null ? "<white>" + prettyId(entry.id()) + "</white>" : machine.displayName();
        }
        return service.specialItems().item(entry.id()).map(SpecialItemDefinition::displayName).orElse("<white>" + prettyId(entry.id()) + "</white>");
    }

    private String electronicsMachineCategory(MachineDefinition machine) {
        if (machine.energy().generator()) return "Generatory";
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) return "Akumulatory";
        return "Urządzenia";
    }

    private int electronicsMachineOrder(MachineDefinition machine) {
        if (machine.energy().generator()) return 10;
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) return 40;
        return 20;
    }

    private String electronicsItemCategory(String id) {
        if (isCableSpecialItem(id)) return "Kable";
        if (isBatterySpecialItem(id)) return "Akumulatory";
        if (isMachineUpgradeSpecialItem(id)) return "Ulepszenia maszyn";
        return "Elektronika";
    }

    private int electronicsItemOrder(String id) {
        if (isCableSpecialItem(id)) return 30;
        if (isBatterySpecialItem(id)) return 41;
        if (isMachineUpgradeSpecialItem(id)) return 50;
        return 90;
    }

    private String machineUnlockText(MachineDefinition machine) {
        if (machine == null || machine.specialItemId() == null || machine.specialItemId().isBlank()) return "brak danych";
        return recipeIdForSpecialOutput(machine.specialItemId())
                .flatMap(id -> service.specialItems().recipe(id))
                .map(service::recipeUnlockText)
                .orElse("brak wymagań albo brak receptury");
    }

    private boolean isMachineUnlockedFor(Player player, MachineDefinition machine, MinionTypeDefinition type) {
        if (machine == null) return false;
        if (player == null) return true;
        String specialItem = machine.specialItemId() == null ? "" : machine.specialItemId();
        Optional<SpecialRecipeDefinition> recipe = service.specialItems().recipes().values().stream()
                .filter(r -> !specialItem.isBlank() && specialItem.equalsIgnoreCase(r.outputSpecialItem()))
                .findFirst();
        if (recipe.isEmpty() && type != null) {
            recipe = service.specialItems().recipes().values().stream()
                    .filter(r -> r.unlock().townMinionLevels().containsKey(type.id().toLowerCase(java.util.Locale.ROOT)))
                    .filter(r -> !specialItem.isBlank() && specialItem.equalsIgnoreCase(r.outputSpecialItem()))
                    .findFirst();
        }
        return recipe.map(r -> isRecipeUnlockedFor(player, r)).orElse(true);
    }

    private List<String> wikiMachineProcessIds(MachineDefinition machine) {
        LinkedHashMap<String, String> representatives = new LinkedHashMap<>();
        if (machine.energy().enabled() && machine.energy().generator()) {
            for (String fuel : new LinkedHashSet<>(machine.energy().fuelEu().keySet())) {
                SpecialItemDefinition fuelDef = service.specialItems().item(fuel).orElse(null);
                if (fuelDef != null && !fuelDef.enabled()) continue;
                representatives.putIfAbsent("fuel:" + fuel.toLowerCase(java.util.Locale.ROOT), "fuel:" + fuel);
            }
        }
        for (MachineRecipe recipe : machine.recipes()) {
            representatives.putIfAbsent(machineRecipeOutputKey(recipe), recipe.id());
        }
        if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) {
            representatives.putIfAbsent("special:steel_ingot", "electric_steel");
            for (VanillaFurnaceProcess process : pluginFurnaceProcesses()) {
                representatives.putIfAbsent(furnaceOutputKey(process), process.id());
            }
        }
        return new ArrayList<>(representatives.values());
    }

    private String machineRecipeOutputKey(MachineRecipe recipe) {
        if (recipe.outputSpecialItem() != null && !recipe.outputSpecialItem().isBlank()) return "special:" + recipe.outputSpecialItem().toLowerCase(java.util.Locale.ROOT);
        return "material:" + recipe.outputMaterial().name() + ":" + recipe.outputCustomModelData();
    }

    private String furnaceOutputKey(VanillaFurnaceProcess process) {
        String special = service.specialItems().readSpecialItemId(process.output()).orElse("");
        if (!special.isBlank()) return "special:" + special.toLowerCase(java.util.Locale.ROOT);
        ItemMeta meta = process.output().getItemMeta();
        int cmd = meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : 0;
        return "material:" + process.output().getType().name() + ":" + cmd;
    }

    private List<String> machineProcessVariants(MachineDefinition machine, String representativeId) {
        if (representativeId == null || representativeId.isBlank()) return List.of();
        if (representativeId.startsWith("fuel:")) return List.of(representativeId);
        String key;
        if (representativeId.equalsIgnoreCase("electric_steel")) key = "special:steel_ingot";
        else if (representativeId.startsWith("vanilla:")) {
            VanillaFurnaceProcess selected = pluginFurnaceProcesses().stream().filter(v -> v.id().equalsIgnoreCase(representativeId)).findFirst().orElse(null);
            key = selected == null ? representativeId : furnaceOutputKey(selected);
        } else {
            MachineRecipe selected = machine.recipes().stream().filter(r -> r.id().equalsIgnoreCase(representativeId)).findFirst().orElse(null);
            key = selected == null ? representativeId : machineRecipeOutputKey(selected);
        }
        ArrayList<String> variants = new ArrayList<>();
        for (MachineRecipe recipe : machine.recipes()) if (machineRecipeOutputKey(recipe).equals(key)) variants.add(recipe.id());
        if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) {
            if ("special:steel_ingot".equals(key)) variants.add(0, "electric_steel");
            for (VanillaFurnaceProcess process : pluginFurnaceProcesses()) if (furnaceOutputKey(process).equals(key)) variants.add(process.id());
        }
        return variants.isEmpty() ? List.of(representativeId) : variants.stream().distinct().toList();
    }

    private String activeMachineProcessVariant(MachineDefinition machine, String representativeId) {
        List<String> variants = machineProcessVariants(machine, representativeId);
        if (variants.isEmpty()) return representativeId == null ? "" : representativeId;
        return variants.get((int) ((System.currentTimeMillis() / 1000L) % variants.size()));
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
        ArrayList<String> lore = new ArrayList<>(machineDescriptionLore(machine));
        String energy = energySummaryLine(machine);
        if (energy != null && !energy.isBlank()) lore.add(energy);
        lore.add("");
        lore.add("<yellow>Kliknij proces, aby zobaczyć szczegóły.</yellow>");
        return trimLore(lore, 8);
    }

    private List<String> machineDescriptionLore(MachineDefinition machine) {
        if (machine == null) return List.of("<gray>Urządzenie technologiczne.</gray>");
        String description = switch (machine.type().toUpperCase(java.util.Locale.ROOT)) {
            case "URANIUM_ENRICHER" -> "Wzbogaca uran do wzbogaconego uranu.";
            case "SMELTING_FURNACE" -> "Przetapia surowce i pozwala wytwarzać stal. Nie wymaga zasilania EU.";
            case "ELECTRIC_FURNACE" -> "Szybko przetapia surowce i obsługuje produkcję stali.";
            case "COAL_GENERATOR" -> "Spala paliwo i zasila urządzenia energią EU.";
            case "SOLAR_PANEL_GENERATOR", "SOLAR_GENERATOR" -> "Wytwarza energię ze światła słonecznego.";
            case "ACCUMULATOR" -> "Magazynuje energię i przekazuje ją dalej do sieci.";
            case "MACERATOR" -> "Kruszy rudy i surowce na pyły do dalszego przetwarzania.";
            case "EXTRACTOR" -> "Pozyskuje żywicę ze skompresowanego drewna świerkowego.";
            case "COMPRESSOR" -> "Kompresuje i łączy materiały w zaawansowane surowce.";
            case "ELECTRIC_MILL" -> "Przetwarza materiały organiczne w elektrycznym kompostorze.";
            case "MEAT_REFINERY" -> "Rafinuje mięso do dalszego wykorzystania technologicznego.";
            default -> "Urządzenie technologiczne.";
        };
        return List.of("<gray>" + description + "</gray>");
    }

    private String energySummaryLine(MachineDefinition machine) {
        MachineEnergyDefinition energy = machine.energy();
        if (!energy.enabled()) return "";
        if (energy.generator()) {
            double maxRate = 0.0D;
            for (Map.Entry<String, Integer> fuel : energy.fuelEu().entrySet()) {
                int burn = Math.max(1, energy.fuelBurnSeconds(fuel.getKey(), 8));
                maxRate = Math.max(maxRate, fuel.getValue() / (double) burn);
            }
            for (Map.Entry<String, Integer> fuel : energy.fallbackFuelEu().entrySet()) {
                int burn = Math.max(1, energy.fallbackFuelBurnSeconds(fuel.getKey(), 8));
                maxRate = Math.max(maxRate, fuel.getValue() / (double) burn);
            }
            String rate = maxRate > 0.0D ? formatRate(maxRate) : String.valueOf(Math.max(0, energy.euPerSecond()));
            return "<gray>Generowanie: <green>do " + rate + " EU/s</green></gray>";
        }
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            return "<gray>Pojemność: <green>" + formatNumber(energy.bufferCapacity()) + " EU</green> <dark_gray>•</dark_gray> <gray>Transfer: <green>" + formatNumber(energy.transferPerSecond()) + " EU/s</green></gray>";
        }
        return "<gray>Zużycie: <green>" + formatNumber(energy.euPerSecond()) + " EU/s</green></gray>";
    }

    private ItemStack machineProcessIcon(MachineDefinition machine, String processId) {
        if (processId.startsWith("fuel:")) {
            String fuel = processId.substring("fuel:".length());
            int eu = machine.energy().fuelEu(fuel);
            int burn = machine.energy().fuelBurnSeconds(fuel, 8);
            boolean redstonePower = fuel.equalsIgnoreCase("REDSTONE") || fuel.equalsIgnoreCase("REDSTONE_BLOCK");
            String sourceLabel = redstonePower ? "<red>Zasilanie Redstone: " : "<gold>Paliwo: ";
            String sourceClose = redstonePower ? "</red>" : "</gold>";
            return fuelIcon(fuel, 1, sourceLabel + prettyId(fuel) + sourceClose, List.of(
                    "<gray>Dostarcza: <green>" + eu + " EU</green></gray>",
                    "<gray>Czas spalania: <white>" + burn + "s</white></gray>",
                    "<gray>Maszyna: <white>" + stripMini(machine.displayName()) + "</white></gray>",
                    "",
                    "<yellow>Kliknij, aby zobaczyć schemat.</yellow>"
            ));
        }
        if (processId.startsWith("vanilla:") || processId.equals("electric_steel")) {
            VanillaFurnaceProcess process = processId.equals("electric_steel") ? electricSteelWikiProcess() : pluginFurnaceProcesses().stream().filter(v -> v.id().equalsIgnoreCase(processId)).findFirst().orElse(null);
            if (process == null) return item(Material.BARRIER, "<red>Nieznany proces</red>", List.of());
            ItemStack icon = process.output().clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                // Dla zwykłych wyników zachowujemy nazwę tłumaczoną przez klienta Minecraft.
                // Customowe wyniki (np. stal) otrzymują własną nazwę już w SpecialItemRegistry.
                int variants = machineProcessVariants(machine, processId).size();
                ArrayList<String> lore = new ArrayList<>();
                lore.add("<gray>Czas: <gold>" + process.timeSeconds() + "s</gold></gray>");
                if (variants > 1) lore.add("<gray>Warianty: <gold>" + variants + "</gold> — zmiana co <gold>1 s</gold></gray>");
                lore.add("<yellow>Kliknij, aby zobaczyć schemat.</yellow>");
                applyLore(meta, lore);
                hideAttributes(meta); icon.setItemMeta(meta);
            }
            return icon;
        }
        MachineRecipe recipe = machine.recipes().stream().filter(r -> r.id().equalsIgnoreCase(processId)).findFirst().orElse(null);
        if (recipe == null) return item(Material.BARRIER, "<red>Nieznany proces</red>", List.of("<gray>Ten proces jest obecnie niedostępny.</gray>"));
        ItemStack icon = machineRecipeOutputIcon(recipe);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(component("<aqua>" + itemLabel(recipe.outputSpecialItem(), recipe.outputMaterial(), recipe.outputCustomModelData()) + "</aqua>"));
            ArrayList<String> lore = new ArrayList<>();
            lore.add("<gray>Wynik: <white>" + itemLabel(recipe.outputSpecialItem(), recipe.outputMaterial()) + "</white> <gold>x" + recipe.outputAmount() + "</gold></gray>");
            lore.add("<gray>Czas: <gold>" + recipe.timeSeconds() + "s</gold></gray>");
            lore.add(machineRecipeEnergyLine(machine, recipe));
            if (recipe.successChance() < 0.999999D) {
                lore.add("<gray>Szansa: <green>" + chance(recipe.successChance()) + "%</green></gray>");
            }
            int variants = machineProcessVariants(machine, processId).size();
            if (variants > 1) lore.add("<gray>Warianty: <gold>" + variants + "</gold> — zmiana co <gold>1 s</gold></gray>");
            lore.add("");
            lore.add("<yellow>Kliknij, aby zobaczyć schemat.</yellow>");
            applyLore(meta, lore);
            hideAttributes(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void renderMachineRecipe(Inventory inv, MachineDefinition machine, MachineRecipe recipe) {
        inv.setItem(20, machineRecipeInputIcon(recipe));
        if (hasSecondary(recipe)) inv.setItem(21, machineRecipeSecondaryIcon(recipe));
        if (hasFuel(recipe)) inv.setItem(22, machineRecipeFuelIcon(recipe));
        inv.setItem(24, machineRecipeOutputIcon(recipe));
        ArrayList<String> summary = new ArrayList<>();
        summary.add("<gray>Czas: <gold>" + recipe.timeSeconds() + "s</gold></gray>");
        summary.add(machineRecipeEnergyLine(machine, recipe));
        if (recipe.successChance() < 0.999999D) {
            summary.add("<gray>Szansa: <green>" + chance(recipe.successChance()) + "%</green></gray>");
        }
        inv.setItem(31, machineIcon(machine, summary));
    }

    private void renderGeneratorFuelRecipe(Inventory inv, MachineDefinition machine, String fuel) {
        int eu = machine.energy().fuelEu(fuel);
        int burn = machine.energy().fuelBurnSeconds(fuel, 8);
        boolean redstonePower = fuel.equalsIgnoreCase("REDSTONE") || fuel.equalsIgnoreCase("REDSTONE_BLOCK");
        inv.setItem(20, fuelIcon(fuel, 1, redstonePower ? "<red>Zasilanie Redstone</red>" : "<gold>Paliwo</gold>", List.of()));
        inv.setItem(24, item(Material.REDSTONE_BLOCK, "<green>" + formatNumber(eu) + " EU</green>", List.of(
                "<gray>Czas spalania: <gold>" + burn + "s</gold></gray>",
                "<gray>Transfer: <green>" + formatNumber(machine.energy().transferPerSecond()) + " EU/s</green></gray>"
        )));
        inv.setItem(31, machineIcon(machine, List.of(energySummaryLine(machine))));
    }

    private void renderVanillaFurnaceRecipe(Inventory inv, MachineDefinition machine, VanillaFurnaceProcess process) {
        inv.setItem(20, process.input().clone());
        if ("electric_steel".equalsIgnoreCase(process.id())) inv.setItem(21, new ItemStack(Material.COAL, 8));
        inv.setItem(24, process.output().clone());
        inv.setItem(31, machineIcon(machine, List.of(
                "<gray>Czas: <gold>" + process.timeSeconds() + "s</gold></gray>",
                "<gray>Zużycie: <green>" + formatNumber(machine.energy().euPerSecond()) + " EU/s</green></gray>"
        )));
    }

    private VanillaFurnaceProcess electricSteelWikiProcess() {
        ItemStack output = service.specialItems().createItem("steel_ingot", 1);
        return new VanillaFurnaceProcess("electric_steel", new ItemStack(Material.IRON_INGOT), output, 9);
    }

    private List<VanillaFurnaceProcess> pluginFurnaceProcesses() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<VanillaFurnaceProcess> result = new ArrayList<>();
        java.util.Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (!(recipe instanceof FurnaceRecipe furnace)) continue;
            ItemStack input = furnaceInput(furnace.getInputChoice());
            ItemStack output = furnace.getResult().clone();
            if (input == null || input.getType().isAir() || output.getType().isAir()) continue;
            if (!isPluginItem(input) && !isPluginItem(output)) continue;
            String key = furnaceItemKey(input) + ">" + furnaceItemKey(output);
            if (!seen.add(key)) continue;
            result.add(new VanillaFurnaceProcess("vanilla:" + furnace.getKey(), input, output, Math.max(1, furnace.getCookingTime() / 20 - 1)));
        }
        result.sort(Comparator.comparing(v -> v.output().getType().name()));
        return result;
    }

    private boolean isPluginItem(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && service.specialItems().readSpecialItemId(stack).isPresent();
    }

    private String furnaceItemKey(ItemStack stack) {
        String special = service.specialItems().readSpecialItemId(stack).orElse("");
        if (!special.isBlank()) return "special:" + special.toLowerCase(java.util.Locale.ROOT);
        ItemMeta meta = stack.getItemMeta();
        int cmd = meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : 0;
        return stack.getType().name() + ":" + cmd;
    }

    private ItemStack furnaceInput(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materials && !materials.getChoices().isEmpty()) return new ItemStack(materials.getChoices().get(0));
        if (choice instanceof RecipeChoice.ExactChoice exact && !exact.getChoices().isEmpty()) return exact.getChoices().get(0).clone();
        return null;
    }

    private ItemStack machineCraftingRecipeIcon(MachineDefinition machine) {
        if (machine.specialItemId() == null || machine.specialItemId().isBlank()) {
            return item(Material.CRAFTING_TABLE, "<yellow>Jak stworzyć urządzenie</yellow>", List.of("<gray>To urządzenie nie jest jeszcze możliwe do wytworzenia.</gray>"));
        }
        Optional<String> recipeId = recipeIdForSpecialOutput(machine.specialItemId());
        if (recipeId.isEmpty()) {
            return item(Material.CRAFTING_TABLE, "<yellow>Jak stworzyć urządzenie</yellow>", List.of("<gray>To urządzenie nie jest jeszcze możliwe do wytworzenia.</gray>"));
        }
        SpecialRecipeDefinition recipe = service.specialItems().recipe(recipeId.get()).orElse(null);
        if (recipe == null) return item(Material.CRAFTING_TABLE, "<yellow>Jak stworzyć urządzenie</yellow>", List.of("<gray>To urządzenie nie jest jeszcze możliwe do wytworzenia.</gray>"));
        ItemStack icon = service.recipeOutput(recipe);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(component("<yellow>Jak stworzyć urządzenie</yellow>"));
            applyLore(meta, List.of("<gray>Kliknij, aby zobaczyć potrzebne składniki.</gray>"));
            hideAttributes(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private String machineRecipeEnergyLine(MachineDefinition machine, MachineRecipe recipe) {
        if (!machine.energy().enabled()) return "";
        if (machine.energy().generator()) return "<gray>Generowanie: <green>proces generatora</green></gray>";
        long total = (long) machine.energy().euPerSecond() * Math.max(1, recipe.timeSeconds());
        return "<gray>Zużycie: <green>" + formatNumber(machine.energy().euPerSecond()) + " EU/s</green> <dark_gray>•</dark_gray> <gray>łącznie <green>" + formatNumber(total) + " EU</green></gray>";
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
        if (specialItemId != null && !specialItemId.isBlank()) {
            icon = service.specialItems().createItem(specialItemId, amount);
        } else {
            ResourceDefinition resource = resourceByMaterial(material, customModelData);
            if (resource != null) icon = resourceIcon(resource, amount);
            else icon = item(material == Material.AIR ? Material.PAPER : material, "<white>" + (material == Material.AIR ? "Item" : prettyId(material.name())) + "</white>", List.of(), amount, customModelData);
        }
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(component("<white>" + itemLabel(specialItemId, material, customModelData) + "</white> <gold>x" + amount + "</gold>"));
            applyLore(meta, List.of());
            hideAttributes(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack resourceIcon(ResourceDefinition resource, int amount) {
        ItemStack icon = new ItemStack(resource.material(), Math.max(1, Math.min(resource.stackSize(), amount)));
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            if (resource.customModelData() > 0) meta.setCustomModelData(resource.customModelData());
            meta.displayName(component(resource.displayName()));
            if ("spruce_resin".equalsIgnoreCase(resource.id())) meta.setEnchantmentGlintOverride(true);
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
        return itemLabel(specialItemId, material, 0);
    }

    private String itemLabel(String specialItemId, Material material, int customModelData) {
        if (specialItemId != null && !specialItemId.isBlank()) {
            return service.specialItems().item(specialItemId).map(def -> stripMini(def.displayName())).orElse(prettyId(specialItemId));
        }
        ResourceDefinition resource = resourceByMaterial(material, customModelData);
        if (resource != null) return stripMini(resource.displayName());
        return material == null || material == Material.AIR ? "brak" : prettyId(material.name());
    }

    private ResourceDefinition resourceByMaterial(Material material, int customModelData) {
        if (material == null || material == Material.AIR) return null;
        for (ResourceDefinition resource : service.definitions().resources().values()) {
            if (resource.material() != material) continue;
            if (customModelData > 0 && resource.customModelData() != customModelData) continue;
            if (customModelData == 0 && resource.customModelData() > 0) continue;
            return resource;
        }
        return null;
    }

    private static String prettyId(String raw) {
        if (raw == null || raw.isBlank()) return "brak";
        String id = raw.toLowerCase(java.util.Locale.ROOT);
        return switch (id) {
            case "emerald" -> "Szmaragd";
            case "auto_smelter" -> "Automatyczny przetapiacz";
            case "minion_booster_tier_1" -> "Booster miniona Tier I";
            case "minion_booster_tier_2" -> "Booster miniona Tier II";
            case "silverfish" -> "Rybik cukrowy";
            default -> {
                String normalized = id.replace("super_compressed", "superskompresowany")
                        .replace("compressed", "skompresowany")
                        .replace("storage", "magazyn")
                        .replace("upgrade", "ulepszenie")
                        .replace("update", "ulepszenie")
                        .replace('_', ' ');
                yield normalized.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + normalized.substring(1);
            }
        };
    }

    private static String categoryDisplayName(String raw) {
        if (raw == null || raw.isBlank()) return "Inne";
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "mining" -> "Górnictwo";
            case "farming" -> "Rolnictwo";
            case "foraging" -> "Leśnictwo";
            case "animals" -> "Zwierzęta";
            case "combat", "mobs" -> "Moby";
            case "industrial" -> "Przemysł";
            default -> prettyId(raw);
        };
    }

    private static String machineTypeDisplayName(String raw) {
        if (raw == null || raw.isBlank()) return "Urządzenie";
        return switch (raw.toUpperCase(java.util.Locale.ROOT)) {
            case "COAL_GENERATOR" -> "Generator paliwowy";
            case "SOLAR_PANEL_GENERATOR", "SOLAR_GENERATOR" -> "Generator słoneczny";
            case "ACCUMULATOR" -> "Magazyn energii";
            case "MACERATOR" -> "Rozdrabniacz";
            case "EXTRACTOR" -> "Ekstraktor";
            case "COMPRESSOR" -> "Kompresor";
            case "ELECTRIC_FURNACE" -> "Piec elektryczny";
            case "ELECTRIC_MILL" -> "Elektryczny kompostor";
            case "MEAT_REFINERY" -> "Rafinator mięsa";
            case "SMELTING_FURNACE" -> "Piec hutniczy";
            case "URANIUM_ENRICHER" -> "Wzbogacacz uranu";
            default -> prettyId(raw);
        };
    }

    private static String stripMini(String input) {
        if (input == null) return "";
        return input.replaceAll("<[^>]+>", "").trim();
    }


    private static String stationDisplayName(String stationId) {
        if (stationId == null || stationId.isBlank()) return "stół rzemieślniczy";
        String normalized = stationId.toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("VANILLA_CRAFTING") || normalized.equals("VANILLA_CRAFTING_TABLE") || normalized.equals("CRAFTING_TABLE")) {
            return "stół rzemieślniczy";
        }
        if (normalized.equals("ADVANCE_CRAFTING") || normalized.equals("ADVANCED_CRAFTING") || normalized.equals("ENCHANTED_CRAFTING_TABLE")) {
            return "zaawansowany stół rzemieślniczy";
        }
        return prettyId(stationId);
    }

    private String recipeOutputName(SpecialRecipeDefinition recipe) {
        if (recipe == null) return "przedmiot";
        if (recipe.outputSpecialItem() != null && !recipe.outputSpecialItem().isBlank()) {
            return service.specialItems().item(recipe.outputSpecialItem()).map(def -> stripMini(def.displayName())).orElse(prettyId(recipe.outputSpecialItem()));
        }
        if (recipe.outputMinionType() != null && !recipe.outputMinionType().isBlank()) {
            MinionTypeDefinition type = service.definitions().minionTypes().get(recipe.outputMinionType().toLowerCase(java.util.Locale.ROOT));
            return type == null ? prettyId(recipe.outputMinionType()) : stripMini(type.displayName());
        }
        if (recipe.outputMaterial() != null && recipe.outputMaterial() != Material.AIR) return prettyId(recipe.outputMaterial().name());
        return "przedmiot";
    }

    private String humanCollectionName(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return "kolekcji";
        String id = collectionId.toLowerCase(java.util.Locale.ROOT);
        String tail = id.contains(".") ? id.substring(id.lastIndexOf('.') + 1) : id;
        ResourceDefinition resource = service.definitions().resources().get(tail);
        return resource == null ? prettyId(tail).toLowerCase(java.util.Locale.ROOT) : stripMini(resource.displayName()).toLowerCase(java.util.Locale.ROOT);
    }

    private String humanResourceName(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) return "przedmiot";
        ResourceDefinition resource = service.definitions().resources().get(resourceId.toLowerCase(java.util.Locale.ROOT));
        return resource == null ? prettyId(resourceId) : stripMini(resource.displayName());
    }

    private static String formatNumber(long value) {
        return String.format(java.util.Locale.US, "%,d", Math.max(0L, value)).replace(',', ' ');
    }

    private static String formatRate(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) return formatNumber((long) Math.rint(value));
        return String.format(java.util.Locale.US, "%.1f", value).replace('.', ',');
    }

    private List<String> wikiIndexLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        lore.add("<yellow>Generowane surowce:</yellow>");
        lore.addAll(dropsLore(type));
        List<String> requirements = craftingUnlockLore(type);
        if (!requirements.isEmpty()) {
            lore.add("");
            lore.add("<yellow>Wymagania:</yellow>");
            lore.addAll(requirements);
        }
        return trimLore(lore, 18);
    }

    private List<String> craftingUnlockLore(MinionTypeDefinition type) {
        Optional<SpecialRecipeDefinition> recipe = service.specialItems().recipes().values().stream()
                .filter(r -> type.id().equalsIgnoreCase(r.outputMinionType()))
                .findFirst();
        if (recipe.isEmpty() || recipe.get().unlock().isEmpty()) return List.of();
        return List.of("<dark_gray>•</dark_gray> <gray>" + service.recipeUnlockText(recipe.get()) + "</gray>");
    }

    private List<String> wikiHeaderLore(MinionTypeDefinition type) {
        return wikiIndexLore(type);
    }

    private ItemStack tierGlass(MinionTypeDefinition type, int tier, Player player) {
        TierDefinition def = type.tiers().get(tier);
        Material material = tier <= 5 ? Material.LIME_STAINED_GLASS_PANE : tier == 6 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        String name = def == null ? "<gray>Poziom " + tier + "</gray>" : "<white>Poziom " + tier + "</white>";
        List<String> lore = new ArrayList<>();
        if (def == null) {
            lore.add("<gray>Ten poziom nie jest obecnie dostępny.</gray>");
            return item(material, name, lore);
        }
        List<String> requirements = requirementsLore(def, player, tier);
        if (!requirements.isEmpty()) {
            lore.add("<yellow>Wymagania:</yellow>");
            lore.addAll(requirements);
            lore.add("");
        }
        lore.add("<yellow>Efekt poziomu:</yellow>");
        lore.add("<gray>Czas generowania: <gold>" + def.actionTimeText() + "s</gold></gray>");
        lore.add("<gray>Sloty: <gold>" + def.storageSlots() + "</gold></gray>");
        return item(material, name, trimLore(lore, 18));
    }

    private List<String> tierSummaryLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        for (int tier = 1; tier <= Math.max(type.maxTier(), WIKI_TIER_SLOTS.length); tier++) {
            TierDefinition def = type.tiers().get(tier);
            if (def == null) continue;
            lore.add("<gray>Tier <gold>" + tier + "</gold>: czas <gold>" + def.actionTimeText() + "s</gold>, magazyn <gold>" + def.storageSlots() + " slotów</gold></gray>");
        }
        return lore.isEmpty() ? List.of("<gray>Brak dostępnych poziomów.</gray>") : trimLore(lore, 18);
    }

    private List<String> requirementsLore(TierDefinition tier, Player player, int targetTier) {
        List<String> lore = new ArrayList<>();
        if (tier == null || tier.upgradeRequirements().emptyRequirements()) return lore;
        for (Map.Entry<String, Long> entry : tier.upgradeRequirements().collectionAmounts().entrySet()) {
            lore.add("<dark_gray>•</dark_gray> <gray>Kolekcja " + humanCollectionName(entry.getKey()) + ": <gold>" + formatNumber(entry.getValue()) + "</gold></gray>");
        }
        DynamicCollectionCost dynamic = tier.upgradeRequirements().dynamicCollectionCost();
        if (dynamic != null && dynamic.enabled()) {
            UUID townId = player == null ? null : service.towns().townIdOf(player.getUniqueId()).orElse(null);
            long required = service.dynamicCollectionCostAmount(townId, targetTier, dynamic);
            if (required > 0L) {
                ResourceDefinition resource = service.definitions().resources().get(dynamic.resourceId());
                lore.add("<dark_gray>•</dark_gray> " + dynamicRequirementPresentation(required, resource, targetTier));
            } else {
                lore.add("<dark_gray>•</dark_gray> <gray>Koszt zależny od poziomu kolekcji " + humanCollectionName(dynamic.collectionId()) + "</gray>");
            }
        }
        for (ItemRequirement item : tier.upgradeRequirements().items()) {
            String label = item.displayName();
            if (item.specialItemId() != null && !item.specialItemId().isBlank() && service.specialItems() != null) {
                label = service.specialItems().item(item.specialItemId()).map(def -> stripMini(def.displayName())).orElse(stripMini(label));
            }
            int maxStack = item.material() == null ? 64 : Math.max(1, item.material().getMaxStackSize());
            lore.add("<dark_gray>•</dark_gray> <gold>" + formatRequirementAmount(item.amount(), maxStack) + "</gold> <gray>" + stripMini(label) + "</gray>");
        }
        return lore;
    }

    private boolean isBiologicalType(MinionTypeDefinition type) {
        if (type == null || type.category() == null) return false;
        String category = type.category().toLowerCase(java.util.Locale.ROOT);
        return category.equals("farming") || category.equals("plants") || category.equals("plant")
                || category.equals("animals") || category.equals("animal")
                || category.equals("mobs") || category.equals("mob") || category.equals("combat");
    }

    private List<String> boosterWikiLore(MinionTypeDefinition type) {
        if (type.supportedBoosterTiers().isEmpty() || service.specialItems() == null || service.specialItems().boosters().isEmpty()) {
            return List.of("<gray>Boostery: <dark_gray>brak obsługiwanych</dark_gray></gray>");
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Boostery: <green>obsługiwane</green></gray>");
        service.specialItems().boosters().values().stream()
                .filter(booster -> booster.tier() <= 2 || type.supportedBoosterTiers().contains(booster.tier()))
                .sorted(Comparator.comparingInt(booster -> booster.tier()))
                .forEach(booster -> lore.add("<gray>- <gold>Booster miniona Tier " + booster.tier() + "</gold>: <green>+" + formatPercent(booster.speedBoostPercent()) + "%</green> przez <white>" + formatDuration(booster.durationSeconds()) + "</white></gray>"));
        return lore;
    }

    private List<String> autoSmelterWikiLore(MinionTypeDefinition type) {
        if (type.autoSmelter() == null || !type.autoSmelter().enabled() || type.autoSmelter().replacements().isEmpty()) {
            return List.of();
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Automatyczny przetapiacz: <green>obsługiwany</green></gray>");
        for (Map.Entry<String, String> entry : type.autoSmelter().replacements().entrySet()) {
            ResourceDefinition input = service.definitions().resources().get(entry.getKey());
            ResourceDefinition output = service.definitions().resources().get(entry.getValue());
            String inputName = input == null ? entry.getKey() : input.displayName();
            String outputName = output == null ? entry.getValue() : output.displayName();
            lore.add("<gray>- <white>" + inputName + "</white> → <gold>" + outputName + "</gold></gray>");
        }
        lore.add("<dark_gray>Wymaga Automatycznego przetapiacza w slocie ulepszenia.</dark_gray>");
        return trimLore(lore, 8);
    }

    private List<String> dropsLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        if (type.resourceTable().isEmpty()) return List.of("<gray>Brak generowanych surowców.</gray>");
        for (ResourceDrop drop : type.resourceTable()) {
            ResourceDefinition resource = service.definitions().resources().get(drop.resourceId());
            String name = resource == null ? humanResourceName(drop.resourceId()) : stripMini(resource.displayName());
            String amount = drop.amountMin() == drop.amountMax() ? String.valueOf(drop.amountMin()) : drop.amountMin() + "–" + drop.amountMax();
            String chanceText = chance(drop.chance());
            String special = drop.specialDrop() ? " <gray>— <gold>" + chanceText + "%</gold></gray>" : (drop.chance() < 0.999999D ? " <gray>— <gold>" + chanceText + "%</gold></gray>" : "");
            lore.add("<dark_gray>•</dark_gray> " + (drop.specialDrop() ? "<light_purple>" : "<white>") + name + (drop.specialDrop() ? "</light_purple>" : "</white>") + (drop.amountMax() > 1 ? " <gray>x" + amount + "</gray>" : "") + special);
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
        Inventory inv = Bukkit.createInventory(new MinionStorageChestMenuHolder(minionId), size, miniMessage.deserialize("<dark_gray>Magazyn skrzynki miniona"));
        fill(inv);
        org.bukkit.inventory.Inventory chestInv = chest.get().getBlockInventory();
        int[] visibleSlots = centeredStorageSlots(size, usableSlots);
        for (int i = 0; i < Math.min(visibleSlots.length, chestInv.getSize()); i++) {
            inv.setItem(visibleSlots[i], chestInv.getItem(i));
        }
        inv.setItem(size - 5, item(Material.ARROW, "<yellow>Powrót do miniona</yellow>", List.of("<gray>Zapisuje magazyn i wraca do menu miniona.</gray>")));
        player.openInventory(inv);
    }

    public void saveStorageChestMenu(UUID minionId, Inventory inv) {
        int usableSlots = service.storageChestSlotCapacity(minionId);
        service.saveStorageChestMenu(minionId, inv, centeredStorageSlots(inv.getSize(), usableSlots));
    }

    public boolean isStorageChestMenuSlot(UUID minionId, int inventorySize, int slot) {
        int usableSlots = service.storageChestSlotCapacity(minionId);
        for (int visible : centeredStorageSlots(inventorySize, usableSlots)) if (visible == slot) return true;
        return false;
    }

    private int[] centeredStorageSlots(int inventorySize, int usableSlots) {
        int contentRows = Math.max(1, inventorySize / 9 - 1);
        int count = Math.max(0, Math.min(usableSlots, contentRows * 9));
        int[] slots = new int[count];
        int written = 0;
        int rowsNeeded = Math.max(1, (count + 8) / 9);
        int firstRow = Math.max(0, (contentRows - rowsNeeded) / 2);
        for (int row = 0; row < rowsNeeded && written < count; row++) {
            int inRow = Math.min(9, count - written);
            int startCol = Math.max(0, (9 - inRow) / 2);
            for (int col = 0; col < inRow; col++) slots[written++] = (firstRow + row) * 9 + startCol + col;
        }
        return slots;
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

    public boolean isAllowedInAddonSlot(UUID minionId, int slot, ItemStack item) {
        String slotId = slot == ADDON_SLOT_1 ? "addon_1" : slot == ADDON_SLOT_2 ? "addon_2" : "";
        return service.isAllowedAddonItem(minionId, slotId, item);
    }

    public boolean isAllowedInAddonSlot(UUID minionId, ItemStack item) {
        return service.isAllowedAddonItem(minionId, item);
    }


    public String wikiRecipeAtSlot(String typeId, int slot) {
        return wikiRecipeAtSlot(null, typeId, slot, 0);
    }

    public String wikiRecipeAtSlot(Player player, String typeId, int slot, int page) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(typeId);
        if (type == null) return "";
        List<String> recipeIds = wikiRecipeIds(player, type);
        int offset = Math.max(0, page) * WIKI_SPECIAL_SLOTS.length;
        for (int i = 0; i < WIKI_SPECIAL_SLOTS.length && offset + i < recipeIds.size(); i++) {
            if (WIKI_SPECIAL_SLOTS[i] == slot) return recipeIds.get(offset + i);
        }
        return "";
    }

    public boolean wikiSpecialHasPage(Player player, String typeId, int page) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(typeId);
        return type != null && page >= 0 && page * WIKI_SPECIAL_SLOTS.length < wikiRecipeIds(player, type).size();
    }

    private int wikiSpecialPageCount(Player player, MinionTypeDefinition type) {
        return Math.max(1, (int) Math.ceil(wikiRecipeIds(player, type).size() / (double) WIKI_SPECIAL_SLOTS.length));
    }

    private void renderWikiSpecialItems(Inventory inv, Player player, MinionTypeDefinition type, int page) {
        List<String> recipes = wikiRecipeIds(player, type);
        int offset = Math.max(0, page) * WIKI_SPECIAL_SLOTS.length;
        for (int i = 0; i < WIKI_SPECIAL_SLOTS.length && offset + i < recipes.size(); i++) {
            String id = recipes.get(offset + i);
            if (isMachineWikiEntry(id)) {
                String machineId = machineWikiMachineId(id);
                String outputId = machineWikiOutputId(id);
                MachineDefinition machine = service.machines().machines().get(machineId);
                if (machine == null) continue;
                ItemStack icon = machineWikiOutputIcon(machine, outputId);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    ArrayList<String> lore = new ArrayList<>();
                    lore.add("<gray>Wytwarzane w: <white>" + stripMini(machine.displayName()) + "</white></gray>");
                    lore.add("<yellow>Kliknij, aby przejść do listy procesów maszyny.</yellow>");
                    if (player != null && !isMachineUnlockedFor(player, machine, type)) {
                        lore.add("");
                        lore.add("<gray>Odblokowywanie: <gold>" + machineUnlockText(machine) + "</gold></gray>");
                    }
                    applyLore(meta, lore);
                    hideAttributes(meta); icon.setItemMeta(meta);
                }
                inv.setItem(WIKI_SPECIAL_SLOTS[i], icon);
                continue;
            }
            SpecialRecipeDefinition recipe = service.specialItems().recipe(id).orElse(null);
            ItemStack icon;
            if (recipe == null) {
                SpecialItemDefinition definition = service.specialItems().item(id).orElse(null);
                if (definition == null || !definition.enabled()) continue;
                icon = service.specialItems().createItem(id, 1);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    ArrayList<String> lore = new ArrayList<>(withoutStaticUnlockLore(definition.lore()));
                    Optional<String> outputRecipeId = recipeIdForSpecialOutput(id);
                    outputRecipeId.flatMap(rid -> service.specialItems().recipe(rid)).ifPresent(outputRecipe -> {
                        lore.add("");
                        lore.add("<yellow>Kliknij, aby zobaczyć recepturę.</yellow>");
                        if (player != null && !isRecipeUnlockedFor(player, outputRecipe) && !outputRecipe.unlock().isEmpty()) {
                            lore.add("");
                            lore.add("<gray>Odblokowywanie: <gold>" + service.recipeUnlockText(outputRecipe) + "</gold></gray>");
                        }
                    });
                    applyLore(meta, lore);
                    hideAttributes(meta);
                    icon.setItemMeta(meta);
                }
                inv.setItem(WIKI_SPECIAL_SLOTS[i], icon);
                continue;
            }
            icon = service.recipeOutput(recipe);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                ArrayList<String> lore = new ArrayList<>();
                if (recipe.outputSpecialItem() != null && !recipe.outputSpecialItem().isBlank()) {
                    service.specialItems().item(recipe.outputSpecialItem()).ifPresent(def -> lore.addAll(withoutStaticUnlockLore(def.lore())));
                }
                if (!lore.isEmpty()) lore.add("");
                lore.add("<yellow>Kliknij, aby zobaczyć recepturę.</yellow>");
                if (player != null && !isRecipeUnlockedFor(player, recipe) && !recipe.unlock().isEmpty()) {
                    lore.add("");
                    lore.add("<gray>Odblokowywanie: <gold>" + service.recipeUnlockText(recipe) + "</gold></gray>");
                }
                applyLore(meta, lore);
                hideAttributes(meta);
                icon.setItemMeta(meta);
            }
            inv.setItem(WIKI_SPECIAL_SLOTS[i], icon);
        }
    }

    private List<String> wikiRecipeIds(Player player, MinionTypeDefinition type) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> storage = new LinkedHashSet<>();
        LinkedHashSet<String> updates = new LinkedHashSet<>();

        // Pierwszy kafelek receptur zawsze prowadzi do receptury aktualnie oglądanego miniona.
        service.specialItems().recipes().values().stream()
                .filter(SpecialRecipeDefinition::enabled)
                .filter(recipe -> type.id().equalsIgnoreCase(recipe.outputMinionType()))
                .sorted(Comparator.comparingInt(SpecialRecipeDefinition::outputMinionTier).thenComparing(SpecialRecipeDefinition::id))
                .map(SpecialRecipeDefinition::id)
                .findFirst()
                .ifPresent(ids::add);

        for (String id : type.wikiSpecialItems()) {
            addWikiRecipeId(ids, storage, updates, id);
        }

        String exactTypeId = type.id().toLowerCase(java.util.Locale.ROOT);
        service.specialItems().recipes().values().stream()
                .filter(SpecialRecipeDefinition::enabled)
                .filter(recipe -> recipe.unlock().townMinionLevels().containsKey(exactTypeId))
                .filter(recipe -> !"repair_compressor_update".equalsIgnoreCase(recipe.id()))
                .forEach(recipe -> addWikiRecipeId(ids, storage, updates, recipe.id()));

        if (minionSupportsStorage(type)) {
            addStorageRecipe(storage, "storage_expander");
            addStorageRecipe(storage, "medium_minion_storage");
            addStorageRecipe(storage, "large_minion_storage");
        }

        service.specialItems().boosters().values().stream()
                .filter(booster -> booster.tier() <= 2 || type.supportedBoosterTiers().contains(booster.tier()))
                .sorted(Comparator.comparingInt(booster -> booster.tier()))
                .forEach(booster -> recipeIdForSpecialOutput(booster.specialItemId()).ifPresent(updates::add));
        if (type.autoSmelter() != null && type.autoSmelter().enabled()) {
            recipeIdForSpecialOutput(type.autoSmelter().requiredSpecialItem()).ifPresent(updates::add);
        }
        if (typeSupportsCompression(type)) {
            recipeIdForSpecialOutput("compressor_update").ifPresent(updates::add);
        }

        ids.addAll(storage);
        ids.addAll(updates);

        Set<String> craftingIngredients = minionCraftingIngredientIds(type);
        String ownRecipe = ids.stream().filter(id -> service.specialItems().recipe(id).map(r -> type.id().equalsIgnoreCase(r.outputMinionType())).orElse(false)).findFirst().orElse("");
        ids.removeIf(id -> !id.equalsIgnoreCase(ownRecipe) && isCraftingIngredientEntry(id, craftingIngredients));

        // W trybie „tylko odblokowane” ukrywamy również zablokowane receptury/itemy
        // na stronie konkretnego miniona. Tryb „pokaż wszystko” pozostawia pełną listę.
        if (!wikiShowAll(player)) {
            ids.removeIf(id -> !isRecipeOrItemUnlockedFor(player, id));
        }
        return new ArrayList<>(ids);
    }

    private Set<String> minionCraftingIngredientIds(MinionTypeDefinition type) {
        if (type == null) return Set.of();
        return service.specialItems().recipes().values().stream()
                .filter(recipe -> type.id().equalsIgnoreCase(recipe.outputMinionType()))
                .flatMap(recipe -> recipe.ingredients().values().stream())
                .map(SpecialIngredient::specialItemId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isCraftingIngredientEntry(String entry, Set<String> craftingIngredients) {
        if (entry == null || entry.isBlank() || craftingIngredients == null || craftingIngredients.isEmpty()) return false;
        if (isMachineWikiEntry(entry)) return false;
        String normalized = entry.toLowerCase(java.util.Locale.ROOT);
        if (craftingIngredients.contains(normalized)) return true;
        SpecialRecipeDefinition recipe = service.specialItems().recipe(normalized).orElse(null);
        return recipe != null && recipe.outputSpecialItem() != null
                && craftingIngredients.contains(recipe.outputSpecialItem().toLowerCase(java.util.Locale.ROOT));
    }

    public boolean toggleWikiViewMode(Player player) {
        if (player == null) return false;
        UUID id = player.getUniqueId();
        if (wikiShowAll.contains(id)) {
            wikiShowAll.remove(id);
            return false;
        }
        wikiShowAll.add(id);
        return true;
    }

    public boolean wikiShowAll(Player player) {
        return player != null && wikiShowAll.contains(player.getUniqueId());
    }

    private boolean isElectronicsReturn(String returnTypeId) {
        return ELECTRONICS_RETURN_ID.equals(returnTypeId);
    }

    private ItemStack wikiViewToggleItem(Player player) {
        boolean showAll = wikiShowAll(player);
        return showAll
                ? item(Material.GLASS, "<aqua>Pokaż tylko odblokowane</aqua>", List.of("<gray>Aktualnie widzisz wszystko.</gray>", "<yellow>Kliknij, aby ukryć zablokowane rzeczy.</yellow>"))
                : item(Material.DIAMOND_BLOCK, "<aqua>Pokaż wszystko</aqua>", List.of("<gray>Aktualnie widzisz tylko odblokowane rzeczy.</gray>", "<yellow>Kliknij, aby pokazać też zablokowane.</yellow>"));
    }

    private boolean isRecipeOrItemUnlockedFor(Player player, String id) {
        if (id == null || id.isBlank()) return false;
        if (isMachineWikiEntry(id)) {
            MachineDefinition machine = service.machines().machines().get(machineWikiMachineId(id));
            return machine != null && isMachineUnlockedFor(player, machine, null);
        }
        SpecialRecipeDefinition recipe = service.specialItems().recipe(id).orElse(null);
        if (recipe == null) {
            Optional<SpecialRecipeDefinition> outputRecipe = recipeIdForSpecialOutput(id).flatMap(rid -> service.specialItems().recipe(rid));
            return outputRecipe.map(r -> isRecipeUnlockedFor(player, r)).orElse(true);
        }
        return isRecipeUnlockedFor(player, recipe);
    }

    private boolean isRecipeUnlockedFor(Player player, SpecialRecipeDefinition recipe) {
        if (recipe == null) return true;
        if (recipe.unlock().isEmpty()) return true;
        if (player == null) return true;
        if (service.developerMode(player)) return true;
        UUID townId = service.towns().townIdOf(player.getUniqueId()).orElse(null);
        return townId != null && service.hasRecipeUnlocks(townId, recipe);
    }

    private boolean isMinionUnlockedFor(Player player, MinionTypeDefinition type) {
        if (type == null) return false;
        Optional<SpecialRecipeDefinition> recipe = service.specialItems().recipes().values().stream()
                .filter(r -> type.id().equalsIgnoreCase(r.outputMinionType()))
                .findFirst();
        return recipe.map(r -> isRecipeUnlockedFor(player, r)).orElse(true);
    }

    private void addWikiRecipeId(Set<String> normal, Set<String> storage, Set<String> updates, String id) {
        if (id == null || id.isBlank() || isHiddenRobotId(id)) return;
        SpecialRecipeDefinition recipe = service.specialItems().recipe(id).orElse(null);
        String output = recipe == null ? id : recipe.outputSpecialItem();
        if (isHiddenRobotId(output)) return;
        if (recipe == null) {
            Optional<MachineDefinition> directMachine = machineByIdOrSpecialItem(id);
            if (directMachine.isPresent()) {
                MachineDefinition machine = directMachine.get();
                return;
            }
            Optional<MachineDefinition> producer = machineProducing(id);
            if (producer.isPresent()) {
                normal.add(machineWikiEntry(producer.get().id(), id));
                return;
            }
        }
        if (isElectronicsWikiOnlySpecialItem(id) || isElectronicsWikiOnlySpecialItem(output)) return;
        if (isStorageRecipe(id, output)) storage.add(id);
        else if (isUpdateRecipe(id, output)) updates.add(id);
        else normal.add(id);
    }

    public boolean isMachineWikiEntry(String id) { return id != null && id.startsWith(MACHINE_WIKI_PREFIX); }

    public String pluginItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";
        Optional<String> special = service.specialItems().readSpecialItemId(item);
        if (special.isPresent()) return special.get().toLowerCase(java.util.Locale.ROOT);
        Optional<MinionItemFactory.MinionItemData> minion = itemFactory.read(item);
        return minion.map(data -> "minion:" + data.typeId() + ":" + data.tier()).orElse("");
    }

    public boolean openWikiCraftingForItem(Player player, ItemStack item, String returnTypeId) {
        String recipeId = wikiRecipeForItem(item);
        if (!recipeId.isBlank()) {
            openRecipe(player, recipeId, returnTypeId);
            return true;
        }
        String specialId = service.specialItems().readSpecialItemId(item).orElse("");
        if (specialId.isBlank()) return false;
        for (MachineDefinition machine : service.machines().machines().values()) {
            if (!machine.enabled() || isHiddenRobotId(machine.id())) continue;
            String processId = machineProcessForOutput(machine, specialId);
            if (!processId.isBlank()) {
                openWikiMachineRecipe(player, returnTypeId, machine.id(), processId);
                return true;
            }
        }
        return false;
    }

    public void openWikiItemUsages(Player player, ItemStack item, String returnTypeId) {
        String specialId = service.specialItems().readSpecialItemId(item).orElse("");
        if (specialId.isBlank()) return;
        List<String> usages = wikiUsageEntries(specialId);
        Inventory inv = Bukkit.createInventory(new SpecialRecipeMenuHolder(USES_PREFIX + specialId, returnTypeId == null ? "" : returnTypeId), 54,
                miniMessage.deserialize("<dark_gray>Zastosowania: " + prettyId(specialId)));
        fill(inv);
        ItemStack header = service.specialItems().createItem(specialId, 1);
        ItemMeta headerMeta = header.getItemMeta();
        if (headerMeta != null) {
            applyLore(headerMeta, List.of("<gray>PPM pokazuje, co można utworzyć z tego przedmiotu.</gray>", "<gray>Zastosowania: <white>" + usages.size() + "</white></gray>"));
            header.setItemMeta(headerMeta);
        }
        inv.setItem(4, header);
        for (int i = 0; i < Math.min(usages.size(), WIKI_MACHINE_RECIPE_SLOTS.length); i++) {
            inv.setItem(WIKI_MACHINE_RECIPE_SLOTS[i], wikiUsageIcon(usages.get(i)));
        }
        if (usages.isEmpty()) inv.setItem(22, item(Material.BARRIER, "<gray>Brak dalszych zastosowań</gray>", List.of()));
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of()));
        player.openInventory(inv);
    }

    public boolean isUsesHolder(SpecialRecipeMenuHolder holder) {
        return holder != null && holder.recipeId() != null && holder.recipeId().startsWith(USES_PREFIX);
    }

    public String wikiUsageEntryAtSlot(SpecialRecipeMenuHolder holder, int slot) {
        if (!isUsesHolder(holder)) return "";
        String specialId = holder.recipeId().substring(USES_PREFIX.length());
        List<String> entries = wikiUsageEntries(specialId);
        for (int i = 0; i < WIKI_MACHINE_RECIPE_SLOTS.length && i < entries.size(); i++) if (WIKI_MACHINE_RECIPE_SLOTS[i] == slot) return entries.get(i);
        return "";
    }

    public void openWikiUsageEntry(Player player, String entry, String returnTypeId) {
        if (entry == null || entry.isBlank()) return;
        if (entry.startsWith("recipe:")) openRecipe(player, entry.substring("recipe:".length()), returnTypeId);
        else if (entry.startsWith("machine:")) {
            String[] parts = entry.split(":", 3);
            if (parts.length == 3) openWikiMachineRecipe(player, returnTypeId, parts[1], parts[2]);
        }
    }

    private List<String> wikiUsageEntries(String specialId) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (SpecialRecipeDefinition recipe : service.specialItems().recipes().values()) {
            boolean used = recipe.ingredients().values().stream().anyMatch(ingredient -> ingredient != null && specialId.equalsIgnoreCase(ingredient.specialItemId()));
            if (used && !isHiddenRobotId(recipe.id()) && !isHiddenRobotId(recipe.outputSpecialItem())) entries.add("recipe:" + recipe.id());
        }
        for (MachineDefinition machine : service.machines().machines().values()) {
            if (!machine.enabled() || isHiddenRobotId(machine.id())) continue;
            for (MachineRecipe recipe : machine.recipes()) {
                if (specialId.equalsIgnoreCase(recipe.inputSpecialItem()) || specialId.equalsIgnoreCase(recipe.secondarySpecialItem()) || specialId.equalsIgnoreCase(recipe.fuelSpecialItem())) {
                    entries.add("machine:" + machine.id() + ":" + recipe.id());
                }
            }
        }
        return new ArrayList<>(entries);
    }

    private ItemStack wikiUsageIcon(String entry) {
        if (entry.startsWith("recipe:")) {
            SpecialRecipeDefinition recipe = service.specialItems().recipe(entry.substring("recipe:".length())).orElse(null);
            return recipe == null ? item(Material.BARRIER, "<red>Brak receptury</red>", List.of()) : service.recipeOutput(recipe);
        }
        if (entry.startsWith("machine:")) {
            String[] parts = entry.split(":", 3);
            MachineDefinition machine = parts.length == 3 ? service.machines().machines().get(parts[1]) : null;
            return machine == null ? item(Material.BARRIER, "<red>Brak maszyny</red>", List.of()) : machineProcessIcon(machine, parts[2]);
        }
        return item(Material.BARRIER, "<red>Nieznane zastosowanie</red>", List.of());
    }

    private String machineProcessForOutput(MachineDefinition machine, String outputId) {
        if (machine == null || outputId == null || outputId.isBlank()) return "";
        if ("steel_ingot".equalsIgnoreCase(outputId) && "ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) return "electric_steel";
        for (String representative : wikiMachineProcessIds(machine)) {
            for (String variant : machineProcessVariants(machine, representative)) {
                if (variant.equals("electric_steel") && "steel_ingot".equalsIgnoreCase(outputId)) return representative;
                MachineRecipe recipe = machine.recipes().stream().filter(r -> r.id().equalsIgnoreCase(variant)).findFirst().orElse(null);
                if (recipe != null && outputId.equalsIgnoreCase(recipe.outputSpecialItem())) return representative;
                VanillaFurnaceProcess process = pluginFurnaceProcesses().stream().filter(v -> v.id().equalsIgnoreCase(variant)).findFirst().orElse(null);
                if (process != null && outputId.equalsIgnoreCase(service.specialItems().readSpecialItemId(process.output()).orElse(""))) return representative;
            }
        }
        return "";
    }

    public void openWikiSpecialEntry(Player player, String entry, String returnTypeId) {
        if (isMachineWikiEntry(entry)) {
            String machineId = machineWikiMachineId(entry);
            String outputId = machineWikiOutputId(entry);
            MachineDefinition machine = service.machines().machines().get(machineId);
            String processId = machine == null ? "" : machineProcessForOutput(machine, outputId);
            if (!processId.isBlank()) openWikiMachineRecipe(player, returnTypeId, machineId, processId);
            else openWikiMachine(player, returnTypeId, machineId);
        } else openRecipe(player, entry, returnTypeId);
    }

    private String machineWikiEntry(String machineId, String outputId) {
        return MACHINE_WIKI_PREFIX + machineId.toLowerCase(java.util.Locale.ROOT) + "@" + (outputId == null ? "" : outputId.toLowerCase(java.util.Locale.ROOT));
    }

    private String machineWikiMachineId(String entry) {
        String[] parts = entry.split("@", -1);
        return parts.length > 1 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "";
    }

    private String machineWikiOutputId(String entry) {
        String[] parts = entry.split("@", -1);
        return parts.length > 2 ? parts[2].toLowerCase(java.util.Locale.ROOT) : "";
    }

    private ItemStack machineWikiOutputIcon(MachineDefinition machine, String outputToken) {
        if (machine == null) return item(Material.BARRIER, "<red>Brak maszyny</red>", List.of());
        if (outputToken != null && !outputToken.isBlank()) {
            if (outputToken.startsWith("vanilla:") || outputToken.equalsIgnoreCase("electric_steel")) {
                return machineProcessIcon(machine, outputToken);
            }
            ItemStack special = service.specialItems().createItem(outputToken, 1);
            if (!special.getType().isAir()) return special;
            MachineRecipe recipe = machine.recipes().stream()
                    .filter(candidate -> candidate.id().equalsIgnoreCase(outputToken))
                    .findFirst()
                    .orElse(null);
            if (recipe != null) return machineRecipeOutputIcon(recipe);
        }
        return machineIcon(machine, List.of());
    }

    private Optional<MachineDefinition> machineByIdOrSpecialItem(String id) {
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        return service.machines().machines().values().stream().filter(MachineDefinition::enabled)
                .filter(machine -> normalized.equals(machine.id()) || normalized.equalsIgnoreCase(machine.specialItemId())).findFirst();
    }

    private Optional<MachineDefinition> machineProducing(String specialItemId) {
        if (specialItemId == null || specialItemId.isBlank()) return Optional.empty();
        // Stal ma także proces techniczny pieca elektrycznego, który nie jest zwykłym wpisem
        // MachineRecipe. Preferujemy ten widok, aby wiki od razu pokazało żelazo + 8 węgla.
        if ("steel_ingot".equalsIgnoreCase(specialItemId)) {
            Optional<MachineDefinition> electric = service.machines().machines().values().stream()
                    .filter(MachineDefinition::enabled)
                    .filter(machine -> "ELECTRIC_FURNACE".equalsIgnoreCase(machine.type()))
                    .findFirst();
            if (electric.isPresent()) return electric;
        }
        return service.machines().machines().values().stream().filter(MachineDefinition::enabled)
                .filter(machine -> machine.recipes().stream().anyMatch(recipe -> specialItemId.equalsIgnoreCase(recipe.outputSpecialItem())))
                .findFirst();
    }

    private boolean minionSupportsStorage(MinionTypeDefinition type) {
        return type.tiers().values().stream().anyMatch(tier -> tier.storageSlots() > 0);
    }

    private void addStorageRecipe(Set<String> storage, String recipeId) {
        if (service.specialItems().recipe(recipeId).isPresent()) storage.add(recipeId);
    }

    private boolean isStorageRecipe(String recipeId, String outputSpecialItem) {
        String id = (outputSpecialItem == null || outputSpecialItem.isBlank() ? recipeId : outputSpecialItem).toLowerCase(java.util.Locale.ROOT);
        return id.equals("storage_expander") || id.equals("medium_minion_storage") || id.equals("large_minion_storage") || id.contains("minion_storage");
    }

    private boolean isUpdateRecipe(String recipeId, String outputSpecialItem) {
        String id = (outputSpecialItem == null || outputSpecialItem.isBlank() ? recipeId : outputSpecialItem).toLowerCase(java.util.Locale.ROOT);
        return id.contains("update") || id.contains("booster") || id.equals("auto_smelter");
    }

    private boolean isElectricMachineSpecialItem(String id) {
        if (id == null || id.isBlank() || service.machines() == null) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        return service.machines().machines().values().stream()
                .filter(machine -> machine.enabled() && machine.energy().enabled())
                .anyMatch(machine -> normalized.equals(machine.id().toLowerCase(java.util.Locale.ROOT))
                        || (machine.specialItemId() != null && normalized.equals(machine.specialItemId().toLowerCase(java.util.Locale.ROOT))));
    }

    private boolean isElectronicsWikiOnlySpecialItem(String id) {
        return isElectricMachineSpecialItem(id) || id != null && (id.equalsIgnoreCase("smelting_furnace") || id.equalsIgnoreCase("uranium_enricher")) || isElectronicsStandaloneSpecialItem(id);
    }

    private boolean isHiddenRobotId(String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("robot") || normalized.contains("miner_robot") || normalized.contains("mining_robot")
                || normalized.equals("sugar_cube");
    }

    private boolean isElectronicsStandaloneSpecialItem(String id) {
        return isCableSpecialItem(id) || isBatterySpecialItem(id) || isMachineUpgradeSpecialItem(id);
    }

    private boolean isMachineUpgradeSpecialItem(String id) {
        return service.specialItems().machineUpgradeBySpecialItemId(id).isPresent();
    }

    private boolean isCableSpecialItem(String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("cable") || normalized.contains("kabel");
    }

    private boolean isBatterySpecialItem(String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("battery") || normalized.equals("energy_diamond") || normalized.endsWith("_battery");
    }

    private boolean typeSupportsCompression(MinionTypeDefinition type) {
        if (type == null) return false;
        for (ResourceDrop drop : type.resourceTable()) {
            ResourceDefinition resource = service.definitions().resources().get(drop.resourceId());
            if (resource == null || !resource.compressionEnabled() || !resource.blockConvertible()) continue;
            if (service.definitions().resources().containsKey("compressed_" + resource.id().toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
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
            ItemStack icon = service.specialItems().createItem(ingredient.specialItemId(), ingredient.amount());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(component("<white>" + itemLabel(ingredient.specialItemId(), ingredient.material(), ingredient.customModelData()) + " x" + ingredient.amount() + "</white>"));
                List<String> lore = recipeIdForSpecialOutput(ingredient.specialItemId()).isPresent()
                        ? List.of("<yellow>Kliknij, aby zobaczyć sposób zdobycia.</yellow>")
                        : List.of();
                applyLore(meta, lore);
                hideAttributes(meta);
                icon.setItemMeta(meta);
            }
            return icon;
        }
        ResourceDefinition resource = resourceByMaterial(ingredient.material(), ingredient.customModelData());
        if (resource != null) {
            ItemStack icon = resourceIcon(resource, ingredient.amount());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(component("<white>" + stripMini(resource.displayName()) + " x" + ingredient.amount() + "</white>"));
                applyLore(meta, List.of());
                icon.setItemMeta(meta);
            }
            return icon;
        }
        return item(ingredient.material(), "<white>" + itemLabel("", ingredient.material(), ingredient.customModelData()) + " x" + ingredient.amount() + "</white>", List.of(), ingredient.amount(), ingredient.customModelData());
    }

    private List<String> ingredientDependencyLore(SpecialIngredient ingredient) {
        // Rola składnika jest widoczna z układu receptury; nie dokładamy technicznego źródła ani ID.
        return List.of();
    }

    private List<String> resourceSourceLore(String resourceId) {
        // Źródło zasobu nie jest częścią domyślnego hovera składnika.
        return List.of();
    }

    private ItemStack stationIcon(SpecialRecipeDefinition recipe) {
        return item(Material.CRAFTING_TABLE, " ", List.of());
    }

    private void renderStorage(Inventory inv, MinionMenuData data) {
        List<ItemStack> stacks = storageStacks(data.storage());
        for (int i = 0; i < STORAGE_SLOTS.length; i++) {
            int slot = STORAGE_SLOTS[i];
            int storageSlot = i + 1;
            if (storageSlot > data.storageSlotsUnlocked()) {
                inv.setItem(slot, item(Material.RED_STAINED_GLASS_PANE, "<red>Slot zablokowany</red>", List.of(
                        "<gray>Odblokowanie: Tier <gold>" + unlockTier(data.typeId(), storageSlot) + "</gold></gray>"
                )));
                continue;
            }
            inv.setItem(slot, i < stacks.size() ? stacks.get(i) : null);
        }
    }

    private List<ItemStack> storageStacks(Map<String, Long> storage) {
        ArrayList<ItemStack> result = new ArrayList<>();
        storage.entrySet().stream()
                .sorted(this::compareStorageEntries)
                .forEach(entry -> {
                    ResourceDefinition def = service.definitions().resources().get(entry.getKey());
                    Material material = def == null ? Material.CHEST : def.material();
                    long remaining = entry.getValue();
                    int stackSize = def == null ? 64 : Math.max(1, Math.min(64, def.stackSize()));
                    while (remaining > 0 && result.size() < STORAGE_SLOTS.length) {
                        int amount = (int) Math.max(1, Math.min(stackSize, remaining));
                        result.add(storageStack(def, material, amount, def == null ? 0 : def.customModelData()));
                        remaining -= amount;
                    }
                });
        return result;
    }

    private int compareStorageEntries(Map.Entry<String, Long> left, Map.Entry<String, Long> right) {
        int priority = Integer.compare(storageEntryPriority(left.getKey()), storageEntryPriority(right.getKey()));
        if (priority != 0) return priority;
        return left.getKey().compareToIgnoreCase(right.getKey());
    }

    private int storageEntryPriority(String resourceId) {
        if (resourceId == null) return 100;
        String id = resourceId.toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("super_compressed_") || id.startsWith("compressed_") || id.startsWith("enchanted_")) return 0;
        return 10;
    }

    private ItemStack storageStack(ResourceDefinition resource, Material material, int amount, int customModelData) {
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (customModelData > 0) meta.setCustomModelData(customModelData);
            if (resource != null && resource.displayName() != null && !resource.displayName().isBlank()) meta.displayName(component(resource.displayName()));
            if (resource != null && "spruce_resin".equalsIgnoreCase(resource.id())) meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
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
                if (data.activeBoosterTier() > 0 && data.boosterSecondsRemaining() > 0) {
                    lore.add("<gray>Aktywny bonus: <green>+" + formatPercent(data.boosterSpeedBoostPercent()) + "%</green> <dark_gray>•</dark_gray> <gold>" + formatDuration(data.boosterSecondsRemaining()) + "</gold></gray>");
                } else {
                    lore.add("<gray>Booster oczekuje na aktywację.</gray>");
                }
                lore.add("<yellow>PPM: wyjmij booster.</yellow>");
                applyLore(meta, lore);
                hideAttributes(meta);
                copy.setItemMeta(meta);
            }
            return copy;
        }
        return item(Material.RED_STAINED_GLASS_PANE, "<gold>Slot boostera</gold>", List.of(
                "<gray>Włóż booster, aby czasowo przyspieszyć produkcję miniona.</gray>",
                "<yellow>PPM: wyjmij booster.</yellow>"
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
        if (saved != null && !saved.getType().isAir()) {
            ItemStack copy = saved.clone();
            ItemMeta meta = copy.getItemMeta();
            if (meta != null) {
                applyLore(meta, List.of(
                        "<gray>Ulepszenie poprawia działanie miniona.</gray>",
                        "<yellow>PPM: wyjmij ulepszenie.</yellow>"
                ));
                hideAttributes(meta);
                copy.setItemMeta(meta);
            }
            return copy;
        }
        return item(placeholder, "<gold>Ulepszenie</gold>", List.of(
                "<gray>Włóż ulepszenie, aby poprawić działanie miniona.</gray>",
                "<yellow>PPM: wyjmij ulepszenie.</yellow>"
        ));
    }

    private ItemStack storageChestStatus(MinionMenuData data) {
        if (service.hasStorageChest(data.id())) {
            ItemStack icon = service.storageChestItemForMenu(data.id());
            if (icon == null || icon.getType().isAir()) icon = new ItemStack(Material.CHEST);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(miniMessage.deserialize("<green>Magazyn podłączony</green>"));
                applyLore(meta, List.of(
                        "<gray>Dodatkowy magazyn odbiera nadmiar surowców miniona.</gray>",
                        "<yellow>Kliknij: otwórz <dark_gray>•</dark_gray> PPM: odłącz.</yellow>"
                ));
                hideAttributes(meta);
                icon.setItemMeta(meta);
            }
            return icon;
        }
        return item(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Rozszerzenie magazynu</yellow>", List.of(
                "<gray>Włóż rozszerzenie, aby zwiększyć miejsce na surowce miniona.</gray>",
                "<yellow>PPM: odłącz zamontowane rozszerzenie.</yellow>"
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
            if ((name == null || name.isBlank() || " ".equals(name)) && (lore == null || lore.isEmpty())) hideTooltip(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyLore(ItemMeta meta, List<String> lore) {
        if (lore == null || lore.isEmpty()) {
            meta.lore(null);
            return;
        }
        List<Component> components = new ArrayList<>();
        boolean previousBlank = true;
        for (String line : lore) {
            if (line == null) continue;
            boolean blank = line.isBlank();
            if (blank && previousBlank) continue;
            components.add(blank ? Component.empty() : component(line));
            previousBlank = blank;
        }
        while (!components.isEmpty() && components.get(components.size() - 1).equals(Component.empty())) components.remove(components.size() - 1);
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

    private void hideTooltip(ItemMeta meta) {
        if (meta != null) meta.setHideTooltip(true);
    }

    private String dynamicRequirementPresentation(long required, ResourceDefinition resource, int targetTier) {
        int stackSize = resource == null ? 64 : Math.max(1, Math.min(64, resource.stackSize()));
        String baseName = resource == null ? "Surowiec" : humanResourceName(resource.id());
        if (resource != null && targetTier >= 4 && resource.compressionEnabled() && resource.blockConvertible()
                && required > (long) stackSize * 2L && service.specialItems() != null) {
            int unit = Math.max(1, service.specialItems().compressedUnitValue());
            long compressed = Math.max(1L, (required + unit - 1L) / unit);
            String compressedId = "compressed_" + resource.id();
            String compressedName = service.specialItems().item(compressedId)
                    .map(def -> stripMini(def.displayName()))
                    .orElse("Skompresowany " + baseName.toLowerCase(java.util.Locale.ROOT));
            return "<gold>" + formatRequirementAmount(compressed, 64) + "</gold> <gray>" + compressedName + "</gray>"
                    + " <dark_gray>(równowartość " + formatNumber(required) + " bazowych)</dark_gray>";
        }
        return "<gold>" + formatRequirementAmount(required, stackSize) + "</gold> <gray>" + baseName + "</gray>";
    }

    private static String formatRequirementAmount(long amount, int stackSize) {
        long safe = Math.max(0L, amount);
        int stack = Math.max(1, Math.min(64, stackSize));
        if (safe > 2L * stack && safe % stack == 0L) {
            return formatNumber(safe / stack) + " stacków";
        }
        return formatNumber(safe) + "x";
    }

    private List<String> withoutStaticUnlockLore(List<String> lore) {
        if (lore == null || lore.isEmpty()) return List.of();
        ArrayList<String> cleaned = new ArrayList<>();
        for (String line : lore) {
            if (line == null) continue;
            String plain = stripMini(line).trim().toLowerCase(java.util.Locale.ROOT);
            if (plain.startsWith("odblokowanie:") || plain.startsWith("odblokowywanie:") || plain.startsWith("wymaga miniona ")) continue;
            cleaned.add(line);
        }
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isBlank()) cleaned.remove(cleaned.size() - 1);
        return cleaned;
    }

    private Component component(String text) {
        if (text == null || text.isBlank()) return Component.empty();
        return miniMessage.deserialize(text);
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        long minutes = safe / 60L;
        long remainder = safe % 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, remainder);
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
        trimmed.add("<dark_gray>... i więcej</dark_gray>");
        return trimmed;
    }
}
