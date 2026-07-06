package hexnpc.storage;

import hexnpc.model.Dialogue;
import hexnpc.model.DialogueLine;
import hexnpc.model.InteractionSettings;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcAppearance;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.model.NpcSkin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

public final class YamlNpcStorage implements NpcStorage {

    private final File file;
    private final Logger logger;
    private final Map<NpcId, NpcDefinition> cache = new LinkedHashMap<>();

    public YamlNpcStorage(File file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void load() throws IOException {
        cache.clear();
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory " + parent);
            }
            if (!file.createNewFile()) {
                throw new IOException("Failed to create " + file);
            }
            YamlConfiguration empty = new YamlConfiguration();
            empty.createSection("npcs");
            empty.save(file);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                NpcDefinition def = readDefinition(key, section);
                cache.put(def.id(), def);
            } catch (Exception e) {
                logger.warning("HexNPC: skipping invalid NPC '" + key + "': " + e.getMessage());
            }
        }
    }

    @Override
    public Collection<NpcDefinition> all() {
        return Collections.unmodifiableCollection(cache.values());
    }

    @Override
    public Optional<NpcDefinition> find(NpcId id) {
        return Optional.ofNullable(cache.get(id));
    }

    @Override
    public synchronized void save(NpcDefinition definition) throws IOException {
        Objects.requireNonNull(definition, "definition");
        cache.put(definition.id(), definition);
        writeAll();
    }

    @Override
    public synchronized boolean delete(NpcId id) throws IOException {
        if (cache.remove(id) == null) {
            return false;
        }
        writeAll();
        return true;
    }

    private void writeAll() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("npcs");
        for (NpcDefinition def : cache.values()) {
            writeDefinition(root.createSection(def.id().value()), def);
        }
        yaml.save(file);
    }

    private NpcDefinition readDefinition(String key, ConfigurationSection section) {
        NpcId id = new NpcId(key);
        NpcSkin skin = readSkin(section.getConfigurationSection("skin"), id.value());
        NpcLocation location = readLocation(section.getConfigurationSection("location"));
        InteractionSettings interaction = readInteraction(section.getConfigurationSection("interaction"));
        Dialogue dialogue = readDialogue(section.getConfigurationSection("dialogue"));
        NpcActions actions = readActions(section);
        NpcAppearance appearance = readAppearance(section.getConfigurationSection("appearance"), skin);
        return new NpcDefinition(id, skin, location, interaction, dialogue, actions, appearance);
    }

    /**
     * Liest den Darstellungs-Block. Rueckwaerts-kompatibel: alte npcs.yml haben
     * keinen {@code appearance}-Block. Der sichtbare Name wurde frueher aus
     * {@code skin.name} abgeleitet — den migrieren wir hier als Nickname, damit
     * bestehende NPCs weiterhin denselben sichtbaren Namen behalten. Skin-Quelle
     * ({@code skin.name}) bleibt davon unabhaengig erhalten.
     */
    private NpcAppearance readAppearance(ConfigurationSection section, NpcSkin skin) {
        if (section == null) {
            // Echte Legacy-Config ohne appearance-Block: sichtbarer Name kam frueher
            // aus skin.name -> als Nickname migrieren.
            return new NpcAppearance(skin.name(), false, NpcPose.STANDING);
        }
        // appearance-Block vorhanden: fehlender display-name bedeutet "kein expliziter
        // Name" (Renderer faellt auf die Id zurueck) — NICHT auf skin.name migrieren,
        // sonst wuerde ein bewusstes /hexnpc name <id> clear beim Reload wieder den
        // Skin-Namen als sichtbaren Namen einblenden.
        String displayName = section.getString("display-name");
        boolean glow = section.getBoolean("glow", false);
        NpcPose pose = NpcPose.fromStorage(section.getString("pose"));
        return new NpcAppearance(displayName, glow, pose);
    }

    private NpcSkin readSkin(ConfigurationSection section, String fallbackName) {
        if (section == null) {
            return NpcSkin.ofName(fallbackName);
        }
        String name = section.getString("name");
        String value = section.getString("value");
        String signature = section.getString("signature");
        return new NpcSkin(name, value, signature);
    }

    private NpcLocation readLocation(ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("missing 'location'");
        }
        String world = section.getString("world");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("missing 'location.world'");
        }
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw", 0.0D);
        float pitch = (float) section.getDouble("pitch", 0.0D);
        return new NpcLocation(world, x, y, z, yaw, pitch);
    }

    private InteractionSettings readInteraction(ConfigurationSection section) {
        if (section == null) {
            return InteractionSettings.defaultClick();
        }
        boolean click = section.getBoolean("click", true);
        ConfigurationSection proximity = section.getConfigurationSection("proximity");
        if (proximity == null) {
            // No proximity block at all -> use global config defaults via 0 sentinels.
            return new InteractionSettings(click, false, 0.0D, 0);
        }
        return new InteractionSettings(
                click,
                proximity.getBoolean("enabled", false),
                proximity.getDouble("radius", 0.0D),
                proximity.getInt("cooldown-ticks", 0)
        );
    }

    private Dialogue readDialogue(ConfigurationSection section) {
        if (section == null) {
            return Dialogue.empty();
        }
        int cooldown = section.getInt("cooldown-ticks", 0);
        List<Map<?, ?>> raw = section.getMapList("lines");
        List<DialogueLine> lines = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            Object text = entry.get("text");
            Object delay = entry.get("delay-ticks");
            if (text == null) {
                continue;
            }
            int delayTicks = (delay instanceof Number n) ? n.intValue() : 0;
            lines.add(new DialogueLine(String.valueOf(text), delayTicks));
        }
        return new Dialogue(lines, cooldown);
    }

    private NpcActions readActions(ConfigurationSection parent) {
        if (parent == null) {
            return NpcActions.empty();
        }
        // New map format: actions: { on-click: [...], on-proximity: [...] }
        ConfigurationSection actionsSection = parent.getConfigurationSection("actions");
        if (actionsSection != null) {
            List<NpcAction> click = readActionList(actionsSection.getMapList("on-click"));
            List<NpcAction> proximity = readActionList(actionsSection.getMapList("on-proximity"));
            return new NpcActions(click, proximity);
        }
        // Legacy flat list: actions: [...]. Backwards-compat: treat as on-click.
        List<NpcAction> legacy = readActionList(parent.getMapList("actions"));
        return new NpcActions(legacy, List.of());
    }

    private List<NpcAction> readActionList(List<Map<?, ?>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<NpcAction> actions = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            Object type = entry.get("type");
            if (type == null) {
                continue;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            for (Map.Entry<?, ?> kv : entry.entrySet()) {
                String key = String.valueOf(kv.getKey());
                if (key.equals("type")) {
                    continue;
                }
                args.put(key, kv.getValue());
            }
            actions.add(new NpcAction(String.valueOf(type), args));
        }
        return List.copyOf(actions);
    }

    private void writeDefinition(ConfigurationSection section, NpcDefinition def) {
        writeSkin(section.createSection("skin"), def.skin());
        writeLocation(section.createSection("location"), def.location());
        writeInteraction(section.createSection("interaction"), def.interaction());
        writeDialogue(section.createSection("dialogue"), def.dialogue());
        ConfigurationSection actions = section.createSection("actions");
        actions.set("on-click", toActionList(def.actions().onClick()));
        actions.set("on-proximity", toActionList(def.actions().onProximity()));
        writeAppearance(section.createSection("appearance"), def.appearance());
    }

    private void writeAppearance(ConfigurationSection section, NpcAppearance appearance) {
        // display-name nur schreiben wenn explizit gesetzt; fehlt es, faellt der
        // Renderer beim Laden auf die NPC-Id zurueck.
        if (appearance.displayName() != null) {
            section.set("display-name", appearance.displayName());
        }
        section.set("glow", appearance.glow());
        section.set("pose", appearance.pose().storageKey());
    }

    private void writeSkin(ConfigurationSection section, NpcSkin skin) {
        if (skin.name() != null) {
            section.set("name", skin.name());
        }
        if (skin.value() != null) {
            section.set("value", skin.value());
        }
        if (skin.signature() != null) {
            section.set("signature", skin.signature());
        }
    }

    private void writeLocation(ConfigurationSection section, NpcLocation loc) {
        section.set("world", loc.world());
        section.set("x", loc.x());
        section.set("y", loc.y());
        section.set("z", loc.z());
        section.set("yaw", loc.yaw());
        section.set("pitch", loc.pitch());
    }

    private void writeInteraction(ConfigurationSection section, InteractionSettings interaction) {
        section.set("click", interaction.clickEnabled());
        ConfigurationSection prox = section.createSection("proximity");
        prox.set("enabled", interaction.proximityEnabled());
        prox.set("radius", interaction.proximityRadius());
        prox.set("cooldown-ticks", interaction.proximityCooldownTicks());
    }

    private void writeDialogue(ConfigurationSection section, Dialogue dialogue) {
        section.set("cooldown-ticks", dialogue.cooldownTicks());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (DialogueLine line : dialogue.lines()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", line.text());
            entry.put("delay-ticks", line.delayTicks());
            lines.add(entry);
        }
        section.set("lines", lines);
    }

    private List<Map<String, Object>> toActionList(List<NpcAction> actions) {
        List<Map<String, Object>> output = new ArrayList<>();
        for (NpcAction action : actions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", action.type());
            entry.putAll(action.mutableArgs());
            output.add(entry);
        }
        return output;
    }

    /** Test-only hook so we can swap the backing FileConfiguration if needed. */
    void replaceFromYaml(FileConfiguration yaml) {
        cache.clear();
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            NpcDefinition def = readDefinition(key, section);
            cache.put(def.id(), def);
        }
    }
}
