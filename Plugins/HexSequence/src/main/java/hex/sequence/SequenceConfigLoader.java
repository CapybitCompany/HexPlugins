package hex.sequence;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SequenceConfigLoader {

    private static final Pattern TEXT_ENTRY = Pattern.compile("^\\s*\\[(console|player)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PREFIX = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s+(.+)$");

    private final HexSequencePlugin plugin;

    public SequenceConfigLoader(HexSequencePlugin plugin) {
        this.plugin = plugin;
    }

    public List<SequenceEntry> load(String sequenceName) throws SequenceParseException {
        List<?> rawEntries = findSequenceList(sequenceName);
        if (rawEntries == null) {
            return null;
        }

        List<SequenceEntry> entries = new ArrayList<>();
        long currentDelayTicks = 0L;

        for (int i = 0; i < rawEntries.size(); i++) {
            ParsedEntry parsed = parseEntry(rawEntries.get(i), i + 1);
            if (parsed.delayTicks() != null) {
                currentDelayTicks = parsed.delayTicks();
            }
            entries.add(new SequenceEntry(parsed.executorType(), currentDelayTicks, parsed.command(), i + 1));
        }

        return entries;
    }

    public Set<String> sequenceNames() {
        Set<String> names = new LinkedHashSet<>();
        ConfigurationSection sequences = plugin.getConfig().getConfigurationSection("sequences");
        if (sequences != null) {
            names.addAll(sequences.getKeys(false));
        }

        for (String key : plugin.getConfig().getKeys(false)) {
            if (plugin.getConfig().isList(key) && !key.equalsIgnoreCase("messages")) {
                names.add(key);
            }
        }
        return Collections.unmodifiableSet(names);
    }

    private List<?> findSequenceList(String sequenceName) {
        String nestedPath = "sequences." + sequenceName;
        if (plugin.getConfig().isList(nestedPath)) {
            return plugin.getConfig().getList(nestedPath);
        }
        if (plugin.getConfig().isList(sequenceName)) {
            return plugin.getConfig().getList(sequenceName);
        }
        return null;
    }

    private ParsedEntry parseEntry(Object raw, int lineIndex) throws SequenceParseException {
        if (raw instanceof String text) {
            return parseTextEntry(text, lineIndex);
        }
        if (raw instanceof Map<?, ?> map) {
            return parseMapEntry(map, lineIndex);
        }
        throw new SequenceParseException("Linia " + lineIndex + ": wpis musi byc tekstem albo mapa.");
    }

    private ParsedEntry parseTextEntry(String raw, int lineIndex) throws SequenceParseException {
        Matcher entryMatcher = TEXT_ENTRY.matcher(raw);
        if (!entryMatcher.matches()) {
            throw new SequenceParseException("Linia " + lineIndex + ": wpis musi zaczynac sie od [console] albo [player].");
        }

        SequenceExecutorType executorType = SequenceExecutorType.parse(entryMatcher.group(1));
        String remainder = entryMatcher.group(2).trim();
        if (remainder.isEmpty()) {
            throw new SequenceParseException("Linia " + lineIndex + ": brakuje komendy.");
        }

        Long delayTicks = null;
        Matcher timeMatcher = TIME_PREFIX.matcher(remainder);
        if (timeMatcher.matches() && !startsWithQuote(remainder)) {
            delayTicks = secondsToTicks(timeMatcher.group(1), lineIndex);
            remainder = timeMatcher.group(2).trim();
        }

        String command = normalizeCommand(stripOptionalQuotes(remainder));
        if (command.isEmpty()) {
            throw new SequenceParseException("Linia " + lineIndex + ": komenda jest pusta.");
        }

        return new ParsedEntry(executorType, delayTicks, command);
    }

    private ParsedEntry parseMapEntry(Map<?, ?> map, int lineIndex) throws SequenceParseException {
        Object executorRaw = firstPresent(map, "executor", "type", "as", "run-as");
        Object commandRaw = firstPresent(map, "command", "cmd", "run");
        Object timeRaw = firstPresent(map, "time", "at", "delay");

        if (executorRaw == null) {
            throw new SequenceParseException("Linia " + lineIndex + ": brakuje executor/type/as.");
        }
        if (commandRaw == null) {
            throw new SequenceParseException("Linia " + lineIndex + ": brakuje command/cmd/run.");
        }

        SequenceExecutorType executorType = SequenceExecutorType.parse(String.valueOf(executorRaw));
        Long delayTicks = timeRaw == null ? null : secondsToTicks(String.valueOf(timeRaw), lineIndex);
        String command = normalizeCommand(stripOptionalQuotes(String.valueOf(commandRaw).trim()));
        if (command.isEmpty()) {
            throw new SequenceParseException("Linia " + lineIndex + ": komenda jest pusta.");
        }

        return new ParsedEntry(executorType, delayTicks, command);
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (String.valueOf(entry.getKey()).equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private boolean startsWithQuote(String text) {
        return text.startsWith("\"") || text.startsWith("'");
    }

    private String stripOptionalQuotes(String text) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\"' && last == '\"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private String normalizeCommand(String command) {
        String normalized = command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private long secondsToTicks(String rawSeconds, int lineIndex) throws SequenceParseException {
        try {
            double seconds = Double.parseDouble(rawSeconds.replace(',', '.'));
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw new NumberFormatException("negative or non-finite");
            }
            return Math.max(0L, Math.round(seconds * 20.0D));
        } catch (NumberFormatException ex) {
            throw new SequenceParseException("Linia " + lineIndex + ": niepoprawny czas w sekundach: " + rawSeconds);
        }
    }

    private record ParsedEntry(SequenceExecutorType executorType, Long delayTicks, String command) {
    }
}

