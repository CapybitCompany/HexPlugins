package hexnpc.command;

import hexnpc.HexNpcPlugin;
import hexnpc.model.DialogueLine;
import hexnpc.model.InteractionSettings;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcSkin;
import hexnpc.service.NpcService;
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
                case "dialogue" -> handleDialogue(sender, args);
                case "trigger" -> handleTrigger(sender, args);
                case "action" -> handleAction(sender, args);
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
                "&7/" + label + " dialogue <id> <add|clear|cooldown> ...",
                "&7/" + label + " trigger <id> <click|proximity> <on|off> [radius] [cooldown]",
                "&7/" + label + " action <id> add <click|proximity> <type> key=value...",
                "&7/" + label + " action <id> clear <click|proximity|all>",
                "&7/" + label + " reload"
        }) {
            sender.sendMessage(LegacyFormat.component(line));
        }
    }

    private static final List<String> TOP_LEVEL = List.of(
            "create", "remove", "list", "tp", "move", "rotate", "skin",
            "dialogue", "trigger", "action", "reload");

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
        if (args.length == 2 && !sub.equals("list") && !sub.equals("reload") && !sub.equals("create")) {
            return filterPrefix(idList(service), args[1]);
        }
        if (args.length == 3) {
            return switch (sub) {
                case "dialogue" -> filterPrefix(List.of("add", "clear", "cooldown"), args[2]);
                case "trigger" -> filterPrefix(List.of("click", "proximity"), args[2]);
                case "action" -> filterPrefix(List.of("add", "clear"), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("trigger")) {
            return filterPrefix(List.of("on", "off"), args[3]);
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
            return filterPrefix(List.of("message", "console-command", "player-command"), args[4]);
        }
        return List.of();
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
