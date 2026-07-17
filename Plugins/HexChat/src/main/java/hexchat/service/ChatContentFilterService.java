package hexchat.service;

import hexchat.config.HexChatConfig;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Filtr treści czatu: anti-reklama, blacklista słów (m.in. hate speech) oraz anti-spam.
 * <p>
 * Logika jest deterministyczna i testowalna przez {@link #inspect(Player, String)}.
 * Dla akcji CENSOR zwracany jest ocenzurowany tekst do wyświetlenia — listener podmienia
 * jedynie render (wyświetlanie), nigdy podpisaną treść wiadomości, co jest bezpieczne dla
 * podpisanego czatu 1.19+.
 */
public final class ChatContentFilterService {

    private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private static final List<String> DEFAULT_AD_PATTERNS = List.of(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b",
            "\\b(?:discord\\.gg|discord(?:app)?\\.com/invite|dsc\\.gg)/\\S+",
            "\\b[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9-]+)*"
                    + "\\.(?:com|net|org|pl|gg|io|me|xyz|eu|de|tv|co|shop|store|club|online|site|link|fun|top|pro|info)\\b"
    );

    private final AtomicReference<State> stateRef;
    private final ConcurrentHashMap<UUID, RepeatTracker> repeatTrackers = new ConcurrentHashMap<>();

    public ChatContentFilterService(HexChatConfig initialConfig) {
        Objects.requireNonNull(initialConfig, "initialConfig");
        this.stateRef = new AtomicReference<>(State.from(initialConfig.contentFilter()));
    }

    public void updateConfig(HexChatConfig updatedConfig) {
        Objects.requireNonNull(updatedConfig, "updatedConfig");
        this.stateRef.set(State.from(updatedConfig.contentFilter()));
    }

    public void clearHistory(UUID playerId) {
        repeatTrackers.remove(playerId);
    }

    public InspectionResult inspect(Player player, String rawMessage) {
        State state = stateRef.get();
        String message = rawMessage == null ? "" : rawMessage;
        if (!state.enabled || message.isBlank()) {
            return InspectionResult.allowed();
        }

        // 1) Anti-spam (zawsze BLOCK).
        if (state.spamEnabled) {
            if (exceedsCaps(state, message)) {
                return InspectionResult.block(state.spamBlockMessage);
            }
            if (isRepetition(state, player, message)) {
                return InspectionResult.block(state.spamBlockMessage);
            }
        }

        // 2) Blacklista (priorytet nad reklamą — hate speech).
        List<int[]> blacklistRanges = state.blacklistEnabled ? findBlacklistRanges(state, message) : List.of();
        List<int[]> advertRanges = state.advertEnabled ? findAdvertRanges(state, message) : List.of();

        if (!blacklistRanges.isEmpty() && state.blacklistAction == HexChatConfig.FilterAction.BLOCK) {
            return InspectionResult.block(state.blacklistBlockMessage);
        }
        if (!advertRanges.isEmpty() && state.advertAction == HexChatConfig.FilterAction.BLOCK) {
            return InspectionResult.block(state.advertBlockMessage);
        }

        // 3) Cenzura (podmiana tylko w wyświetlaniu).
        List<int[]> censorRanges = new ArrayList<>();
        if (!blacklistRanges.isEmpty() && state.blacklistAction == HexChatConfig.FilterAction.CENSOR) {
            censorRanges.addAll(blacklistRanges);
        }
        if (!advertRanges.isEmpty() && state.advertAction == HexChatConfig.FilterAction.CENSOR) {
            censorRanges.addAll(advertRanges);
        }
        if (!censorRanges.isEmpty()) {
            return InspectionResult.censor(applyMask(message, censorRanges, state.censorMask));
        }

        return InspectionResult.allowed();
    }

    private boolean exceedsCaps(State state, String message) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        if (letters < state.spamMinLengthForCaps) {
            return false;
        }
        int percentage = (upper * 100) / letters;
        return percentage > state.spamMaxCapsPercentage;
    }

    private boolean isRepetition(State state, Player player, String message) {
        if (player == null) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        RepeatTracker tracker = repeatTrackers.computeIfAbsent(player.getUniqueId(), id -> new RepeatTracker());
        int count = tracker.register(normalized);
        return count >= state.spamMaxRepeatedMessages;
    }

    private List<int[]> findAdvertRanges(State state, String message) {
        List<int[]> ranges = new ArrayList<>();
        for (Pattern pattern : state.advertPatterns) {
            Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                String matched = message.substring(matcher.start(), matcher.end());
                if (!isAllowedHost(extractHost(matched), state.advertAllowedDomains)) {
                    ranges.add(new int[]{matcher.start(), matcher.end()});
                }
            }
        }
        return ranges;
    }

    /**
     * Wyodrębnia sam host z dopasowanego fragmentu (bez schematu, ścieżki i portu),
     * aby porównanie z allowlistą było bezpieczne (a nie oparte na "contains").
     */
    static String extractHost(String matched) {
        String host = matched.toLowerCase(Locale.ROOT).trim();

        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int at = host.indexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host;
    }

    /**
     * Host jest dozwolony tylko, gdy jest dokładnie równy dozwolonej domenie
     * lub jest jej prawdziwą subdomeną ({@code host.endsWith("." + domain)}).
     * Odrzuca przypadki typu {@code twojserwer.pl.evil.com} i {@code evil-twojserwer.pl}.
     */
    static boolean isAllowedHost(String host, Set<String> allowedDomains) {
        if (host.isBlank()) {
            return false;
        }
        for (String domain : allowedDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private List<int[]> findBlacklistRanges(State state, String message) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = WORD_TOKEN.matcher(message);
        while (matcher.find()) {
            String token = message.substring(matcher.start(), matcher.end());
            String normalized = normalizeWord(token, state.blacklistLeet);
            if (state.blacklistWords.contains(normalized)) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        return ranges;
    }

    private static String normalizeWord(String input, boolean leet) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (!leet) {
            return lower;
        }
        StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            builder.append(switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4' -> 'a';
                case '5' -> 's';
                case '7' -> 't';
                case '8' -> 'b';
                case '@' -> 'a';
                case '$' -> 's';
                default -> c;
            });
        }
        return builder.toString();
    }

    private static String applyMask(String message, List<int[]> ranges, String mask) {
        List<int[]> merged = mergeRanges(ranges);
        merged.sort(Comparator.comparingInt((int[] r) -> r[0]).reversed());
        StringBuilder builder = new StringBuilder(message);
        for (int[] range : merged) {
            builder.replace(range[0], range[1], mask);
        }
        return builder.toString();
    }

    private static List<int[]> mergeRanges(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : sorted) {
            if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        return merged;
    }

    private static final class RepeatTracker {
        private String last = null;
        private int count = 0;

        synchronized int register(String normalized) {
            if (normalized.equals(last)) {
                count++;
            } else {
                last = normalized;
                count = 1;
            }
            return count;
        }
    }

    public enum Decision {
        ALLOWED,
        BLOCK,
        CENSOR
    }

    public record InspectionResult(Decision decision, String blockMessage, String censoredText) {
        public static InspectionResult allowed() {
            return new InspectionResult(Decision.ALLOWED, null, null);
        }

        public static InspectionResult block(String blockMessage) {
            return new InspectionResult(Decision.BLOCK, blockMessage, null);
        }

        public static InspectionResult censor(String censoredText) {
            return new InspectionResult(Decision.CENSOR, null, censoredText);
        }
    }

    private record State(
            boolean enabled,
            String censorMask,
            boolean advertEnabled,
            HexChatConfig.FilterAction advertAction,
            String advertBlockMessage,
            List<Pattern> advertPatterns,
            Set<String> advertAllowedDomains,
            boolean blacklistEnabled,
            HexChatConfig.FilterAction blacklistAction,
            String blacklistBlockMessage,
            boolean blacklistLeet,
            Set<String> blacklistWords,
            boolean spamEnabled,
            String spamBlockMessage,
            int spamMaxRepeatedMessages,
            int spamMaxCapsPercentage,
            int spamMinLengthForCaps
    ) {
        private static State from(HexChatConfig.ContentFilter config) {
            List<Pattern> patterns = new ArrayList<>();
            List<String> rawPatterns = new ArrayList<>(DEFAULT_AD_PATTERNS);
            rawPatterns.addAll(config.antiAdvertising().extraPatterns());
            for (String raw : rawPatterns) {
                try {
                    patterns.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (RuntimeException ignored) {
                    // Niepoprawny wzorzec pomijamy — nie wywracamy całego filtra.
                }
            }

            Set<String> allowedDomains = config.antiAdvertising().allowedDomains().stream()
                    .map(domain -> domain.toLowerCase(Locale.ROOT).trim())
                    .filter(domain -> !domain.isBlank())
                    .collect(Collectors.toUnmodifiableSet());

            Set<String> words = config.blacklist().words().stream()
                    .map(word -> normalizeWord(word.trim(), config.blacklist().matchLeetspeak()))
                    .filter(word -> !word.isBlank())
                    .collect(Collectors.toUnmodifiableSet());

            return new State(
                    config.enabled(),
                    config.censorMask(),
                    config.antiAdvertising().enabled(),
                    config.antiAdvertising().action(),
                    config.antiAdvertising().blockMessage(),
                    List.copyOf(patterns),
                    allowedDomains,
                    config.blacklist().enabled(),
                    config.blacklist().action(),
                    config.blacklist().blockMessage(),
                    config.blacklist().matchLeetspeak(),
                    words,
                    config.antiSpam().enabled(),
                    config.antiSpam().blockMessage(),
                    config.antiSpam().maxRepeatedMessages(),
                    config.antiSpam().maxCapsPercentage(),
                    config.antiSpam().minLengthForCapsCheck()
            );
        }
    }
}
