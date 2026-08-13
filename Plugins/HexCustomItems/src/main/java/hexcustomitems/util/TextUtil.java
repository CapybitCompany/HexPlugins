package hexcustomitems.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Zentrale Text-Umwandlung auf Basis von Adventure/MiniMessage.
 *
 * <p>Reihenfolge der Verarbeitung: erst optional PlaceholderAPI ({@link PapiSupport}),
 * danach MiniMessage. So bleiben MiniMessage-Platzhalter wie &lt;charges&gt; intakt,
 * während %papi%-Platzhalter vorher aufgelöst werden.
 */
public final class TextUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern LEGACY_COLOR = Pattern.compile(".*&[0-9a-fk-orA-FK-OR].*");

    private TextUtil() {
    }

    /** Parst eine MiniMessage-Zeile ohne Platzhalter. */
    public static Component parse(String text) {
        return MINI.deserialize(text == null ? "" : text);
    }

    /** Parst eine MiniMessage-Zeile mit &lt;key&gt;-Platzhaltern aus der Map (unparsed = sicher für Namen/Zahlen). */
    public static Component parse(String text, Map<String, String> placeholders) {
        return parse(text, placeholders, null);
    }

    /** Wie {@link #parse(String, Map)}, aber mit optionalem PlaceholderAPI-Kontext. */
    public static Component parse(String text, Map<String, String> placeholders, OfflinePlayer papiContext) {
        if (text == null) {
            return Component.empty();
        }
        String processed = PapiSupport.apply(papiContext, text);
        if (LEGACY_COLOR.matcher(processed).matches()) {
            return LEGACY.deserialize(applyPlainPlaceholders(processed, placeholders));
        }
        return MINI.deserialize(processed, toResolvers(placeholders));
    }

    /** Parst eine MiniMessage-Zeile mit einem beliebigen TagResolver (z.B. Component-Platzhalter). */
    public static Component parse(String text, TagResolver resolver) {
        if (text == null) {
            return Component.empty();
        }
        return MINI.deserialize(text, resolver == null ? TagResolver.empty() : resolver);
    }

    /** Item-Anzeigename: geparst und ohne das Standard-Kursiv der Vanilla-Items. */
    public static Component itemName(String text, Map<String, String> placeholders, OfflinePlayer papiContext) {
        return nonItalic(parse(text, placeholders, papiContext));
    }

    /** Item-Lore: jede Zeile geparst und nicht kursiv. */
    public static List<Component> itemLore(List<String> lines, Map<String, String> placeholders, OfflinePlayer papiContext) {
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(nonItalic(parse(line, placeholders, papiContext)));
        }
        return result;
    }

    private static Component nonItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private static TagResolver toResolvers(Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return TagResolver.empty();
        }
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }
        return builder.build();
    }

    private static String applyPlainPlaceholders(String text, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        return result;
    }
}
