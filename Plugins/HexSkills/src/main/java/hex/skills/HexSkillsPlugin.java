package hex.skills;

import hex.core.api.compat.MinecraftCompatibility;
import hex.core.api.compat.SoundCompatibility;
import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.skills.config.SkillRegistry;
import hex.skills.database.SkillRepository;
import hex.skills.model.SkillDefinition;
import hex.skills.placeholder.SkillsPlaceholderExpansion;
import hex.skills.model.TriggerData;
import hex.skills.model.XpSource;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class HexSkillsPlugin extends JavaPlugin implements TabExecutor, Listener {
    private HexApi hex;
    private TownsApi towns;
    private Object triggers;
    private SkillRepository repository;
    private SkillRegistry registry = SkillRegistry.load(new File("missing-skills.yml"));
    private SkillsPlaceholderExpansion placeholderExpansion;
    private SkillEffectSettings effectSettings = SkillEffectSettings.defaults();
    private final Map<String, Object> subscriptions = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        MinecraftCompatibility.logStartupCompatibility(this);
        saveResourceIfMissing("skills.yml");
        saveResourceIfMissing("deluxemenus/hexskills.yml");

        if (!isHexTownsEnabled()) {
            return;
        }

        var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        var townsReg = Bukkit.getServicesManager().getRegistration(TownsApi.class);
        if (hexReg == null || townsReg == null) {
            getLogger().severe("HexCore or HexTowns not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hex = hexReg.getProvider();
        registerUiDefaults();
        this.towns = townsReg.getProvider();
        this.triggers = findTriggerService();
        this.repository = new SkillRepository(hex.db().db());

        hex.db().asyncRun(repository::ensureTables);
        towns.dataNamespace(this, "skills", (townId, members) -> hex.db().asyncRun(() -> repository.purgeTown(townId)));

        reloadSkills();
        registerPlaceholderExpansion(towns);
        var command = getCommand("hexskills");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("HexSkills enabled");
    }

    private boolean isHexTownsEnabled() {
        if (Bukkit.getPluginManager().isPluginEnabled("HexTowns")) {
            return true;
        }

        getLogger().severe("HexTowns is not enabled; disabling HexSkills.");
        getServer().getPluginManager().disablePlugin(this);
        return false;
    }

    @Override
    public void onDisable() {
        unsubscribeAll();
        if (placeholderExpansion != null) {
            invokeExpansion(placeholderExpansion, "unregister");
            placeholderExpansion = null;
        }
        getLogger().info("HexSkills disabled");
    }


    private void registerPlaceholderExpansion(TownsApi towns) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; skipping HexSkills placeholders for DeluxeMenus.");
            return;
        }
        try {
            this.placeholderExpansion = new SkillsPlaceholderExpansion(towns, repository, () -> registry);
            if (invokeExpansionBoolean(placeholderExpansion, "register")) {
                getLogger().info("Registered PlaceholderAPI expansion %hexskills_%.");
            } else {
                getLogger().warning("Could not register PlaceholderAPI expansion %hexskills_%.");
                this.placeholderExpansion = null;
            }
        } catch (Throwable throwable) {
            getLogger().warning("Could not register HexSkills placeholders: " + rootMessage(throwable));
            this.placeholderExpansion = null;
        }
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private void reloadSkills() {
        unsubscribeAll();
        File skillsFile = new File(getDataFolder(), "skills.yml");
        YamlConfiguration skillsYaml = YamlConfiguration.loadConfiguration(skillsFile);
        this.registry = SkillRegistry.load(skillsFile);
        this.effectSettings = SkillEffectSettings.from(skillsYaml);
        for (String triggerId : registry.triggerIds()) {
            Object listener = subscribeTrigger(triggerId);
            if (listener != null) {
                subscriptions.put(triggerId, listener);
            }
        }
        getLogger().info("Loaded skills=" + registry.all().size() + ", triggers=" + subscriptions.size());
    }

    private void unsubscribeAll() {
        if (triggers != null && !subscriptions.isEmpty()) {
            try {
                Class<?> triggerListenerClass = Class.forName("hex.core.api.trigger.TriggerListener");
                Class<?> triggerServiceClass = Class.forName("hex.core.api.trigger.TriggerService");
                Method unsubscribe = triggerServiceClass.getMethod("unsubscribe", String.class, triggerListenerClass);
                subscriptions.forEach((triggerId, listener) -> {
                    try {
                        unsubscribe.invoke(triggers, triggerId, triggerListenerClass.cast(listener));
                    } catch (Throwable throwable) {
                        getLogger().warning("Could not unsubscribe skill trigger '" + triggerId + "': " + rootMessage(throwable));
                    }
                });
            } catch (ClassNotFoundException ignored) {
                // HexCore trigger API is optional.
            } catch (Throwable throwable) {
                getLogger().warning("Could not access HexCore trigger API while unsubscribing: " + rootMessage(throwable));
            }
        }
        subscriptions.clear();
    }

    private Object findTriggerService() {
        try {
            Class<?> triggerServiceClass = Class.forName("hex.core.api.trigger.TriggerService");
            Method serviceMethod = hex.getClass().getMethod("service", Class.class);
            Object result = serviceMethod.invoke(hex, triggerServiceClass);
            if (result instanceof java.util.Optional<?> optional) {
                return optional.orElse(null);
            }
        } catch (ClassNotFoundException ignored) {
            getLogger().info("HexCore trigger API not found; HexSkills trigger subscriptions will be skipped.");
        } catch (Throwable throwable) {
            getLogger().warning("Could not access HexCore trigger API: " + rootMessage(throwable));
        }
        return null;
    }

    private Object subscribeTrigger(String triggerId) {
        if (triggers == null) {
            return null;
        }
        try {
            Class<?> gameTriggerClass = Class.forName("hex.core.api.trigger.GameTrigger");
            Class<?> triggerListenerClass = Class.forName("hex.core.api.trigger.TriggerListener");
            Class<?> triggerServiceClass = Class.forName("hex.core.api.trigger.TriggerService");
            Object listener = Proxy.newProxyInstance(
                    triggerListenerClass.getClassLoader(),
                    new Class<?>[]{triggerListenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "HexSkillsTriggerListener[" + triggerId + "]";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        if (args != null && args.length == 1 && gameTriggerClass.isInstance(args[0])) {
                            String id = String.valueOf(gameTriggerClass.getMethod("triggerId").invoke(args[0]));
                            Object rawData = gameTriggerClass.getMethod("data").invoke(args[0]);
                            if (rawData instanceof hex.core.api.messaging.HexMessageData data) {
                                handleTrigger(id, data);
                            }
                        }
                        return null;
                    });
            Method subscribe = triggerServiceClass.getMethod("subscribe", String.class, triggerListenerClass);
            subscribe.invoke(triggers, triggerId, triggerListenerClass.cast(listener));
            return listener;
        } catch (Throwable throwable) {
            getLogger().warning("Could not subscribe skill trigger '" + triggerId + "': " + rootMessage(throwable));
            return null;
        }
    }

    private void handleTrigger(String triggerId, hex.core.api.messaging.HexMessageData data) {
        UUID townId = TriggerData.townId(data).orElse(null);
        UUID playerUuid = TriggerData.playerUuid(data).orElse(null);
        if (townId == null || playerUuid == null) {
            return;
        }

        for (SkillDefinition skill : registry.all()) {
            for (XpSource source : skill.xpSources()) {
                if (source.triggerId().equalsIgnoreCase(triggerId) && source.matches(data)) {
                    long xp = source.xpAmount(data);
                    UUID finalPlayerUuid = playerUuid;
                    hex.db().async(() -> repository.addXp(townId, finalPlayerUuid, skill, xp))
                            .thenAccept(change -> Bukkit.getScheduler().runTask(this, () -> {
                                if (change.after().level() > change.before().level()) {
                                    var player = Bukkit.getPlayer(finalPlayerUuid);
                                    if (player != null) {
                                        SoundCompatibility.play(player, player.getLocation(), "ENTITY_PLAYER_LEVELUP", 1.0f, 1.2f);
                                        hex.ui().send(player, "skills.level-up", UiTokens.of("skill", readableName(skill.displayName())).put("from", String.valueOf(change.before().level())).put("to", String.valueOf(change.after().level())));
                                    }
                                }
                            }));
                }
            }
        }
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCropHarvest(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isConfiguredCrop(block.getType()) || !isFullyGrown(block)) {
            return;
        }
        var player = event.getPlayer();
        int level = skillLevel(player.getUniqueId(), "farming");
        if (level <= 0) {
            addSkillXp(player.getUniqueId(), "farming", effectSettings.farmingXpPerHarvest());
            return;
        }
        double tripleChance = clamp(effectSettings.farmingTripleChancePerLevel() * level, 0.0D, effectSettings.farmingTripleMaxChance());
        double doubleChance = clamp(effectSettings.farmingDoubleChancePerLevel() * level, 0.0D, effectSettings.farmingDoubleMaxChance());
        int extraCopies = 0;
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < tripleChance) {
            extraCopies = 2;
        } else if (roll < tripleChance + doubleChance) {
            extraCopies = 1;
        }
        if (extraCopies > 0) {
            dropExtraCropDrops(player, block, extraCopies);
        }
        addSkillXp(player.getUniqueId(), "farming", effectSettings.farmingXpPerHarvest());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!isCaughtFish(event)) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        int level = skillLevel(playerId, "fishing");
        if (level > 0) {
            applyBetterFishingLoot(event, level);
            rollExtraFishingLoot(event, level);
        }
        addSkillXp(playerId, "fishing", effectSettings.fishingXpPerCatch());
    }

    private boolean isConfiguredCrop(Material material) {
        return effectSettings.farmingCropMaterials().contains(material.name());
    }

    private boolean isFullyGrown(Block block) {
        try {
            Object blockData = block.getClass().getMethod("getBlockData").invoke(block);
            Method getAge = blockData.getClass().getMethod("getAge");
            Method getMaximumAge = blockData.getClass().getMethod("getMaximumAge");
            return ((Number) getAge.invoke(blockData)).intValue() >= ((Number) getMaximumAge.invoke(blockData)).intValue();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void dropExtraCropDrops(org.bukkit.entity.Player player, Block block, int extraCopies) {
        try {
            ItemStack tool = currentMainHand(player);
            Object drops = block.getClass().getMethod("getDrops", ItemStack.class).invoke(block, tool);
            if (!(drops instanceof Iterable<?> iterable)) {
                return;
            }
            for (int copy = 0; copy < extraCopies; copy++) {
                for (Object raw : iterable) {
                    if (!(raw instanceof ItemStack stack) || stack.getType().isAir()) {
                        continue;
                    }
                    block.getWorld().getClass().getMethod("dropItemNaturally", Location.class, ItemStack.class)
                            .invoke(block.getWorld(), block.getLocation(), stack.clone());
                }
            }
        } catch (Throwable throwable) {
            getLogger().warning("Nie udało się dodać bonusowego dropu z upraw: " + rootMessage(throwable));
        }
    }

    private ItemStack currentMainHand(org.bukkit.entity.Player player) {
        try {
            Object inventory = player.getInventory();
            Object item = inventory.getClass().getMethod("getItemInMainHand").invoke(inventory);
            return item instanceof ItemStack stack ? stack : new ItemStack(Material.AIR);
        } catch (Throwable ignored) {
            return new ItemStack(Material.AIR);
        }
    }

    private boolean isCaughtFish(PlayerFishEvent event) {
        return event.getState() != null && "CAUGHT_FISH".equalsIgnoreCase(event.getState().name());
    }

    private void applyBetterFishingLoot(PlayerFishEvent event, int level) {
        double chance = clamp(effectSettings.fishingBetterLootChancePerLevel() * level, 0.0D, effectSettings.fishingBetterLootMaxChance());
        if (chance <= 0.0D || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        FishLootEntry entry = effectSettings.pickBetterFishingLoot(level);
        if (entry == null) {
            return;
        }
        setCaughtItem(event, entry.toItemStack());
    }

    private void rollExtraFishingLoot(PlayerFishEvent event, int level) {
        for (FishLootEntry entry : effectSettings.extraFishingLoot()) {
            if (level < entry.minLevel()) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() < entry.chance()) {
                try {
                    event.getPlayer().getWorld().getClass().getMethod("dropItemNaturally", Location.class, ItemStack.class)
                            .invoke(event.getPlayer().getWorld(), event.getPlayer().getLocation(), entry.toItemStack());
                } catch (Throwable throwable) {
                    getLogger().warning("Nie udało się dodać dodatkowego łupu z wędkowania: " + rootMessage(throwable));
                }
            }
        }
    }

    private void setCaughtItem(PlayerFishEvent event, ItemStack item) {
        Entity caught = event.getCaught();
        if (caught == null) {
            return;
        }
        try {
            caught.getClass().getMethod("setItemStack", ItemStack.class).invoke(caught, item);
        } catch (NoSuchMethodException ignored) {
            try {
                Object current = caught.getClass().getMethod("getItemStack").invoke(caught);
                if (current instanceof ItemStack) {
                    caught.getClass().getMethod("setItemStack", ItemStack.class).invoke(caught, item);
                }
            } catch (Throwable ignoredAgain) {
            }
        } catch (Throwable throwable) {
            getLogger().warning("Nie udało się podmienić łupu z wędkowania: " + rootMessage(throwable));
        }
    }

    private int skillLevel(UUID playerId, String skillId) {
        Optional<UUID> townId = towns.townIdOf(playerId);
        if (townId.isEmpty()) {
            return 0;
        }
        return repository.getProgress(townId.get(), playerId, skillId).map(SkillRepository.Progress::level).orElse(0);
    }

    private void addSkillXp(UUID playerId, String skillId, long xp) {
        if (xp <= 0L) {
            return;
        }
        Optional<UUID> townId = towns.townIdOf(playerId);
        Optional<SkillDefinition> skill = registry.byId(skillId);
        if (townId.isEmpty() || skill.isEmpty()) {
            return;
        }
        hex.db().async(() -> repository.addXp(townId.get(), playerId, skill.get(), xp))
                .thenAccept(change -> Bukkit.getScheduler().runTask(this, () -> {
                    if (change.after().level() > change.before().level()) {
                        var player = Bukkit.getPlayer(playerId);
                        if (player != null) {
                            SoundCompatibility.play(player, player.getLocation(), "ENTITY_PLAYER_LEVELUP", 1.0f, 1.2f);
                            hex.ui().send(player, "skills.level-up", UiTokens.of("skill", readableName(skill.get().displayName())).put("from", String.valueOf(change.before().level())).put("to", String.valueOf(change.after().level())));
                        }
                    }
                }));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadSkills();
            if (towns != null) {
                registerPlaceholderExpansion(towns);
            }
            hex.ui().send(sender, "skills.reload.success", UiTokens.of("count", String.valueOf(registry.all().size())));
            return true;
        }
        hex.ui().send(sender, "skills.info", UiTokens.of("count", String.valueOf(registry.all().size())).put("triggers", String.valueOf(subscriptions.size())));
        return true;
    }


    private String readableName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.replaceAll("<[^>]+>", "");
    }

    private void registerUiDefaults() {
        try {
            hex.ui().registerDefaults("skills", Map.of(
                    "level-up", "<green>Awans skillu!</green> <yellow><skill></yellow> <gray>z poziomu</gray> <white><from></white> <gray>na</gray> <white><to></white><gray>.</gray>",
                    "reload.success", "<green>Przeladowano HexSkills.</green> <gray>Skille:</gray> <white><count></white>",
                    "info", "<gold>HexSkills</gold> <gray>| skille:</gray> <white><count></white> <gray>| triggery:</gray> <white><triggers></white>"
            ));
        } catch (Throwable t) {
            getLogger().warning("Could not register UI defaults: " + t.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 ? List.of("info", "reload") : List.of();
    }

    private String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private boolean invokeExpansionBoolean(Object expansion, String methodName) {
        try {
            Method method = expansion.getClass().getMethod(methodName);
            Object result = method.invoke(expansion);
            return result instanceof Boolean value ? value : result == null;
        } catch (Throwable throwable) {
            getLogger().warning("Could not invoke PlaceholderAPI expansion method '" + methodName + "': " + rootMessage(throwable));
            return false;
        }
    }

    private void invokeExpansion(Object expansion, String methodName) {
        try {
            Method method = expansion.getClass().getMethod(methodName);
            method.invoke(expansion);
        } catch (Throwable throwable) {
            getLogger().warning("Could not invoke PlaceholderAPI expansion method '" + methodName + "': " + rootMessage(throwable));
        }
    }


    private record FishLootEntry(int minLevel, double chance, Material material, int amount, int customModelData) {
        ItemStack toItemStack() {
            ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
            if (customModelData > 0 && stack.getItemMeta() != null) {
                var meta = stack.getItemMeta();
                meta.setCustomModelData(customModelData);
                stack.setItemMeta(meta);
            }
            return stack;
        }
    }

    private record SkillEffectSettings(
            long farmingXpPerHarvest,
            double farmingDoubleChancePerLevel,
            double farmingTripleChancePerLevel,
            double farmingDoubleMaxChance,
            double farmingTripleMaxChance,
            java.util.Set<String> farmingCropMaterials,
            long fishingXpPerCatch,
            double fishingBetterLootChancePerLevel,
            double fishingBetterLootMaxChance,
            java.util.List<FishLootEntry> betterFishingLoot,
            java.util.List<FishLootEntry> extraFishingLoot
    ) {
        static SkillEffectSettings defaults() {
            return new SkillEffectSettings(
                    1L,
                    0.003D,
                    0.00075D,
                    0.35D,
                    0.12D,
                    java.util.Set.of("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA"),
                    3L,
                    0.002D,
                    0.25D,
                    java.util.List.of(new FishLootEntry(5, 1.0D, Material.matchMaterial("COD") == null ? Material.PAPER : Material.matchMaterial("COD"), 1, 0)),
                    java.util.List.of()
            );
        }

        static SkillEffectSettings from(YamlConfiguration yaml) {
            SkillEffectSettings def = defaults();
            java.util.Set<String> crops = new java.util.LinkedHashSet<>(yaml.getStringList("effects.farming.crops"));
            if (crops.isEmpty()) crops = def.farmingCropMaterials();
            java.util.List<FishLootEntry> better = readLoot(yaml, "effects.fishing.better-loot");
            if (better.isEmpty()) better = def.betterFishingLoot();
            java.util.List<FishLootEntry> extra = readLoot(yaml, "effects.fishing.extra-loot");
            return new SkillEffectSettings(
                    yaml.getLong("effects.farming.xp-per-harvest", def.farmingXpPerHarvest()),
                    yaml.getDouble("effects.farming.double-drop-chance-per-level", def.farmingDoubleChancePerLevel()),
                    yaml.getDouble("effects.farming.triple-drop-chance-per-level", def.farmingTripleChancePerLevel()),
                    yaml.getDouble("effects.farming.max-double-drop-chance", def.farmingDoubleMaxChance()),
                    yaml.getDouble("effects.farming.max-triple-drop-chance", def.farmingTripleMaxChance()),
                    java.util.Set.copyOf(crops),
                    yaml.getLong("effects.fishing.xp-per-catch", def.fishingXpPerCatch()),
                    yaml.getDouble("effects.fishing.better-loot-chance-per-level", def.fishingBetterLootChancePerLevel()),
                    yaml.getDouble("effects.fishing.max-better-loot-chance", def.fishingBetterLootMaxChance()),
                    java.util.List.copyOf(better),
                    java.util.List.copyOf(extra)
            );
        }

        static java.util.List<FishLootEntry> readLoot(YamlConfiguration yaml, String path) {
            java.util.List<FishLootEntry> result = new java.util.ArrayList<>();
            for (java.util.Map<?, ?> map : yaml.getMapList(path)) {
                Material material = Material.matchMaterial(String.valueOf(map.containsKey("material") ? map.get("material") : "COD"));
                if (material == null) continue;
                int minLevel = intValue(map.get("min-level"), 1);
                double chance = doubleValue(map.get("chance"), 0.0D);
                int amount = intValue(map.get("amount"), 1);
                int customModelData = intValue(map.get("custom-model-data"), 0);
                result.add(new FishLootEntry(minLevel, Math.max(0.0D, Math.min(1.0D, chance)), material, amount, customModelData));
            }
            return result;
        }

        FishLootEntry pickBetterFishingLoot(int level) {
            java.util.List<FishLootEntry> available = betterFishingLoot.stream().filter(entry -> level >= entry.minLevel()).toList();
            if (available.isEmpty()) return null;
            return available.get(ThreadLocalRandom.current().nextInt(available.size()));
        }

        static int intValue(Object value, int def) {
            return value instanceof Number number ? number.intValue() : parseInt(String.valueOf(value), def);
        }

        static double doubleValue(Object value, double def) {
            return value instanceof Number number ? number.doubleValue() : parseDouble(String.valueOf(value), def);
        }

        static int parseInt(String value, int def) {
            try { return Integer.parseInt(value); } catch (Exception ignored) { return def; }
        }

        static double parseDouble(String value, double def) {
            try { return Double.parseDouble(value); } catch (Exception ignored) { return def; }
        }
    }

}
