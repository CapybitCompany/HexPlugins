package hex.collections;

import hex.collections.api.HexCollectionsApi;
import hex.collections.api.CollectionProgressContext;
import hex.collections.config.CollectionRegistry;
import hex.collections.config.CollectionsSettings;
import hex.collections.database.CollectionRepository;
import hex.collections.listener.CollectionEventListener;
import hex.collections.listener.EggProductionCollectionListener;
import hex.collections.listener.MobKillCollectionListener;
import hex.collections.listener.TownCollectionScalingListener;
import hex.collections.model.CollectionDefinition;
import hex.collections.model.CollectionSource;
import hex.collections.model.TriggerData;
import hex.collections.placeholder.CollectionPlaceholderExpansion;
import hex.collections.service.AntiExploitService;
import hex.collections.service.CollectionProgressService;
import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.core.api.trigger.TriggerListener;
import hex.core.api.trigger.TriggerService;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HexCollectionsPlugin extends JavaPlugin implements TabExecutor {
	private HexApi hexApi;
	private TownsApi towns;
	private TriggerService triggers;
	private CollectionRepository repository;
	private CollectionRegistry registry = CollectionRegistry.load(new File("missing-collections.yml"));
	private final Map<String, TriggerListener> subscriptions = new HashMap<>();
	private CollectionPlaceholderExpansion placeholderExpansion;
	private CollectionProgressService progressService;
	private CollectionsSettings settings;
	private final AntiExploitService antiExploit = new AntiExploitService();
	private BukkitTask flushTask;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		migrateSettingsConfig();
		saveResource("collections.yml", false);
		saveBundledCollectionDefinitions();
		migrateKnownCollectionDefinitions();

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
		this.hexApi = hexReg.getProvider();
		registerUiDefaults();
		this.towns = townsReg.getProvider();
		this.triggers = findTriggerService();
		this.repository = new CollectionRepository(hexApi.db().db());
		this.settings = CollectionsSettings.load(getConfig());

		hexApi.db().asyncRun(repository::ensureTables);

		if (!reloadCollections(true)) {
			getLogger().severe("No valid collection definitions could be loaded; disabling HexCollections.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		// V2 cleanup receives the durable destroy snapshot and routes through the service so
		// caches/tombstones are cleared before the final verified DELETE.
		towns.dataNamespaceV2(this, "collections", progressService::purgeTownData);
		Bukkit.getServicesManager().register(HexCollectionsApi.class, progressService, this, ServicePriority.Normal);
		getServer().getPluginManager().registerEvents(new CollectionEventListener(towns, progressService, antiExploit), this);
		getServer().getPluginManager().registerEvents(new MobKillCollectionListener(towns, progressService), this);
		getServer().getPluginManager().registerEvents(new EggProductionCollectionListener(towns, progressService), this);
		getServer().getPluginManager().registerEvents(new TownCollectionScalingListener(progressService), this);
		registerPlaceholderExpansion();
		var command = getCommand("hexcollections");
		if (command != null) {
			command.setExecutor(this);
			command.setTabCompleter(this);
		}
		getLogger().info("HexCollections enabled");
	}

	private void migrateSettingsConfig() {
		boolean changed = false;
		if (!getConfig().isSet("anti_exploit.private_town_rules.own_town_block_break_collection_enabled")) {
			getConfig().set("anti_exploit.private_town_rules.own_town_block_break_collection_enabled", true);
			changed = true;
		}
		if (!getConfig().isSet("anti_exploit.private_town_rules.foreign_town_block_break_collection_enabled")) {
			getConfig().set("anti_exploit.private_town_rules.foreign_town_block_break_collection_enabled", false);
			changed = true;
		}
		if (changed) {
			saveConfig();
			getLogger().info("Migrated relation-aware collection policy: own town enabled, foreign town disabled.");
		}
	}

	private boolean isHexTownsEnabled() {
		if (Bukkit.getPluginManager().isPluginEnabled("HexTowns")) {
			return true;
		}

		getLogger().severe("HexTowns is not enabled; disabling HexCollections.");
		getServer().getPluginManager().disablePlugin(this);
		return false;
	}

	private void saveBundledCollectionDefinitions() {
		String[] files = {
				"collections/mining_cobblestone.yml",
				"collections/mining_stone.yml",
				"collections/mining_dirt.yml",
				"collections/mining_iron.yml",
				"collections/mining_gold.yml",
				"collections/mining_coal.yml",
				"collections/mining_redstone.yml",
				"collections/mining_copper.yml",
				"collections/mining_diamond.yml",
				"collections/mining_emerald.yml",
				"collections/mining_obsidian.yml",
				"collections/mining_netherrack.yml",
				"collections/mining_netherite.yml",
				"collections/mining_uranium.yml",
				"collections/mining_rare_elements.yml",
				"collections/industrial_energy.yml",
				"collections/industrial_enriched_uranium.yml",
				"collections/foraging_oak_wood.yml",
				"collections/foraging_spruce_wood.yml",
				"collections/foraging_spruce_resin.yml",
				"collections/farming_wheat.yml",
				"collections/farming_sugar_cane.yml",
				"collections/farming_beetroot.yml",
				"collections/farming_cactus.yml",
				"collections/mob_zombie.yml",
				"collections/mob_skeleton.yml",
				"collections/mob_spider.yml",
				"collections/mob_silverfish.yml",
				"collections/animals_wool.yml",
				"collections/animals_chicken_meat.yml",
				"collections/animals_eggs.yml",
				"collections/animals_pork.yml",
				"collections/animals_beef.yml",
				"collections/animals_leather.yml",
				"collections/animals_mutton.yml"
		};
		for (String file : files) saveResourceIfMissing(file);
	}

	private void saveResourceIfMissing(String path) {
		if (!new File(getDataFolder(), path).exists()) {
			saveResource(path, false);
		}
	}

	/**
	 * Targeted compatibility migrations for bundled collection definitions.
	 * They patch only known legacy values so unrelated admin customisation remains untouched.
	 */
	private void migrateKnownCollectionDefinitions() {
		migrateNetheriteDefinition();
		migrateTinDefinition();
		migrateEmeraldDefinition();
		migrateSpruceResinDefinition();
	}

	private void migrateNetheriteDefinition() {
		File file = new File(getDataFolder(), "collections/mining_netherite.yml");
		if (!file.isFile()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		List<String> sources = new ArrayList<>(yaml.getStringList("valid_sources"));
		boolean removed = sources.removeIf(value -> "NATURAL_BLOCK_BREAK".equalsIgnoreCase(value));
		boolean hadRule = yaml.getConfigurationSection("source_rules.NATURAL_BLOCK_BREAK") != null;
		boolean customRuleMissing = yaml.getConfigurationSection("source_rules.CUSTOM_PLUGIN_GRANTED") == null;
		if (!removed && !hadRule && !customRuleMissing) return;
		yaml.set("valid_sources", sources);
		yaml.set("source_rules.NATURAL_BLOCK_BREAK", null);
		yaml.set("source_rules.CUSTOM_PLUGIN_GRANTED.allow_in_town_claims", true);
		saveMigratedDefinition(file, yaml, "mining.netherite: manual progress moved to actual NETHERITE_SCRAP furnace extraction");
	}

	private void migrateTinDefinition() {
		File file = new File(getDataFolder(), "collections/mining_tin.yml");
		if (!file.isFile()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		List<String> sources = new ArrayList<>(yaml.getStringList("valid_sources"));
		boolean removed = sources.removeIf(value -> "NATURAL_BLOCK_BREAK".equalsIgnoreCase(value));
		boolean hadRule = yaml.getConfigurationSection("source_rules.NATURAL_BLOCK_BREAK") != null;
		if (!removed && !hadRule) return;
		yaml.set("valid_sources", sources);
		yaml.set("source_rules.NATURAL_BLOCK_BREAK", null);
		yaml.set("source_rules.CUSTOM_PLUGIN_GRANTED.allow_in_town_claims", true);
		saveMigratedDefinition(file, yaml, "mining.tin: progress now comes only from an actual custom tin drop/minion");
	}

	private void migrateSpruceResinDefinition() {
		File file = new File(getDataFolder(), "collections/foraging_spruce_resin.yml");
		if (!file.isFile()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

		int[] previous = {4, 63, 250, 1250, 2500, 12500, 25000};
		int[] scaled = {1, 13, 50, 250, 500, 2500, 5000};
		for (int level = 1; level <= previous.length; level++) {
			if (yaml.getInt("levels." + level + ".required", -1) != previous[level - 1]) {
				return;
			}
		}

		for (int level = 1; level <= scaled.length; level++) {
			yaml.set("levels." + level + ".required", scaled[level - 1]);
		}
		saveMigratedDefinition(file, yaml, "foraging.spruce_resin: collection requirements reduced 5x");
	}

	private void migrateEmeraldDefinition() {
		File file = new File(getDataFolder(), "collections/mining_emerald.yml");
		if (!file.isFile()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		List<String> materials = new ArrayList<>(yaml.getStringList("source_rules.NATURAL_BLOCK_BREAK.allowed_materials"));
		if (containsIgnoreCase(materials, "DEEPSLATE_EMERALD_ORE")) return;
		if (!containsIgnoreCase(materials, "EMERALD_ORE")) return;
		materials.add("DEEPSLATE_EMERALD_ORE");
		yaml.set("source_rules.NATURAL_BLOCK_BREAK.allowed_materials", materials);
		saveMigratedDefinition(file, yaml, "mining.emerald: added DEEPSLATE_EMERALD_ORE");
	}

	private boolean containsIgnoreCase(List<String> values, String expected) {
		if (values == null || expected == null) return false;
		for (String value : values) if (expected.equalsIgnoreCase(value)) return true;
		return false;
	}

	private void saveMigratedDefinition(File file, YamlConfiguration yaml, String description) {
		try {
			yaml.save(file);
			getLogger().warning("Migrated collection definition: " + description);
		} catch (IOException error) {
			getLogger().severe("Could not migrate " + file.getName() + ": " + rootMessage(error));
		}
	}

	@Override
	public void onDisable() {
		cancelFlushTask();
		if (progressService != null) {
			progressService.shutdownAndFlush();
		}
		unsubscribeAll();
		if (progressService != null) {
			Bukkit.getServicesManager().unregister(HexCollectionsApi.class, progressService);
		}
		if (placeholderExpansion != null) {
			placeholderExpansion.unregister();
			placeholderExpansion = null;
		}
		getLogger().info("HexCollections disabled");
	}

	private void registerPlaceholderExpansion() {
		if (placeholderExpansion != null) {
			placeholderExpansion.unregister();
			placeholderExpansion = null;
		}
		if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
			getLogger().info("PlaceholderAPI not found; skipping HexCollections placeholders.");
			return;
		}
		try {
			if (progressService == null) {
				return;
			}
			this.placeholderExpansion = new CollectionPlaceholderExpansion(this, progressService, registry, towns);
			if (placeholderExpansion.register()) {
				getLogger().info("Registered PlaceholderAPI expansion %hexcollections_%.");
			} else {
				getLogger().warning("Could not register PlaceholderAPI expansion %hexcollections_%.");
				this.placeholderExpansion = null;
			}
		} catch (Throwable throwable) {
			getLogger().warning("Could not register HexCollections placeholders: " + throwable.getMessage());
			this.placeholderExpansion = null;
		}
	}

	private boolean reloadCollections(boolean allowPartialOnStartup) {
		CollectionRegistry candidate = CollectionRegistry.load(new File(getDataFolder(), "collections.yml"));
		if (!candidate.valid()) {
			for (String validationError : candidate.validationErrors()) {
				getLogger().severe("Collection definition disabled: " + validationError);
			}
			if (!allowPartialOnStartup) return false;
			if (candidate.all().isEmpty()) return false;
			getLogger().warning("Starting HexCollections with valid definitions only; invalid definitions above are disabled.");
		}

		// On manual reload replace runtime state only after the entire candidate validates.
		// Startup may intentionally continue with valid definitions only, so one malformed YAML
		// can never turn into a wildcard or take down every unrelated collection.
		unsubscribeAll();
		this.registry = candidate;
		this.settings = CollectionsSettings.load(getConfig());
		if (progressService == null) {
			this.progressService = new CollectionProgressService(this, hexApi, towns, repository, registry, settings);
		} else {
			this.progressService.reload(registry, settings);
		}
		for (String triggerId : registry.triggerIds()) {
			final TriggerListener listener = subscribeTrigger(triggerId);
			if (listener != null) subscriptions.put(triggerId, listener);
		}
		scheduleFlushTask();
		getLogger().info("Loaded collections=" + registry.all().size() + ", triggers=" + subscriptions.size()
				+ ", flushIntervalSeconds=" + settings.flushIntervalSeconds());
		return true;
	}

	private void scheduleFlushTask() {
		cancelFlushTask();
		if (progressService == null || settings == null) return;
		long periodTicks = Math.max(100L, settings.flushIntervalSeconds() * 20L);
		flushTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
			try {
				progressService.flushDirty();
			} catch (Throwable error) {
				getLogger().severe("Could not schedule collection dirty flush: " + rootMessage(error));
			}
		}, periodTicks, periodTicks);
	}

	private void cancelFlushTask() {
		if (flushTask != null) {
			flushTask.cancel();
			flushTask = null;
		}
	}

	private void unsubscribeAll() {
		if (triggers != null && !subscriptions.isEmpty()) {
			subscriptions.forEach((triggerId, listener) -> {
				try {
					triggers.unsubscribe(triggerId, listener);
				} catch (Throwable throwable) {
					getLogger().warning("Could not unsubscribe collection trigger '" + triggerId + "': " + rootMessage(throwable));
				}
			});
		}
		subscriptions.clear();
	}

	private TriggerService findTriggerService() {
		try {
			return hexApi.service(TriggerService.class).orElse(null);
		} catch (Throwable throwable) {
			getLogger().warning("Could not access HexCore trigger API: " + rootMessage(throwable));
		}
		return null;
	}

	private TriggerListener subscribeTrigger(String triggerId) {
		if (triggers == null) {
			return null;
		}
		try {
			TriggerListener listener = trigger -> handleTrigger(trigger.triggerId(), trigger.data());
			triggers.subscribe(triggerId, listener);
			return listener;
		} catch (Throwable throwable) {
			getLogger().warning("Could not subscribe collection trigger '" + triggerId + "': " + rootMessage(throwable));
			return null;
		}
	}

	private void handleTrigger(String triggerId, hex.core.api.messaging.HexMessageData data) {
		UUID townId = TriggerData.townId(data).orElse(null);
		if (townId == null) {
			return;
		}
		for (CollectionDefinition collection : registry.all()) {
			for (CollectionSource source : collection.sources()) {
				if (source.triggerId().equalsIgnoreCase(triggerId) && source.matches(data)) {
					long amount = source.amount(data);
					progressService.addProgress(new CollectionProgressContext()
							.townId(townId)
							.collectionId(collection.id())
							.amount(amount)
							.source(hex.collections.api.CollectionSource.CUSTOM_PLUGIN_GRANTED)
							.reason("trigger:" + triggerId));
				}
			}
		}
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
		if (!sender.hasPermission("hexcollections.admin")) {
			hexApi.ui().send(sender, "collections.error.no-permission");
			return true;
		}
		if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			migrateKnownCollectionDefinitions();
			if (reloadCollections(false)) {
				hexApi.ui().send(sender, "collections.reload.success", UiTokens.of("count", String.valueOf(registry.all().size())));
			} else {
				hexApi.ui().send(sender, "collections.reload.failed");
			}
			return true;
		}
		hexApi.ui().send(sender, "collections.info", UiTokens.of("count", String.valueOf(registry.all().size())).put("triggers", String.valueOf(subscriptions.size())));
		return true;
	}


	private void registerUiDefaults() {
		try {
			hexApi.ui().registerDefaults("collections", Map.of(
					"error.no-permission", "<red>Brak uprawnień.</red>",
					"reload.success", "<green>Przeladowano HexCollections.</green> <gray>Kolekcje:</gray> <white><count></white>",
					"reload.failed", "<red>Nie przeladowano HexCollections: konfiguracja kolekcji zawiera bledy. Poprzednia poprawna konfiguracja pozostala aktywna.</red>",
					"info", "<gold>HexCollections</gold> <gray>| kolekcje:</gray> <white><count></white> <gray>| triggery:</gray> <white><triggers></white>"
			));
		} catch (Throwable t) {
			getLogger().warning("Could not register UI defaults: " + t.getMessage());
		}
	}

	@Override
	public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
		if (!sender.hasPermission("hexcollections.admin")) return List.of();
		return args.length == 1 ? List.of("info", "reload") : List.of();
	}

	private String rootMessage(Throwable throwable) {
		Throwable t = throwable;
		while (t.getCause() != null) t = t.getCause();
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}
}

