package hex.minions.diagnostics;

import hex.minions.config.Definitions;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.ResourceDefinition;
import hex.minions.config.ResourceDrop;
import hex.minions.config.TierDefinition;
import hex.minions.crafting.MachineUpgradeDefinition;
import hex.minions.crafting.SpecialIngredient;
import hex.minions.crafting.SpecialItemCarrierResolver;
import hex.minions.crafting.SpecialItemDefinition;
import hex.minions.crafting.SpecialItemRegistry;
import hex.minions.crafting.SpecialRecipeDefinition;
import hex.minions.energy.CableType;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineRecipe;
import hex.minions.service.MinionService;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Lightweight, read-only diagnostics for configuration consistency and balance.
 * It deliberately does not mutate database data or runtime minion state.
 */
public final class ProjectDiagnosticsService {
    private final Plugin plugin;
    private final MinionService service;

    public ProjectDiagnosticsService(Plugin plugin, MinionService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public ValidationResult validateAndLog() {
        ValidationResult result = validate();
        plugin.getLogger().info("HexMinions config validation: errors=" + result.errors().size()
                + ", warnings=" + result.warnings().size() + ", info=" + result.info().size());
        result.errors().forEach(line -> plugin.getLogger().severe("[validator] " + line));
        result.warnings().stream().limit(100).forEach(line -> plugin.getLogger().warning("[validator] " + line));
        if (result.warnings().size() > 100) {
            plugin.getLogger().warning("[validator] Pominięto " + (result.warnings().size() - 100)
                    + " dalszych ostrzeżeń. Pełny wynik jest dostępny w raporcie balansu.");
        }
        return result;
    }

    public ValidationResult validate() {
        Definitions definitions = service.definitions();
        SpecialItemRegistry specialItems = service.specialItems();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> info = new ArrayList<>();

        validateCustomItemCarriers(specialItems, errors, info);
        validateRecipes(definitions, specialItems, errors, warnings);
        validateMinionDefinitions(definitions, specialItems, errors, warnings);
        validateMachines(specialItems, errors, warnings);
        validateRuntimeModes(errors, warnings);
        validateItemIdentity(definitions, specialItems, warnings);
        validateItemGraph(definitions, specialItems, warnings, info);
        validateRawConfigReferences(specialItems, errors, warnings);

        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings), List.copyOf(info));
    }

    public Path generateBalanceReport() throws IOException {
        ValidationResult validation = validate();
        String reportDirectory = plugin.getConfig().getString("diagnostics.report-directory", "reports");
        Path directory = plugin.getDataFolder().toPath().resolve(reportDirectory == null || reportDirectory.isBlank() ? "reports" : reportDirectory);
        Files.createDirectories(directory);
        Path output = directory.resolve("hexminions_balance_report.md");
        Files.writeString(output, buildBalanceReport(validation), StandardCharsets.UTF_8);
        return output;
    }

    private void validateCustomItemCarriers(SpecialItemRegistry registry,
                                            List<String> errors,
                                            List<String> info) {
        int blockCarrierCount = 0;
        int invalidStoragePlacedCount = 0;
        int invalidCompressionCount = 0;
        int missingPlacementStrategyCount = 0;

        YamlConfiguration resourcesYaml = loadYaml("resources.yml");
        ConfigurationSection resourcesRoot = resourcesYaml.getConfigurationSection("resources");

        // A/P: active resource-pack special items. Active registry is still the runtime truth.
        for (SpecialItemDefinition item : registry.items().values()) {
            if (item.material() == null) {
                errors.add("custom item " + item.id() + " has no effective carrier material.");
                continue;
            }
            boolean vanillaCompression = isVanillaCompressionIdentity(item.id(), item.material(), item.customModelData(), resourcesRoot);
            if (CustomItemCarrierPolicy.requiresNonBlockCarrier(CustomItemCarrierPolicy.Category.SPECIAL_ITEM)
                    && item.material().isBlock() && !vanillaCompression) {
                blockCarrierCount++;
                errors.add("custom item " + item.id() + " uses block material " + item.material().name()
                        + ". Resource-pack custom items must use non-block carrier materials.");
            }
        }

        // A/P: runtime storage registry separates icon carrier from physical world block.
        for (var definition : service.storageChests().definitions().values()) {
            if (definition.material() == null) {
                errors.add("storage custom item " + definition.id() + " has no carrier material.");
            } else if (definition.material().isBlock()) {
                blockCarrierCount++;
                errors.add("storage custom item " + definition.id() + " uses block material "
                        + definition.material().name() + ". Resource-pack storage items must use non-block carriers.");
            }
            if (definition.placedMaterial() == null || definition.placedMaterial() == Material.AIR
                    || !definition.placedMaterial().isBlock()) {
                invalidStoragePlacedCount++;
                errors.add("storage " + definition.id() + " has invalid physical placed material: "
                        + (definition.placedMaterial() == null ? "<null>" : definition.placedMaterial().name()) + '.');
            }
        }

        YamlConfiguration specialYaml = loadYaml("special-items.yml");
        ConfigurationSection specialRoot = specialYaml.getConfigurationSection("special-items");
        ConfigurationSection stationRoot = specialYaml.getConfigurationSection("crafting-stations");
        YamlConfiguration machinesYaml = loadYaml("machines.yml");
        ConfigurationSection machineRoot = machinesYaml.getConfigurationSection("machines");

        // A/O/P: raw special items include disabled and intentionally hidden robot content.
        if (specialRoot != null) {
            for (String id : specialRoot.getKeys(false)) {
                ConfigurationSection section = specialRoot.getConfigurationSection(id);
                if (section == null) continue;
                String resourceRef = section.getString("resource-ref", "").trim();
                String configured = section.isSet("material") ? section.getString("material", "") : null;
                Material material;
                if (configured != null && !configured.isBlank()) {
                    material = Material.matchMaterial(configured);
                    if (material == null) {
                        errors.add("raw special-items.yml custom item " + id + " has invalid material " + configured + '.');
                    }
                } else {
                    material = SpecialItemCarrierResolver.resolveConfiguredCarrier(id, resourceRef, resourcesRoot).orElse(null);
                    if (!resourceRef.isBlank() && material == null) {
                        errors.add("raw special-items.yml custom item " + id
                                + " cannot resolve canonical carrier from resource-ref " + resourceRef + '.');
                    }
                }
                boolean vanillaCompression = isVanillaCompressionIdentity(id, material, 0, resourcesRoot);
                if (material != null && material.isBlock() && !vanillaCompression) {
                    blockCarrierCount++;
                    errors.add("raw special-items.yml custom item " + id + " resolves to block material "
                            + material.name() + '.');
                }

                if (section.getBoolean("placeable", false)) {
                    String blockKind = section.getString("block-kind", id);
                    PlaceableItemPolicy.Strategy strategy = PlaceableItemPolicy.classify(blockKind);
                    PlaceableItemPolicy.Resolution resolution = PlaceableItemPolicy.resolve(blockKind, stationRoot, machineRoot);
                    boolean supported = resolution.supported();
                    if (strategy == PlaceableItemPolicy.Strategy.CABLE) {
                        supported = CableType.fromSpecialItem(id) != null;
                    }
                    if (!supported) {
                        missingPlacementStrategyCount++;
                        errors.add("placeable custom item " + id + " has no physical placement strategy for block-kind "
                                + blockKind + '.');
                    }
                }
            }
        }

        // A/P: raw storage config validates invalid enum strings as well as isBlock semantics.
        YamlConfiguration storageYaml = loadYaml("storage-chests.yml");
        ConfigurationSection storageRoot = storageYaml.getConfigurationSection("storage-chests");
        if (storageRoot != null) {
            for (String id : storageRoot.getKeys(false)) {
                ConfigurationSection section = storageRoot.getConfigurationSection(id);
                if (section == null) continue;

                String carrierRaw = section.getString("item.material", "").trim();
                Material carrier = Material.matchMaterial(carrierRaw);
                if (carrier == null) {
                    errors.add("raw storage-chests.yml custom item " + id + " has invalid/missing item.material "
                            + printable(carrierRaw) + '.');
                } else if (carrier.isBlock()) {
                    blockCarrierCount++;
                    errors.add("raw storage-chests.yml custom item " + id + " uses block material " + carrier.name() + '.');
                }

                String placedRaw = section.getString("item.placed-material", "").trim();
                Material placed = Material.matchMaterial(placedRaw);
                if (placed == null || placed == Material.AIR || !placed.isBlock()) {
                    invalidStoragePlacedCount++;
                    errors.add("raw storage-chests.yml storage " + id + " has invalid item.placed-material "
                            + printable(placedRaw) + ". It must resolve to a non-AIR block.");
                }
            }
        }

        // A/O/P: resource items with CMD are resource-pack custom; compression has one canonical source.
        if (resourcesRoot != null) {
            for (String id : resourcesRoot.getKeys(false)) {
                ConfigurationSection section = resourcesRoot.getConfigurationSection(id);
                if (section == null) continue;

                int cmd = Math.max(0, section.getInt("custom-model-data", 0));
                if (cmd > 0) {
                    Material resourceCarrier = SpecialItemCarrierResolver.resolveConfiguredCarrier(id, id, resourcesRoot).orElse(null);
                    if (resourceCarrier == null) {
                        errors.add("resources.yml custom resource " + id + " (CMD " + cmd
                                + ") has no valid canonical carrier material.");
                    } else if (resourceCarrier.isBlock()) {
                        blockCarrierCount++;
                        errors.add("resources.yml custom resource " + id + " uses block material "
                                + resourceCarrier.name() + '.');
                    }
                }

                if (SpecialItemCarrierResolver.compressionEnabled(id, section)) {
                    Material rawMaterial = Material.matchMaterial(section.getString("material", ""));
                    int rawCmd = Math.max(0, section.getInt("custom-model-data", 0));
                    Material material = SpecialItemCarrierResolver.resolveConfiguredCarrier("compressed_" + id, id, resourcesRoot).orElse(null);
                    if (material == null) {
                        invalidCompressionCount++;
                        errors.add("resources.yml compression carrier " + id + " cannot be resolved.");
                    } else if (rawCmd <= 0) {
                        Material expected = SpecialItemCarrierResolver.compressionCarrier(id, section, rawMaterial);
                        if (material != expected) {
                            invalidCompressionCount++;
                            errors.add("resources.yml vanilla compression " + id + " should resolve to canonical carrier "
                                    + expected.name() + " but resolves to " + material.name() + '.');
                        }
                    } else if (material.isBlock()) {
                        invalidCompressionCount++;
                        blockCarrierCount++;
                        errors.add("resources.yml custom compression carrier " + id + " uses block material "
                                + material.name() + ". Custom resource-pack compression must use a non-block Material.");
                    }

                    if (material != null) {
                        validateCompressionAliasCarrier(registry, id, "compressed_" + id, material, rawCmd <= 0 ? 0 : null, errors);
                        validateCompressionAliasCarrier(registry, id, "super_compressed_" + id, material, rawCmd <= 0 ? 0 : null, errors);
                    }
                }
            }
        }

        // A: explicitly classify minion ItemStacks instead of silently excluding their PDC path.
        YamlConfiguration minionTypesYaml = loadYaml("minion-types.yml");
        ConfigurationSection minionRoot = minionTypesYaml.getConfigurationSection("minion-types");
        int vanillaMinionItems = 0;
        int resourcePackMinionItems = 0;
        if (minionRoot != null) {
            for (String id : minionRoot.getKeys(false)) {
                ConfigurationSection item = minionRoot.getConfigurationSection(id + ".item");
                if (item == null) continue;
                int cmd = Math.max(0, item.getInt("custom-model-data", 0));
                CustomItemCarrierPolicy.Classification classification = CustomItemCarrierPolicy.minionItemClassification(cmd);
                if (classification == CustomItemCarrierPolicy.Classification.PLUGIN_IDENTIFIED_VANILLA_ITEM) {
                    vanillaMinionItems++;
                } else {
                    resourcePackMinionItems++;
                    String raw = item.getString("material", "").trim();
                    Material material = Material.matchMaterial(raw);
                    if (material == null) {
                        errors.add("minion item " + id + " has CMD " + cmd + " but invalid material " + printable(raw) + '.');
                    } else if (material.isBlock()) {
                        blockCarrierCount++;
                        errors.add("minion item " + id + " has resource-pack CMD " + cmd
                                + " and block carrier " + material.name() + '.');
                    }
                }
            }
        }

        // A/P: appearance equipment is not a persistent PDC identity, but any concrete CMD>0 ItemStack
        // is still a resource-pack custom item and must use a non-block carrier.
        int appearanceCmdItems = validateAppearanceCmdItems(errors);
        blockCarrierCount += appearanceCmdItems;

        // A/P: direct custom-model outputs not represented by special-item PDC must still be non-block.
        blockCarrierCount += validateDirectCmdOutputs(specialYaml.getConfigurationSection("recipes"), "special recipe", errors);
        blockCarrierCount += validateMachineDirectCmdOutputs(machineRoot, errors);

        info.add("custom item carrier policy categories: " + CustomItemCarrierPolicy.rules().size());
        info.add("appearance equipment block-based resource-pack CMD carriers: " + appearanceCmdItems);
        info.add("minion item classification: PLUGIN_IDENTIFIED_VANILLA_ITEM=" + vanillaMinionItems
                + ", RESOURCE_PACK_CUSTOM_ITEM=" + resourcePackMinionItems);
        info.add("block-based resource-pack custom item carriers: " + blockCarrierCount);
        info.add("placeable custom items without placement strategy: " + missingPlacementStrategyCount);
        info.add("invalid storage placed materials: " + invalidStoragePlacedCount);
        info.add("invalid compression carriers: " + invalidCompressionCount);
    }

    private void validateCompressionAliasCarrier(SpecialItemRegistry registry,
                                                 String rawResourceId,
                                                 String specialItemId,
                                                 Material expected,
                                                 Integer expectedCmd,
                                                 List<String> errors) {
        SpecialItemDefinition item = registry.items().get(specialItemId.toLowerCase(Locale.ROOT));
        if (item == null) return; // raw/disabled aliases may intentionally not be in the active registry
        if (item.material() != expected) {
            errors.add("compression alias " + specialItemId + " resolves to " + item.material()
                    + " but canonical carrier for " + rawResourceId + " is " + expected.name() + '.');
        }
        if (expectedCmd != null && item.customModelData() != expectedCmd) {
            errors.add("vanilla compression alias " + specialItemId + " has CMD " + item.customModelData()
                    + " but vanilla resources must keep CMD " + expectedCmd + '.');
        }
    }

    private boolean isVanillaCompressionIdentity(String specialItemId,
                                                 Material material,
                                                 int effectiveCmd,
                                                 ConfigurationSection resourcesRoot) {
        if (specialItemId == null || material == null || resourcesRoot == null) return false;
        var source = SpecialItemCarrierResolver.compressionSourceResourceId(specialItemId, resourcesRoot);
        if (source.isEmpty()) return false;
        ConfigurationSection raw = resourcesRoot.getConfigurationSection(source.get());
        if (raw == null || Math.max(0, raw.getInt("custom-model-data", 0)) > 0) return false;
        Material rawMaterial = Material.matchMaterial(raw.getString("material", ""));
        return rawMaterial != null && rawMaterial == material && effectiveCmd == 0;
    }

    private int validateAppearanceCmdItems(List<String> errors) {
        YamlConfiguration appearanceYaml = loadYaml("appearance.yml");
        ConfigurationSection appearances = appearanceYaml.getConfigurationSection("appearances");
        if (appearances == null) return 0;

        int blockCarriers = 0;
        for (String appearanceId : appearances.getKeys(false)) {
            ConfigurationSection equipment = appearances.getConfigurationSection(appearanceId + ".base.equipment");
            if (equipment == null) continue;
            for (String slot : equipment.getKeys(false)) {
                ConfigurationSection item = equipment.getConfigurationSection(slot);
                if (item == null || item.getInt("custom-model-data", 0) <= 0) continue;

                String raw = item.getString("material", "").trim();
                Material material = Material.matchMaterial(raw);
                if (material == null) {
                    errors.add("appearance " + appearanceId + " equipment " + slot
                            + " has CMD " + item.getInt("custom-model-data")
                            + " but invalid material " + printable(raw) + '.');
                } else if (material.isBlock()) {
                    blockCarriers++;
                    errors.add("appearance " + appearanceId + " equipment " + slot
                            + " has resource-pack CMD " + item.getInt("custom-model-data")
                            + " and block carrier " + material.name() + '.');
                }
            }
        }
        return blockCarriers;
    }

    private int validateDirectCmdOutputs(ConfigurationSection recipeRoot,
                                         String context,
                                         List<String> errors) {
        if (recipeRoot == null) return 0;
        int blockCarriers = 0;
        for (String id : recipeRoot.getKeys(false)) {
            ConfigurationSection recipe = recipeRoot.getConfigurationSection(id);
            if (recipe == null) continue;
            ConfigurationSection output = recipe.getConfigurationSection("output");
            blockCarriers += validateDirectCmdOutput(output, context + ' ' + id, errors);
        }
        return blockCarriers;
    }

    private int validateMachineDirectCmdOutputs(ConfigurationSection machineRoot, List<String> errors) {
        if (machineRoot == null) return 0;
        int blockCarriers = 0;
        for (String machineId : machineRoot.getKeys(false)) {
            ConfigurationSection recipes = machineRoot.getConfigurationSection(machineId + ".recipes");
            if (recipes == null) continue;
            for (String recipeId : recipes.getKeys(false)) {
                ConfigurationSection output = recipes.getConfigurationSection(recipeId + ".output");
                blockCarriers += validateDirectCmdOutput(output, "machine recipe " + machineId + '/' + recipeId, errors);
            }
        }
        return blockCarriers;
    }

    private int validateDirectCmdOutput(ConfigurationSection output, String context, List<String> errors) {
        if (output == null || output.getInt("custom-model-data", 0) <= 0) return 0;
        if (!output.getString("special-item", "").isBlank() || !output.getString("minion-type", "").isBlank()) return 0;
        String raw = output.getString("material", "").trim();
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            errors.add(context + " has direct CMD output with invalid material " + printable(raw) + '.');
            return 0;
        }
        if (material.isBlock()) {
            errors.add(context + " has direct resource-pack CMD output using block carrier " + material.name() + '.');
            return 1;
        }
        return 0;
    }

    private static String printable(String raw) {
        return raw == null || raw.isBlank() ? "<missing>" : raw;
    }

    private void validateRecipes(Definitions definitions, SpecialItemRegistry registry, List<String> errors, List<String> warnings) {
        for (SpecialRecipeDefinition recipe : registry.recipes().values()) {
            boolean hasOutput = false;
            if (!blank(recipe.outputSpecialItem())) {
                hasOutput = true;
                if (!registry.items().containsKey(lower(recipe.outputSpecialItem()))) {
                    errors.add("Receptura " + recipe.id() + " wskazuje nieistniejący output special-item: " + recipe.outputSpecialItem());
                }
            }
            if (!blank(recipe.outputMinionType())) {
                hasOutput = true;
                if (!definitions.minionTypes().containsKey(lower(recipe.outputMinionType()))) {
                    errors.add("Receptura " + recipe.id() + " wskazuje nieistniejący typ miniona: " + recipe.outputMinionType());
                }
            }
            if (recipe.outputMaterial() != Material.AIR) hasOutput = true;
            if (!hasOutput) errors.add("Receptura " + recipe.id() + " nie ma żadnego outputu.");

            for (Map.Entry<Character, SpecialIngredient> entry : recipe.ingredients().entrySet()) {
                SpecialIngredient ingredient = entry.getValue();
                if (!blank(ingredient.specialItemId()) && !registry.items().containsKey(lower(ingredient.specialItemId()))) {
                    errors.add("Receptura " + recipe.id() + " używa nieistniejącego special-item "
                            + ingredient.specialItemId() + " pod kluczem " + entry.getKey() + '.');
                }
                if (blank(ingredient.specialItemId()) && ingredient.material() == Material.AIR && ingredient.materialChoices().isEmpty()) {
                    warnings.add("Receptura " + recipe.id() + " ma pusty składnik pod kluczem " + entry.getKey() + '.');
                }
            }
            for (String minionId : recipe.unlock().townMinionLevels().keySet()) {
                if (!definitions.minionTypes().containsKey(lower(minionId))) {
                    errors.add("Receptura " + recipe.id() + " wymaga nieistniejącego miniona: " + minionId);
                }
            }
            if (recipe.unlock().townMinionLevels().size() > 1 && recipe.unlock().townMinionLevelsMode() == null) {
                errors.add("Receptura " + recipe.id() + " ma wiele wymagań minionów bez jawnego trybu ALL/ANY.");
            }
        }
    }

    private void validateMinionDefinitions(Definitions definitions, SpecialItemRegistry registry, List<String> errors, List<String> warnings) {
        for (MinionTypeDefinition type : definitions.minionTypes().values()) {
            if (type.tiers().isEmpty()) errors.add("Minion " + type.id() + " nie ma skonfigurowanych tierów.");
            int previousSlots = 0;
            for (TierDefinition tier : type.tiers().values().stream().sorted(Comparator.comparingInt(TierDefinition::tier)).toList()) {
                if (tier.storageSlots() < previousSlots) {
                    warnings.add("Minion " + type.id() + " traci sloty storage na Tier " + tier.tier() + '.');
                }
                previousSlots = tier.storageSlots();
                if (tier.storage() != tier.storageSlots() * 64) {
                    warnings.add("Minion " + type.id() + " Tier " + tier.tier()
                            + " ma niespójny legacy storage=" + tier.storage() + "; runtime użyje " + tier.storageSlots() + " slotów.");
                }
            }
            for (ResourceDrop drop : type.resourceTable()) {
                if (!definitions.resources().containsKey(lower(drop.resourceId()))) {
                    errors.add("Minion " + type.id() + " generuje nieistniejący zasób: " + drop.resourceId());
                }
            }
            if ("WEIGHTED_ONE".equalsIgnoreCase(type.dropSelectionMode())) {
                double baseWeight = type.resourceTable().stream().filter(drop -> !drop.specialDrop()).mapToDouble(ResourceDrop::chance).sum();
                if (baseWeight <= 0.0D) errors.add("Minion " + type.id() + " w trybie WEIGHTED_ONE nie ma dodatniej wagi bazowej.");
                if (Math.abs(baseWeight - 1.0D) > 0.0001D) {
                    warnings.add("Minion " + type.id() + " ma sumę bazowych wag WEIGHTED_ONE=" + format(baseWeight * 100.0D)
                            + "%; runtime normalizuje ją do 100%. Bonusowe special-drop są losowane niezależnie.");
                }
            }
            for (String itemId : type.wikiSpecialItems()) {
                if (!registry.items().containsKey(lower(itemId)) && !registry.recipes().containsKey(lower(itemId))) {
                    warnings.add("Wiki miniona " + type.id() + " wskazuje pustą lub nieistniejącą nagrodę: " + itemId);
                }
            }
        }
    }

    private void validateMachines(SpecialItemRegistry registry, List<String> errors, List<String> warnings) {
        Map<String, MachineUpgradeDefinition> upgrades = registry.machineUpgrades();
        for (MachineDefinition machine : service.machines().machines().values()) {
            if (!blank(machine.specialItemId()) && !registry.items().containsKey(lower(machine.specialItemId()))) {
                errors.add("Maszyna " + machine.id() + " używa nieistniejącego special-item: " + machine.specialItemId());
            }
            for (MachineRecipe recipe : machine.recipes()) {
                validateMachineItemRef(machine, recipe.id(), "input", recipe.inputSpecialItem(), registry, errors);
                validateMachineItemRef(machine, recipe.id(), "secondary", recipe.secondarySpecialItem(), registry, errors);
                validateMachineItemRef(machine, recipe.id(), "fuel", recipe.fuelSpecialItem(), registry, errors);
                validateMachineItemRef(machine, recipe.id(), "output", recipe.outputSpecialItem(), registry, errors);
            }
            if (!machine.upgradeSlots().isEmpty()) {
                boolean supported = upgrades.values().stream().anyMatch(upgrade -> upgrade.supportsMachineType(machine.type()));
                if (!supported) warnings.add("Maszyna " + machine.id() + " pokazuje slot ulepszenia, ale żadne ulepszenie maszyny nie wspiera typu " + machine.type() + '.');
            }
            Set<Integer> dedicatedSlots = new HashSet<>();
            for (int slot : List.of(machine.inputStorageExtensionSlot(), machine.outputStorageExtensionSlot(), machine.fuelStorageExtensionSlot())) {
                if (slot < 0) continue;
                if (!dedicatedSlots.add(slot)) errors.add("Maszyna " + machine.id() + " używa tego samego slotu dla kilku rozszerzeń magazynu: " + slot);
                if (machine.upgradeSlots().contains(slot)) errors.add("Maszyna " + machine.id() + " miesza slot magazynu i ulepszenia maszyny: " + slot);
            }
            if (!machine.energy().enabled() && !machine.upgradeSlots().isEmpty()) {
                warnings.add("Maszyna " + machine.id() + " ma sloty ulepszeń energetycznych przy wyłączonym energy.enabled.");
            }
        }

        YamlConfiguration raw = loadYaml("machines.yml");
        ConfigurationSection machines = raw.getConfigurationSection("machines");
        if (machines == null) return;
        for (String id : machines.getKeys(false)) {
            ConfigurationSection section = machines.getConfigurationSection(id);
            if (section == null || !section.isSet("menu.fuel-slot")) continue;
            MachineDefinition machine = service.machines().machines().get(lower(id));
            if (machine == null) continue;
            boolean supportsFuel = machine.hasRecipeFuelSlot()
                    || !machine.energy().fuelEu().isEmpty()
                    || !machine.energy().fuelBurnSeconds().isEmpty()
                    || !machine.energy().fallbackFuelEu().isEmpty()
                    || !machine.energy().fallbackFuelBurnSeconds().isEmpty();
            if (!supportsFuel) warnings.add("Maszyna " + id + " deklaruje menu.fuel-slot, ale nie obsługuje paliwa.");
        }
    }

    private void validateMachineItemRef(MachineDefinition machine, String recipeId, String role, String itemId,
                                        SpecialItemRegistry registry, List<String> errors) {
        if (!blank(itemId) && !registry.items().containsKey(lower(itemId))) {
            errors.add("Maszyna " + machine.id() + "/" + recipeId + " ma nieistniejący " + role + " special-item: " + itemId);
        }
    }

    private void validateRuntimeModes(List<String> errors, List<String> warnings) {
        String temporal = plugin.getConfig().getString("minions.boosters.stacking.temporal-mode", "QUEUE");
        if (!"QUEUE".equalsIgnoreCase(temporal)) {
            warnings.add("Nieobsługiwany temporal-mode boosterów: " + temporal + ". Runtime używa kolejki QUEUE.");
        }
        String combination = plugin.getConfig().getString("minions.boosters.stacking.combination-mode", "ADDITIVE");
        if (!("ADDITIVE".equalsIgnoreCase(combination)
                || "HIGHEST_ONLY".equalsIgnoreCase(combination)
                || "MULTIPLICATIVE".equalsIgnoreCase(combination))) {
            errors.add("Nieobsługiwany combination-mode boosterów: " + combination
                    + ". Dozwolone: ADDITIVE, HIGHEST_ONLY, MULTIPLICATIVE.");
        }
        double cap = plugin.getConfig().getDouble("minions.boosters.stacking.max-total-speed-boost-percent", 50.0D);
        if (cap < 0.0D || cap > 95.0D) {
            warnings.add("Limit łącznego boosta szybkości wynosi " + format(cap)
                    + "%. Zalecany zakres to 0-95%, aby opóźnienie akcji nie spadło praktycznie do zera.");
        }
    }

    private void validateItemIdentity(Definitions definitions, SpecialItemRegistry registry, List<String> warnings) {
        Map<String, Set<String>> identityOwners = new LinkedHashMap<>();
        for (ResourceDefinition resource : definitions.resources().values()) {
            identityOwners.computeIfAbsent(identity(resource.material(), resource.customModelData()), ignored -> new LinkedHashSet<>())
                    .add("resource:" + resource.id());
        }
        for (SpecialItemDefinition item : registry.items().values()) {
            identityOwners.computeIfAbsent(identity(item.material(), item.customModelData()), ignored -> new LinkedHashSet<>())
                    .add("special:" + item.id());
        }
        identityOwners.forEach((identity, owners) -> {
            if (identity.endsWith(":0")) return; // CMD 0 oznacza zwykły wariant vanilla i może być współdzielony świadomie.
            Set<String> logicalIds = new HashSet<>();
            owners.forEach(owner -> logicalIds.add(owner.substring(owner.indexOf(':') + 1)));
            if (logicalIds.size() > 1) warnings.add("Materiał+CMD " + identity + " jest współdzielony przez różne ID: " + String.join(", ", owners));
        });
    }

    private void validateItemGraph(Definitions definitions, SpecialItemRegistry registry, List<String> warnings, List<String> info) {
        Set<String> sources = new HashSet<>();
        Set<String> consumers = new HashSet<>();
        sources.addAll(definitions.resources().keySet());
        for (SpecialRecipeDefinition recipe : registry.recipes().values()) {
            if (!blank(recipe.outputSpecialItem())) sources.add(lower(recipe.outputSpecialItem()));
            recipe.ingredients().values().stream().map(SpecialIngredient::specialItemId).filter(id -> !blank(id)).map(ProjectDiagnosticsService::lower).forEach(consumers::add);
        }
        for (MachineDefinition machine : service.machines().machines().values()) {
            sources.add(lower(machine.specialItemId()));
            for (MachineRecipe recipe : machine.recipes()) {
                if (!blank(recipe.outputSpecialItem())) sources.add(lower(recipe.outputSpecialItem()));
                if (!blank(recipe.inputSpecialItem())) consumers.add(lower(recipe.inputSpecialItem()));
                if (!blank(recipe.secondarySpecialItem())) consumers.add(lower(recipe.secondarySpecialItem()));
                if (!blank(recipe.fuelSpecialItem())) consumers.add(lower(recipe.fuelSpecialItem()));
            }
        }
        registry.productionUpdates().values().forEach(update -> consumers.add(lower(update.specialItemId())));
        registry.machineUpgrades().values().forEach(upgrade -> consumers.add(lower(upgrade.specialItemId())));
        registry.robotUpgrades().values().forEach(upgrade -> consumers.add(lower(upgrade.specialItemId())));
        registry.boosters().values().forEach(booster -> consumers.add(lower(booster.specialItemId())));

        // resource-ref oznacza ten sam logiczny przedmiot. Źródła i konsumenci zasobu nadrzędnego
        // muszą być dziedziczone przez wpis special-item i odwrotnie.
        YamlConfiguration specialYaml = loadYaml("special-items.yml");
        ConfigurationSection specialRoot = specialYaml.getConfigurationSection("special-items");
        if (specialRoot != null) {
            for (String alias : specialRoot.getKeys(false)) {
                ConfigurationSection section = specialRoot.getConfigurationSection(alias);
                if (section == null || !section.isSet("resource-ref")) continue;
                String aliasId = lower(alias);
                String resourceId = lower(section.getString("resource-ref", alias));
                if (sources.contains(resourceId)) sources.add(aliasId);
                if (sources.contains(aliasId)) sources.add(resourceId);
                if (consumers.contains(resourceId)) consumers.add(aliasId);
                if (consumers.contains(aliasId)) consumers.add(resourceId);
            }
        }

        for (SpecialItemDefinition item : registry.items().values()) {
            String id = lower(item.id());
            if (!sources.contains(id) && !item.placeable()) warnings.add("Item " + id + " nie ma wykrytego źródła pozyskania.");
            if (!consumers.contains(id) && !item.placeable() && registry.recipes().values().stream().noneMatch(r -> id.equals(lower(r.outputSpecialItem())))) {
                info.add("Item " + id + " nie ma wykrytego konsumenta ani dalszej receptury.");
            }
        }
    }

    private void validateRawConfigReferences(SpecialItemRegistry registry, List<String> errors, List<String> warnings) {
        YamlConfiguration special = loadYaml("special-items.yml");
        ConfigurationSection items = special.getConfigurationSection("special-items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(id);
                if (section == null || !section.isSet("resource-ref")) continue;
                String ref = lower(section.getString("resource-ref", ""));
                if (!service.definitions().resources().containsKey(ref)) errors.add("special-item " + id + " ma nieistniejący resource-ref: " + ref);
                if (section.isSet("material") || section.isSet("custom-model-data") || section.isSet("display-name")) {
                    warnings.add("special-item " + id + " używa resource-ref, ale nadal duplikuje material/CMD/display-name.");
                }
            }
        }
        YamlConfiguration minionYaml = loadYaml("minion-types.yml");
        ConfigurationSection minionRoot = minionYaml.getConfigurationSection("minion-types");
        if (minionRoot != null) {
            for (String minionId : minionRoot.getKeys(false)) {
                ConfigurationSection tiers = minionRoot.getConfigurationSection(minionId + ".tiers");
                if (tiers == null) continue;
                for (String tier : tiers.getKeys(false)) {
                    if (tiers.isSet(tier + ".storage")) {
                        warnings.add("Minion " + minionId + " Tier " + tier + " ma legacy pole storage; jest ignorowane. Źródłem prawdy jest storage-slots.");
                    }
                }
            }
        }

        YamlConfiguration machinesYaml = loadYaml("machines.yml");
        ConfigurationSection machineRoot = machinesYaml.getConfigurationSection("machines");
        if (machineRoot != null) {
            for (String machineId : machineRoot.getKeys(false)) {
                ConfigurationSection machine = machineRoot.getConfigurationSection(machineId);
                if (machine == null) continue;
                if (machine.isSet("menu.upgrade-slots")) warnings.add("Maszyna " + machineId + " używa legacy menu.upgrade-slots; użyj menu.machine-upgrade-slots lub dedykowanych slotów magazynu.");
                if (machine.isSet("storage.upgrade-item")) warnings.add("Maszyna " + machineId + " ma nieużywane storage.upgrade-item; rozszerzenia magazynu muszą mieć dedykowany slot menu.");
            }
        }

        if (new File(plugin.getDataFolder(), "upgrades.yml").exists()) {
            warnings.add("W katalogu danych nadal istnieje legacy upgrades.yml. Plik nie jest ładowany; można go usunąć po migracji.");
        }
        if (registry.items().containsKey("enchanted_cobblestone") || registry.recipes().containsKey("enchanted_cobblestone")) {
            errors.add("Legacy enchanted_cobblestone nadal istnieje w aktywnym rejestrze; użyj compressed_cobblestone.");
        }
    }

    private String buildBalanceReport(ValidationResult validation) {
        StringBuilder out = new StringBuilder();
        out.append("# HexMinions — automatyczny raport balansu\n\n")
                .append("Wygenerowano: `").append(OffsetDateTime.now()).append("`\n\n")
                .append("Raport jest wyliczany bez modyfikowania bazy danych ani świata.\n\n");
        appendValidation(out, validation);
        appendRuntimeRules(out);
        appendMinionProduction(out);
        appendRareDrops(out);
        appendMachineBalance(out);
        appendItemCatalog(out);
        appendRecipeGraph(out);
        return out.toString();
    }

    private void appendRuntimeRules(StringBuilder out) {
        String temporal = plugin.getConfig().getString("minions.boosters.stacking.temporal-mode", "QUEUE");
        String combination = plugin.getConfig().getString("minions.boosters.stacking.combination-mode", "ADDITIVE");
        double cap = plugin.getConfig().getDouble("minions.boosters.stacking.max-total-speed-boost-percent", 50.0D);
        out.append("## Reguły runtime\n\n")
                .append("- Model pojemności minionów: **liczba slotów**, każdy zasób używa własnego maksymalnego stacku.\n")
                .append("- Boostery czasowe: **").append(temporal).append("**.\n")
                .append("- Łączenie boostera czasowego i stałego ulepszenia: **").append(combination).append("**.\n")
                .append("- Maksymalny łączny bonus szybkości: **").append(format(cap)).append("%**.\n\n");
    }

    private void appendValidation(StringBuilder out, ValidationResult validation) {
        out.append("## Walidacja konfiguracji\n\n")
                .append("- Błędy: **").append(validation.errors().size()).append("**\n")
                .append("- Ostrzeżenia: **").append(validation.warnings().size()).append("**\n")
                .append("- Informacje: **").append(validation.info().size()).append("**\n\n");
        appendMessages(out, "### Błędy", validation.errors());
        appendMessages(out, "### Ostrzeżenia", validation.warnings());
        appendMessages(out, "### Elementy bez dalszego konsumenta", validation.info());
    }

    private void appendMessages(StringBuilder out, String title, List<String> messages) {
        out.append(title).append("\n\n");
        if (messages.isEmpty()) out.append("Brak.\n\n");
        else {
            messages.forEach(message -> out.append("- ").append(message).append('\n'));
            out.append('\n');
        }
    }

    private void appendMinionProduction(StringBuilder out) {
        out.append("## Produkcja minionów\n\n")
                .append("| Minion | Tier | Czas akcji | Akcje/h | Oczekiwane itemy/h | Sloty |\n")
                .append("|---|---:|---:|---:|---|---:|\n");
        service.definitions().minionTypes().values().stream().sorted(Comparator.comparing(MinionTypeDefinition::id)).forEach(type -> {
            for (TierDefinition tier : type.tiers().values().stream().sorted(Comparator.comparingInt(TierDefinition::tier)).toList()) {
                double actions = 3600.0D / Math.max(0.05D, tier.actionTimeSeconds());
                String production = expectedProduction(type, tier.tier(), actions);
                out.append('|').append(stripMini(type.displayName())).append(" (`").append(type.id()).append("`)").append('|').append(tier.tier()).append('|')
                        .append(format(tier.actionTimeSeconds())).append(" s|").append(format(actions)).append('|')
                        .append(production).append('|').append(tier.storageSlots()).append("|\n");
            }
        });
        out.append('\n');
    }

    private String expectedProduction(MinionTypeDefinition type, int tier, double actionsPerHour) {
        double baseSum = type.resourceTable().stream().filter(drop -> !drop.specialDrop()).mapToDouble(ResourceDrop::chance).sum();
        List<String> parts = new ArrayList<>();
        for (ResourceDrop drop : type.resourceTable()) {
            double chance = effectiveDropChance(drop, tier);
            if ("WEIGHTED_ONE".equalsIgnoreCase(type.dropSelectionMode()) && !drop.specialDrop() && baseSum > 0.0D) chance = drop.chance() / baseSum;
            double averageAmount = (drop.amountMin() + drop.amountMax()) / 2.0D;
            parts.add(itemDisplayName(drop.resourceId()) + " " + format(actionsPerHour * chance * averageAmount));
        }
        return String.join("; ", parts);
    }

    private void appendRareDrops(StringBuilder out) {
        out.append("## Rzadkie dropy\n\n")
                .append("| Minion | Tier | Drop | Szansa/akcję | Średnio akcji | Średnio czasu |\n")
                .append("|---|---:|---|---:|---:|---:|\n");
        for (MinionTypeDefinition type : service.definitions().minionTypes().values().stream().sorted(Comparator.comparing(MinionTypeDefinition::id)).toList()) {
            for (ResourceDrop drop : type.resourceTable()) {
                if (!drop.specialDrop()) continue;
                for (TierDefinition tier : type.tiers().values().stream().sorted(Comparator.comparingInt(TierDefinition::tier)).toList()) {
                    double chance = effectiveDropChance(drop, tier.tier());
                    if (chance <= 0.0D) continue;
                    double actions = 1.0D / chance;
                    double seconds = actions * tier.actionTimeSeconds();
                    out.append('|').append(stripMini(type.displayName())).append(" (`").append(type.id()).append("`)").append('|').append(tier.tier()).append('|').append(itemDisplayName(drop.resourceId())).append('|')
                            .append(format(chance * 100.0D)).append("%|").append(format(actions)).append('|')
                            .append(formatDuration(seconds)).append("|\n");
                }
            }
        }
        out.append('\n');
    }

    private void appendMachineBalance(StringBuilder out) {
        out.append("## Procesy maszyn\n\n")
                .append("| Maszyna | Proces | Input | Output | Czas | Szansa | EU/proces | Wartość input | Wartość output ocz. |\n")
                .append("|---|---|---|---|---:|---:|---:|---:|---:|\n");
        for (MachineDefinition machine : service.machines().machines().values().stream().sorted(Comparator.comparing(MachineDefinition::id)).toList()) {
            for (MachineRecipe recipe : machine.recipes()) {
                double inputWorth = ingredientWorth(recipe.inputSpecialItem(), recipe.inputMaterial(), recipe.inputAmount())
                        + ingredientWorth(recipe.secondarySpecialItem(), recipe.secondaryMaterial(), recipe.secondaryAmount())
                        + ingredientWorth(recipe.fuelSpecialItem(), recipe.fuelMaterial(), recipe.fuelAmount());
                double outputWorth = ingredientWorth(recipe.outputSpecialItem(), recipe.outputMaterial(), recipe.outputAmount()) * recipe.successChance();
                long eu = machine.energy().enabled() && !machine.energy().generator()
                        ? (long) machine.energy().euPerSecond() * recipe.timeSeconds() : 0L;
                out.append('|').append(stripMini(machine.displayName())).append(" (`").append(machine.id()).append("`)").append('|').append(recipe.id()).append('|')
                        .append(machineInputText(recipe)).append('|').append(machineOutputText(recipe)).append('|')
                        .append(recipe.timeSeconds()).append(" s|").append(format(recipe.successChance() * 100.0D)).append("%|")
                        .append(eu).append('|').append(format(inputWorth)).append('|').append(format(outputWorth)).append("|\n");
            }
        }
        out.append('\n');
    }

    private void appendItemCatalog(StringBuilder out) {
        out.append("## Katalog aktywnych itemów customowych\n\n")
                .append("Tabela obejmuje również przedmioty kompresji generowane w runtime z `resources.yml`.\n\n")
                .append("| ID | Nazwa dla gracza | Źródło | Konsumenci |\n")
                .append("|---|---|---|---|\n");
        Map<String, Set<String>> sources = new TreeMap<>();
        Map<String, Set<String>> consumers = new TreeMap<>();
        for (SpecialRecipeDefinition recipe : service.specialItems().recipes().values()) {
            if (!blank(recipe.outputSpecialItem())) sources.computeIfAbsent(lower(recipe.outputSpecialItem()), ignored -> new LinkedHashSet<>()).add("receptura " + recipe.id());
            recipe.ingredients().values().stream().map(SpecialIngredient::specialItemId).filter(id -> !blank(id))
                    .forEach(id -> consumers.computeIfAbsent(lower(id), ignored -> new LinkedHashSet<>()).add("receptura " + recipe.id()));
        }
        for (MachineDefinition machine : service.machines().machines().values()) {
            for (MachineRecipe recipe : machine.recipes()) {
                if (!blank(recipe.outputSpecialItem())) sources.computeIfAbsent(lower(recipe.outputSpecialItem()), ignored -> new LinkedHashSet<>()).add(stripMini(machine.displayName()));
                for (String id : List.of(recipe.inputSpecialItem(), recipe.secondarySpecialItem(), recipe.fuelSpecialItem())) {
                    if (!blank(id)) consumers.computeIfAbsent(lower(id), ignored -> new LinkedHashSet<>()).add(stripMini(machine.displayName()));
                }
            }
        }
        service.specialItems().machineUpgrades().values().forEach(upgrade -> consumers.computeIfAbsent(lower(upgrade.specialItemId()), ignored -> new LinkedHashSet<>()).add("slot ulepszenia maszyny"));
        service.specialItems().robotUpgrades().values().forEach(upgrade -> consumers.computeIfAbsent(lower(upgrade.specialItemId()), ignored -> new LinkedHashSet<>()).add("slot ulepszenia robota"));
        service.specialItems().productionUpdates().values().forEach(update -> consumers.computeIfAbsent(lower(update.specialItemId()), ignored -> new LinkedHashSet<>()).add("slot stałego ulepszenia miniona"));
        service.specialItems().boosters().values().forEach(booster -> consumers.computeIfAbsent(lower(booster.specialItemId()), ignored -> new LinkedHashSet<>()).add("slot boostera miniona"));

        propagateResourceReferences(sources, consumers);

        for (SpecialItemDefinition item : service.specialItems().items().values().stream().sorted(Comparator.comparing(SpecialItemDefinition::id)).toList()) {
            String id = lower(item.id());
            String source = sources.getOrDefault(id, Set.of()).isEmpty() ? "brak wykrytego źródła" : String.join(", ", sources.get(id));
            String use = consumers.getOrDefault(id, Set.of()).isEmpty() ? "brak dalszego konsumenta" : String.join(", ", consumers.get(id));
            out.append('|').append(id).append('|').append(stripMini(item.displayName())).append('|').append(source).append('|').append(use).append("|\n");
        }
        out.append('\n');
    }

    private void appendRecipeGraph(StringBuilder out) {
        out.append("## Graf zależności receptur\n\n");
        for (SpecialRecipeDefinition recipe : service.specialItems().recipes().values().stream().sorted(Comparator.comparing(SpecialRecipeDefinition::id)).toList()) {
            String output = !blank(recipe.outputSpecialItem()) ? itemDisplayName(recipe.outputSpecialItem())
                    : !blank(recipe.outputMinionType()) ? minionDisplayName(recipe.outputMinionType())
                    : materialDisplayName(recipe.outputMaterial());
            List<String> ingredients = recipe.ingredients().values().stream().map(this::ingredientText).distinct().toList();
            out.append("- **").append(output).append("** (`").append(recipe.id()).append("`) — stacja: ")
                    .append(stationDisplayName(recipe.station())).append("; odblokowanie: ").append(unlockText(recipe)).append("; składniki: ")
                    .append(String.join(", ", ingredients)).append('\n');
        }
        out.append('\n');
    }

    private String unlockText(SpecialRecipeDefinition recipe) {
        if (recipe.unlock().isEmpty()) return "brak";
        List<String> conditions = new ArrayList<>();
        recipe.unlock().townMinionLevels().forEach((id, tier) -> conditions.add(minionDisplayName(id) + " Tier " + tier));
        recipe.unlock().collections().forEach((id, amount) -> conditions.add("kolekcja " + id + " ≥ " + amount));
        String joiner = recipe.unlock().townMinionLevelsMode().name().equalsIgnoreCase("ANY") ? " lub " : " oraz ";
        return String.join(joiner, conditions);
    }

    private String ingredientText(SpecialIngredient ingredient) {
        if (!blank(ingredient.specialItemId())) return ingredient.amount() + "x " + itemDisplayName(ingredient.specialItemId());
        if (ingredient.hasMaterialChoices()) return ingredient.amount() + "x " + ingredient.materialChoices().stream().map(this::materialDisplayName).toList();
        return ingredient.amount() + "x " + materialDisplayName(ingredient.material());
    }

    private String machineInputText(MachineRecipe recipe) {
        List<String> parts = new ArrayList<>();
        parts.add(itemRef(recipe.inputSpecialItem(), recipe.inputMaterial(), recipe.inputAmount()));
        if (!blank(recipe.secondarySpecialItem()) || recipe.secondaryMaterial() != Material.AIR) parts.add(itemRef(recipe.secondarySpecialItem(), recipe.secondaryMaterial(), recipe.secondaryAmount()));
        if (!blank(recipe.fuelSpecialItem()) || recipe.fuelMaterial() != Material.AIR) parts.add(itemRef(recipe.fuelSpecialItem(), recipe.fuelMaterial(), recipe.fuelAmount()));
        return String.join(" + ", parts);
    }

    private String machineOutputText(MachineRecipe recipe) {
        return itemRef(recipe.outputSpecialItem(), recipe.outputMaterial(), recipe.outputAmount());
    }

    private String itemRef(String special, Material material, int amount) {
        return amount + "x " + (!blank(special) ? itemDisplayName(special) : materialDisplayName(material));
    }

    private double ingredientWorth(String special, Material material, int amount) {
        ResourceDefinition resource = null;
        if (!blank(special)) resource = service.definitions().resources().get(lower(special));
        if (resource == null && material != null && material != Material.AIR) {
            resource = service.definitions().resources().values().stream()
                    .filter(candidate -> candidate.material() == material && candidate.customModelData() == 0)
                    .findFirst().orElse(null);
        }
        return resource == null ? 0.0D : resource.worth() * amount;
    }

    private double effectiveDropChance(ResourceDrop drop, int tier) {
        double chance = drop.chance();
        if (drop.specialDrop() && tier >= drop.specialDropScalingFromTier()) {
            chance += Math.max(0, tier - drop.specialDropScalingFromTier()) * drop.specialDropPerTierBonus();
        }
        return Math.max(0.0D, Math.min(1.0D, chance));
    }

    private void propagateResourceReferences(Map<String, Set<String>> sources, Map<String, Set<String>> consumers) {
        YamlConfiguration yaml = loadYaml("special-items.yml");
        ConfigurationSection root = yaml.getConfigurationSection("special-items");
        if (root == null) return;
        for (String aliasRaw : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(aliasRaw);
            if (section == null || !section.isSet("resource-ref")) continue;
            String alias = lower(aliasRaw);
            String resource = lower(section.getString("resource-ref", aliasRaw));
            Set<String> sharedSources = new LinkedHashSet<>();
            sharedSources.addAll(sources.getOrDefault(alias, Set.of()));
            sharedSources.addAll(sources.getOrDefault(resource, Set.of()));
            if (!sharedSources.isEmpty()) {
                sources.put(alias, new LinkedHashSet<>(sharedSources));
                sources.put(resource, new LinkedHashSet<>(sharedSources));
            }
            Set<String> sharedConsumers = new LinkedHashSet<>();
            sharedConsumers.addAll(consumers.getOrDefault(alias, Set.of()));
            sharedConsumers.addAll(consumers.getOrDefault(resource, Set.of()));
            if (!sharedConsumers.isEmpty()) {
                consumers.put(alias, new LinkedHashSet<>(sharedConsumers));
                consumers.put(resource, new LinkedHashSet<>(sharedConsumers));
            }
        }
    }

    private String itemDisplayName(String id) {
        if (blank(id)) return "brak";
        SpecialItemDefinition special = service.specialItems().items().get(lower(id));
        if (special != null) return stripMini(special.displayName());
        ResourceDefinition resource = service.definitions().resources().get(lower(id));
        if (resource != null) return stripMini(resource.displayName());
        return friendlyId(id);
    }

    private String minionDisplayName(String id) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(lower(id));
        return type == null ? friendlyId(id) : stripMini(type.displayName());
    }

    private String stationDisplayName(String station) {
        if (blank(station)) return "brak stacji";
        return switch (station.toUpperCase(Locale.ROOT)) {
            case "VANILLA_CRAFTING_TABLE" -> "stół rzemieślniczy";
            case "ENCHANTED_CRAFTING_TABLE", "ADVANCED_CRAFTING_TABLE" -> "zaawansowany stół rzemieślniczy";
            default -> friendlyId(station);
        };
    }

    private String materialDisplayName(Material material) {
        if (material == null || material == Material.AIR) return "brak";
        ResourceDefinition resource = service.definitions().resources().values().stream()
                .filter(candidate -> candidate.material() == material && candidate.customModelData() == 0)
                .findFirst().orElse(null);
        return resource == null ? friendlyId(material.name()) : stripMini(resource.displayName());
    }

    private static String friendlyId(String raw) {
        if (blank(raw)) return "brak";
        String normalized = lower(raw)
                .replace("super_compressed", "superskompresowany")
                .replace("compressed", "skompresowany")
                .replace("storage", "magazyn")
                .replace("upgrade", "ulepszenie")
                .replace("update", "ulepszenie")
                .replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private YamlConfiguration loadYaml(String name) {
        File file = new File(plugin.getDataFolder(), name);
        return YamlConfiguration.loadConfiguration(file);
    }

    private static String stripMini(String input) {
        return input == null ? "" : input.replaceAll("<[^>]+>", "").trim();
    }

    private static String identity(Material material, int cmd) {
        return (material == null ? "AIR" : material.name()) + ':' + Math.max(0, cmd);
    }

    private static boolean blank(String raw) { return raw == null || raw.isBlank(); }
    private static String lower(String raw) { return raw == null ? "" : raw.toLowerCase(Locale.ROOT); }
    private static String format(double value) {
        String text = String.format(Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        return text.isBlank() || "-".equals(text) ? "0" : text;
    }

    private static String formatDuration(double seconds) {
        if (seconds < 60.0D) return format(seconds) + " s";
        if (seconds < 3600.0D) return format(seconds / 60.0D) + " min";
        return format(seconds / 3600.0D) + " h";
    }

    public record ValidationResult(List<String> errors, List<String> warnings, List<String> info) {
        public boolean valid() { return errors.isEmpty(); }
    }
}
