package hexnpc.menu;

import hexnpc.model.NpcDefinition;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native HexNPC replacement for the former HexNPCClickFix menu subsystem.
 *
 * <p>Menus are persisted in clickable-menus.yml, so /hexnpc edits that rewrite
 * npcs.yml can no longer destroy clickable menu payloads. Old
 * # HEXNPC_CLICKFIX:&lt;id&gt;:&lt;base64&gt; comments are imported once and persisted.
 */
public final class ClickableMenuService {

    private static final String LEGACY_MARKER = "# HEXNPC_CLICKFIX:";
    private static final int LEGACY_PAYLOAD_VERSION = 1;

    private final File menusFile;
    private final Logger logger;
    private volatile Map<String, ClickableMenu> menus = Map.of();

    public ClickableMenuService(File menusFile, Logger logger) {
        this.menusFile = menusFile;
        this.logger = logger;
    }

    public synchronized int reload(File npcsFile) {
        Map<String, ClickableMenu> loaded = loadYaml();
        int imported = importLegacyMarkers(npcsFile, loaded);
        if (imported > 0) {
            saveYaml(loaded);
            logger.info("HexNPC: imported " + imported
                    + " legacy HexNPCClickFix menu(s) into clickable-menus.yml.");
        }
        this.menus = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        logger.info("HexNPC: loaded " + menus.size() + " clickable menu(s).");
        return menus.size();
    }

    public Optional<ClickableMenu> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        ClickableMenu exact = menus.get(id.trim());
        if (exact != null) {
            return Optional.of(exact);
        }
        String prefix = id.trim() + "_";
        return menus.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public boolean open(Player player, String id) {
        return open(player, null, id);
    }

    public boolean open(Player player, NpcDefinition npc, String id) {
        ClickableMenu menu = find(id).orElse(null);
        if (menu == null) {
            player.sendMessage(LegacyFormat.component("&cNie znaleziono tego menu NPC."));
            return false;
        }
        sendMenu(player, npc, menu);
        return true;
    }

    public void sendMenu(Player player, NpcDefinition npc, ClickableMenu menu) {
        for (String line : menu.lines()) {
            player.sendMessage(LegacyFormat.component(render(line, player, npc)));
        }
        for (ClickableMenu.Option option : menu.options()) {
            ((Audience) player).sendMessage(clickableOption(player, npc, menu.timeoutSeconds(), option));
        }
    }

    private Component clickableOption(Player player,
                                      NpcDefinition npc,
                                      int timeoutSeconds,
                                      ClickableMenu.Option option) {
        Duration lifetime = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        ClickEvent callback = ClickEvent.callback(
                audience -> {
                    if (!(audience instanceof Player callbackPlayer)) {
                        return;
                    }
                    for (String line : option.response()) {
                        callbackPlayer.sendMessage(LegacyFormat.component(render(line, callbackPlayer, npc)));
                    }
                },
                options -> options
                        .lifetime(lifetime)
                        .uses(ClickCallback.UNLIMITED_USES)
        );

        Component component = LegacyFormat.component(render(option.text(), player, npc));
        String hover = render(option.hover(), player, npc);
        if (!hover.isBlank()) {
            component = component.hoverEvent(HoverEvent.showText(LegacyFormat.component(hover)));
        }
        return component.clickEvent(callback);
    }

