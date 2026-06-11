package hex.skills;

import hex.core.api.HexApi;
import hex.skills.config.SkillRegistry;
import hex.skills.database.SkillRepository;
import hex.skills.model.SkillDefinition;
import hex.skills.placeholder.SkillsPlaceholderExpansion;
import hex.skills.model.TriggerData;
import hex.skills.model.XpSource;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HexSkillsPlugin extends JavaPlugin implements TabExecutor {
    private HexApi hex;
    private TownsApi towns;
    private Object triggers;
    private SkillRepository repository;
    private SkillRegistry registry = SkillRegistry.load(new File("missing-skills.yml"));
    private SkillsPlaceholderExpansion placeholderExpansion;
    private final Map<String, Object> subscriptions = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        saveResourceIfMissing("skills.yml");
        saveResourceIfMissing("deluxemenus/hexskills.yml");

        var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        var townsReg = Bukkit.getServicesManager().getRegistration(TownsApi.class);
        if (hexReg == null || townsReg == null) {
            getLogger().severe("HexCore or HexTowns not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hex = hexReg.getProvider();
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
        getLogger().info("HexSkills enabled");
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
        this.registry = SkillRegistry.load(new File(getDataFolder(), "skills.yml"));
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
                                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                                        player.sendMessage(miniMessage.deserialize("<green>Awans skillu!</green> <yellow>" + skill.displayName() + "</yellow> <gray>z poziomu</gray> <white>" + change.before().level() + "</white> <gray>na</gray> <white>" + change.after().level() + "</white><gray>.</gray>"));
                                    }
                                }
                            }));
                }
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadSkills();
            if (towns != null) {
                registerPlaceholderExpansion(towns);
            }
            sender.sendMessage("HexSkills reloaded. skills=" + registry.all().size());
            return true;
        }
        sender.sendMessage("HexSkills: skills=" + registry.all().size() + ", triggers=" + subscriptions.size());
        return true;
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
}

