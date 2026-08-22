package pl.hexnetwork.hexnpcclickfix;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HexNpcClickFixPlugin extends JavaPlugin {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String MARKER = "# HEXNPC_CLICKFIX:";
    private static final int PAYLOAD_VERSION = 1;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda jest przeznaczona dla graczy.");
            return true;
        }

        Map<String, Menu> menus = loadMenus();
        if (menus.isEmpty()) {
            // Kompatybilność z pierwszą wersją poprawki, która miała menu kasyna
            // zakodowane w JAR i używała /hexnpcinfo bez argumentów.
            menus.put("kasyno_info_on-click_0", legacyCasinoFallback());
        }

        Menu menu = null;
        if (args.length > 0) {
            menu = menus.get(args[0]);
            if (menu == null) {
                // Kompatybilność z krótszym identyfikatorem NPC, np. "kasyno_info".
                String prefix = args[0] + "_";
                for (Map.Entry<String, Menu> entry : menus.entrySet()) {
                    if (entry.getKey().startsWith(prefix)) {
                        menu = entry.getValue();
                        break;
                    }
                }
            }
        } else {
            // Kompatybilność z pierwszą wersją poprawki: /hexnpcinfo bez argumentów.
            menu = menus.values().iterator().next();
        }

        if (menu == null) {
            sendLegacy(player, "&cNie znaleziono tego menu NPC.");
            return true;
        }

        sendMenu(player, menu);
        return true;
    }


    private Menu legacyCasinoFallback() {
        List<String> lines = List.of(
                "&f&l------------------&6&lKasyno&f&l------------------",
                "&fWitaj &e<nick>&f, co chcesz wiedzieć?",
                "&7(kliknij w odpowiedni temat)",
                "&f-------------------------------------------------"
        );
        List<MenuOption> options = List.of(
                new MenuOption(
                        "&61. &fCzym jest &6Bus Driver&f?",
                        "&7Kliknij, aby przeczytać.",
                        List.of(
                                "&f", "&f",
                                "&f--------------------&6Bus Driver&f--------------------",
                                "&6• &fGra karciana na ryzyko. Zacznij od wyboru stawki.",
                                "&6• &fPotem zgadujesz etapy: &6kolor&f, &6wyżej&8/&6niżej&f, &6pomiędzy&8/&6poza &fi &6znak&f.",
                                "&6• &fPo każdej dobrej odpowiedzi rośnie możliwa wypłata. ",
                                "&6• &fMożesz wypłacić wcześniej albo grać dalej po więcej.",
                                "&f-------------------------------------------------"
                        )
                ),
                new MenuOption(
                        "&62. &fCzym jest &6Jednoręki Bandyta&f?",
                        "&7Kliknij, aby przeczytać.",
                        List.of(
                                "&f", "&f",
                                "&f---------------&6Jednoręki Bandyta&f---------------",
                                "&6• &fSlot machine. Wybierasz stawkę i liczbę linii.",
                                "&6• &fkręcisz, a wygrana wpada za pasujące symbole na aktywnych liniach.",
                                "&6• &fDroższe symbole i większa stawka oznaczają większą możliwą wypłatę.",
                                "&f----------------------------------------------"
                        )
                )
        );
        return new Menu(300, lines, options);
    }

    private Map<String, Menu> loadMenus() {
        Map<String, Menu> result = new LinkedHashMap<>();
        File pluginsDir = getDataFolder().getParentFile();
        File npcsFile = new File(new File(pluginsDir, "HexNPC"), "npcs.yml");
        if (!npcsFile.isFile()) return result;

        try {
            for (String rawLine : Files.readAllLines(npcsFile.toPath(), StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (!line.startsWith(MARKER)) continue;

                String rest = line.substring(MARKER.length());
                int separator = rest.indexOf(':');
                if (separator <= 0 || separator >= rest.length() - 1) continue;

                String key = rest.substring(0, separator);
                String encoded = rest.substring(separator + 1);
                try {
                    Menu menu = decodeMenu(encoded);
                    result.put(key, menu);
                } catch (Exception ignored) {
                    // Uszkodzony pojedynczy wpis nie może wyłączyć pozostałych menu.
                }
            }
        } catch (IOException ignored) {
            // Błąd odczytu zostanie pokazany graczowi jako brak menu.
        }
        return result;
    }

    private Menu decodeMenu(String encoded) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedByte();
            if (version != PAYLOAD_VERSION) throw new IOException("Unsupported payload version: " + version);

            int timeoutSeconds = in.readInt();
            List<String> lines = readStringList(in);
            int optionCount = in.readInt();
            if (optionCount < 0 || optionCount > 1000) throw new IOException("Invalid option count");

            List<MenuOption> options = new ArrayList<>(optionCount);
            for (int i = 0; i < optionCount; i++) {
                String text = readString(in);
                String hover = readString(in);
                List<String> response = readStringList(in);
                options.add(new MenuOption(text, hover, response));
            }
            return new Menu(timeoutSeconds, lines, options);
        }
    }

    private List<String> readStringList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10000) throw new IOException("Invalid string list size");
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readString(in));
        return values;
    }

    private String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1_000_000) throw new IOException("Invalid string length");
        byte[] data = in.readNBytes(length);
        if (data.length != length) throw new IOException("Unexpected end of payload");
        return new String(data, StandardCharsets.UTF_8);
    }

    private void sendMenu(Player player, Menu menu) {
        for (String line : menu.lines()) {
            sendLegacy(player, replacePlaceholders(line, player));
        }
        for (MenuOption option : menu.options()) {
            ((Audience) player).sendMessage(clickableOption(player, menu.timeoutSeconds(), option));
        }
    }

    private Component clickableOption(Player player, int timeoutSeconds, MenuOption option) {
        Duration lifetime = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        ClickEvent callback = ClickEvent.callback(
                audience -> handleResponseCallback(audience, option.response()),
                options -> options
                        .lifetime(lifetime)
                        .uses(ClickCallback.UNLIMITED_USES)
        );

        Component component = LEGACY.deserialize(replacePlaceholders(option.text(), player));
        String hover = replacePlaceholders(option.hover(), player);
        if (!hover.isEmpty()) {
            component = component.hoverEvent(HoverEvent.showText(LEGACY.deserialize(hover)));
        }
        return component.clickEvent(callback);
    }

    private void handleResponseCallback(Audience audience, List<String> response) {
        if (!(audience instanceof Player player)) return;
        for (String line : response) {
            sendLegacy(player, replacePlaceholders(line, player));
        }
    }

    private String replacePlaceholders(String value, Player player) {
        if (value == null) return "";
        return value.replace("<nick>", player.getName());
    }

    private void sendLegacy(Player player, String line) {
        ((Audience) player).sendMessage(LEGACY.deserialize(line));
    }

    private record Menu(int timeoutSeconds, List<String> lines, List<MenuOption> options) {}
    private record MenuOption(String text, String hover, List<String> response) {}
}
