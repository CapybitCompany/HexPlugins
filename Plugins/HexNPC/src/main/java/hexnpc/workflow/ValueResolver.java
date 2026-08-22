package hexnpc.workflow;

import hexnpc.data.PlayerDataService;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One resolver for workflow/menu internal variables and optional PlaceholderAPI. */
public final class ValueResolver {
    private static final Pattern INPUT = Pattern.compile("\\{input:([^}|]+)(?:\\|([^}]*))?}");
    private static final Pattern DATA = Pattern.compile("\\{data:([^}|]+)(?:\\|([^}]*))?}");
    private static final Pattern VAR = Pattern.compile("\\{var:([^}|]+)(?:\\|([^}]*))?}");

    private final PlayerDataService playerData;

    public ValueResolver(PlayerDataService playerData) {
        this.playerData = Objects.requireNonNull(playerData, "playerData");
    }

    public String resolve(String template, Player player, WorkflowContext context) {
        return resolve(template, player, context, true, true);
    }

    public String resolveForMenu(String template, Player player) {
        return resolve(template, player, null, false, true);
    }

    public String resolveCommand(String template, Player player, WorkflowContext context, boolean allowInputVariables) {
        return resolve(template, player, context, allowInputVariables, true);
    }

    private String resolve(String template, Player player, WorkflowContext context,
                           boolean allowInputVariables, boolean placeholderApi) {
        if (template == null) return "";
        String out = template;
        if (player != null) {
            out = out.replace("{player}", player.getName())
                    .replace("{player_name}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString());
        }
        if (allowInputVariables && context != null) out = replace(out, INPUT, (key, fallback) -> valueOrFallback(context.input(key), fallback));
        if (context != null) out = replace(out, VAR, (key, fallback) -> valueOrFallback(context.variable(key), fallback));
        if (player != null) out = replace(out, DATA,
                (key, fallback) -> valueOrFallback(playerData.getCached(player.getUniqueId(), key), fallback));
        if (placeholderApi && player != null && Bukkit.isPrimaryThread()
                && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                out = PlaceholderAPI.setPlaceholders(player, out);
            } catch (Throwable ignored) {
                // Optional integration — never fail a workflow only because PAPI is absent/broken.
            }
        }
        return out;
    }

    private String replace(String source, Pattern pattern, Resolver resolver) {
        Matcher matcher = pattern.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String fallback = matcher.group(2) == null ? "" : matcher.group(2);
            String value = resolver.resolve(key, fallback);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String valueOrFallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    @FunctionalInterface
    private interface Resolver {
        String resolve(String key, String fallback);
    }
}
