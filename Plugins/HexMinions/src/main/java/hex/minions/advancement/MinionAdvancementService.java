package hex.minions.advancement;

import hex.collections.api.CollectionProgress;
import hex.collections.api.HexCollectionsApi;
import hex.collections.event.CollectionLevelUpEvent;
import hex.collections.event.CollectionProgressAddEvent;
import hex.minions.api.MinionView;
import hex.minions.api.TownAdvancementProgressView;
import hex.minions.api.MinionsListener;
import hex.minions.service.MinionService;
import hex.towns.api.TownsApi;
import hex.towns.api.event.TownCoopJoinedEvent;
import hex.towns.api.event.TownCoopLeftEvent;
import hex.towns.api.event.TownCreatedEvent;
import hex.towns.api.event.TownDestroyedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MinionAdvancementService implements Listener, MinionsListener {
    private static final String CRITERION = "done";

    private final Plugin plugin;
    private final TownsApi towns;
    private final HexCollectionsApi collections;
    private final MinionService minions;
    private final Set<NamespacedKey> registeredKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> townGrowthRewardsGranted = ConcurrentHashMap.newKeySet();
    private final Set<String> townMinionLimitRewardsGranted = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled;
    private volatile boolean checkOnJoin;
    private volatile String namespace;
    private volatile String background;
    private volatile Map<String, MinionAdvancementDefinition> definitions = Map.of();

    public MinionAdvancementService(Plugin plugin, TownsApi towns, HexCollectionsApi collections, MinionService minions) {
        this.plugin = plugin;
        this.towns = towns;
        this.collections = collections;
        this.minions = minions;
    }

    public void reload() {
        YamlConfiguration yaml = loadYaml();
        this.enabled = yaml.getBoolean("advancements.enabled", true);
        this.checkOnJoin = yaml.getBoolean("advancements.check-on-join", true);
        this.namespace = yaml.getString("advancements.namespace", "hexminions");
        this.background = yaml.getString("advancements.tab-background", "minecraft:textures/gui/advancements/backgrounds/stone.png");
        this.definitions = loadDefinitions(yaml);
        registerAdvancements();
        if (enabled) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::evaluate), 20L);
        }
    }

    public void shutdown() {
        // Nie usuwamy advancementów na disable, żeby zwykły restart/reload nie czyścił widoku gracza
        // w trakcie pracy serwera. Przy następnym enable/reload i tak nadpisujemy swoje klucze.
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (towns.townIdOf(player.getUniqueId()).isEmpty()) {
                revokeAll(player);
                return;
            }
            if (checkOnJoin) {
                evaluate(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onTownCoopLeft(TownCoopLeftEvent event) {
        if (!enabled || event == null || event.playerId() == null) return;
        revokeSoon(event.playerId());
    }

    @EventHandler
    public void onTownDestroyed(TownDestroyedEvent event) {
        if (!enabled || event == null || event.affectedPlayers() == null) return;
        for (UUID playerId : event.affectedPlayers()) {
            revokeSoon(playerId);
        }
    }

    @EventHandler
    public void onTownCreated(TownCreatedEvent event) {
        Player player = Bukkit.getPlayer(event.ownerId());
        if (player != null) evaluate(player);
    }

    @EventHandler
    public void onTownCoopJoined(TownCoopJoinedEvent event) {
        Player player = Bukkit.getPlayer(event.playerId());
        if (player != null) evaluate(player);
    }

    @EventHandler
    public void onCollectionProgressAdd(CollectionProgressAddEvent event) {
        if (!enabled || event == null || event.townId() == null) return;
        evaluateTownMembersSoon(event.townId());
    }

    @EventHandler
    public void onCollectionLevelUp(CollectionLevelUpEvent event) {
        if (!enabled || event == null || event.townId() == null) return;
        evaluateTownMembersSoon(event.townId());
    }

    private void evaluateTownMembersSoon(UUID townId) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (towns.isMember(player.getUniqueId(), townId)) {
                    evaluate(player);
                }
            }
        }, 1L);
    }

    @Override
    public void onMinionChanged(MinionView minion) {
        if (!enabled || minion == null || minion.townUuid() == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (towns.isMember(player.getUniqueId(), minion.townUuid())) {
                    evaluate(player);
                }
            }
        }, 1L);
    }

    public void evaluate(Player player) {
        if (!enabled || player == null || !player.isOnline()) return;
        PlayerSnapshot snapshot = snapshot(player);
        for (MinionAdvancementDefinition definition : definitions.values()) {
            if (isSatisfied(definition.requirement(), snapshot)) {
                grant(player, definition, snapshot);
            }
        }
    }

    public void revokeAll(Player player) {
        if (player == null || !player.isOnline()) return;
        for (MinionAdvancementDefinition definition : definitions.values()) {
            Advancement advancement = Bukkit.getAdvancement(definition.key(plugin, namespace));
            if (advancement == null) continue;
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : List.copyOf(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criterion);
            }
        }
    }

    private void revokeSoon(UUID playerId) {
        if (playerId == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                revokeAll(player);
            }
        }, 1L);
    }

    private PlayerSnapshot snapshot(Player player) {
        UUID townId = player == null ? null : towns.townIdOf(player.getUniqueId()).orElse(null);
        return snapshotTown(townId);
    }

    private PlayerSnapshot snapshotTown(UUID townId) {
        boolean townMember = townId != null;
        Map<String, Integer> maxTierByType = new LinkedHashMap<>();
        Map<String, CollectionProgress> collectionProgress = Map.of();
        int highestTier = 0;
        int minionCount = 0;
        int distinctMinionTypes = 0;
        if (townMember) {
            List<MinionView> townMinions = minions.viewsOfTown(townId);
            minionCount = townMinions.size();
            for (MinionView view : townMinions) {
                if (view == null || view.typeId() == null) continue;
                String typeId = view.typeId().toLowerCase(Locale.ROOT);
                int tier = Math.max(1, view.tier());
                maxTierByType.merge(typeId, tier, Math::max);
            }
            highestTier = maxTierByType.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            distinctMinionTypes = Math.max(maxTierByType.size(), minions.countKnownMinionTypes(townId));
            try {
                collections.loadTown(townId);
                collectionProgress = collections.getAllCollections(townId);
            } catch (Throwable ignored) {
                collectionProgress = Map.of();
            }
        }
        return new PlayerSnapshot(townMember, townId, maxTierByType, distinctMinionTypes, minionCount, highestTier, collectionProgress == null ? Map.of() : collectionProgress);
    }

    public List<TownAdvancementProgressView> growthPointAdvancements(UUID townId, Player viewer) {
        if (!enabled || townId == null) return List.of();
        PlayerSnapshot snapshot = snapshotTown(townId);

        // Do not issue one town_meta SELECT per advancement from a GUI click. HexTowns stores
        // these rewards under namespace=minion_advancements, key=growth.<advancementId>, so one
        // prefix lookup is enough for the entire guide page (currently dozens of milestones).
        Map<String, String> growthRewardMeta;
        try {
            growthRewardMeta = towns.getMetaPrefix(townId, "minion_advancements.growth.");
        } catch (Throwable ignored) {
            growthRewardMeta = Map.of();
        }

        List<TownAdvancementProgressView> result = new ArrayList<>();
        for (MinionAdvancementDefinition definition : definitions.values()) {
            if (definition.growthPoints() <= 0) continue;
            ProgressNumbers numbers = progressNumbers(definition.requirement(), snapshot);
            boolean completed = Boolean.parseBoolean(growthRewardMeta.getOrDefault("growth." + definition.id(), "false"));
            result.add(new TownAdvancementProgressView(
                    definition.id(), definition.title(), definition.description(), definition.icon(),
                    definition.growthPoints(), completed, numbers.current(), numbers.required(),
                    progressCategory(definition.requirement())
            ));
        }
        return List.copyOf(result);
    }

    private ProgressNumbers progressNumbers(MinionAdvancementRequirement requirement, PlayerSnapshot snapshot) {
        if (requirement == null || snapshot == null || !snapshot.townMember()) return new ProgressNumbers(0, 1);
        if (requirement.isTownMember()) return new ProgressNumbers(1, 1);
        if (requirement.isMinionType()) {
            long current = snapshot.maxTierByType().getOrDefault(requirement.minionType().toLowerCase(Locale.ROOT), 0);
            return new ProgressNumbers(current, Math.max(1, requirement.minTier()));
        }
        if (requirement.isMinionTier()) return new ProgressNumbers(snapshot.highestTier(), Math.max(1, requirement.minTier()));
        if (requirement.isCollectionAmount()) {
            CollectionProgress progress = snapshot.collectionProgress().get(requirement.collectionId().toLowerCase(Locale.ROOT));
            long current = progress == null ? collections.getAmount(snapshot.townId(), requirement.collectionId()) : progress.amount();
            return new ProgressNumbers(current, Math.max(1L, requirement.minAmount()));
        }
        if (requirement.isCollectionLevel()) {
            CollectionProgress progress = snapshot.collectionProgress().get(requirement.collectionId().toLowerCase(Locale.ROOT));
            long current = progress == null ? collections.getLevel(snapshot.townId(), requirement.collectionId()) : progress.level();
            return new ProgressNumbers(current, Math.max(1, requirement.minLevel()));
        }
        if (requirement.isCollectionMaxLevel()) {
            int required = Math.max(1, collections.getMaxLevel(requirement.collectionId()));
            CollectionProgress progress = snapshot.collectionProgress().get(requirement.collectionId().toLowerCase(Locale.ROOT));
            long current = progress == null ? collections.getLevel(snapshot.townId(), requirement.collectionId()) : progress.level();
            return new ProgressNumbers(current, required);
        }
        if (requirement.isAnyCollectionLevel()) {
            long current = snapshot.collectionProgress().values().stream().filter(java.util.Objects::nonNull).mapToInt(CollectionProgress::level).max().orElse(0);
            return new ProgressNumbers(current, Math.max(1, requirement.minLevel()));
        }
        if (requirement.isCollectionTierCount()) {
            long current = snapshot.collectionProgress().values().stream()
                    .filter(progress -> progress != null && progress.level() >= Math.max(1, requirement.minLevel())).count();
            return new ProgressNumbers(current, Math.max(1, requirement.minCount()));
        }
        if (requirement.isMinionTypeCount()) return new ProgressNumbers(snapshot.distinctMinionTypes(), Math.max(1, requirement.minCount()));
        if (requirement.isMinionCount()) return new ProgressNumbers(snapshot.minionCount(), Math.max(1, requirement.minCount()));
        return new ProgressNumbers(isSatisfied(requirement, snapshot) ? 1 : 0, 1);
    }

    private String progressCategory(MinionAdvancementRequirement requirement) {
        if (requirement == null) return "OTHER";
        if (requirement.isMinionTypeCount()) return "MINION_DIVERSITY";
        if (requirement.isMinionType() || requirement.isMinionTier() || requirement.isMinionCount()) return "MINION_DEVELOPMENT";
        if (requirement.isCollectionTierCount() || requirement.isAnyCollectionLevel()) return "COLLECTION_BREADTH";
        if (requirement.isCollectionAmount() || requirement.isCollectionLevel() || requirement.isCollectionMaxLevel()) return "COLLECTION_COMPLETION";
        return "OTHER";
    }

    private record ProgressNumbers(long current, long required) {
        private ProgressNumbers {
            current = Math.max(0L, current);
            required = Math.max(1L, required);
        }
    }

    private boolean isSatisfied(MinionAdvancementRequirement requirement, PlayerSnapshot snapshot) {
        if (requirement == null || snapshot == null) return false;
        if (requirement.isTownMember()) {
            return snapshot.townMember();
        }
        if (requirement.isMinionType()) {
            if (!snapshot.townMember()) return false;
            int current = snapshot.maxTierByType().getOrDefault(requirement.minionType().toLowerCase(Locale.ROOT), 0);
            return current >= Math.max(1, requirement.minTier());
        }
        if (requirement.isMinionTier()) {
            return snapshot.townMember() && snapshot.highestTier() >= Math.max(1, requirement.minTier());
        }
        if (requirement.isCollectionAmount()) {
            if (!snapshot.townMember() || requirement.collectionId().isBlank()) return false;
            CollectionProgress progress = snapshot.collectionProgress().get(requirement.collectionId().toLowerCase(Locale.ROOT));
            long current = progress == null ? collections.getAmount(snapshot.townId(), requirement.collectionId()) : progress.amount();
            return current >= Math.max(0L, requirement.minAmount());
        }
        if (requirement.isCollectionLevel()) {
            if (!snapshot.townMember() || requirement.collectionId().isBlank()) return false;
            CollectionProgress progress = snapshot.collectionProgress().get(requirement.collectionId().toLowerCase(Locale.ROOT));
            int current = progress == null ? collections.getLevel(snapshot.townId(), requirement.collectionId()) : progress.level();
            return current >= Math.max(1, requirement.minLevel());
        }
        if (requirement.isCollectionMaxLevel()) {
            if (!snapshot.townMember() || requirement.collectionId().isBlank()) return false;
            int maxLevel = Math.max(1, collections.getMaxLevel(requirement.collectionId()));
            CollectionProgress progress = snapshot.collectionProgress().get(requirement.collectionId().toLowerCase(Locale.ROOT));
            int current = progress == null ? collections.getLevel(snapshot.townId(), requirement.collectionId()) : progress.level();
            return maxLevel > 0 && current >= maxLevel;
        }
        if (requirement.isAnyCollectionLevel()) {
            return snapshot.townMember() && snapshot.collectionProgress().values().stream()
                    .anyMatch(progress -> progress != null && progress.level() >= Math.max(1, requirement.minLevel()));
        }
        if (requirement.isCollectionTierCount()) {
            if (!snapshot.townMember()) return false;
            long reached = snapshot.collectionProgress().values().stream()
                    .filter(progress -> progress != null && progress.level() >= Math.max(1, requirement.minLevel()))
                    .count();
            return reached >= Math.max(1, requirement.minCount());
        }
        if (requirement.isMinionTypeCount()) {
            return snapshot.townMember() && snapshot.distinctMinionTypes() >= Math.max(1, requirement.minCount());
        }
        if (requirement.isMinionCount()) {
            return snapshot.townMember() && snapshot.minionCount() >= Math.max(1, requirement.minCount());
        }
        return false;
    }

    private void grant(Player player, MinionAdvancementDefinition definition, PlayerSnapshot snapshot) {
        Advancement advancement = Bukkit.getAdvancement(definition.key(plugin, namespace));
        if (advancement == null) return;
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (progress.isDone()) {
            awardRewardsOnce(definition, snapshot);
            return;
        }
        Collection<String> remaining = List.copyOf(progress.getRemainingCriteria());
        for (String criterion : remaining) {
            progress.awardCriteria(criterion);
        }
        awardRewardsOnce(definition, snapshot);
    }

    private void awardRewardsOnce(MinionAdvancementDefinition definition, PlayerSnapshot snapshot) {
        awardGrowthRewardOnce(definition, snapshot);
        awardMinionLimitRewardOnce(definition, snapshot);
    }

    private void awardGrowthRewardOnce(MinionAdvancementDefinition definition, PlayerSnapshot snapshot) {
        if (definition.growthPoints() <= 0 || snapshot == null || snapshot.townId() == null) return;
        String rewardKey = snapshot.townId() + ":" + definition.id();
        if (!townGrowthRewardsGranted.add(rewardKey)) return;
        String metaKey = "minion_advancements.growth." + definition.id();
        if (Boolean.parseBoolean(towns.getMeta(snapshot.townId(), metaKey, "false"))) {
            return;
        }
        towns.setMeta(snapshot.townId(), metaKey, "true");
        towns.addGrowthPoints(snapshot.townId(), definition.growthPoints(), "minion_advancement." + definition.id());
    }

    private void awardMinionLimitRewardOnce(MinionAdvancementDefinition definition, PlayerSnapshot snapshot) {
        if (definition.minionLimitBonus() <= 0 || snapshot == null || snapshot.townId() == null) return;
        String rewardKey = snapshot.townId() + ":" + definition.id();
        if (!townMinionLimitRewardsGranted.add(rewardKey)) return;
        String metaKey = "minion_advancements.minion_limit." + definition.id();
        if (Boolean.parseBoolean(towns.getMeta(snapshot.townId(), metaKey, "false"))) {
            return;
        }
        towns.setMeta(snapshot.townId(), metaKey, "true");
        minions.addMinionLimitBonus(snapshot.townId(), definition.minionLimitBonus(), "minion_advancement." + definition.id());
    }

    private void registerAdvancements() {
        for (NamespacedKey key : Set.copyOf(registeredKeys)) {
            try {
                Bukkit.getUnsafe().removeAdvancement(key);
            } catch (Throwable ignored) {
            }
        }
        registeredKeys.clear();
        if (!enabled) return;
        // Rooty muszą wejść przed dziećmi. Używamy kolejności z YAML-a, a jeśli ktoś ustawił parent
        // wcześniej niż definicję rodzica, kilka przebiegów domknie zależności.
        Set<String> loadedPaths = new LinkedHashSet<>();
        List<MinionAdvancementDefinition> pending = new ArrayList<>(definitions.values());
        int guard = pending.size() + 5;
        while (!pending.isEmpty() && guard-- > 0) {
            boolean progress = false;
            for (MinionAdvancementDefinition definition : List.copyOf(pending)) {
                if (!definition.parent().isBlank() && !loadedPaths.contains(definition.parent())) continue;
                if (loadAdvancement(definition)) {
                    loadedPaths.add(definition.path());
                    pending.remove(definition);
                    progress = true;
                }
            }
            if (!progress) break;
        }
        for (MinionAdvancementDefinition definition : pending) {
            loadAdvancement(definition);
        }
    }

    private boolean loadAdvancement(MinionAdvancementDefinition definition) {
        NamespacedKey key = definition.key(plugin, namespace);
        if (advancementExists(key) && !removeLoadedAdvancement(key)) {
            // Przy /reload lub przeładowaniu pluginu Paper potrafi zostawić runtime advancement
            // w rejestrze, mimo że nowa instancja pluginu nie ma go już w registeredKeys.
            // Nie próbujemy wtedy ładować go ponownie, bo kończy się to masowym spamem
            // "Advancement ... already exists". Istniejący advancement dalej działa dla graczy.
            registeredKeys.add(key);
            return true;
        }
        try {
            Bukkit.getUnsafe().loadAdvancement(key, toJson(definition, true));
            registeredKeys.add(key);
            return true;
        } catch (Throwable modernError) {
            if (isAlreadyExists(modernError) && advancementExists(key)) {
                registeredKeys.add(key);
                return true;
            }
            // Paper/Minecraft 1.20.5+ używa w ikonie advancementu pola "id". Starsze warianty
            // oczekują "item". Zostawiamy fallback, żeby ta sama konfiguracja działała po drobnych
            // zmianach API/formatu datapacków.
            removeLoadedAdvancement(key);
            try {
                Bukkit.getUnsafe().loadAdvancement(key, toJson(definition, false));
                registeredKeys.add(key);
                return true;
            } catch (Throwable legacyError) {
                if (isAlreadyExists(legacyError) && advancementExists(key)) {
                    registeredKeys.add(key);
                    return true;
                }
                plugin.getLogger().warning("Nie udało się załadować advancementu " + key + ": " + legacyError.getMessage());
                return false;
            }
        }
    }

    private boolean removeLoadedAdvancement(NamespacedKey key) {
        try {
            Bukkit.getUnsafe().removeAdvancement(key);
        } catch (Throwable ignored) {
        }
        return !advancementExists(key);
    }

    private boolean advancementExists(NamespacedKey key) {
        try {
            return Bukkit.getAdvancement(key) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isAlreadyExists(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("already exists")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String toJson(MinionAdvancementDefinition definition, boolean modernIconFormat) {
        StringBuilder json = new StringBuilder("{");
        String parentKey = definition.parentKey(namespace);
        if (!parentKey.isBlank()) {
            json.append("\"parent\":\"").append(escape(parentKey)).append("\",");
        }
        json.append("\"display\":{");
        json.append("\"icon\":{");
        json.append("\"").append(modernIconFormat ? "id" : "item").append("\":\"").append(escape(materialKey(definition.icon()))).append("\"}");
        json.append(",\"title\":{");
        json.append("\"text\":\"").append(escape(definition.title())).append("\"}");
        json.append(",\"description\":{");
        json.append("\"text\":\"").append(escape(definition.description())).append("\"}");
        if (definition.parent().isBlank() && background != null && !background.isBlank()) {
            json.append(",\"background\":\"").append(escape(background)).append("\"");
        }
        json.append(",\"frame\":\"").append(escape(definition.frame())).append("\"");
        json.append(",\"show_toast\":").append(definition.showToast());
        json.append(",\"announce_to_chat\":").append(definition.announceToChat());
        json.append(",\"hidden\":").append(definition.hidden());
        json.append("}");
        json.append(",\"criteria\":{");
        json.append("\"").append(CRITERION).append("\":{");
        json.append("\"trigger\":\"minecraft:impossible\"}");
        json.append("}");
        json.append(",\"requirements\":[[\"").append(CRITERION).append("\"]]");
        json.append("}");
        return json.toString();
    }

    private String materialKey(Material material) {
        Material safe = material == null ? Material.STONE : material;
        try {
            return safe.getKey().toString();
        } catch (Throwable ignored) {
            return "minecraft:" + safe.name().toLowerCase(Locale.ROOT);
        }
    }

    private Map<String, MinionAdvancementDefinition> loadDefinitions(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("advancements.nodes");
        Map<String, MinionAdvancementDefinition> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            if (id.equalsIgnoreCase("industrial_bronze") || id.equalsIgnoreCase("industrial_wrench")) continue;
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            result.put(id, MinionAdvancementDefinition.fromConfig(id, section));
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private YamlConfiguration loadYaml() {
        File file = new File(plugin.getDataFolder(), "minion-advancements.yml");
        if (!file.exists()) plugin.saveResource("minion-advancements.yml", false);
        return YamlConfiguration.loadConfiguration(file);
    }

    private String escape(String raw) {
        if (raw == null) return "";
        StringBuilder escaped = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private record PlayerSnapshot(boolean townMember, UUID townId, Map<String, Integer> maxTierByType, int distinctMinionTypes, int minionCount, int highestTier, Map<String, CollectionProgress> collectionProgress) {
    }
}
