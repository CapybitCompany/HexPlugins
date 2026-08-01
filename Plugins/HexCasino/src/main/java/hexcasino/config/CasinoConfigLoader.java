package hexcasino.config;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class CasinoConfigLoader {

    public CasinoConfig load(FileConfiguration config, Logger logger) {
        Map<String, CasinoConfig.Machine> machines = loadMachines(config, logger);
        CasinoConfig.ParticleSetting idleParticles = particleSetting(config, "idle-particles", Particle.HAPPY_VILLAGER,
                60, 255, 60, 1.0F, 10, 3, 0.25D, 0.25D, 0.25D, 0.06D, 1.4D, logger);
        CasinoConfig.ParticleSetting occupiedParticles = particleSetting(config, "occupied-particles", Particle.DUST,
                255, 35, 35, 1.15F, 10, 3, 0.25D, 0.25D, 0.25D, 0.06D, 1.4D, logger);

        CasinoConfig.SlotMachine slotMachine = new CasinoConfig.SlotMachine(
                positiveDoubles(config.getDoubleList("slot-machine.bet-per-line-options"), List.of(1.0D, 2.0D, 5.0D, 10.0D)),
                positiveInts(config.getIntegerList("slot-machine.line-options"), List.of(1, 3, 5)),
                Math.max(0.01D, config.getDouble("slot-machine.default-bet-per-line", 1.0D)),
                Math.max(1, config.getInt("slot-machine.default-lines", 5)),
                Math.max(1, config.getInt("slot-machine.roll-tick-interval", 3)),
                Math.max(1, config.getInt("slot-machine.result-subtitle-ticks", 40)),
                new CasinoConfig.ExitVelocity(
                        config.getBoolean("slot-machine.exit-velocity.enabled", true),
                        Math.max(0.0D, config.getDouble("slot-machine.exit-velocity.backwards-strength", 0.45D)),
                        config.getDouble("slot-machine.exit-velocity.y", 0.15D)
                ),
                new CasinoConfig.Highlight(
                        config.getBoolean("slot-machine.highlight.enabled", true),
                        Math.max(1, config.getInt("slot-machine.highlight.duration-ticks", 8)),
                        Math.max(1, config.getInt("slot-machine.highlight.flash-count", 3))
                ),
                new CasinoConfig.WinAssist(
                        config.getBoolean("slot-machine.win-assist.enabled", true),
                        clampPercent(config.getDouble("slot-machine.win-assist.chance-percent", 35.0D))
                )
        );

        CasinoConfig.WheelOfFortune wheelOfFortune = loadWheelOfFortune(config, logger);
        CasinoConfig.BusDriver busDriver = loadBusDriver(config, logger);

        CasinoConfig.Economy economy = new CasinoConfig.Economy(
                config.getString("economy.balance-placeholder", "%hexeconomy_balance%"),
                config.getString("economy.remove-command", "hexeconomy remove {player} {amount}"),
                config.getString("economy.add-command", "hexeconomy add {player} {amount}")
        );

        CasinoConfig.Gui gui = loadGui(config, logger);
        List<String> initialSymbols = config.getStringList("initial-symbols");
        List<CasinoConfig.WinningLine> winningLines = loadWinningLines(config);
        SymbolLoadResult symbols = loadSymbols(config, logger);
        CasinoConfig.Messages messages = loadMessages(config);
        CasinoConfig.Sounds sounds = loadSounds(config);

        if (machines.isEmpty()) {
            throw new IllegalArgumentException("machines cannot be empty");
        }
        if (gui.reelSlots().size() != 9) {
            throw new IllegalArgumentException("gui.reel-slots must contain exactly 9 slots");
        }
        if (symbols.symbols().isEmpty()) {
            throw new IllegalArgumentException("symbols cannot be empty");
        }
        if (initialSymbols.size() != 9) {
            throw new IllegalArgumentException("initial-symbols must contain exactly 9 symbol ids");
        }
        for (String symbolId : initialSymbols) {
            if (!symbols.symbolsById().containsKey(symbolId)) {
                throw new IllegalArgumentException("initial-symbols contains unknown symbol id: " + symbolId);
            }
        }
        if (winningLines.isEmpty()) {
            throw new IllegalArgumentException("winning-lines cannot be empty");
        }

        return new CasinoConfig(
                machines,
                idleParticles,
                occupiedParticles,
                slotMachine,
                wheelOfFortune,
                busDriver,
                economy,
                gui,
                List.copyOf(initialSymbols),
                winningLines,
                symbols.symbols(),
                symbols.symbolsById(),
                messages,
                sounds
        );
    }

    private Map<String, CasinoConfig.Machine> loadMachines(FileConfiguration config, Logger logger) {
        return loadMachines(config, "machines", logger);
    }

    private Map<String, CasinoConfig.Machine> loadMachines(FileConfiguration config, String sectionPath, Logger logger) {
        Map<String, CasinoConfig.Machine> machines = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection(sectionPath);
        if (section == null) {
            return machines;
        }
        for (String id : section.getKeys(false)) {
            String path = sectionPath + "." + id + ".";
            Material material = material(config.getString(path + "activation-material", "MAGENTA_GLAZED_TERRACOTTA"),
                    Material.MAGENTA_GLAZED_TERRACOTTA, logger);
            CasinoConfig.BlockLocation block = new CasinoConfig.BlockLocation(
                    config.getInt(path + "activation-block.x"),
                    config.getInt(path + "activation-block.y"),
                    config.getInt(path + "activation-block.z")
            );
            CasinoConfig.PlayerLocation playerLocation = new CasinoConfig.PlayerLocation(
                    config.getDouble(path + "player-location.x", block.x() + 0.5D),
                    config.getDouble(path + "player-location.y", block.y() + 1.0D),
                    config.getDouble(path + "player-location.z", block.z() + 0.5D),
                    (float) config.getDouble(path + "player-location.yaw", 90.0D),
                    (float) config.getDouble(path + "player-location.pitch", 0.0D)
            );
            machines.put(id, new CasinoConfig.Machine(
                    id,
                    config.getString(path + "world", "world"),
                    material,
                    block,
                    playerLocation
            ));
        }
        return machines;
    }

    private CasinoConfig.WheelOfFortune loadWheelOfFortune(FileConfiguration config, Logger logger) {
        Map<String, CasinoConfig.Machine> machines = loadMachines(config, "wheel-of-fortune.machines", logger);
        if (machines.isEmpty()) {
            machines = Map.of("wheel-1", defaultMachine("wheel-1", 2798, 73, 958));
        }

        WheelSegmentLoadResult segments = loadWheelSegments(config, logger);
        List<String> layout = config.getStringList("wheel-of-fortune.segment-layout").stream()
                .filter(segments.segmentsById()::containsKey)
                .toList();
        if (layout.isEmpty()) {
            layout = defaultWheelLayout().stream()
                    .filter(segments.segmentsById()::containsKey)
                    .toList();
        }
        if (layout.isEmpty()) {
            layout = segments.segments().stream().map(CasinoConfig.WheelSegment::id).toList();
        }

        return new CasinoConfig.WheelOfFortune(
                machines,
                positiveDoubles(config.getDoubleList("wheel-of-fortune.multiplier-options"), List.of(1.0D, 2.0D, 5.0D, 10.0D)),
                Math.max(0.01D, config.getDouble("wheel-of-fortune.default-multiplier", 1.0D)),
                Math.max(1, config.getInt("wheel-of-fortune.spin-tick-interval", 2)),
                Math.max(1, config.getInt("wheel-of-fortune.result-subtitle-ticks", 40)),
                new CasinoConfig.ExitVelocity(
                        config.getBoolean("wheel-of-fortune.exit-velocity.enabled", true),
                        Math.max(0.0D, config.getDouble("wheel-of-fortune.exit-velocity.backwards-strength", 0.45D)),
                        config.getDouble("wheel-of-fortune.exit-velocity.y", 0.15D)
                ),
                loadWheelGui(config, logger),
                segments.segments(),
                segments.segmentsById(),
                List.copyOf(layout)
        );
    }

    private CasinoConfig.WheelGui loadWheelGui(FileConfiguration config, Logger logger) {
        return new CasinoConfig.WheelGui(
                config.getString("wheel-of-fortune.gui.title", "&6KOŁO FORTUNY"),
                normalizeGuiSize(config.getInt("wheel-of-fortune.gui.size", 54)),
                config.isList("wheel-of-fortune.gui.wheel-slots") ? config.getIntegerList("wheel-of-fortune.gui.wheel-slots")
                        : List.of(10, 11, 12, 13, 14, 15, 16, 25, 34, 43, 42, 41, 40, 39, 38, 37, 36, 27, 18, 9),
                config.getInt("wheel-of-fortune.gui.action-slot", 22),
                config.getInt("wheel-of-fortune.gui.multiplier-slot", 49),
                config.getInt("wheel-of-fortune.gui.balance-slot", 46),
                config.getInt("wheel-of-fortune.gui.exit-slot", 45),
                config.getInt("wheel-of-fortune.gui.info-slot", 53),
                guiItem(config, "wheel-of-fortune.gui.filler", Material.BLACK_STAINED_GLASS_PANE, "", List.of(), true, logger),
                guiItem(config, "wheel-of-fortune.gui.balance-item", Material.BUNDLE, "&aŚrodki: &f{balance_display}", List.of(), false, logger),
                guiItem(config, "wheel-of-fortune.gui.spin-item", Material.LIME_DYE, "&d&lZAKRĘĆ KOŁEM!",
                        List.of("&c&m--------------------", "&fAktualny koszt: &a{total_cost}",
                                "&c&m--------------------", "&7Mnożnik: &f{multiplier}"), false, logger),
                guiItem(config, "wheel-of-fortune.gui.no-funds-item", Material.RED_DYE, "&cBrak środków",
                        List.of("&c&m--------------------", "&7Wymagane: &f{total_cost}$", "&7Twoje środki: &f{balance_display}"), false, logger),
                guiItem(config, "wheel-of-fortune.gui.rolling-item", Material.YELLOW_DYE, "&eKoło się kręci", List.of(), false, logger),
                guiItem(config, "wheel-of-fortune.gui.multiplier-item", Material.WHITE_DYE, "&fUstawienia gry:",
                        List.of("&c&m--------------------", "&eLPM: &fZmień mnożnik",
                                "&c&m--------------------", "&7Mnożnik: &f{multiplier}"), false, logger),
                guiItem(config, "wheel-of-fortune.gui.exit-item", Material.BARRIER, "&cWyjście", List.of(), false, logger),
                guiItem(config, "wheel-of-fortune.gui.info-item", Material.PAPER, "&fLegenda Koła",
                        List.of("&c&m--------------------", "{segment_payouts}"), false, logger),
                config.getString("wheel-of-fortune.gui.info-item.segment-line", "&e{index}. {segment_name} &7(&a{multiplier}x&7)")
        );
    }

    private WheelSegmentLoadResult loadWheelSegments(FileConfiguration config, Logger logger) {
        List<CasinoConfig.WheelSegment> fallback = List.of(
                new CasinoConfig.WheelSegment("lose", Material.RED_STAINED_GLASS_PANE, "&cPrzegrana", List.of(), 0.0D, 35.0D),
                new CasinoConfig.WheelSegment("x1", Material.IRON_NUGGET, "&fZwrot", List.of(), 1.0D, 25.0D),
                new CasinoConfig.WheelSegment("x2", Material.GOLD_NUGGET, "&eMała wygrana", List.of(), 2.0D, 18.0D),
                new CasinoConfig.WheelSegment("x3", Material.GOLD_INGOT, "&6Dobra wygrana", List.of(), 3.0D, 10.0D),
                new CasinoConfig.WheelSegment("x5", Material.EMERALD, "&aDuża wygrana", List.of(), 5.0D, 7.0D),
                new CasinoConfig.WheelSegment("x10", Material.DIAMOND, "&bMega wygrana", List.of(), 10.0D, 3.0D),
                new CasinoConfig.WheelSegment("x25", Material.AMETHYST_SHARD, "&dSuper wygrana", List.of(), 25.0D, 1.5D),
                new CasinoConfig.WheelSegment("x50", Material.NETHER_STAR, "&f&lJACKPOT", List.of(), 50.0D, 0.5D)
        );
        ConfigurationSection section = config.getConfigurationSection("wheel-of-fortune.segments");
        if (section == null) {
            return wheelSegmentResult(fallback);
        }

        List<CasinoConfig.WheelSegment> segments = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String path = "wheel-of-fortune.segments." + id + ".";
            segments.add(new CasinoConfig.WheelSegment(
                    id,
                    material(config.getString(path + "material", "PAPER"), Material.PAPER, logger),
                    config.getString(path + "name", id),
                    config.isList(path + "lore") ? config.getStringList(path + "lore") : List.of(),
                    Math.max(0.0D, config.getDouble(path + "multiplier", 0.0D)),
                    Math.max(0.0D, config.getDouble(path + "chance-weight", 1.0D))
            ));
        }
        return wheelSegmentResult(segments.isEmpty() ? fallback : segments);
    }

    private WheelSegmentLoadResult wheelSegmentResult(List<CasinoConfig.WheelSegment> segments) {
        Map<String, CasinoConfig.WheelSegment> byId = new LinkedHashMap<>();
        for (CasinoConfig.WheelSegment segment : segments) {
            byId.put(segment.id(), segment);
        }
        return new WheelSegmentLoadResult(List.copyOf(segments), Map.copyOf(byId));
    }

    private List<String> defaultWheelLayout() {
        return List.of(
                "lose", "x1", "lose", "x2", "x1",
                "lose", "x3", "x1", "lose", "x5",
                "x2", "lose", "x1", "x10", "lose",
                "x2", "x3", "lose", "x25", "x50"
        );
    }

    private CasinoConfig.BusDriver loadBusDriver(FileConfiguration config, Logger logger) {
        Map<String, CasinoConfig.Machine> machines = loadMachines(config, "bus-driver.machines", logger);
        if (machines.isEmpty()) {
            machines = Map.of("bus-driver-1", defaultMachine("bus-driver-1", 2798, 73, 943));
        }

        return new CasinoConfig.BusDriver(
                machines,
                positiveDoubles(config.getDoubleList("bus-driver.multiplier-options"), List.of(1.0D, 2.0D, 5.0D, 10.0D)),
                Math.max(0.01D, config.getDouble("bus-driver.default-multiplier", 1.0D)),
                positiveDoubles(config.getDoubleList("bus-driver.round-payout-multipliers"), List.of(1.5D, 2.5D, 4.0D, 7.0D, 12.0D)),
                Math.max(1, config.getInt("bus-driver.result-subtitle-ticks", 40)),
                new CasinoConfig.ExitVelocity(
                        config.getBoolean("bus-driver.exit-velocity.enabled", true),
                        Math.max(0.0D, config.getDouble("bus-driver.exit-velocity.backwards-strength", 0.45D)),
                        config.getDouble("bus-driver.exit-velocity.y", 0.15D)
                ),
                loadBusDriverGui(config, logger)
        );
    }

    private CasinoConfig.BusDriverGui loadBusDriverGui(FileConfiguration config, Logger logger) {
        return new CasinoConfig.BusDriverGui(
                config.getString("bus-driver.gui.title", "&cBUS DRIVER"),
                normalizeGuiSize(config.getInt("bus-driver.gui.size", 54)),
                config.getInt("bus-driver.gui.card-slot", 22),
                config.getInt("bus-driver.gui.lower-slot", 30),
                config.getInt("bus-driver.gui.higher-slot", 32),
                config.getInt("bus-driver.gui.cashout-slot", 40),
                config.getInt("bus-driver.gui.multiplier-slot", 41),
                config.getInt("bus-driver.gui.balance-slot", 46),
                config.getInt("bus-driver.gui.exit-slot", 45),
                config.getInt("bus-driver.gui.info-slot", 53),
                config.isList("bus-driver.gui.progress-slots") ? config.getIntegerList("bus-driver.gui.progress-slots")
                        : List.of(11, 12, 13, 14, 15),
                guiItem(config, "bus-driver.gui.filler", Material.BLACK_STAINED_GLASS_PANE, "", List.of(), true, logger),
                guiItem(config, "bus-driver.gui.balance-item", Material.BUNDLE, "&aŚrodki: &f{balance_display}", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.start-item", Material.LIME_DYE, "&a&lROZPOCZNIJ",
                        List.of("&c&m--------------------", "&fKoszt wejścia: &a{total_cost}",
                                "&c&m--------------------", "&7Mnożnik: &f{multiplier}"), false, logger),
                guiItem(config, "bus-driver.gui.no-funds-item", Material.RED_DYE, "&cBrak środków",
                        List.of("&c&m--------------------", "&7Wymagane: &f{total_cost}$", "&7Twoje środki: &f{balance_display}"), false, logger),
                guiItem(config, "bus-driver.gui.lower-item", Material.RED_DYE, "&c&lNIŻEJ", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.higher-item", Material.LIME_DYE, "&a&lWYŻEJ", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.cashout-item", Material.GOLD_INGOT, "&e&lWYPŁAĆ",
                        List.of("&c&m--------------------", "&7Aktualna wygrana: &a{current_win}$"), false, logger),
                guiItem(config, "bus-driver.gui.cashout-unavailable-item", Material.GRAY_DYE, "&7Brak wygranej do wypłaty", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.multiplier-item", Material.WHITE_DYE, "&fUstawienia gry:",
                        List.of("&c&m--------------------", "&eLPM: &fZmień mnożnik",
                                "&c&m--------------------", "&7Mnożnik: &f{multiplier}"), false, logger),
                guiItem(config, "bus-driver.gui.exit-item", Material.BARRIER, "&cWyjście", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.info-item", Material.PAPER, "&fBus Driver",
                        List.of("&c&m--------------------", "&7Zgadnij czy kolejna karta będzie wyżej albo niżej.", "{round_payouts}"), false, logger),
                guiItem(config, "bus-driver.gui.progress-pending-item", Material.GRAY_STAINED_GLASS_PANE, "&7Runda {round}",
                        List.of("&7Wypłata: &f{x}x"), false, logger),
                guiItem(config, "bus-driver.gui.progress-complete-item", Material.LIME_STAINED_GLASS_PANE, "&aRunda {round}",
                        List.of("&7Wypłata: &a{x}x"), false, logger),
                config.getString("bus-driver.gui.info-item.round-line", "&eRunda {round}: &a{x}x")
        );
    }

    private CasinoConfig.Machine defaultMachine(String id, int x, int y, int z) {
        CasinoConfig.BlockLocation block = new CasinoConfig.BlockLocation(x, y, z);
        return new CasinoConfig.Machine(
                id,
                "world",
                Material.MAGENTA_GLAZED_TERRACOTTA,
                block,
                new CasinoConfig.PlayerLocation(x + 0.5D, y + 1.0D, z + 0.5D, 90.0F, 0.0F)
        );
    }

    private CasinoConfig.Gui loadGui(FileConfiguration config, Logger logger) {
        return new CasinoConfig.Gui(
                config.getString("gui.title", "&cJEDNORĘKI BANDYTA"),
                normalizeGuiSize(config.getInt("gui.size", 54)),
                config.isList("gui.reel-slots") ? config.getIntegerList("gui.reel-slots")
                        : List.of(3, 4, 5, 12, 13, 14, 21, 22, 23),
                config.getInt("gui.action-slot", 39),
                config.getInt("gui.bet-slot", 41),
                config.getInt("gui.balance-slot", 46),
                config.getInt("gui.exit-slot", 45),
                config.getInt("gui.info-slot", 53),
                guiItem(config, "gui.filler", Material.BLACK_STAINED_GLASS_PANE, "", List.of(), true, logger),
                guiItem(config, "gui.balance-item", Material.BUNDLE, "&aŚrodki: &f{balance_display}", List.of(), false, logger),
                guiItem(config, "gui.spin-available-item", Material.LIME_DYE, "&d&lZAKRĘĆ!",
                        List.of("&c&m--------------------", "&fAktualny koszt: &a{total_cost}",
                                "&c&m--------------------", "&7Mnożnik: &f{bet_per_line}", "&7Linie: &f{lines}"),
                        false, logger),
                guiItem(config, "gui.spin-unavailable-item", Material.RED_DYE, "&cBrak środków",
                        List.of("&c&m--------------------", "&7Wymagane: &f{total_cost}$", "&7Twoje środki: &f{balance_display}"),
                        false, logger),
                guiItem(config, "gui.rolling-item", Material.YELLOW_DYE, "&eZatrzymaj kolumnę", List.of(), false, logger),
                guiItem(config, "gui.bet-item", Material.WHITE_DYE, "&fUstawienia gry:",
                        List.of("&c&m--------------------", "&eLPM: &fZmień mnożnik", "&ePPM: &fZmień ilość linii",
                                "&c&m--------------------", "&7Mnożnik: &f{bet_per_line}", "&7Linie: &f{lines}"),
                        false, logger),
                guiItem(config, "gui.exit-item", Material.BARRIER, "&cWyjście", List.of(), false, logger),
                guiItem(config, "gui.highlight-item", Material.LIME_STAINED_GLASS_PANE, "&a&lWYGRANA LINIA", List.of(), true, logger),
                guiItem(config, "gui.info-item", Material.PAPER, "&fLegenda Wypłat",
                        List.of("&c&m--------------------", "&7Przy aktualnym mnożniku: &f{bet_per_line}", "{symbol_payouts}"),
                        false, logger),
                config.getString("gui.info-item.symbol-line", "&e{index}. {legend_name} &7(&a{payout}$&7)")
        );
    }

    private CasinoConfig.GuiItem guiItem(FileConfiguration config,
                                         String path,
                                         Material fallbackMaterial,
                                         String fallbackName,
                                         List<String> fallbackLore,
                                         boolean fallbackHideTooltip,
                                         Logger logger) {
        return new CasinoConfig.GuiItem(
                material(config.getString(path + ".material", fallbackMaterial.name()), fallbackMaterial, logger),
                config.getString(path + ".name", fallbackName),
                config.isList(path + ".lore") ? config.getStringList(path + ".lore") : fallbackLore,
                config.getBoolean(path + ".hide-tooltip", fallbackHideTooltip),
                config.getBoolean(path + ".hide-additional-tooltip", false)
        );
    }

    private List<CasinoConfig.WinningLine> loadWinningLines(FileConfiguration config) {
        List<CasinoConfig.WinningLine> lines = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("winning-lines");
        if (section == null) {
            return lines;
        }
        for (String id : section.getKeys(false)) {
            lines.add(new CasinoConfig.WinningLine(
                    id,
                    config.getString("winning-lines." + id + ".name", id),
                    config.getIntegerList("winning-lines." + id + ".slots")
            ));
        }
        return List.copyOf(lines);
    }

    private SymbolLoadResult loadSymbols(FileConfiguration config, Logger logger) {
        List<CasinoConfig.Symbol> symbols = new ArrayList<>();
        Map<String, CasinoConfig.Symbol> byId = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("symbols");
        if (section == null) {
            return new SymbolLoadResult(List.of(), Map.of());
        }
        for (String id : section.getKeys(false)) {
            String path = "symbols." + id + ".";
            CasinoConfig.Symbol symbol = new CasinoConfig.Symbol(
                    id,
                    material(config.getString(path + "material", "STONE"), Material.STONE, logger),
                    config.getString(path + "display-name", id),
                    config.getString(path + "legend-name", config.getString(path + "display-name", id)),
                    config.isList(path + "lore") ? config.getStringList(path + "lore") : List.of(),
                    Math.max(0.0D, config.getDouble(path + "multiplier", 1.0D)),
                    Math.max(0.0D, config.getDouble(path + "chance-weight", 1.0D)),
                    config.getString(path + "win-actionbar", ""),
                    sounds(config, path + "win-sounds", List.of()),
                    particleSetting(config, path + "win-particles", Particle.HAPPY_VILLAGER,
                            60, 255, 60, 1.0F, 10, 10, 0.45D, 0.35D, 0.45D, 0.02D, 1.0D, logger)
            );
            symbols.add(symbol);
            byId.put(id, symbol);
        }
        return new SymbolLoadResult(List.copyOf(symbols), Map.copyOf(byId));
    }

    private CasinoConfig.Messages loadMessages(FileConfiguration config) {
        return new CasinoConfig.Messages(
                config.getString("messages.prefix", "&8[&cHexCasino&8] "),
                config.getString("messages.reload-success", "&aPrzeładowano HexCasino."),
                config.getString("messages.reload-failed", "&cNie udało się przeładować HexCasino. Sprawdź konsolę."),
                config.getString("messages.no-permission", "&cNie masz uprawnień."),
                config.getString("messages.usage", "&7Użycie: &f/hexcasino reload"),
                config.getString("messages.machine-busy", "&cTa maszyna jest aktualnie zajęta."),
                config.getString("messages.already-playing", "&cJuż korzystasz z innej maszyny."),
                config.getString("messages.no-funds-actionbar", "&cNie masz wystarczających środków. Wymagane: &f{total_cost}$"),
                config.getString("messages.economy-unavailable-actionbar", "&cEkonomia jest chwilowo niedostępna."),
                config.getString("messages.spin-start-actionbar", "&aZakręcono za &f{total_cost}$&a."),
                config.getString("messages.lose-actionbar", "&cBrak wygranej. Spróbuj ponownie."),
                config.getString("messages.win-actionbar", "&aWygrana: &f{win}$ &8(&7{winning_lines}&8)"),
                config.getString("messages.win-subtitle", "&aWygrana: &f{win}$"),
                config.getString("messages.bet-changed-actionbar", "&aMnożnik: &f{bet_per_line} &8x &f{lines} linii &8= &a{total_cost}$")
        );
    }

    private CasinoConfig.Sounds loadSounds(FileConfiguration config) {
        return new CasinoConfig.Sounds(
                sounds(config, "sounds.open", List.of(new CasinoConfig.SoundSetting(true, "ENTITY_EXPERIENCE_ORB_PICKUP", 0.85F, 1.45F, 0))),
                sounds(config, "sounds.close", List.of(new CasinoConfig.SoundSetting(true, "ENTITY_ITEM_PICKUP", 0.65F, 0.8F, 0))),
                sounds(config, "sounds.no-funds", List.of(new CasinoConfig.SoundSetting(true, "ENTITY_VILLAGER_NO", 0.9F, 1.0F, 0))),
                sounds(config, "sounds.spin-start", List.of(new CasinoConfig.SoundSetting(true, "ENTITY_EXPERIENCE_ORB_PICKUP", 1.0F, 1.1F, 0))),
                sounds(config, "sounds.roll-tick", List.of(new CasinoConfig.SoundSetting(true, "BLOCK_NOTE_BLOCK_HAT", 0.55F, 1.2F, 0))),
                sounds(config, "sounds.column-stop", List.of(new CasinoConfig.SoundSetting(true, "BLOCK_NOTE_BLOCK_PLING", 0.8F, 1.4F, 0))),
                sounds(config, "sounds.lose", List.of(new CasinoConfig.SoundSetting(true, "BLOCK_NOTE_BLOCK_BASS", 0.8F, 0.7F, 0))),
                sounds(config, "sounds.win-small", List.of(new CasinoConfig.SoundSetting(true, "ENTITY_PLAYER_LEVELUP", 0.9F, 1.1F, 0))),
                sounds(config, "sounds.win-big", List.of(new CasinoConfig.SoundSetting(true, "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 1.0F, 0)))
        );
    }

    private CasinoConfig.ParticleSetting particleSetting(FileConfiguration config,
                                                         String path,
                                                         Particle fallbackParticle,
                                                         int fallbackRed,
                                                         int fallbackGreen,
                                                         int fallbackBlue,
                                                         float fallbackSize,
                                                         int fallbackInterval,
                                                         int fallbackCount,
                                                         double fallbackOffsetX,
                                                         double fallbackOffsetY,
                                                         double fallbackOffsetZ,
                                                         double fallbackSpeed,
                                                         double fallbackYOffset,
                                                         Logger logger) {
        return new CasinoConfig.ParticleSetting(
                config.getBoolean(path + ".enabled", true),
                particle(config.getString(path + ".particle", fallbackParticle.name()), fallbackParticle, logger),
                clampColor(config.getInt(path + ".red", fallbackRed)),
                clampColor(config.getInt(path + ".green", fallbackGreen)),
                clampColor(config.getInt(path + ".blue", fallbackBlue)),
                Math.max(0.01F, (float) config.getDouble(path + ".size", fallbackSize)),
                Math.max(1, config.getInt(path + ".interval-ticks", fallbackInterval)),
                Math.max(0, config.getInt(path + ".count", fallbackCount)),
                Math.max(0.0D, config.getDouble(path + ".offset-x", fallbackOffsetX)),
                Math.max(0.0D, config.getDouble(path + ".offset-y", fallbackOffsetY)),
                Math.max(0.0D, config.getDouble(path + ".offset-z", fallbackOffsetZ)),
                Math.max(0.0D, config.getDouble(path + ".speed", fallbackSpeed)),
                config.getDouble(path + ".y-offset", fallbackYOffset)
        );
    }

    private List<CasinoConfig.SoundSetting> sounds(FileConfiguration config,
                                                   String path,
                                                   List<CasinoConfig.SoundSetting> fallback) {
        CasinoConfig.SoundSetting first = fallback.isEmpty()
                ? new CasinoConfig.SoundSetting(true, "ENTITY_EXPERIENCE_ORB_PICKUP", 0.8F, 1.0F, 0)
                : fallback.getFirst();
        if (config.isList(path)) {
            List<CasinoConfig.SoundSetting> out = new ArrayList<>();
            for (Map<?, ?> entry : config.getMapList(path)) {
                out.add(new CasinoConfig.SoundSetting(
                        bool(entry.get("enabled"), true),
                        string(entry.get("name"), first.name()),
                        decimal(entry.get("volume"), first.volume()),
                        decimal(entry.get("pitch"), first.pitch()),
                        Math.max(0, integer(entry.get("delay-ticks"), 0))
                ));
            }
            return out.isEmpty() ? fallback : List.copyOf(out);
        }
        if (config.isConfigurationSection(path)) {
            return List.of(new CasinoConfig.SoundSetting(
                    config.getBoolean(path + ".enabled", first.enabled()),
                    config.getString(path + ".name", first.name()),
                    (float) config.getDouble(path + ".volume", first.volume()),
                    (float) config.getDouble(path + ".pitch", first.pitch()),
                    Math.max(0, config.getInt(path + ".delay-ticks", first.delayTicks()))
            ));
        }
        return fallback;
    }

    private boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private float decimal(Object value, float fallback) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return value == null ? fallback : Float.parseFloat(value.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private double clampPercent(double value) {
        return Math.max(0.0D, Math.min(100.0D, value));
    }

    private Material material(String raw, Material fallback, Logger logger) {
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            logger.warning("Unknown material '" + raw + "', using " + fallback.name());
            return fallback;
        }
    }

    private Particle particle(String raw, Particle fallback, Logger logger) {
        try {
            return Particle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            logger.warning("Unknown particle '" + raw + "', using " + fallback.name());
            return fallback;
        }
    }

    private int normalizeGuiSize(int size) {
        if (size < 9) {
            return 9;
        }
        if (size > 54) {
            return 54;
        }
        return ((size + 8) / 9) * 9;
    }

    private List<Double> positiveDoubles(List<Double> values, List<Double> fallback) {
        List<Double> out = values.stream()
                .filter(value -> value != null && value > 0.0D)
                .toList();
        return out.isEmpty() ? fallback : List.copyOf(out);
    }

    private List<Integer> positiveInts(List<Integer> values, List<Integer> fallback) {
        List<Integer> out = values.stream()
                .filter(value -> value != null && value > 0)
                .toList();
        return out.isEmpty() ? fallback : List.copyOf(out);
    }

    private record SymbolLoadResult(List<CasinoConfig.Symbol> symbols, Map<String, CasinoConfig.Symbol> symbolsById) {
    }

    private record WheelSegmentLoadResult(List<CasinoConfig.WheelSegment> segments,
                                          Map<String, CasinoConfig.WheelSegment> segmentsById) {
    }
}