    private Map<String, ClickableMenu> loadYaml() {
        Map<String, ClickableMenu> result = new LinkedHashMap<>();
        if (!menusFile.isFile()) {
            return result;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(menusFile);
        ConfigurationSection root = yaml.getConfigurationSection("menus");
        if (root == null) {
            return result;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                int timeout = Math.max(1, section.getInt("timeout-seconds", 300));
                List<String> lines = section.getStringList("lines");
                List<ClickableMenu.Option> options = new ArrayList<>();
                for (Map<?, ?> raw : section.getMapList("options")) {
                    String text = string(raw, "text", "");
                    if (text.isBlank()) {
                        continue;
                    }
                    String hover = string(raw, "hover", "&7Kliknij, aby przeczytać.");
                    List<String> response = stringList(raw.get("response"));
                    if (response.isEmpty()) {
                        response = stringList(raw.get("message"));
                    }
                    if (!response.isEmpty()) {
                        options.add(new ClickableMenu.Option(text, hover, response));
                    }
                }
                result.put(id, new ClickableMenu(timeout, lines, options));
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "HexNPC: invalid clickable menu '" + id + "'.", ex);
            }
        }
        return result;
    }

    private int importLegacyMarkers(File npcsFile, Map<String, ClickableMenu> target) {
        if (npcsFile == null || !npcsFile.isFile()) {
            return 0;
        }
        int imported = 0;
        try {
            for (String rawLine : Files.readAllLines(npcsFile.toPath(), StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (!line.startsWith(LEGACY_MARKER)) {
                    continue;
                }
                String rest = line.substring(LEGACY_MARKER.length());
                int separator = rest.indexOf(':');
                if (separator <= 0 || separator >= rest.length() - 1) {
                    continue;
                }
                String id = rest.substring(0, separator).trim();
                try {
                    // Legacy payload is authoritative during migration so any menu
                    // customized before the merge is preserved instead of being
                    // overwritten by the bundled default clickable-menus.yml.
                    ClickableMenu decoded = decodeLegacy(rest.substring(separator + 1));
                    ClickableMenu previous = target.put(id, decoded);
                    if (!decoded.equals(previous)) {
                        imported++;
                    }
                } catch (Exception ex) {
                    logger.log(Level.WARNING,
                            "HexNPC: failed to import legacy HexNPCClickFix menu '" + id + "'.", ex);
                }
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "HexNPC: failed to scan npcs.yml for legacy ClickFix menus.", ex);
        }
        return imported;
    }

    private ClickableMenu decodeLegacy(String encoded) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedByte();
            if (version != LEGACY_PAYLOAD_VERSION) {
                throw new IOException("Unsupported legacy payload version: " + version);
            }
            int timeout = in.readInt();
            List<String> lines = readStringList(in);
            int optionCount = in.readInt();
            if (optionCount < 0 || optionCount > 1000) {
                throw new IOException("Invalid option count: " + optionCount);
            }
            List<ClickableMenu.Option> options = new ArrayList<>(optionCount);
            for (int i = 0; i < optionCount; i++) {
                String text = readString(in);
                String hover = readString(in);
                List<String> response = readStringList(in);
                options.add(new ClickableMenu.Option(text, hover, response));
            }
            return new ClickableMenu(timeout, lines, options);
        }
    }

    private void saveYaml(Map<String, ClickableMenu> values) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("menus");
        for (Map.Entry<String, ClickableMenu> entry : values.entrySet()) {
            ConfigurationSection section = root.createSection(entry.getKey());
            ClickableMenu menu = entry.getValue();
            section.set("timeout-seconds", menu.timeoutSeconds());
            section.set("lines", menu.lines());
            List<Map<String, Object>> options = new ArrayList<>();
            for (ClickableMenu.Option option : menu.options()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("text", option.text());
                if (!option.hover().isBlank()) {
                    map.put("hover", option.hover());
                }
                map.put("response", option.response());
                options.add(map);
            }
            section.set("options", options);
        }
        try {
            yaml.save(menusFile);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "HexNPC: failed to persist clickable-menus.yml.", ex);
        }
    }

    private List<String> readStringList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10000) {
            throw new IOException("Invalid string list size: " + count);
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readString(in));
        }
        return values;
    }

    private String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1_000_000) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new IOException("Unexpected end of payload");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private String render(String text, Player player, NpcDefinition npc) {
        String rendered = text == null ? "" : text;
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("<player>", player.getName());
        placeholders.put("<nick>", player.getName());
        placeholders.put("<npc>", npc == null ? "" : npc.id().value());
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
            rendered = rendered.replace(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
        }
        return rendered;
    }

    private String string(Map<?, ?> map, String key, String fallback) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue() == null ? fallback : String.valueOf(entry.getValue());
            }
        }
        return fallback;
    }

    private List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object value : list) {
                if (value != null) {
                    result.add(String.valueOf(value));
                }
            }
            return result;
        }
        if (raw instanceof String value && !value.isBlank()) {
            return List.of(value);
        }
        return List.of();
    }
}
