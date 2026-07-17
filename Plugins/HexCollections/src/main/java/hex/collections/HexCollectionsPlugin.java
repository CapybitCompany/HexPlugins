package hex.collections;

import hex.core.api.compat.MinecraftCompatibility;
import hex.collections.api.HexCollectionsApi;
import hex.collections.api.CollectionProgressContext;
import hex.collections.config.CollectionRegistry;
import hex.collections.config.CollectionsSettings;
import hex.collections.database.CollectionRepository;
import hex.collections.listener.CollectionEventListener;
import hex.collections.listener.MobKillCollectionListener;
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
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
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

	@Override
	public void onEnable() {
		MinecraftCompatibility.logStartupCompatibility(this);
		saveDefaultConfig();
		saveResource("collections.yml", false);
		saveBundledCollectionDefinitions();

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
		towns.dataNamespace(this, "collections", (townId, members) -> hexApi.db().asyncRun(() -> repository.purgeTown(townId)));

		reloadCollections();
		Bukkit.getServicesManager().register(HexCollectionsApi.class, progressService, this, ServicePriority.Normal);
		getServer().getPluginManager().registerEvents(new CollectionEventListener(towns, progressService, antiExploit), this);
		getServer().getPluginManager().registerEvents(new MobKillCollectionListener(towns, progressService), this);
		registerPlaceholderExpansion();
		var command = getCommand("hexcollections");
		if (command != null) {
			command.setExecutor(this);
			command.setTabCompleter(this);
		}
		getLogger().info("HexCollections enabled");
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

	@Override
	public void onDisable() {
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

	private void reloadCollections() {
		unsubscribeAll();
		this.registry = CollectionRegistry.load(new File(getDataFolder(), "collections.yml"));
		if (progressService == null) {
			this.progressService = new CollectionProgressService(this, hexApi, repository, registry, settings);
		} else {
			this.progressService.reload(registry, settings);
		}
		for (String triggerId : registry.triggerIds()) {
			final TriggerListener listener = subscribeTrigger(triggerId);
			if (listener != null) {
				subscriptions.put(triggerId, listener);
			}
		}
		getLogger().info("Loaded collections=" + registry.all().size() + ", triggers=" + subscriptions.size());
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
		if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
			reloadCollections();
			hexApi.ui().send(sender, "collections.reload.success", UiTokens.of("count", String.valueOf(registry.all().size())));
			return true;
		}
		hexApi.ui().send(sender, "collections.info", UiTokens.of("count", String.valueOf(registry.all().size())).put("triggers", String.valueOf(subscriptions.size())));
		return true;
	}


	private void registerUiDefaults() {
		try {
			hexApi.ui().registerDefaults("collections", Map.of(
					"reload.success", "<green>Przeladowano HexCollections.</green> <gray>Kolekcje:</gray> <white><count></white>",
					"info", "<gold>HexCollections</gold> <gray>| kolekcje:</gray> <white><count></white> <gray>| triggery:</gray> <white><triggers></white>"
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
}

