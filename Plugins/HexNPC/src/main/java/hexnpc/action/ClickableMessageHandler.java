package hexnpc.action;

import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClickableMessageHandler implements NpcActionHandler, CommandExecutor {

    private static final String COMMAND = "hexnpcreply";
    private static final long DEFAULT_TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<UUID, Map<String, Response>> responsesByPlayer = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "clickable-message";
    }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        cleanupExpired();
        long ttlMillis = ttlMillis(action.args().get("timeout-seconds"));

        for (String line : stringList(action.args().get("lines"))) {
            player.sendMessage(LegacyFormat.component(render(line, player, npc)));
        }

        for (Option option : options(action.args().get("options"))) {
            String token = UUID.randomUUID().toString();
            responsesByPlayer
                    .computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                    .put(token, new Response(option.response(), System.currentTimeMillis() + ttlMillis));

            Component message = LegacyFormat.component(render(option.text(), player, npc))
                    .clickEvent(ClickEvent.runCommand("/" + COMMAND + " " + token));
            if (!option.hover().isBlank()) {
                message = message.hoverEvent(HoverEvent.showText(LegacyFormat.component(render(option.hover(), player, npc))));
            }
            player.sendMessage(message);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length < 1) {
            return true;
        }
        cleanupExpired();
        Response response = responsesByPlayer.getOrDefault(player.getUniqueId(), Map.of()).get(args[0]);
        if (response == null || response.expired()) {
            return true;
        }
        for (String line : response.lines()) {
            player.sendMessage(LegacyFormat.component(render(line, player, null)));
        }
        return true;
    }

    public void clear() {
        responsesByPlayer.clear();
    }

    private long ttlMillis(Object raw) {
        if (raw instanceof Number number) {
            return Math.max(1L, number.longValue()) * 1000L;
        }
        if (raw != null) {
            try {
                return Math.max(1L, Long.parseLong(String.valueOf(raw).trim())) * 1000L;
            } catch (NumberFormatException ignored) {
                return DEFAULT_TTL_MILLIS;
            }
        }
        return DEFAULT_TTL_MILLIS;
    }

    private void cleanupExpired() {
        responsesByPlayer.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(response -> response.getValue().expired());
            return entry.getValue().isEmpty();
        });
    }

    private List<Option> options(Object raw) {
        List<Option> options = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                Option option = option(entry);
                if (option != null) {
                    options.add(option);
                }
            }
            return options;
        }
        if (raw instanceof Map<?, ?> map) {
            for (Object entry : map.values()) {
                Option option = option(entry);
                if (option != null) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    private Option option(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String text = string(map, "text", "");
        if (text.isBlank()) {
            return null;
        }
        List<String> response = stringList(map.get("response"));
        if (response.isEmpty()) {
            response = stringList(map.get("message"));
        }
        if (response.isEmpty()) {
            return null;
        }
        String hover = string(map, "hover", "&7Kliknij, aby przeczytac.");
        return new Option(text, hover, response);
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
        if (raw instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private String string(Map<?, ?> map, String key, String fallback) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                Object value = entry.getValue();
                return value == null ? fallback : String.valueOf(value);
            }
        }
        return fallback;
    }

    private String render(String text, Player player, NpcDefinition npc) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("<player>", player.getName());
        placeholders.put("<nick>", player.getName());
        placeholders.put("<npc>", npc == null ? "" : npc.id().value());

        String rendered = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
            rendered = rendered.replace(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
        }
        return rendered;
    }

    private record Option(String text, String hover, List<String> response) {
    }

    private record Response(List<String> lines, long expiresAtMillis) {
        private boolean expired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }
}
