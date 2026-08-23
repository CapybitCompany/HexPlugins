package hexcasino.config;

import hexcasino.machine.BusDriverEngine;
import hexcasino.machine.SlotLayout;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class CasinoConfigLoader {

    private final BusDriverEngine busDriverEngine = new BusDriverEngine();

    public CasinoConfig load(FileConfiguration config, Logger logger) {
        Map<String, CasinoConfig.Machine> machines = loadMachines(config, logger);
        CasinoConfig.ParticleSetting idleParticles = particleSetting(config, "idle-particles", Particle.DUST,
                60, 255, 60, 1.0F, 10, 3, 0.25D, 0.25D, 0.25D, 0.06D, 1.4D, logger);
        CasinoConfig.ParticleSetting occupiedParticles = particleSetting(config, "occupied-particles", Particle.DUST,
                255, 35, 35, 1.15F, 10, 3, 0.25D, 0.25D, 0.25D, 0.06D, 1.4D, logger);

        List<Integer> reelOptions = config.isList("slot-machine.reel-options")
                ? List.copyOf(config.getIntegerList("slot-machine.reel-options"))
                : (config.isList("slot-machine.line-options")
                ? List.copyOf(config.getIntegerList("slot-machine.line-options"))
                : List.of(1, 3, 5));
        List<Double> baseBetOptions = config.isList("slot-machine.base-bet-options")
                ? positiveDoubles(config.getDoubleList("slot-machine.base-bet-options"), List.of(1.0D, 10.0D, 20.0D, 50.0D, 100.0D))
                : positiveDoubles(config.getDoubleList("slot-machine.bet-per-line-options"), List.of(1.0D, 10.0D, 20.0D, 50.0D, 100.0D));
        double defaultBaseBet = config.contains("slot-machine.default-base-bet")
                ? config.getDouble("slot-machine.default-base-bet", 1.0D)
                : config.getDouble("slot-machine.default-bet-per-line", 1.0D);
        int defaultReels = config.contains("slot-machine.default-reels")
                ? config.getInt("slot-machine.default-reels", 5)
                : config.getInt("slot-machine.default-lines", 5);

        CasinoConfig.SlotMachine slotMachine = new CasinoConfig.SlotMachine(
                baseBetOptions,
                reelOptions,
                Math.max(0.01D, defaultBaseBet),
                defaultReels,
                config.getInt("slot-machine.rows", 3),
                Math.max(1, config.getInt("slot-machine.roll-tick-interval", 5)),
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
                )
        );

        CasinoConfig.WheelOfFortune wheelOfFortune = loadWheelOfFortune(config, logger);
        CasinoConfig.BusDriver busDriver = loadBusDriver(config, logger);

        CasinoConfig.Economy economy = new CasinoConfig.Economy(
                config.getString("economy.balance-placeholder", "%hexeconomy_balance%"),
                config.getString("economy.remove-command", "hexeconomy remove {player} {amount}"),
                config.getString("economy.add-command", "hexeconomy add {player} {amount}")
        );

        CasinoConfig.Gui gui = migrateLegacySlotGui(loadGui(config, logger), logger);
        List<String> initialSymbols = normalizeInitialSymbols(config.getStringList("initial-symbols"), 15, logger);
        if (config.getConfigurationSection("winning-lines") != null) {
            logger.warning("slot-machine winning-lines is deprecated and ignored; winning patterns are generated from board geometry.");
        }
        SymbolLoadResult symbols = loadSymbols(config, logger);
        CasinoConfig.Messages messages = loadMessages(config);
        CasinoConfig.Sounds sounds = loadSounds(config);

        if (machines.isEmpty()) {
            throw new IllegalArgumentException("machines cannot be empty");
        }
        validateSlotMachine(slotMachine, gui);
        if (symbols.symbols().isEmpty()) {
            throw new IllegalArgumentException("symbols cannot be empty");
        }
        if (initialSymbols.size() != 15) {
            throw new IllegalArgumentException("initial-symbols must contain exactly 15 symbol ids for the maximum 5x3 layout");
        }
        for (String symbolId : initialSymbols) {
            if (!symbols.symbolsById().containsKey(symbolId)) {
                throw new IllegalArgumentException("initial-symbols contains unknown symbol id: " + symbolId);
            }
        }
        validateSymbols(symbols.symbols());

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
                List.of(),
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
                    (float) config.getDouble(path + "player-location.yaw", 180.0D),
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
        List<Double> betOptions = positiveDoubles(
                config.getDoubleList("bus-driver.bet-options"),
                List.of(1.0D, 10.0D, 20.0D, 50.0D, 100.0D));
        double defaultBet = Math.max(0.01D, config.getDouble("bus-driver.default-bet", 20.0D));
        ensureOptionExists("bus-driver.default-bet", defaultBet, betOptions);

        List<Double> payoutLadder = loadBusDriverPayoutLadder(config, logger);
        validateBusDriverLadder(payoutLadder);
        double rtp = busDriverEngine.expectedOptimalRtp(payoutLadder);
        if (rtp >= 1.0D) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "Invalid BusDriver payout ladder: optimal RTP %.4f%% must be below 100%%",
                    rtp * 100.0D));
        }
        if (rtp > 0.95D) {
            logger.warning(String.format(Locale.US,
                    "BusDriver optimal RTP is high: %.4f%%", rtp * 100.0D));
        } else if (rtp < 0.80D || rtp > 0.85D) {
            logger.warning(String.format(Locale.US,
                    "BusDriver optimal RTP %.4f%% is outside the recommended 80-85%% balance band.",
                    rtp * 100.0D));
        }

        CasinoConfig.BusDriverGui gui = loadBusDriverGui(config, logger);
        validateBusDriverGui(gui);

        return new CasinoConfig.BusDriver(
                machines,
                betOptions,
                defaultBet,
                payoutLadder,
                Math.max(1, config.getInt("bus-driver.result-subtitle-ticks", 40)),
                Math.max(1.0D, config.getDouble("bus-driver.max-distance", 8.0D)),
                new CasinoConfig.ExitVelocity(
                        config.getBoolean("bus-driver.exit-velocity.enabled", true),
                        Math.max(0.0D, config.getDouble("bus-driver.exit-velocity.backwards-strength", 0.45D)),
                        config.getDouble("bus-driver.exit-velocity.y", 0.15D)
                ),
                gui
        );
    }

    private void ensureOptionExists(String name, double value, List<Double> options) {
        for (double option : options) {
            if (Math.abs(option - value) < 0.0001D) {
                return;
            }
        }
        throw new IllegalArgumentException(name + " must be present in its option list");
    }

    /**
     * Bus Driver now has exactly one game variant: all four stages.
     * New configs use bus-driver.payout-ladder. For backward compatibility an old x11/full
     * ladder is migrated in-memory; shorter x2/x3 ladders can no longer select a shorter game.
     */
    private List<Double> loadBusDriverPayoutLadder(FileConfiguration config, Logger logger) {
        List<Double> configured = config.getDoubleList("bus-driver.payout-ladder");
        if (!configured.isEmpty()) {
            return List.copyOf(configured);
        }

        ConfigurationSection legacy = config.getConfigurationSection("bus-driver.payout-ladders");
        if (legacy != null) {
            List<Double> preferred = config.getDoubleList("bus-driver.payout-ladders.x11");
            if (preferred.size() == 4) {
                logger.warning("Deprecated BusDriver payout-ladders/target-multiplier options detected; "
                        + "using the four-stage x11 ladder as the single game variant.");
                return List.copyOf(preferred);
            }

            List<Double> selected = null;
            double selectedFinal = Double.NEGATIVE_INFINITY;
            for (String key : legacy.getKeys(false)) {
                List<Double> candidate = config.getDoubleList("bus-driver.payout-ladders." + key);
                if (candidate.size() == 4) {
                    double finalPayout = candidate.get(3);
                    if (selected == null || finalPayout > selectedFinal) {
                        selected = List.copyOf(candidate);
                        selectedFinal = finalPayout;
                    }
                }
            }
            if (selected != null) {
                logger.warning("Deprecated BusDriver payout-ladders/target-multiplier options detected; "
                        + "using the available four-stage ladder as the single game variant.");
                return selected;
            }
        }

        logger.warning("BusDriver payout-ladder missing; using default four-stage ladder [1.4, 2.2, 2.7, 11].");
        return List.of(1.4D, 2.2D, 2.7D, 11.0D);
    }

    private void validateBusDriverLadder(List<Double> payouts) {
        if (payouts.size() != 4) {
            throw new IllegalArgumentException("BusDriver payout-ladder must contain exactly 4 rounds");
        }
        double previous = 0.0D;
        for (int index = 0; index < payouts.size(); index++) {
            double payout = payouts.get(index);
            if (!Double.isFinite(payout) || payout <= 0.0D) {
                throw new IllegalArgumentException("BusDriver payout-ladder contains invalid payout at round "
                        + (index + 1));
            }
            if (index > 0 && payout <= previous) {
                throw new IllegalArgumentException("BusDriver payout-ladder must be strictly increasing");
            }
            previous = payout;
        }
        double finalPayout = payouts.get(3);
        if (payouts.get(2) > (0.25D * finalPayout) + 0.0001D) {
            throw new IllegalArgumentException("BusDriver payout-ladder has a dominated final suit round: cashout "
                    + payouts.get(2) + " is greater than final-round EV " + (0.25D * finalPayout));
        }
    }

    private void validateBusDriverGui(CasinoConfig.BusDriverGui gui) {
        validateGuiSlot("bus-driver.gui.card-slot", gui.cardSlot(), gui.size());
        validateGuiSlot("bus-driver.gui.cashout-slot", gui.cashoutSlot(), gui.size());
        validateGuiSlot("bus-driver.gui.multiplier-slot", gui.multiplierSlot(), gui.size());
        validateGuiSlot("bus-driver.gui.balance-slot", gui.balanceSlot(), gui.size());
        validateGuiSlot("bus-driver.gui.exit-slot", gui.exitSlot(), gui.size());
        validateGuiSlot("bus-driver.gui.info-slot", gui.infoSlot(), gui.size());
        if (gui.suitSlots().size() != 4 || new HashSet<>(gui.suitSlots()).size() != 4) {
            throw new IllegalArgumentException("bus-driver.gui.suit-slots must contain exactly 4 unique slots");
        }
        for (int slot : gui.suitSlots()) {
            if (slot < 0 || slot >= gui.size()) {
                throw new IllegalArgumentException("bus-driver.gui.suit-slots contains out-of-range slot: " + slot);
            }
        }
        if (gui.rankSlots().size() != 13 || new HashSet<>(gui.rankSlots()).size() != 13) {
            throw new IllegalArgumentException("bus-driver.gui.rank-slots must contain exactly 13 unique slots");
        }
        for (int slot : gui.rankSlots()) {
            if (slot < 0 || slot >= gui.size()) {
                throw new IllegalArgumentException("bus-driver.gui.rank-slots contains out-of-range slot: " + slot);
            }
        }
    }

    private CasinoConfig.BusDriverGui loadBusDriverGui(FileConfiguration config, Logger logger) {
        return new CasinoConfig.BusDriverGui(
                config.getString("bus-driver.gui.title", "&cBUS DRIVER — DEDUKCJA"),
                normalizeGuiSize(config.getInt("bus-driver.gui.size", 54)),
                config.getInt("bus-driver.gui.card-slot", 22),
                config.getInt("bus-driver.gui.lower-slot", 12),
                config.getInt("bus-driver.gui.higher-slot", 14),
                config.getInt("bus-driver.gui.cashout-slot", 40),
                config.getInt("bus-driver.gui.multiplier-slot", 41),
                config.getInt("bus-driver.gui.balance-slot", 46),
                config.getInt("bus-driver.gui.exit-slot", 45),
                config.getInt("bus-driver.gui.info-slot", 53),
                config.isList("bus-driver.gui.suit-slots") ? List.copyOf(config.getIntegerList("bus-driver.gui.suit-slots"))
                        : List.of(10, 12, 14, 16),
                config.isList("bus-driver.gui.rank-slots") ? List.copyOf(config.getIntegerList("bus-driver.gui.rank-slots"))
                        : List.of(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21),
                guiItem(config, "bus-driver.gui.filler", Material.BLACK_STAINED_GLASS_PANE, "", List.of(), true, logger),
                guiItem(config, "bus-driver.gui.balance-item", Material.BUNDLE, "&aŚrodki: &f{balance_display}", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.start-item", Material.LIME_DYE, "&a&lROZPOCZNIJ PRÓBĘ",
                        List.of("&7Gra dedukcyjna — wszystkie informacje są na planszy.",
                                "&7Aktualna plansza: &f#{board} / {board_count}",
                                "&7Koszt: &a{total_cost}$",
                                "&8Nic nie jest losowane w trakcie gry."), false, logger),
                guiItem(config, "bus-driver.gui.no-funds-item", Material.RED_DYE, "&cBrak środków",
                        List.of("&7Koszt próby: &f{total_cost}$", "&7Twoje środki: &f{balance_display}"), false, logger),
                guiItem(config, "bus-driver.gui.red-item", Material.RED_BUNDLE, "&c&lCZERWONA", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.black-item", Material.BLACK_BUNDLE, "&8&lCZARNA", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.lower-item", Material.PLAYER_HEAD, "&c&lNIZEJ", List.of(), false,
                        "9335", null, "a3852bf616f31ed67c37de4b0baa2c5f8d8fca82e72dbcafcba66956a81c4", logger),
                guiItem(config, "bus-driver.gui.higher-item", Material.PLAYER_HEAD, "&a&lWYZEJ", List.of(), false,
                        "10192", null, "5da027477197c6fd7ad33014546de392b4a51c634ea68c8b7bcc0131c83e3f", logger),
                guiItem(config, "bus-driver.gui.between-item", Material.ENDER_EYE, "&b&lPOMIEDZY", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.outside-item", Material.ENDER_PEARL, "&5&lPOZA", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.hearts-item", Material.PLAYER_HEAD, "&c&lKIER", List.of(), false,
                        "67606", null, "a64ddfe9a5319cfe81ce856ca2fb3b8495d735d8337b010b695b462e6ddf6999", logger),
                guiItem(config, "bus-driver.gui.diamonds-item", Material.PLAYER_HEAD, "&d&lKARO", List.of(), false,
                        "67607", null, "3f237bdc9fc3e54466b62f544ef39c190b84fecd36103f5946f844a9a0828063", logger),
                guiItem(config, "bus-driver.gui.clubs-item", Material.PLAYER_HEAD, "&8&lTREFL", List.of(), false,
                        "67605", null, "473a0503b96d052c12680e71faefed703b7333373defa80854598b2777b4f28b", logger),
                guiItem(config, "bus-driver.gui.spades-item", Material.PLAYER_HEAD, "&8&lPIK", List.of(), false,
                        "67608", null, "8f89f34902a3e7816feda65d7c5686a2e42b0cc4f284d0e45e8e6da5f77846dc", logger),
                guiItem(config, "bus-driver.gui.cashout-item", Material.GOLD_INGOT, "&e&lWYPŁAĆ",
                        List.of("&c&m--------------------", "&7Aktualna wygrana: &a{current_win}$"), false, logger),
                guiItem(config, "bus-driver.gui.cashout-unavailable-item", Material.GRAY_DYE, "&7Brak wygranej do wypłaty", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.multiplier-item", Material.WHITE_DYE, "&fWariant gry",
                        List.of("&ePPM: &fZmień koszt", "&7Koszt: &f{total_cost}$", "&7Tier: &f{tier}",
                                "&7Czas na etap: &f{decision_time_ms} ms"), false, logger),
                guiItem(config, "bus-driver.gui.exit-item", Material.BARRIER, "&cWyjście", List.of(), false, logger),
                guiItem(config, "bus-driver.gui.active-exit-item", Material.BARRIER, "&cPrzerwij próbę",
                        List.of("&7Przerwanie zużywa planszę i kończy próbę bez nagrody."), false, logger),
                guiItem(config, "bus-driver.gui.multiplier-locked-item", Material.BLACK_STAINED_GLASS_PANE, "",
                        List.of(), true, logger),
                guiItem(config, "bus-driver.gui.hint-item", Material.PAPER, "&fPodpowiedź",
                        List.of("&7{hint}"), false, logger),
                guiItem(config, "bus-driver.gui.rank-item", Material.PAPER, "&f&l{rank}",
                        List.of("&7Kliknij, aby wybrać tę rangę."), false, logger),
                guiItem(config, "bus-driver.gui.stage-item", Material.CLOCK, "&fPróba dedukcji",
                        List.of("&7Plansza: &f#{board} / {board_count}",
                                "&7Etap: &f{stage} / {stage_count}",
                                "&7Typ: &f{stage_type}",
                                "{timer_line}"), false, logger),
                guiItem(config, "bus-driver.gui.info-item", Material.BOOK, "&fBusDriver — Poradnik",
                        List.of("&7Plansza: &f#{board} / {board_count}", "", "&eJak grać?",
                                "&7Każda próba ma &f4 etapy&7.",
                                "&7Na każdy etap masz &f5 sekund&7.",
                                "&7Najedź na &f3 podpowiedzi&7 ustawione obok siebie",
                                "&7i wywnioskuj jedyną poprawną odpowiedź.",
                                "&7Etap może dotyczyć &fkoloru&7 albo &frangi karty&7.",
                                "&7Po poprawnej odpowiedzi przechodzisz dalej.",
                                "&7Gdy masz nagrodę, możesz użyć &eWYPŁAĆ&7 i zakończyć próbę.",
                                "&cBłędna odpowiedź lub koniec czasu kończy próbę bez nagrody.", "",
                                "&8Plansze i podpowiedzi są stałe — wynik zależy od dedukcji i czasu."),
                        false, logger),
                config.getString("bus-driver.gui.info-item.round-line", "&e{round}. runda: &a{x}$")
        );
    }

    private CasinoConfig.Machine defaultMachine(String id, int x, int y, int z) {
        CasinoConfig.BlockLocation block = new CasinoConfig.BlockLocation(x, y, z);
        return new CasinoConfig.Machine(
                id,
                "world",
                Material.MAGENTA_GLAZED_TERRACOTTA,
                block,
                new CasinoConfig.PlayerLocation(x + 0.5D, y + 1.0D, z + 0.5D, 180.0F, 0.0F)
        );
    }

    private CasinoConfig.Gui loadGui(FileConfiguration config, Logger logger) {
        List<Integer> gridSlots = loadReelGridSlots(config);
        return new CasinoConfig.Gui(
                config.getString("gui.title", "&6REEL CHALLENGE"),
                normalizeGuiSize(config.getInt("gui.size", 54)),
                gridSlots,
                config.getInt("gui.action-slot", 39),
                config.getInt("gui.bet-slot", 41),
                config.getInt("gui.difficulty-slot", 40),
                config.getInt("gui.stop-line-left-slot", 10),
                config.getInt("gui.stop-line-right-slot", 16),
                config.getInt("gui.balance-slot", 46),
                config.getInt("gui.exit-slot", 45),
                config.getInt("gui.info-slot", 53),
                loadReelPreviewGui(config, logger),
                guiItem(config, "gui.filler", Material.BLACK_STAINED_GLASS_PANE, "", List.of(), true, logger),
                guiItem(config, "gui.balance-item", Material.BUNDLE, "&aŚrodki: &f{balance_display}$", List.of(), false, logger),
                guiItem(config, "gui.spin-available-item", Material.LIME_DYE, "&a&lSTART PRÓBY",
                        List.of("&7Koszt próby: &f{total_cost}$", "&7Tempo: &f{frame_ms} ms", "&7Zestaw: &f#{next_set}",
                                "", "&7Stała sekwencja — brak losowania."), false, logger),
                guiItem(config, "gui.spin-unavailable-item", Material.RED_DYE, "&cBrak środków",
                        List.of("&7Koszt próby: &f{total_cost}$"), false, logger),
                guiItem(config, "gui.rolling-item", Material.YELLOW_DYE, "&eSTOP — bęben {current_reel}/{reel_count}",
                        List.of("&7Aktywny bęben jest podświetlony.", "&7Kliknięcie zatrzymuje dokładnie widzianą rewizję."), false, logger),
                guiItem(config, "gui.bet-item", Material.WHITE_DYE, "&fUkład: &e{layout}",
                        List.of("&eKliknij: &fzmień układ", "", "&7Wygrane: poziom, pion i każdy poprawny skos."), false, logger),
                guiItem(config, "gui.difficulty-item", Material.CLOCK, "&fWariant wejściowy: &e{total_cost}$",
                        List.of("&eKliknij: &fzmień wariant", "", "&7Koszt próby: &f{total_cost}$",
                                "&7Tempo: &f{frame_ms} ms / pozycję"), false, logger),
                guiItem(config, "gui.reward-mode-unavailable-item", Material.GRAY_DYE, "&cTryb nagród niedostępny",
                        List.of("&7Wymagany jest aktywny PacketEvents/stateId resolver."), false, logger),
                guiItem(config, "gui.daily-limit-item", Material.RED_DYE, "&eDzienny limit nagród Arcade",
                        List.of("&7Dzisiaj: &f{daily_rewards}$", "&7Próg kolejnej próby: &f{daily_threshold}$",
                                "&7Kolejna płatna próba jutro."), false, logger),
                guiItem(config, "gui.stop-line-left-item", Material.ARROW, "&eLINIA STOP &f→",
                        List.of("&7Aktywny bęben jest dodatkowo podświetlony."), false, logger),
                guiItem(config, "gui.stop-line-right-item", Material.ARROW, "&f← &eLINIA STOP",
                        List.of("&7Zatrzymujesz dokładnie widoczną pozycję."), false, logger),
                guiItem(config, "gui.exit-item", Material.BARRIER, "&cWyjście", List.of(), false, logger),
                guiItem(config, "gui.highlight-item", Material.LIME_STAINED_GLASS_PANE, "&a&lNAGRODA", List.of(), true, logger),
                guiItem(config, "gui.info-item", Material.PLAYER_HEAD, "&fNagrody za trafienie",
                        List.of("&7Pion, poziom i każdy poprawny skos liczą się.",
                                "&7Wiele trafień sumuje się bez limitu jednej próby.", "", "{symbol_payouts}"),
                        false, "8767", null, "46ba63344f49dd1c4f5488e926bf3d9e2b29916a6c50d610bb40a5273dc8c82", logger),
                config.getString("gui.info-item.symbol-line", "&e{legend_name_plain} &7x{reward_multiplier} &8(&a{payout}$&8)")
        );
    }

    private CasinoConfig.ReelPreviewGui loadReelPreviewGui(FileConfiguration config, Logger logger) {
        return new CasinoConfig.ReelPreviewGui(
                config.getString("gui.preview.title", "&6ZESTAW #{set} &8| &fBęben {reel}"),
                normalizeGuiSize(config.getInt("gui.preview.size", 54)),
                config.getInt("gui.preview.back-slot", 45),
                config.getInt("gui.preview.previous-reel-slot", 47),
                config.getInt("gui.preview.next-reel-slot", 48),
                config.getInt("gui.preview.previous-page-slot", 50),
                config.getInt("gui.preview.next-page-slot", 51),
                guiItem(config, "gui.preview.filler", Material.BLACK_STAINED_GLASS_PANE, "", List.of(), true, logger),
                guiItem(config, "gui.preview.back-item", Material.BARRIER, "&cPowrót", List.of(), false, logger),
                guiItem(config, "gui.preview.previous-reel-item", Material.ARROW, "&ePoprzedni bęben", List.of(), false, logger),
                guiItem(config, "gui.preview.next-reel-item", Material.ARROW, "&eNastępny bęben", List.of(), false, logger),
                guiItem(config, "gui.preview.previous-page-item", Material.PAPER, "&eStrona 1", List.of("&7Pozycje 0–44"), false, logger),
                guiItem(config, "gui.preview.next-page-item", Material.PAPER, "&eStrona 2", List.of("&7Pozycje 45–85"), false, logger)
        );
    }

    private List<Integer> loadReelGridSlots(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("gui.reel-grid-slots");
        if (section != null) {
            List<Integer> top = section.getIntegerList("top");
            List<Integer> middle = section.getIntegerList("middle");
            List<Integer> bottom = section.getIntegerList("bottom");
            if (!top.isEmpty() || !middle.isEmpty() || !bottom.isEmpty()) {
                List<Integer> combined = new ArrayList<>(15);
                combined.addAll(top);
                combined.addAll(middle);
                combined.addAll(bottom);
                return List.copyOf(combined);
            }
        }
        if (config.isList("gui.reel-grid-slots")) {
            return List.copyOf(config.getIntegerList("gui.reel-grid-slots"));
        }
        if (config.isList("gui.reel-slots")) {
            return List.copyOf(config.getIntegerList("gui.reel-slots"));
        }
        return List.of(2, 3, 4, 5, 6, 11, 12, 13, 14, 15, 20, 21, 22, 23, 24);
    }

    private CasinoConfig.Gui migrateLegacySlotGui(CasinoConfig.Gui gui, Logger logger) {
        List<Integer> slots = gui.reelGridSlots();
        List<Integer> migrated = null;

        if (slots.size() == 9) {
            migrated = expandThreeByThreeToFiveByThree(slots);
            logger.warning("Migrating legacy 3x3 HexCasino reel slots to centered 5x3 reel grid.");
        } else if (looksLikeLegacyFiveRowsByThreeColumns(slots)) {
            migrated = expandThreeByThreeToFiveByThree(slots.subList(0, 9));
            logger.warning("Migrating legacy 3-column x 5-row HexCasino layout to 5 reels x 3 rows.");
        }

        List<Integer> effectiveSlots = migrated == null ? slots : migrated;
        CasinoConfig.GuiItem spinAvailable = migrateLegacySpinAvailableItem(gui.spinAvailableItem());
        CasinoConfig.GuiItem rolling = migrateLegacyRollingItem(gui.rollingItem());
        CasinoConfig.GuiItem bet = migrateLegacyBetItem(gui.betItem());
        CasinoConfig.GuiItem info = migrateLegacyInfoItem(gui.infoItem());

        boolean textMigrated = spinAvailable != gui.spinAvailableItem()
                || rolling != gui.rollingItem()
                || bet != gui.betItem()
                || info != gui.infoItem();
        if (textMigrated) {
            logger.warning("Migrating legacy slot GUI wording from lines/multiplier to layout/base bet semantics.");
        }
        if (migrated == null && !textMigrated) {
            return gui;
        }

        return new CasinoConfig.Gui(
                gui.title(), gui.size(), effectiveSlots,
                gui.actionSlot(), gui.betSlot(), gui.difficultySlot(), gui.stopLineLeftSlot(), gui.stopLineRightSlot(),
                gui.balanceSlot(), gui.exitSlot(), gui.infoSlot(), gui.preview(),
                gui.filler(), gui.balanceItem(), spinAvailable, gui.spinUnavailableItem(),
                rolling, bet, gui.difficultyItem(), gui.rewardModeUnavailableItem(), gui.dailyLimitItem(),
                gui.stopLineLeftItem(), gui.stopLineRightItem(), gui.exitItem(), gui.highlightItem(), info,
                gui.infoSymbolLine()
        );
    }

    private CasinoConfig.GuiItem migrateLegacySpinAvailableItem(CasinoConfig.GuiItem item) {
        List<String> lore = replaceLegacySlotLore(item.lore());
        return lore.equals(item.lore()) ? item : copyGuiItem(item, item.name(), lore);
    }

    private CasinoConfig.GuiItem migrateLegacyRollingItem(CasinoConfig.GuiItem item) {
        if (!"&eZatrzymaj kolumnę".equals(item.name())) {
            return item;
        }
        return copyGuiItem(item, "&eZatrzymaj bęben", item.lore());
    }

    private CasinoConfig.GuiItem migrateLegacyBetItem(CasinoConfig.GuiItem item) {
        List<String> lore = replaceLegacySlotLore(item.lore());
        List<String> migrated = new ArrayList<>(lore.size());
        boolean changed = false;
        for (String line : lore) {
            String replacement = line
                    .replace("&eLPM: &fZmień mnożnik", "&eLPM: &fZmień stawkę bazową")
                    .replace("&ePPM: &fZmień ilość linii", "&ePPM: &fZmień układ");
            migrated.add(replacement);
            changed |= !replacement.equals(line);
        }
        return changed || !lore.equals(item.lore())
                ? copyGuiItem(item, item.name(), List.copyOf(migrated))
                : item;
    }

    private CasinoConfig.GuiItem migrateLegacyInfoItem(CasinoConfig.GuiItem item) {
        boolean legacyDefault = item.lore().stream()
                .anyMatch(line -> line.contains("Przy aktualnym mnożniku") && line.contains("{bet_per_line}"));
        if (legacyDefault) {
            return copyGuiItem(item, item.name(), List.of(
                    "&c&m--------------------",
                    "&7Pion, poziom i każdy skos wygrywa.",
                    "&7Wiele trafień sumuje się.",
                    "&c&m--------------------",
                    "{symbol_payouts}"
            ));
        }

        List<String> migrated = item.lore().stream()
                .filter(line -> !line.contains("{layout}"))
                .filter(line -> !line.contains("{total_cost}"))
                .filter(line -> !line.contains("{pattern_count}"))
                .toList();
        return migrated.equals(item.lore()) ? item : copyGuiItem(item, item.name(), migrated);
    }

    private List<String> replaceLegacySlotLore(List<String> lore) {
        List<String> migrated = new ArrayList<>(lore.size());
        for (String line : lore) {
            migrated.add(line
                    .replace("&7Mnożnik: &f{bet_per_line}", "&7Stawka bazowa: &f{base_bet}$")
                    .replace("&7Linie: &f{lines}", "&7Układ: &f{layout}"));
        }
        return List.copyOf(migrated);
    }

    private CasinoConfig.GuiItem copyGuiItem(CasinoConfig.GuiItem source, String name, List<String> lore) {
        return new CasinoConfig.GuiItem(
                source.material(), name, lore, source.hideTooltip(), source.hideAdditionalTooltip(),
                source.headId(), source.headOwner(), source.headTexture()
        );
    }

    private List<Integer> expandThreeByThreeToFiveByThree(List<Integer> slots) {
        if (slots.size() < 9) {
            return List.copyOf(slots);
        }
        List<Integer> out = new ArrayList<>(15);
        for (int row = 0; row < 3; row++) {
            int base = row * 3;
            int left = slots.get(base);
            int middle = slots.get(base + 1);
            int right = slots.get(base + 2);
            out.add(left - 1);
            out.add(left);
            out.add(middle);
            out.add(right);
            out.add(right + 1);
        }
        return List.copyOf(out);
    }

    private boolean looksLikeLegacyFiveRowsByThreeColumns(List<Integer> slots) {
        if (slots.size() != 15) {
            return false;
        }
        for (int row = 0; row < 5; row++) {
            int base = row * 3;
            if (slots.get(base + 1) != slots.get(base) + 1 || slots.get(base + 2) != slots.get(base) + 2) {
                return false;
            }
            if (row > 0 && slots.get(base) != slots.get(base - 3) + 9) {
                return false;
            }
        }
        return true;
    }

    private List<String> normalizeInitialSymbols(List<String> configured, int expectedCount, Logger logger) {
        if (configured.size() == expectedCount) {
            return List.copyOf(configured);
        }
        if (configured.isEmpty()) {
            return configured;
        }
        List<String> expanded = new ArrayList<>(expectedCount);
        if (configured.size() >= 9) {
            // Keep the old 3x3 symbols in the centered cells of the new 5x3 grid.
            for (int row = 0; row < 3; row++) {
                int source = row * 3;
                String a = configured.get(source);
                String b = configured.get(source + 1);
                String c = configured.get(source + 2);
                expanded.add(a);
                expanded.add(a);
                expanded.add(b);
                expanded.add(c);
                expanded.add(c);
            }
        } else {
            for (int i = 0; i < expectedCount; i++) {
                expanded.add(configured.get(i % configured.size()));
            }
        }
        logger.warning("Migrating initial-symbols to 15 entries for the maximum 5x3 slot layout.");
        return List.copyOf(expanded);
    }

    private CasinoConfig.GuiItem withLore(CasinoConfig.GuiItem item, List<String> lore) {
        return new CasinoConfig.GuiItem(
                item.material(),
                item.name(),
                List.copyOf(lore),
                item.hideTooltip(),
                item.hideAdditionalTooltip(),
                item.headId(),
                item.headOwner(),
                item.headTexture()
        );
    }

    private CasinoConfig.GuiItem guiItem(FileConfiguration config,
                                         String path,
                                         Material fallbackMaterial,
                                         String fallbackName,
                                         List<String> fallbackLore,
                                         boolean fallbackHideTooltip,
                                         Logger logger) {
        return guiItem(config, path, fallbackMaterial, fallbackName, fallbackLore, fallbackHideTooltip,
                null, null, null, logger);
    }

    private CasinoConfig.GuiItem guiItem(FileConfiguration config,
                                         String path,
                                         Material fallbackMaterial,
                                         String fallbackName,
                                         List<String> fallbackLore,
                                         boolean fallbackHideTooltip,
                                         String fallbackHeadId,
                                         String fallbackHeadOwner,
                                         String fallbackHeadTexture,
                                         Logger logger) {
        String headId = blankToNull(config.getString(path + ".head-id", fallbackHeadId));
        String headOwner = blankToNull(config.getString(path + ".head-owner",
                config.getString(path + ".skull-owner",
                        config.getString(path + ".owner", fallbackHeadOwner))));
        String headTexture = blankToNull(config.getString(path + ".head-texture",
                config.getString(path + ".texture",
                        config.getString(path + ".skin-url", fallbackHeadTexture))));
        Material loadedMaterial = material(config.getString(path + ".material", fallbackMaterial.name()), fallbackMaterial, logger);
        if (fallbackMaterial == Material.PLAYER_HEAD && (headId != null || headOwner != null || headTexture != null)) {
            loadedMaterial = Material.PLAYER_HEAD;
        }
        return new CasinoConfig.GuiItem(
                loadedMaterial,
                config.getString(path + ".name", fallbackName),
                config.isList(path + ".lore") ? config.getStringList(path + ".lore") : fallbackLore,
                config.getBoolean(path + ".hide-tooltip", fallbackHideTooltip),
                config.getBoolean(path + ".hide-additional-tooltip", false),
                headId,
                headOwner,
                headTexture
        );
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
            double multiplier = config.getDouble(path + "multiplier", 1.0D);
            double chanceWeight = config.contains(path + "strip-count")
                    ? config.getDouble(path + "strip-count", 1.0D)
                    : config.getDouble(path + "chance-weight", 1.0D);
            if (multiplier < 0.0D) {
                throw new IllegalArgumentException(path + "multiplier must be >= 0");
            }
            if (chanceWeight < 0.0D) {
                throw new IllegalArgumentException(path + "strip-count/chance-weight must be >= 0");
            }
            CasinoConfig.Symbol symbol = new CasinoConfig.Symbol(
                    id,
                    material(config.getString(path + "material", "STONE"), Material.STONE, logger),
                    config.getString(path + "display-name", id),
                    config.getString(path + "legend-name", config.getString(path + "display-name", id)),
                    config.isList(path + "lore") ? config.getStringList(path + "lore") : List.of(),
                    multiplier,
                    chanceWeight,
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
                config.getString("messages.machine-unavailable", "&cTa maszyna jest chwilowo niedostępna."),
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

    private SymbolLoadResult migrateDefaultSlotMultipliers(SymbolLoadResult loaded, Logger logger) {
        Map<String, Double> oldDefaults = Map.of(
                "flint", 8.0D,
                "melon_slice", 16.0D,
                "gold_nugget", 35.0D,
                "blaze_powder", 70.0D,
                "amethyst_shard", 105.0D,
                "emerald", 160.0D,
                "diamond", 270.0D,
                "nether_star", 450.0D
        );
        Map<String, Double> newDefaults = Map.of(
                "flint", 12.0D,
                "melon_slice", 18.0D,
                "gold_nugget", 31.0D,
                "blaze_powder", 55.0D,
                "amethyst_shard", 85.0D,
                "emerald", 135.0D,
                "diamond", 240.0D,
                "nether_star", 450.0D
        );
        boolean matchesOldDefaults = loaded.symbols().size() == oldDefaults.size();
        for (CasinoConfig.Symbol symbol : loaded.symbols()) {
            Double expected = oldDefaults.get(symbol.id());
            if (expected == null || Math.abs(expected - symbol.multiplier()) > 0.0001D) {
                matchesOldDefaults = false;
                break;
            }
        }
        if (!matchesOldDefaults) {
            return loaded;
        }

        logger.warning("Migrating legacy audited slot multipliers to the all-directions 1x3/3x3/5x3 balance table.");
        List<CasinoConfig.Symbol> symbols = new ArrayList<>(loaded.symbols().size());
        Map<String, CasinoConfig.Symbol> byId = new LinkedHashMap<>();
        for (CasinoConfig.Symbol old : loaded.symbols()) {
            CasinoConfig.Symbol migrated = new CasinoConfig.Symbol(
                    old.id(), old.material(), old.displayName(), old.legendName(), old.lore(),
                    newDefaults.getOrDefault(old.id(), old.multiplier()), old.chanceWeight(), old.winActionbar(),
                    old.winSounds(), old.winParticles()
            );
            symbols.add(migrated);
            byId.put(migrated.id(), migrated);
        }
        return new SymbolLoadResult(List.copyOf(symbols), Map.copyOf(byId));
    }

    private void validateSlotMachine(CasinoConfig.SlotMachine slotMachine, CasinoConfig.Gui gui) {
        if (slotMachine.rows() != 3) {
            throw new IllegalArgumentException("slot-machine.rows must be exactly 3");
        }
        if (slotMachine.reelOptions().isEmpty()) {
            throw new IllegalArgumentException("slot-machine.reel-options cannot be empty");
        }
        Set<Integer> uniqueOptions = new HashSet<>();
        for (Integer reels : slotMachine.reelOptions()) {
            if (reels == null || (reels != 1 && reels != 3 && reels != 5)) {
                throw new IllegalArgumentException("slot-machine.reel-options may contain only 1, 3 and 5");
            }
            if (!uniqueOptions.add(reels)) {
                throw new IllegalArgumentException("slot-machine.reel-options contains duplicate value: " + reels);
            }
        }
        if (!slotMachine.reelOptions().contains(slotMachine.defaultReels())) {
            throw new IllegalArgumentException("slot-machine.default-reels must exist in slot-machine.reel-options");
        }
        if (slotMachine.baseBetOptions().isEmpty()) {
            throw new IllegalArgumentException("slot-machine.base-bet-options cannot be empty");
        }
        if (slotMachine.baseBetOptions().stream().noneMatch(value -> Math.abs(value - slotMachine.defaultBaseBet()) < 0.0001D)) {
            throw new IllegalArgumentException("slot-machine.default-base-bet must exist in slot-machine.base-bet-options");
        }
        validateGuiSlot("gui.action-slot", gui.actionSlot(), gui.size());
        validateGuiSlot("gui.bet-slot", gui.betSlot(), gui.size());
        validateGuiSlot("gui.difficulty-slot", gui.difficultySlot(), gui.size());
        validateGuiSlot("gui.stop-line-left-slot", gui.stopLineLeftSlot(), gui.size());
        validateGuiSlot("gui.stop-line-right-slot", gui.stopLineRightSlot(), gui.size());
        validateGuiSlot("gui.balance-slot", gui.balanceSlot(), gui.size());
        validateGuiSlot("gui.exit-slot", gui.exitSlot(), gui.size());
        validateGuiSlot("gui.info-slot", gui.infoSlot(), gui.size());
        validateGuiSlot("gui.preview.back-slot", gui.preview().backSlot(), gui.preview().size());
        validateGuiSlot("gui.preview.previous-reel-slot", gui.preview().previousReelSlot(), gui.preview().size());
        validateGuiSlot("gui.preview.next-reel-slot", gui.preview().nextReelSlot(), gui.preview().size());
        validateGuiSlot("gui.preview.previous-page-slot", gui.preview().previousPageSlot(), gui.preview().size());
        validateGuiSlot("gui.preview.next-page-slot", gui.preview().nextPageSlot(), gui.preview().size());
        if (gui.reelGridSlots().size() != 15) {
            throw new IllegalArgumentException("gui.reel-grid-slots must contain exactly 15 slots for a 5x3 grid");
        }
        if (new HashSet<>(gui.reelGridSlots()).size() != 15) {
            throw new IllegalArgumentException("gui.reel-grid-slots must contain 15 unique slots");
        }
        for (int slot : gui.reelGridSlots()) {
            if (slot < 0 || slot >= gui.size()) {
                throw new IllegalArgumentException("gui.reel-grid-slots contains out-of-range slot: " + slot);
            }
        }
        int one = SlotLayout.centered(1, 3, gui.reelGridSlots()).winningPatterns().size();
        int three = SlotLayout.centered(3, 3, gui.reelGridSlots()).winningPatterns().size();
        int five = SlotLayout.centered(5, 3, gui.reelGridSlots()).winningPatterns().size();
        if (one != 1 || three != 8 || five != 22) {
            throw new IllegalArgumentException("Generated slot geometry mismatch: expected patterns 1/8/22 but got "
                    + one + "/" + three + "/" + five);
        }
    }

    private void validateSymbols(List<CasinoConfig.Symbol> symbols) {
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("symbols cannot be empty");
        }
        double totalWeight = 0.0D;
        for (CasinoConfig.Symbol symbol : symbols) {
            if (symbol.multiplier() < 0.0D) {
                throw new IllegalArgumentException("symbols." + symbol.id() + ".multiplier must be >= 0");
            }
            if (symbol.chanceWeight() < 0.0D) {
                throw new IllegalArgumentException("symbols." + symbol.id() + ".strip-count must be >= 0");
            }
            if (symbol.chanceWeight() > 0.0D) {
                totalWeight += symbol.chanceWeight();
            }
        }
        if (totalWeight <= 0.0D) {
            throw new IllegalArgumentException("sum of positive symbols.*.strip-count must be > 0");
        }
    }

    private void validateGuiSlot(String path, int slot, int size) {
        if (slot < 0 || slot >= size) {
            throw new IllegalArgumentException(path + " contains out-of-range slot: " + slot + " for GUI size " + size);
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

    private record SymbolLoadResult(List<CasinoConfig.Symbol> symbols, Map<String, CasinoConfig.Symbol> symbolsById) {
    }

    private record WheelSegmentLoadResult(List<CasinoConfig.WheelSegment> segments,
                                          Map<String, CasinoConfig.WheelSegment> segmentsById) {
    }
}
