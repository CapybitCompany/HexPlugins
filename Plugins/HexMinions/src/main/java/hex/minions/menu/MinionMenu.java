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
                "<yellow>PPM w menu miasta otwiera to menu.</yellow>"
        )));
        renderStorage(inv, d);
        inv.setItem(ADDON_SLOT_1, addonItem(d, "addon_1", Material.LAVA_BUCKET, "<gold>Slot dodatku #1</gold>"));
        inv.setItem(ADDON_SLOT_2, addonItem(d, "addon_2", Material.HOPPER, "<green>Slot dodatku #2</green>"));
        inv.setItem(STORAGE_CHEST_SLOT, storageChestStatus(d));
        inv.setItem(45, item(Material.ENDER_PEARL, "<aqua>Przenieś tutaj</aqua>", List.of("<gray>Przenieś do pozycji, w której stoisz.</gray>")));
        inv.setItem(47, item(Material.BOOK, "<aqua>Wiki minionów</aqua>", List.of("<gray>Zobacz wszystkie skonfigurowane typy minionów.</gray>")));
        inv.setItem(48, item(Material.CHEST, "<green>Odbierz surowce</green>", List.of("<gray>Przenieś storage do ekwipunku.</gray>")));
        inv.setItem(50, item(Material.ANVIL, "<gold>Ulepsz</gold>", List.of("<gray>Wymagania: <white>" + d.nextUpgradeRequirementsText())));
        inv.setItem(53, item(Material.BARRIER, "<red>Podnieś miniona</red>", List.of("<gray>Zwraca item miniona.</gray>")));
        player.openInventory(inv);
    }

    public void openWiki(Player player) {
        Inventory inv = Bukkit.createInventory(new MinionWikiHolder(""), 54, miniMessage.deserialize("<dark_gray>Wiki minionów"));
        fill(inv);
        inv.setItem(4, item(Material.BOOK, "<aqua>Wiki minionów</aqua>", List.of(
                "<gray>Lista jest generowana automatycznie z pliku</gray>",
                "<white>minion-types.yml</white><gray>.</gray>",
                "",
                "<yellow>Kliknij główkę, aby zobaczyć poziomy, dropy i wymagania.</yellow>"
        )));
        List<MinionTypeDefinition> types = service.definitions().minionTypes().values().stream()
                .filter(MinionTypeDefinition::enabled)
                .sorted(Comparator.comparing(MinionTypeDefinition::category).thenComparing(MinionTypeDefinition::id))
                .toList();
        for (int i = 0; i < Math.min(types.size(), WIKI_INDEX_SLOTS.length); i++) {
            MinionTypeDefinition type = types.get(i);
            inv.setItem(WIKI_INDEX_SLOTS[i], minionHead(type, 1, type.displayName(), wikiIndexLore(type)));
        }
        if (types.size() > WIKI_INDEX_SLOTS.length) {
            inv.setItem(49, item(Material.PAPER, "<yellow>Więcej typów</yellow>", List.of(
                    "<gray>Skonfigurowano <white>" + types.size() + "</white> typów.</gray>",
                    "<gray>Aktualne wiki pokazuje pierwsze <white>" + WIKI_INDEX_SLOTS.length + "</white>.</gray>",
                    "<dark_gray>W razie potrzeby dodaj paginację w kolejnym kroku.</dark_gray>"
            )));
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
        inv.setItem(25, service.specialItems().output(recipe));
        inv.setItem(43, stationIcon(recipe));
        inv.setItem(45, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do wiki miniona.</gray>")));
        player.openInventory(inv);
    }

    public void openEnchantedCrafting(Player player, String stationId) {
        Inventory inv = Bukkit.createInventory(new EnchantedCraftingMenuHolder(stationId), 54, miniMessage.deserialize("<dark_gray>Enchanted Crafting"));
        fill(inv);
        for (int slot : RECIPE_GRID_SLOTS) inv.setItem(slot, null);
        inv.setItem(4, item(Material.ENCHANTING_TABLE, "<aqua>Enchanted Crafting Table</aqua>", List.of("<gray>Włóż itemy w grid 3x3.</gray>", "<gray>Kliknij zielony przycisk craftingu.</gray>")));
        inv.setItem(16, item(Material.ARROW, "<yellow>Craft</yellow>", List.of()));
        inv.setItem(24, item(Material.GRAY_STAINED_GLASS_PANE, "<gray>Wynik trafia do ekwipunku</gray>", List.of("<dark_gray>Receptura jest sprawdzana po kliknięciu przycisku.</dark_gray>")));
        inv.setItem(33, item(Material.LIME_CONCRETE, "<green>Wykonaj crafting</green>", List.of("<gray>Sprawdza receptury przypisane do tego stołu.</gray>")));
        player.openInventory(inv);
    }

    public String wikiTypeAtSlot(int slot) {
        List<MinionTypeDefinition> types = service.definitions().minionTypes().values().stream()
                .filter(MinionTypeDefinition::enabled)
                .sorted(Comparator.comparing(MinionTypeDefinition::category).thenComparing(MinionTypeDefinition::id))
                .toList();
        for (int i = 0; i < Math.min(types.size(), WIKI_INDEX_SLOTS.length); i++) {
            if (WIKI_INDEX_SLOTS[i] == slot) return types.get(i).id();
        }
        return "";
    }

    private List<String> wikiIndexLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>ID: <white>" + type.id() + "</white></gray>");
        lore.add("<gray>Kategoria: <white>" + type.category() + "</white></gray>");
        lore.add("<gray>Max tier: <white>" + type.maxTier() + "</white></gray>");
        lore.add("");
        lore.add("<yellow>Co zdobywa:</yellow>");
        lore.addAll(dropsLore(type));
        lore.add("");
        lore.add("<green>Kliknij, aby zobaczyć poziomy.</green>");
        return trimLore(lore, 18);
    }

    private List<String> wikiHeaderLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>ID: <white>" + type.id() + "</white></gray>");
        lore.add("<gray>Kategoria: <white>" + type.category() + "</white></gray>");
        lore.add("<gray>Max tier: <white>" + type.maxTier() + "</white></gray>");
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

    private List<String> dropsLore(MinionTypeDefinition type) {
        List<String> lore = new ArrayList<>();
        if (type.resourceTable().isEmpty()) return List.of("<gray>Brak dropów w konfiguracji.</gray>");
        for (ResourceDrop drop : type.resourceTable()) {
            ResourceDefinition resource = service.definitions().resources().get(drop.resourceId());
            String name = resource == null ? drop.resourceId() : resource.displayName();
            String amount = drop.amountMin() == drop.amountMax() ? String.valueOf(drop.amountMin()) : drop.amountMin() + "-" + drop.amountMax();
            lore.add("<gray>- <white>" + name + "</white> x" + amount + " <green>" + chance(drop.chance()) + "%</green></gray>");
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
        Inventory inv = Bukkit.createInventory(new MinionStorageChestMenuHolder(minionId), 54, miniMessage.deserialize("<dark_gray>Storage miniona"));
        fill(inv);
        org.bukkit.inventory.Inventory chestInv = chest.get().getBlockInventory();
        for (int i = 0; i < Math.min(45, chestInv.getSize()); i++) {
            inv.setItem(i, chestInv.getItem(i));
        }
        inv.setItem(49, item(Material.ARROW, "<yellow>Powrót do miniona</yellow>", List.of("<gray>Zapisuje podgląd skrzynki i wraca do menu miniona.</gray>")));
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
            SpecialRecipeDefinition recipe = service.specialItems().recipe(recipes.get(i)).orElse(null);
            if (recipe == null) continue;
            ItemStack icon = service.specialItems().output(recipe);
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
        service.specialItems().recipes().values().forEach(recipe -> {
            String lower = recipe.id().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains(type.id().toLowerCase(java.util.Locale.ROOT)) && !ids.contains(recipe.id())) ids.add(recipe.id());
        });
        service.specialItems().recipes().values().forEach(recipe -> {
            if (recipe.id().contains("storage") && !ids.contains(recipe.id())) ids.add(recipe.id());
        });
        return ids;
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
                inv.setItem(slot, item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Zablokowany slot storage</gray>", List.of(
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
        for (Map.Entry<String, Long> entry : storage.entrySet()) {
            ResourceDefinition def = service.definitions().resources().get(entry.getKey());
            Material material = def == null ? Material.CHEST : def.material();
            String name = def == null ? entry.getKey() : def.displayName();
            long remaining = entry.getValue();
            int stackSize = def == null ? 64 : def.stackSize();
            while (remaining > 0 && result.size() < STORAGE_SLOTS.length) {
                int amount = (int) Math.max(1, Math.min(Math.min(64, stackSize), remaining));
                result.add(item(material, name + " <gray>x" + amount + "</gray>", List.of("<gray>Ilość w storage: <white>" + entry.getValue() + "</white></gray>"), amount, def == null ? 0 : def.customModelData()));
                remaining -= amount;
            }
        }
        return result;
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

    private ItemStack addonItem(MinionMenuData data, String slotId, Material placeholder, String name) {
        ItemStack saved = service.addonItem(data.id(), slotId);
        if (saved != null && !saved.getType().isAir()) return saved;
        return item(placeholder, name, List.of(
                "<gray>Włóż tutaj tylko specjalny item wymagany</gray>",
                "<gray>przez konfigurację ulepszeń tego miniona.</gray>",
                "<dark_gray>Slot jest edytowalny.</dark_gray>"
        ));
    }

    private ItemStack storageChestStatus(MinionMenuData data) {
        if (service.hasStorageChest(data.id())) {
            return item(Material.CHEST, "<green>Podpięta skrzynka storage</green>", List.of(
                    "<gray>Kliknij, aby otworzyć menu skrzynki.</gray>",
                    "<dark_gray>Skrzynka jest chroniona przed ręcznym zniszczeniem.</dark_gray>"
            ));
        }
        return item(Material.CHEST, "<yellow>Slot Minion Storage</yellow>", List.of(
                "<gray>Włóż tu specjalny item Minion Storage.</gray>",
                "<gray>Po zatwierdzeniu skrzynka pojawi się po lewej stronie miniona.</gray>",
                "<red>Jeśli po lewej nie ma miejsca, dostaniesz komunikat.</red>"
        ));
    }

    private ItemStack minionHead(MinionTypeDefinition type, int tier, String name, List<String> lore) {
        ItemStack item = itemFactory.createMinionItem(type, Math.max(1, Math.min(tier, type.maxTier())), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name));
            meta.lore(lore.stream().map(this::component).toList());
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
            meta.lore(lore.stream().map(this::component).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private Component component(String text) {
        if (text == null || text.isBlank()) return Component.empty();
        return miniMessage.deserialize(text);
    }

    private static String chance(double chance) {
        double percent = chance * 100.0D;
        if (Math.abs(percent - Math.rint(percent)) < 0.0001D) return String.valueOf((long) Math.rint(percent));
        return String.format(java.util.Locale.US, "%.2f", percent);
    }

    private static List<String> trimLore(List<String> lore, int max) {
        if (lore.size() <= max) return lore;
        ArrayList<String> trimmed = new ArrayList<>(lore.subList(0, Math.max(0, max - 1)));
        trimmed.add("<dark_gray>... i więcej w konfiguracji.</dark_gray>");
        return trimmed;
    }
}
