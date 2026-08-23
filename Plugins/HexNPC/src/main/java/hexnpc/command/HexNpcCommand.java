package hexnpc.command;

import hexnpc.HexNpcPlugin;
import hexnpc.model.DialogueLine;
import hexnpc.guide.GuideMenuService;
import hexnpc.model.InteractionSettings;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.LookAtSettings;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.model.NpcSkin;
import hexnpc.service.NpcService;
import hexnpc.shop.ShopRegistry;
import hexnpc.shop.ShopService;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.util.LegacyFormat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class HexNpcCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "hexnpc.admin";

    private final HexNpcPlugin plugin;

    public HexNpcCommand(HexNpcPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(LegacyFormat.component("&cYou do not have permission."));
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "reload" -> handleReload(sender);
                case "list" -> handleList(sender);
                case "create" -> handleCreate(sender, args);
                case "remove", "delete" -> handleRemove(sender, args);
                case "tp", "teleport" -> handleTp(sender, args);
                case "move" -> handleMove(sender, args);
                case "rotate" -> handleRotate(sender, args);
                case "skin" -> handleSkin(sender, args);
                case "name", "nick", "nickname" -> handleName(sender, args);
                case "glow" -> handleGlow(sender, args);
                case "lookat", "look" -> handleLookAt(sender, args);
                case "pose", "animation" -> handlePose(sender, args);
                case "dialogue" -> handleDialogue(sender, args);
                case "trigger" -> handleTrigger(sender, args);
                case "action" -> handleAction(sender, args);
                case "shop" -> handleShop(sender, args);
                case "guide" -> handleGuide(sender, args);
                case "workflow", "workflows" -> handleWorkflow(sender, args);
                default -> {
                    usage(sender, label);
                    yield true;
                }
            };
        } catch (Exception ex) {
            sender.sendMessage(LegacyFormat.component("&cError: " + ex.getMessage()));
            plugin.getLogger().warning("HexNPC command failure: " + ex.getMessage());
            return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        boolean ok = plugin.reloadPluginRuntime();
        sender.sendMessage(LegacyFormat.component(ok ? "&aHexNPC reloaded." : "&cReload failed, check console."));
        return true;
    }

    private boolean handleList(CommandSender sender) {
        NpcService service = plugin.npcService();
        if (service == null) {
            sender.sendMessage(LegacyFormat.component("&cHexNPC is not initialized."));
            return true;
        }
        var npcs = service.list();
        if (npcs.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&7No NPCs configured."));
            return true;
        }
        sender.sendMessage(LegacyFormat.component("&aNPCs (" + npcs.size() + "):"));
        for (NpcDefinition def : npcs) {
            NpcLocation l = def.location();
            sender.sendMessage(LegacyFormat.component(String.format(
                    Locale.US,
                    "&7- &f%s &7@ &f%s &7(%.1f, %.1f, %.1f) click=%s proximity=%s",
                    def.id(), l.world(), l.x(), l.y(), l.z(),
                    def.interaction().clickEnabled(),
                    def.interaction().proximityEnabled()
            )));
        }
        return true;
    }

    private boolean handleCreate(CommandSender sender, String[] args) throws Exception {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(LegacyFormat.component("&cOnly players can run /hexnpc create."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexnpc create <id>"));
            return true;
        }
        NpcService service = plugin.npcService();
        if (service == null) {
            sender.sendMessage(LegacyFormat.component("&cHexNPC is not initialized."));
            return true;
        }
        if (!NpcId.isValid(args[1])) {
            sender.sendMessage(LegacyFormat.component("&cInvalid id (allowed: a-z 0-9 _ -, max 32)."));
            return true;
        }
        NpcId id = new NpcId(args[1]);
        NpcLocation location = NpcLocation.fromBukkit(player.getLocation());
        NpcDefinition created = service.create(id, location);
        sender.sendMessage(LegacyFormat.component("&aCreated NPC &f" + created.id() + "&a at your location."));
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) throws Exception {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexnpc remove <id>"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        boolean removed = plugin.npcService().remove(id);
        sender.sendMessage(LegacyFormat.component(removed
                ? "&aRemoved NPC &f" + id
                : "&cNo NPC with id &f" + id));
        return true;
    }

    private boolean handleTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(LegacyFormat.component("&cOnly players can teleport."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexnpc tp <id>"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        Optional<NpcDefinition> def = plugin.npcService().find(id);
        if (def.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&cNo NPC with id &f" + id));
            return true;
        }
        var bukkit = def.get().location().toBukkit();
        if (bukkit == null) {
            sender.sendMessage(LegacyFormat.component("&cWorld is not loaded: &f" + def.get().location().world()));
            return true;
        }
        player.teleport(bukkit);
        sender.sendMessage(LegacyFormat.component("&aTeleported to &f" + id));
        return true;
    }

    private boolean handleMove(CommandSender sender, String[] args) throws Exception {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(LegacyFormat.component("&cOnly players can run /hexnpc move."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexnpc move <id>"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        Optional<NpcDefinition> updated = plugin.npcService()
                .move(id, NpcLocation.fromBukkit(player.getLocation()));
        sender.sendMessage(LegacyFormat.component(updated.isPresent()
                ? "&aMoved &f" + id + " &ato your location."
                : "&cNo NPC with id &f" + id));
        return true;
    }

    private boolean handleRotate(CommandSender sender, String[] args) throws Exception {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexnpc rotate <id> [yaw pitch]  (omit to use your facing)"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        float yaw;
        float pitch;
        if (args.length >= 4) {
            try {
                yaw = Float.parseFloat(args[2]);
                pitch = Float.parseFloat(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(LegacyFormat.component("&cYaw and pitch must be numbers."));
                return true;
            }
        } else if (sender instanceof Player player) {
            yaw = player.getLocation().getYaw();
            pitch = player.getLocation().getPitch();
        } else {
            sender.sendMessage(LegacyFormat.component("&cProvide yaw and pitch."));
            return true;
        }
        Optional<NpcDefinition> updated = plugin.npcService().rotate(id, yaw, pitch);
        sender.sendMessage(LegacyFormat.component(updated.isPresent()
                ? String.format(Locale.US, "&aRotated &f%s &ato yaw=%.1f pitch=%.1f", id, yaw, pitch)
                : "&cNo NPC with id &f" + id));
        return true;
    }

    private boolean handleSkin(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexnpc skin <id> <playerName>  OR  /hexnpc skin <id> raw <value> <signature>"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        if (args[2].equalsIgnoreCase("raw")) {
            if (args.length < 5) {
                sender.sendMessage(LegacyFormat.component("&cUsage: /hexnpc skin <id> raw <value> <signature>"));
                return true;
            }
            NpcSkin skin = NpcSkin.ofTexture(args[3], args[4]);
            Optional<NpcDefinition> updated = plugin.npcService().setSkin(id, skin);
            sender.sendMessage(LegacyFormat.component(updated.isPresent()
                    ? "&aUpdated skin for &f" + id
                    : "&cNo NPC with id &f" + id));
            return true;
        }

        // Player-name path: kick off async Mojang lookup, then apply on main thread.
        String playerName = args[2];
        sender.sendMessage(LegacyFormat.component("&7Resolving skin for &f" + playerName + "&7..."));
        plugin.skinResolver().resolve(playerName).whenComplete((resolved, ex) -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    NpcSkin skin = (resolved != null) ? resolved : NpcSkin.ofName(playerName);
                    Optional<NpcDefinition> updated = plugin.npcService().setSkin(id, skin);
                    if (updated.isEmpty()) {
                        sender.sendMessage(LegacyFormat.component("&cNo NPC with id &f" + id));
                        return;
                    }
                    if (skin.hasTexture()) {
                        sender.sendMessage(LegacyFormat.component("&aSkin applied for &f" + id + " &7(textures cached)"));
                    } else {
                        sender.sendMessage(LegacyFormat.component("&eSkin name applied for &f" + id + " &7(textures unavailable, fell back to default)"));
                    }
                } catch (Exception failure) {
                    sender.sendMessage(LegacyFormat.component("&cFailed to apply skin: " + failure.getMessage()));
                }
            });
        });
        return true;
    }

    private boolean handleName(CommandSender sender, String[] args) throws Exception {
        // Unterstuetzte Syntaxen (alt + neu):
        //   /hexnpc name <id> <nick...>        (Legacy)
        //   /hexnpc name <id> clear            (Legacy)
        //   /hexnpc name set <id> <nick...>    (neu)
        //   /hexnpc name clear <id>            (neu)
        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (op.equals("set")) {
            if (args.length < 4) {
                sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc name set <id> <nick...>"));
                return true;
            }
            NpcId id = parseId(sender, args[2]);
            if (id == null) {
                return true;
            }
            String nickname = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            return applyName(sender, id, nickname);
        }
        if (op.equals("clear") || op.equals("reset")) {
            if (args.length < 3) {
                sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc name clear <id>"));
                return true;
            }
            NpcId id = parseId(sender, args[2]);
            if (id == null) {
                return true;
            }
            return applyName(sender, id, null);
        }
        // Legacy: /hexnpc name <id> ...
        if (args.length < 3) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUżycie: /hexnpc name set <id> <nick...>  lub  /hexnpc name clear <id>"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        boolean clear = args.length == 3
                && (args[2].equalsIgnoreCase("clear") || args[2].equalsIgnoreCase("reset"));
        String nickname = clear ? null : String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        return applyName(sender, id, nickname);
    }

    private boolean applyName(CommandSender sender, NpcId id, String nickname) throws Exception {
        Optional<NpcDefinition> updated = plugin.npcService().setDisplayName(id, nickname);
        if (updated.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&cNie znaleziono NPC o id &f" + id));
            return true;
        }
        boolean cleared = nickname == null || nickname.isBlank();
        if (cleared) {
            sender.sendMessage(LegacyFormat.component("&aWyczyszczono nick NPC &f" + id
                    + " &7(widoczny teraz jako id)."));
        } else {
            // Podgląd z zastosowanym formatowaniem, aby admin widział efekt kodów &.
            sender.sendMessage(LegacyFormat.component("&aUstawiono nick NPC &f" + id + "&a: ")
                    .append(LegacyFormat.component(nickname)));
        }
        return true;
    }

    private boolean handleGlow(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUżycie: /hexnpc glow <id> <on|off> [kolor]  (kolory: " + glowColorList() + ")"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        boolean glow = parseOnOff(args[2]);
        // Optionale Farbe (4. Argument). Wird nur zusammen mit glow=on sichtbar.
        String color = null;
        if (args.length >= 4) {
            String raw = args[3].toLowerCase(Locale.ROOT);
            if (!GLOW_COLORS.contains(raw)) {
                sender.sendMessage(LegacyFormat.component(
                        "&cNieznany kolor: &f" + args[3] + "&c. Dostępne: &f" + glowColorList()));
                return true;
            }
            color = raw;
        }
        Optional<NpcDefinition> updated = plugin.npcService().setGlow(id, glow, color);
        if (updated.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&cNie znaleziono NPC o id &f" + id));
            return true;
        }
        String colorSuffix = "";
        if (glow) {
            String effective = updated.get().appearance().glowColor();
            colorSuffix = "&7 (kolor: &f" + (effective == null ? "white" : effective) + "&7)";
        }
        sender.sendMessage(LegacyFormat.component(
                "&aŚwiecenie NPC &f" + id + "&a: " + (glow ? "&2włączone" : "&cwyłączone") + colorSuffix));
        return true;
    }

    /** Gueltige Glow-/Team-Farbnamen (die 16 Minecraft-Standardfarben). */
    private static final List<String> GLOW_COLORS = List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red",
            "light_purple", "yellow", "white");

    private static String glowColorList() {
        return String.join("|", GLOW_COLORS);
    }

    private boolean handleLookAt(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUżycie: /hexnpc lookat <id> <on|off> [range] [interval-ticks]"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        Optional<NpcDefinition> current = plugin.npcService().find(id);
        if (current.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&cNie znaleziono NPC o id &f" + id));
            return true;
        }
        boolean enabled = parseOnOff(args[2]);
        LookAtSettings settings = current.get().lookAt().withEnabled(enabled);
        if (args.length >= 4) {
            try {
                settings = settings.withRange(Double.parseDouble(args[3]));
            } catch (NumberFormatException ex) {
                sender.sendMessage(LegacyFormat.component("&cRange musi być liczbą."));
                return true;
            }
        }
        if (args.length >= 5) {
            try {
                settings = settings.withIntervalTicks(Integer.parseInt(args[4]));
            } catch (NumberFormatException ex) {
                sender.sendMessage(LegacyFormat.component("&cInterwał musi być liczbą całkowitą."));
                return true;
            }
        }
        Optional<NpcDefinition> updated = plugin.npcService().setLookAt(id, settings);
        if (updated.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&cNie znaleziono NPC o id &f" + id));
            return true;
        }
        LookAtSettings eff = updated.get().lookAt();
        String rangeText = eff.hasRange() ? String.format(Locale.US, "%.1f", eff.range()) : "domyślny";
        sender.sendMessage(LegacyFormat.component(
                "&aŚledzenie gracza NPC &f" + id + "&a: " + (enabled ? "&2włączone" : "&cwyłączone")
                        + "&7 (range: &f" + rangeText + "&7, interval: &f" + eff.intervalTicks() + "&7)"));
        return true;
    }

    private boolean handlePose(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUżycie: /hexnpc pose <id> <" + poseChoices() + ">"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        Optional<NpcPose> pose = NpcPose.parse(args[2]);
        if (pose.isEmpty()) {
            sender.sendMessage(LegacyFormat.component(
                    "&cNieznana poza: &f" + args[2] + "&c. Dostępne: &f" + poseChoices()));
            return true;
        }
        Optional<NpcDefinition> updated = plugin.npcService().setPose(id, pose.get());
        sender.sendMessage(LegacyFormat.component(updated.isPresent()
                ? "&aUstawiono pozę NPC &f" + id + "&a: &f" + pose.get().storageKey()
                : "&cNie znaleziono NPC o id &f" + id));
        return true;
    }

    private static String poseChoices() {
        StringBuilder sb = new StringBuilder();
        for (NpcPose p : NpcPose.values()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(p.storageKey());
        }
        return sb.toString();
    }

    private boolean handleDialogue(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUsage: /hexnpc dialogue <id> <add|clear|cooldown> ..."));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        String op = args[2].toLowerCase(Locale.ROOT);
        switch (op) {
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage(LegacyFormat.component(
                            "&cUsage: /hexnpc dialogue <id> add [delayTicks] <text...>"));
                    return true;
                }
                int delay = 0;
                int textStart = 3;
                try {
                    delay = Integer.parseInt(args[3]);
                    textStart = 4;
                } catch (NumberFormatException ignored) {
                    // first token is part of text
                }
                if (textStart >= args.length) {
                    sender.sendMessage(LegacyFormat.component("&cText is required."));
                    return true;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, textStart, args.length));
                Optional<NpcDefinition> updated = plugin.npcService()
                        .addDialogueLine(id, new DialogueLine(text, delay));
                sender.sendMessage(LegacyFormat.component(updated.isPresent()
                        ? "&aAdded dialogue line."
                        : "&cNo NPC with id &f" + id));
            }
            case "clear" -> {
                Optional<NpcDefinition> updated = plugin.npcService().clearDialogue(id);
                sender.sendMessage(LegacyFormat.component(updated.isPresent()
                        ? "&aCleared dialogue."
                        : "&cNo NPC with id &f" + id));
            }
            case "cooldown" -> {
                if (args.length < 4) {
                    sender.sendMessage(LegacyFormat.component(
                            "&cUsage: /hexnpc dialogue <id> cooldown <ticks>"));
                    return true;
                }
                int ticks;
                try {
                    ticks = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(LegacyFormat.component("&cTicks must be a number."));
                    return true;
                }
                Optional<NpcDefinition> updated = plugin.npcService().setDialogueCooldown(id, ticks);
                sender.sendMessage(LegacyFormat.component(updated.isPresent()
                        ? "&aDialogue cooldown set to " + ticks + " ticks."
                        : "&cNo NPC with id &f" + id));
            }
            default -> sender.sendMessage(LegacyFormat.component(
                    "&cUnknown dialogue op. Use add|clear|cooldown."));
        }
        return true;
    }

    private boolean handleTrigger(CommandSender sender, String[] args) throws Exception {
        if (args.length < 4) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUsage: /hexnpc trigger <id> click <on|off>"
                            + "  OR  /hexnpc trigger <id> proximity <on|off> [radius] [cooldown]"));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        Optional<NpcDefinition> current = plugin.npcService().find(id);
        if (current.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&cNo NPC with id &f" + id));
            return true;
        }
        String triggerType = args[2].toLowerCase(Locale.ROOT);
        boolean on = parseOnOff(args[3]);
        InteractionSettings current0 = current.get().interaction();
        InteractionSettings updated;
        switch (triggerType) {
            case "click" -> updated = current0.withClick(on);
            case "proximity" -> {
                double radius = current0.proximityRadius();
                int cooldown = current0.proximityCooldownTicks();
                if (args.length >= 5) {
                    try {
                        radius = Double.parseDouble(args[4]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(LegacyFormat.component("&cRadius must be a number."));
                        return true;
                    }
                }
                if (args.length >= 6) {
                    try {
                        cooldown = Integer.parseInt(args[5]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(LegacyFormat.component("&cCooldown must be a number."));
                        return true;
                    }
                }
                updated = current0.withProximity(on, radius, cooldown);
            }
            default -> {
                sender.sendMessage(LegacyFormat.component("&cUnknown trigger type. Use click or proximity."));
                return true;
            }
        }
        plugin.npcService().setInteraction(id, updated);
        sender.sendMessage(LegacyFormat.component("&aInteraction updated for &f" + id));
        return true;
    }

    private boolean handleAction(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUsage: /hexnpc action <id> <add|clear> ..."));
            return true;
        }
        NpcId id = parseId(sender, args[1]);
        if (id == null) {
            return true;
        }
        String op = args[2].toLowerCase(Locale.ROOT);
        switch (op) {
            case "add" -> {
                if (args.length < 6) {
                    sender.sendMessage(LegacyFormat.component(
                            "&cUsage: /hexnpc action <id> add <click|proximity> <type> key=value [key=value ...]"));
                    return true;
                }
                InteractionTrigger trigger = parseTrigger(sender, args[3]);
                if (trigger == null) {
                    return true;
                }
                String type = args[4];
                Map<String, Object> argsMap = new LinkedHashMap<>();
                for (int i = 5; i < args.length; i++) {
                    int eq = args[i].indexOf('=');
                    if (eq <= 0) {
                        if (!argsMap.isEmpty()) {
                            String lastKey = lastKey(argsMap);
                            argsMap.put(lastKey, argsMap.get(lastKey) + " " + args[i]);
                            continue;
                        }
                        sender.sendMessage(LegacyFormat.component(
                                "&cExpected key=value, got: " + args[i]));
                        return true;
                    }
                    String key = args[i].substring(0, eq);
                    String value = args[i].substring(eq + 1);
                    argsMap.put(key, value);
                }
                Optional<NpcDefinition> updated = plugin.npcService()
                        .addAction(id, trigger, new NpcAction(type, argsMap));
                sender.sendMessage(LegacyFormat.component(updated.isPresent()
                        ? "&aAdded " + trigger.name().toLowerCase(Locale.ROOT) + " action."
                        : "&cNo NPC with id &f" + id));
            }
            case "clear" -> {
                if (args.length < 4) {
                    sender.sendMessage(LegacyFormat.component(
                            "&cUsage: /hexnpc action <id> clear <click|proximity|all>"));
                    return true;
                }
                String scope = args[3].toLowerCase(Locale.ROOT);
                Optional<NpcDefinition> updated;
                if (scope.equals("all")) {
                    updated = plugin.npcService().clearAllActions(id);
                } else {
                    InteractionTrigger trigger = parseTrigger(sender, args[3]);
                    if (trigger == null) {
                        return true;
                    }
                    updated = plugin.npcService().clearActions(id, trigger);
                }
                sender.sendMessage(LegacyFormat.component(updated.isPresent()
                        ? "&aCleared actions."
                        : "&cNo NPC with id &f" + id));
            }
            default -> sender.sendMessage(LegacyFormat.component("&cUnknown op. Use add|clear."));
        }
        return true;
    }


    private boolean handleGuide(CommandSender sender, String[] args) {
        GuideMenuService service = plugin.guideMenuService();
        if (service == null) {
            sender.sendMessage(LegacyFormat.component("&cPodsystem poradników nie jest zainicjalizowany."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc guide <reload|list|open|validate> ..."));
            return true;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "reload" -> {
                int loaded = service.reload();
                sender.sendMessage(LegacyFormat.component("&aPrzeładowano poradniki: &f" + loaded));
            }
            case "list" -> {
                sender.sendMessage(LegacyFormat.component("&aGuide menus (" + service.ids().size() + "): &f"
                        + String.join("&7, &f", service.ids())));
            }
            case "validate" -> {
                List<String> errors = service.validationErrors();
                if (errors.isEmpty()) {
                    sender.sendMessage(LegacyFormat.component("&aGuide menus: konfiguracja poprawna."));
                } else {
                    sender.sendMessage(LegacyFormat.component("&cGuide menus: znaleziono " + errors.size() + " problem(ów):"));
                    for (String error : errors) sender.sendMessage(LegacyFormat.component("&7- &f" + error));
                }
            }
            case "open" -> {
                if (args.length < 3) {
                    sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc guide open <menu-id> [player]"));
                    return true;
                }
                Player target;
                if (args.length >= 4) {
                    target = plugin.getServer().getPlayerExact(args[3]);
                    if (target == null) {
                        sender.sendMessage(LegacyFormat.component("&cGracz jest offline lub nie istnieje: &f" + args[3]));
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(LegacyFormat.component("&cZ konsoli podaj gracza: /hexnpc guide open <menu-id> <player>"));
                    return true;
                }
                if (!service.open(target, args[2])) {
                    sender.sendMessage(LegacyFormat.component("&cNie znaleziono guide menu: &f" + args[2]));
                }
            }
            default -> sender.sendMessage(LegacyFormat.component("&cNieznana operacja. Użyj reload|list|open|validate."));
        }
        return true;
    }

    private boolean handleWorkflow(CommandSender sender, String[] args) {
        var registry = plugin.workflowRegistry();
        var menus = plugin.workflowMenuService();
        var workflows = plugin.workflowService();
        if (registry == null || menus == null || workflows == null) {
            sender.sendMessage(LegacyFormat.component("&cPodsystem workflow nie jest zainicjalizowany."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc workflow <reload|list|validate|status|open|run> ..."));
            return true;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "reload" -> {
                if (plugin.workflowService() != null) plugin.workflowService().cancelAll();
                int loaded = registry.reload();
                sender.sendMessage(LegacyFormat.component("&aPrzeładowano workflow: &f" + loaded
                        + " &7· menus: &f" + registry.menuIds().size()));
            }
            case "list" -> {
                sender.sendMessage(LegacyFormat.component("&aWorkflows (" + registry.workflowIds().size() + "): &f"
                        + String.join("&7, &f", registry.workflowIds())));
                sender.sendMessage(LegacyFormat.component("&aWorkflow menus (" + registry.menuIds().size() + "): &f"
                        + String.join("&7, &f", registry.menuIds())));
            }
            case "validate" -> {
                List<String> errors = registry.errors();
                if (errors.isEmpty()) sender.sendMessage(LegacyFormat.component("&aWorkflow: konfiguracja poprawna."));
                else {
                    sender.sendMessage(LegacyFormat.component("&cWorkflow: znaleziono " + errors.size() + " problem(ów):"));
                    for (String error : errors) sender.sendMessage(LegacyFormat.component("&7- &f" + error));
                }
            }
            case "status" -> {
                var playerData = plugin.playerDataService();
                sender.sendMessage(LegacyFormat.component("&aWorkflow runtime:"));
                sender.sendMessage(LegacyFormat.component("&7Player data: &f"
                        + (playerData == null ? "service unavailable" : playerData.status())));
                for (String id : registry.workflowIds()) {
                    String reason = workflows.unavailableReason(id);
                    sender.sendMessage(LegacyFormat.component("&7- &f" + id + " &7→ "
                            + (reason.isEmpty() ? "&aREADY" : "&cBLOCKED &8(" + reason + "&8)")));
                }
            }
            case "open" -> {
                if (args.length < 3) {
                    sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc workflow open <menu-id> [player]"));
                    return true;
                }
                Player target = resolveTarget(sender, args, 3);
                if (target == null) return true;
                menus.open(target, args[2]);
            }
            case "run" -> {
                if (args.length < 3) {
                    sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc workflow run <workflow-id> [player]"));
                    return true;
                }
                Player target = resolveTarget(sender, args, 3);
                if (target == null) return true;
                workflows.startWorkflow(target, args[2], "admin", "", "command");
            }
            default -> sender.sendMessage(LegacyFormat.component("&cNieznana operacja. Użyj reload|list|validate|status|open|run."));
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int playerArgIndex) {
        if (args.length > playerArgIndex) {
            Player target = plugin.getServer().getPlayerExact(args[playerArgIndex]);
            if (target == null) sender.sendMessage(LegacyFormat.component("&cGracz jest offline lub nie istnieje: &f" + args[playerArgIndex]));
            return target;
        }
        if (sender instanceof Player player) return player;
        sender.sendMessage(LegacyFormat.component("&cZ konsoli podaj gracza."));
        return null;
    }

    private boolean handleShop(CommandSender sender, String[] args) {
        ShopService service = plugin.shopService();
        ShopRegistry registry = plugin.shopRegistry();
        if (service == null || registry == null) {
            sender.sendMessage(LegacyFormat.component("&cPodsystem sklepów nie jest zainicjalizowany."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component(
                    "&cUżycie: /hexnpc shop <reload|list|open|info> [shopId]"));
            return true;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "reload" -> {
                int loaded = plugin.reloadShopCatalog();
                sender.sendMessage(LegacyFormat.component(
                        "&aPrzeładowano sklepy: &f" + loaded));
            }
            case "list" -> {
                if (registry.size() == 0) {
                    sender.sendMessage(LegacyFormat.component("&7Brak skonfigurowanych sklepów."));
                    return true;
                }
                sender.sendMessage(LegacyFormat.component("&aSklepy (" + registry.size() + "):"));
                for (Shop shop : registry.all()) {
                    sender.sendMessage(LegacyFormat.component(
                            "&7- &f" + shop.id() + " &7(przedmioty: " + shop.itemValues().size()
                                    + ", rozmiar: " + shop.size() + ")"));
                }
            }
            case "open" -> {
                if (args.length < 3) {
                    sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc shop open <shopId>"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(LegacyFormat.component("&cTylko gracz może otworzyć sklep."));
                    return true;
                }
                service.openShop(player, args[2]);
            }
            case "info" -> {
                if (args.length < 3) {
                    sender.sendMessage(LegacyFormat.component("&cUżycie: /hexnpc shop info <shopId>"));
                    return true;
                }
                Shop shop = registry.find(args[2]).orElse(null);
                if (shop == null) {
                    sender.sendMessage(LegacyFormat.component("&cNie znaleziono sklepu o id &f" + args[2]));
                    return true;
                }
                sender.sendMessage(LegacyFormat.component(
                        "&aSklep &f" + shop.id() + " &7| rozmiar: &f" + shop.size()
                                + " &7· rozmieszczenie: &f" + describePlacement(shop)
                                + " &7· stron: &f" + hexnpc.shop.gui.ShopPlacement.totalPages(shop)));
                for (ShopItem item : shop.itemValues()) {
                    String buy = item.hasBuyPrice() ? item.buyPrice().toPlainString() : "—";
                    String sell = item.hasSellPrice() ? item.sellPrice().toPlainString() : "—";
                    String limit = item.hasBuyLimit() ? (item.maxBuyAmount() + "/dzień") : "brak";
                    sender.sendMessage(LegacyFormat.component(String.format(Locale.US,
                            "&7- &f%s &7| materiał: &f%s &7· ilość bazowa: &f%d &7· kup: &a%s &7· sprzedaj: &e%s"
                                    + " &7· limit: &f%s &7· dopasowanie: &f%s",
                            item.id(), item.material(), item.amount(), buy, sell, limit,
                            describeSellMatch(item))));
                }
            }
            default -> sender.sendMessage(LegacyFormat.component(
                    "&cNieznana operacja. Użyj reload|list|open|info."));
        }
        return true;
    }

    /** Polski opis trybu rozmieszczenia (enum wewnętrznie pozostaje po angielsku). */
    private static String describePlacement(Shop shop) {
        return switch (shop.layout().placement()) {
            case MANUAL -> "ręczne (sloty z konfiguracji)";
            case AUTO -> "automatyczne (item-slots + strony)";
        };
    }

    /** Polski opis trybu dopasowania sprzedaży. */
    private static String describeSellMatch(ShopItem item) {
        return switch (item.sellMatch()) {
            case PLAIN_MATERIAL -> "surowy materiał";
            case EXACT_ITEM -> "dokładny przedmiot";
        };
    }

    private InteractionTrigger parseTrigger(CommandSender sender, String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "click", "on-click", "onclick" -> InteractionTrigger.CLICK;
            case "proximity", "on-proximity", "onproximity" -> InteractionTrigger.PROXIMITY;
            default -> {
                sender.sendMessage(LegacyFormat.component("&cUnknown trigger: " + raw + " (use click or proximity)"));
                yield null;
            }
        };
    }

    private static String lastKey(Map<String, Object> map) {
        String last = null;
        for (String key : map.keySet()) {
            last = key;
        }
        return last;
    }

    private NpcId parseId(CommandSender sender, String raw) {
        if (!NpcId.isValid(raw)) {
            sender.sendMessage(LegacyFormat.component("&cInvalid id: &f" + raw));
            return null;
        }
        return new NpcId(raw);
    }

    private boolean parseOnOff(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            default -> false;
        };
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(LegacyFormat.component("&aHexNPC commands:"));
        for (String line : new String[]{
                "&7/" + label + " create <id>",
                "&7/" + label + " remove <id>",
                "&7/" + label + " list",
                "&7/" + label + " tp <id>",
                "&7/" + label + " move <id>",
                "&7/" + label + " rotate <id> [yaw pitch]",
                "&7/" + label + " skin <id> <playerName>  | raw <value> <signature>",
                "&7/" + label + " name set <id> <nick...>  | clear <id>  (auch: name <id> <nick...>)",
                "&7/" + label + " glow <id> <on|off> [color]",
                "&7/" + label + " lookat <id> <on|off> [range] [interval-ticks]",
                "&7/" + label + " pose <id> <" + poseChoices() + ">",
                "&7/" + label + " dialogue <id> <add|clear|cooldown> ...",
                "&7/" + label + " trigger <id> <click|proximity> <on|off> [radius] [cooldown]",
                "&7/" + label + " action <id> add <click|proximity> <type> key=value...",
                "&7/" + label + " action <id> clear <click|proximity|all>",
                "&7/" + label + " shop <reload|list|open|info> [shopId]",
                "&7/" + label + " guide <reload|list|open|validate> ...",
                "&7/" + label + " workflow <reload|list|validate|open|run> ...",
                "&7/" + label + " reload"
        }) {
            sender.sendMessage(LegacyFormat.component(line));
        }
    }

    private static final List<String> TOP_LEVEL = List.of(
            "create", "remove", "list", "tp", "move", "rotate", "skin",
            "name", "glow", "lookat", "pose", "dialogue", "trigger", "action", "shop", "guide", "workflow", "reload");

    private static final List<String> SHOP_OPS = List.of("reload", "list", "open", "info");
    private static final List<String> GUIDE_OPS = List.of("reload", "list", "open", "validate");
    private static final List<String> WORKFLOW_OPS = List.of("reload", "list", "validate", "open", "run");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) {
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(TOP_LEVEL, args[0]);
        }
        NpcService service = plugin.npcService();
        if (service == null) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("shop")) {
            return filterPrefix(SHOP_OPS, args[1]);
        }
        if (args.length == 2 && sub.equals("guide")) {
            return filterPrefix(GUIDE_OPS, args[1]);
        }
        if (args.length == 2 && (sub.equals("workflow") || sub.equals("workflows"))) {
            return filterPrefix(WORKFLOW_OPS, args[1]);
        }
        if (args.length == 2 && (sub.equals("name") || sub.equals("nick") || sub.equals("nickname"))) {
            // /hexnpc name <set|clear|id>
            List<String> opts = new ArrayList<>(List.of("set", "clear"));
            opts.addAll(idList(service));
            return filterPrefix(opts, args[1]);
        }
        if (args.length == 2 && !sub.equals("list") && !sub.equals("reload") && !sub.equals("create") && !sub.equals("shop") && !sub.equals("guide") && !sub.equals("workflow") && !sub.equals("workflows")) {
            return filterPrefix(idList(service), args[1]);
        }
        if (args.length == 3 && sub.equals("guide") && args[1].equalsIgnoreCase("open")) {
            GuideMenuService guides = plugin.guideMenuService();
            return guides == null ? List.of() : filterPrefix(guides.ids(), args[2]);
        }
        if (args.length == 3 && (sub.equals("workflow") || sub.equals("workflows"))) {
            if (args[1].equalsIgnoreCase("open")) {
                return plugin.workflowRegistry() == null ? List.of() : filterPrefix(plugin.workflowRegistry().menuIds(), args[2]);
            }
            if (args[1].equalsIgnoreCase("run")) {
                return plugin.workflowRegistry() == null ? List.of() : filterPrefix(plugin.workflowRegistry().workflowIds(), args[2]);
            }
        }
        if (args.length == 3 && sub.equals("shop")) {
            String op = args[1].toLowerCase(Locale.ROOT);
            if (op.equals("open") || op.equals("info")) {
                ShopRegistry registry = plugin.shopRegistry();
                if (registry != null) {
                    List<String> ids = new ArrayList<>();
                    for (Shop shop : registry.all()) {
                        ids.add(shop.id());
                    }
                    return filterPrefix(ids, args[2]);
                }
            }
            return List.of();
        }
        if (args.length == 3) {
            return switch (sub) {
                case "dialogue" -> filterPrefix(List.of("add", "clear", "cooldown"), args[2]);
                case "trigger" -> filterPrefix(List.of("click", "proximity"), args[2]);
                case "action" -> filterPrefix(List.of("add", "clear"), args[2]);
                case "glow" -> filterPrefix(List.of("on", "off"), args[2]);
                case "lookat", "look" -> filterPrefix(List.of("on", "off"), args[2]);
                case "pose", "animation" -> filterPrefix(poseKeys(), args[2]);
                case "name", "nick", "nickname" -> {
                    // /hexnpc name set|clear <id>  -> ids; Legacy /hexnpc name <id> clear -> "clear"
                    if (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("clear")
                            || args[1].equalsIgnoreCase("reset")) {
                        yield filterPrefix(idList(service), args[2]);
                    }
                    yield filterPrefix(List.of("clear"), args[2]);
                }
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("trigger")) {
            return filterPrefix(List.of("on", "off"), args[3]);
        }
        if (args.length == 4 && sub.equals("glow")) {
            // /hexnpc glow <id> <on|off> [color]
            return filterPrefix(GLOW_COLORS, args[3]);
        }
        if (args.length == 4 && sub.equals("action")) {
            if (args[2].equalsIgnoreCase("add")) {
                return filterPrefix(List.of("click", "proximity"), args[3]);
            }
            if (args[2].equalsIgnoreCase("clear")) {
                return filterPrefix(List.of("click", "proximity", "all"), args[3]);
            }
        }
        if (args.length == 5 && sub.equals("action") && args[2].equalsIgnoreCase("add")) {
            return filterPrefix(List.of("message", "clickable-message", "clickable-menu", "guide-menu", "console-command", "player-command", "npc-shop"), args[4]);
        }
        return List.of();
    }

    private static List<String> poseKeys() {
        List<String> keys = new ArrayList<>();
        for (NpcPose p : NpcPose.values()) {
            keys.add(p.storageKey());
        }
        return keys;
    }

    private List<String> idList(NpcService service) {
        List<String> ids = new ArrayList<>();
        for (NpcDefinition def : service.list()) {
            ids.add(def.id().value());
        }
        return ids;
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return options;
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(opt);
            }
        }
        return out;
    }
}
