package hex.gui;

import hex.gui.command.HexCommand;
import hex.gui.command.HexGuiAdminCommand;
import hex.gui.config.HubConfig;
import hex.gui.menu.HubMenu;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HexGUIPlugin extends JavaPlugin {
    private static final int CURRENT_CONFIG_VERSION = 6;
    private volatile HubConfig hubConfig;
    private HubMenu hubMenu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfigIfNeeded();
        this.hubConfig = HubConfig.load(this);
        this.hubMenu = new HubMenu(this);

        PluginCommand hex = getCommand("hex");
        if (hex == null) {
            getLogger().severe("Brak komendy /hex w plugin.yml. Wyłączam HexGUI.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        hex.setExecutor(new HexCommand(this));

        PluginCommand admin = getCommand("hexgui");
        if (admin != null) {
            HexGuiAdminCommand adminCommand = new HexGuiAdminCommand(this);
            admin.setExecutor(adminCommand);
            admin.setTabCompleter(adminCommand);
        }

        getServer().getPluginManager().registerEvents(hubMenu, this);
        getLogger().info("HexGUI 1.2.2 aktywny. Hub graczy: /hex");
    }

    public boolean reloadHubConfig() {
        try {
            reloadConfig();
            migrateConfigIfNeeded();
            HubConfig fresh = HubConfig.load(this);
            this.hubConfig = fresh;
            getLogger().info("Przeładowano config.yml HexGUI.");
            return true;
        } catch (Throwable throwable) {
            getLogger().severe("Nie udało się przeładować konfiguracji HexGUI: " + throwable);
            return false;
        }
    }

    private void migrateConfigIfNeeded() {
        int version = getConfig().getInt("config-version", 1);
        if (version >= CURRENT_CONFIG_VERSION) return;

        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                getLogger().warning("Nie znaleziono wbudowanego config.yml; pomijam migrację konfiguracji.");
                return;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            );
            if (version < 2) {
                copySectionIfMissing(defaults, "entries.daily-quests");
                copySectionIfMissing(defaults, "entries.daily-rewards");
            }
            if (version < 3) {
                // v3 jest nowym, docelowym układem HEX CENTRUM. Nadpisujemy wyłącznie
                // sekcje odpowiedzialne za wygląd i kafelki huba; komunikaty i dźwięki
                // pozostają po stronie administratora serwera.
                replaceSection(defaults, "menu");
                replaceSection(defaults, "entries");
            }
            if (version < 4) {
                // v4 dodaje wejście do informacji o rangach oraz osobne menu rang.
                // Istniejących kafelków administratora nie nadpisujemy.
                copySectionIfMissing(defaults, "entries.ranks");
                copySectionIfMissing(defaults, "rank-menu");
            }
            if (version < 5) {
                // v5 aktualizuje treści benefitów rang. Zachowujemy układ i stylistykę,
                // ale podmieniamy sekcję rank-menu, aby istniejący config dostał nowe lore.
                replaceSection(defaults, "rank-menu");
            }
            if (version < 6) {
                // v6 koryguje wyłącznie kolory tytułów rang w istniejących konfiguracjach.
                // Nie nadpisujemy lore, slotów ani ikon administratora.
                getConfig().set("rank-menu.entries.vip.name",
                        defaults.getString("rank-menu.entries.vip.name", "&e&lVIP"));
                getConfig().set("rank-menu.entries.svip.name",
                        defaults.getString("rank-menu.entries.svip.name", "&6&lSVIP"));
                getConfig().set("rank-menu.entries.elita.name",
                        defaults.getString("rank-menu.entries.elita.name", "&b&lELITA"));
                getConfig().set("rank-menu.entries.media.name",
                        defaults.getString("rank-menu.entries.media.name", "&d&lMEDIA"));
            }
            getConfig().set("config-version", CURRENT_CONFIG_VERSION);
            saveConfig();
            getLogger().info("Zaktualizowano config.yml HexGUI do wersji " + CURRENT_CONFIG_VERSION
                    + " (HEX CENTRUM + zaktualizowane menu rang).");
        } catch (Exception exception) {
            getLogger().warning("Nie udało się automatycznie zmigrować config.yml HexGUI: " + exception);
        }
    }

    private void replaceSection(YamlConfiguration defaults, String path) {
        getConfig().set(path, null);
        ConfigurationSection source = defaults.getConfigurationSection(path);
        if (source == null) return;
        Map<String, Object> values = source.getValues(true);
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if (value.getValue() instanceof ConfigurationSection) continue;
            getConfig().set(path + "." + value.getKey(), value.getValue());
        }
    }

    private void copySectionIfMissing(YamlConfiguration defaults, String path) {
        if (getConfig().isConfigurationSection(path)) return;
        ConfigurationSection source = defaults.getConfigurationSection(path);
        if (source == null) return;
        Map<String, Object> values = source.getValues(true);
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if (value.getValue() instanceof ConfigurationSection) continue;
            getConfig().set(path + "." + value.getKey(), value.getValue());
        }
    }

    public HubConfig hubConfig() {
        return hubConfig;
    }

    public HubMenu hubMenu() {
        return hubMenu;
    }
}
