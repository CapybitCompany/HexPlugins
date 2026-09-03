package hexchat.service;

import hexchat.config.HexChatConfig;
import org.bukkit.entity.Player;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Minimalna długość znormalizowanego wpisu, przy której dopuszczamy dopasowanie końcówek. */
    private static final int MIN_WORD_ENDING_LENGTH = 4;

    /** Ścieżka dopasowania, która nie pominęła żadnego separatora (zwarty zapis słowa). */
    private static final byte PATH_COMPACT = 1;

    /**
     * Ścieżka dopasowania, która pominęła co najmniej jeden separator — również symbol leet
     * przeczytany jako separator. To ona decyduje o doklejaniu rozstrzelonych końcówek.
     */
    private static final byte PATH_OBFUSCATED = 2;

    /**
     * Alfabet leetspeak: znak → litera reprezentująca całą klasę podobnych znaków.
     * Litery {@code i}/{@code l} oraz ich symbole ({@code 1}, {@code !}, {@code |})
     * sprowadzamy do wspólnego {@code i}, dzięki czemu "he11o", "hel1o" i "hello"
     * mają tę samą postać znormalizowaną (wpisy blacklisty przechodzą tę samą normalizację).
     */
    private static final Map<Character, Character> LEET_ALPHABET = Map.ofEntries(
            Map.entry('4', 'a'), Map.entry('@', 'a'),
            Map.entry('8', 'b'),
            Map.entry('3', 'e'),
            Map.entry('6', 'g'), Map.entry('9', 'g'),
            Map.entry('1', 'i'), Map.entry('!', 'i'), Map.entry('|', 'i'), Map.entry('l', 'i'),
            Map.entry('0', 'o'),
            Map.entry('5', 's'), Map.entry('$', 's'),
            Map.entry('7', 't'), Map.entry('+', 't'),
            Map.entry('2', 'z')
    );

    /**
     * Symbole leet, które bywają literą, a bywają zwykłą interpunkcją. Nie rozstrzygamy tego
     * przy normalizacji — obie interpretacje sprawdza dopiero {@link #matchAt}, więc "te$t"
     * i "$hit" są wykrywane, a wykrzyknik w "test!" nie wchodzi do maskowanego zakresu.
     */
    private static final String LEET_SYMBOLS = "@$!|+";

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

    /**
     * Szuka wystąpień słów z blacklisty w znormalizowanej postaci wiadomości.
     * <p>
     * Dopasowanie zawsze startuje na realnej lewej granicy słowa (początek tekstu, separator
     * lub niewykorzystany symbol), dzięki czemu poprzedzający tekst nie staje się przypadkiem
     * częścią dopasowania (np. wpis "test" nie trafia w "protest"). Zwracane zakresy dotyczą
     * oryginalnego tekstu — razem z separatorami użytymi do obejścia i z końcówką słowa.
     */
    private List<int[]> findBlacklistRanges(State state, String message) {
        if (state.blacklistWords.isEmpty()) {
            return List.of();
        }

        NormalizedText text = normalize(message, state.blacklistLeet);
        int size = text.size();
        if (size == 0) {
            return List.of();
        }

        // Bufory dopasowania są lokalne dla wywołania: inspect() bywa wołane z asynchronicznego
        // wątku czatu, a stan serwisu musi pozostać niemutowalny i współdzielony bez blokad.
        byte[] reached = new byte[state.blacklistMaxWordLength + 1];
        byte[] next = new byte[state.blacklistMaxWordLength + 1];

        List<int[]> ranges = new ArrayList<>();
        for (int start = 0; start < size; start++) {
            if (text.kinds()[start] == UnitKind.SEPARATOR || !text.isWordStart(start)) {
                continue;
            }
            List<int[]> candidates = state.blacklistWords.get(text.letters()[start]);
            if (candidates == null) {
                continue;
            }
            for (int[] word : candidates) {
                int end = matchAt(
                        text,
                        start,
                        word,
                        state.blacklistIgnoreSeparators,
                        state.blacklistWordEndings,
                        reached,
                        next
                );
                if (end >= start) {
                    ranges.add(new int[]{text.starts()[start], text.ends()[end]});
                }
            }
        }
        return ranges;
    }

    /**
     * Dopasowuje wpis blacklisty od jednostki {@code start}, sterując się kandydatem.
     * <p>
     * Wieloznaczne symbole leet ({@code @ $ ! | +}) mają dwie interpretacje naraz: literę
     * i separator. Zamiast rozstrzygać je z góry po sąsiadach, prowadzimy obie ścieżki
     * równolegle jako zbiór osiągalnych pozycji w słowie. Każda pozycja pamięta przy tym
     * metadane ścieżki ({@link #PATH_COMPACT}, {@link #PATH_OBFUSCATED}), bo tę samą pozycję
     * można osiągnąć zarówno z pominiętym separatorem, jak i bez niego. Zbiór stanów jest
     * ograniczony (pozycja słowa × dwa znaczniki), więc nie ma wykładniczego nawrotu.
     * <p>
     * Ścieżka domykająca słowo jest od razu sprawdzana przez {@link #resolveWordEnd}. Jeśli
     * przepadnie na prawej granicy słowa, kasujemy wyłącznie ją — pozostałe ścieżki biegną
     * dalej, dzięki czemu "tes+t" trafia wpis "test", a "a$s" wpis "as".
     *
     * @return indeks (włącznie) ostatniej jednostki trafienia albo {@code -1}, gdy brak trafienia
     */
    private static int matchAt(
            NormalizedText text,
            int start,
            int[] word,
            boolean ignoreSeparators,
            boolean matchWordEndings,
            byte[] reached,
            byte[] next
    ) {
        int wordLength = word.length;
        int size = text.size();
        Arrays.fill(reached, 0, wordLength + 1, (byte) 0);
        reached[0] = PATH_COMPACT;

        for (int unit = start; unit < size; unit++) {
            UnitKind kind = text.kinds()[unit];
            int letter = text.letters()[unit];
            Arrays.fill(next, 0, wordLength + 1, (byte) 0);

            for (int position = 0; position < wordLength; position++) {
                byte paths = reached[position];
                if (paths == 0) {
                    continue;
                }
                // Konsumpcja litery: pewnej albo symbolu czytanego jako litera.
                // Metadane ścieżki przechodzą dalej bez zmian.
                if (kind != UnitKind.SEPARATOR && word[position] == letter) {
                    next[position + 1] |= paths;
                }
                // Pominięcie separatora albo symbolu czytanego jako separator —
                // tylko przy ignore-separators i zawsze znaczy ścieżkę jako zaciemnioną.
                if (kind != UnitKind.LETTER && ignoreSeparators) {
                    next[position] |= PATH_OBFUSCATED;
                }
            }

            if (next[wordLength] != 0) {
                int resolved = resolveWordEnd(
                        text,
                        unit,
                        wordLength,
                        ignoreSeparators,
                        matchWordEndings,
                        (next[wordLength] & PATH_OBFUSCATED) != 0
                );
                if (resolved >= 0) {
                    return resolved;
                }
                // Ścieżka terminalna odpadła; pozostałe muszą przeżyć tę jednostkę.
                next[wordLength] = 0;
            }

            if (!hasLivePath(next, wordLength)) {
                return -1;
            }
            System.arraycopy(next, 0, reached, 0, wordLength + 1);
        }

        return -1;
    }

    /** Czy po przetworzeniu jednostki została jeszcze jakakolwiek żywa ścieżka. */
    private static boolean hasLivePath(byte[] states, int wordLength) {
        for (int position = 0; position <= wordLength; position++) {
            if (states[position] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rozstrzyga prawą granicę trafienia i dokleja do niego końcówkę słowa.
     * <ol>
     *   <li>Zwarty ciąg dalszy ("brzydkiego" dla wpisu "brzydkie") wymaga match-word-endings
     *       i wpisu o długości co najmniej {@value #MIN_WORD_ENDING_LENGTH}; bez tego nie ma trafienia.</li>
     *   <li>Końcówka rozstrzelona separatorami ("b r z y d k i e g o") należy do obejścia tylko,
     *       gdy sama zaakceptowana ścieżka pominęła jakiś separator, a kolejne grupy to
     *       pojedyncze litery. Zwarta grupa co najmniej dwóch liter to nowe słowo
     *       ("brzydkie go teraz"), którego nie maskujemy.</li>
     * </ol>
     *
     * @param obfuscated czy ścieżka, która domknęła słowo, pominęła separator lub symbol
     * @return indeks (włącznie) ostatniej jednostki trafienia albo {@code -1}
     */
    private static int resolveWordEnd(
            NormalizedText text,
            int end,
            int wordLength,
            boolean ignoreSeparators,
            boolean matchWordEndings,
            boolean obfuscated
    ) {
        int size = text.size();
        boolean endingsAllowed = matchWordEndings && wordLength >= MIN_WORD_ENDING_LENGTH;
        int cursor = end;

        if (cursor + 1 < size && text.kinds()[cursor + 1] == UnitKind.LETTER) {
            if (!endingsAllowed) {
                return -1;
            }
            do {
                cursor++;
            } while (cursor + 1 < size && text.kinds()[cursor + 1] == UnitKind.LETTER);
        }

        if (!endingsAllowed || !ignoreSeparators || !obfuscated) {
            return cursor;
        }

        while (true) {
            int scan = cursor + 1;
            // Symbole traktujemy tu jak separatory — nie ma już kandydata, który czytałby je jako litery.
            while (scan < size && text.kinds()[scan] != UnitKind.LETTER) {
                scan++;
            }
            boolean newWord = scan + 1 < size && text.kinds()[scan + 1] == UnitKind.LETTER;
            if (scan >= size || newWord) {
                return cursor;
            }
            cursor = scan;
        }
    }

    /** Rodzaj jednostki znormalizowanego tekstu. */
    private enum UnitKind {
        /** Pewna litera lub cyfra (po normalizacji Unicode i podstawieniu leet). */
        LETTER,
        /** Znak wieloznaczny: albo litera leet, albo zwykła interpunkcja. */
        SYMBOL,
        /** Pewny separator: spacja, tabulator, interpunkcja, znak formatujący/zero-width. */
        SEPARATOR
    }

    /**
     * Znormalizowany tekst jako ciąg jednostek wraz z mapowaniem na oryginalne pozycje UTF-16.
     *
     * @param kinds   rodzaj jednostki
     * @param letters kodowy punkt litery (dla symbolu: jego odpowiednik leet), inaczej {@code -1}
     * @param starts  początek (włącznie) oryginalnego fragmentu jednostki
     * @param ends    koniec (wyłącznie) oryginalnego fragmentu jednostki
     */
    private record NormalizedText(UnitKind[] kinds, int[] letters, int[] starts, int[] ends) {

        int size() {
            return kinds.length;
        }

        /** Czy jednostka leży na realnej lewej granicy słowa. */
        boolean isWordStart(int unit) {
            return unit == 0 || kinds[unit - 1] != UnitKind.LETTER;
        }
    }

    /**
     * Rozkłada tekst na jednostki z zachowaniem oryginalnych zakresów UTF-16.
     * <ul>
     *   <li>normalizacja Unicode NFKC + małe litery ({@link Locale#ROOT}) wykonywana dla całej
     *       jednostki graficznej (znak bazowy razem ze znakami łączącymi), więc postać złożona
     *       i rozłożona dają identyczny wynik, a warianty fullwidth nie omijają filtra,</li>
     *   <li>iteracja po punktach kodowych — pary zastępcze (znaki spoza BMP) nie są dzielone,</li>
     *   <li>podstawienia leetspeak: cyfry i {@code l} są pewnymi literami, a symbole
     *       {@code @ $ ! | +} pozostają wieloznaczne i rozstrzyga je dopiero dopasowanie,</li>
     *   <li>reszta (spacje, tabulatory, interpunkcja, znaki formatujące i zero-width) to separatory.</li>
     * </ul>
     */
    private static NormalizedText normalize(String input, boolean leet) {
        int length = input.length();
        int capacity = Math.max(16, length);
        UnitKind[] kinds = new UnitKind[capacity];
        int[] letters = new int[capacity];
        int[] starts = new int[capacity];
        int[] ends = new int[capacity];
        int count = 0;

        int index = 0;
        while (index < length) {
            int codePoint = input.codePointAt(index);
            int start = index;
            int end = index + Character.charCount(codePoint);
            while (end < length) {
                int following = input.codePointAt(end);
                if (!isCombiningMark(following)) {
                    break;
                }
                end += Character.charCount(following);
            }
            index = end;

            String normalized = normalizeCluster(input, start, end, codePoint);
            for (int offset = 0; offset < normalized.length(); ) {
                int character = normalized.codePointAt(offset);
                offset += Character.charCount(character);

                UnitKind kind;
                int letter = -1;
                if (leet && isLeetSymbol(character)) {
                    kind = UnitKind.SYMBOL;
                    letter = LEET_ALPHABET.get((char) character);
                } else {
                    int mapped = leet ? leetSubstitute(character) : character;
                    if (Character.isLetterOrDigit(mapped)) {
                        kind = UnitKind.LETTER;
                        letter = mapped;
                    } else if (isCombiningMark(character) && count > 0) {
                        // Znak łączący, którego NFKC nie złożyło — dopisujemy go do zakresu
                        // poprzedniej jednostki, żeby CENSOR zamaskował całą sekwencję.
                        ends[count - 1] = Math.max(ends[count - 1], end);
                        continue;
                    } else {
                        kind = UnitKind.SEPARATOR;
                    }
                }

                if (count == kinds.length) {
                    int grown = count * 2;
                    kinds = Arrays.copyOf(kinds, grown);
                    letters = Arrays.copyOf(letters, grown);
                    starts = Arrays.copyOf(starts, grown);
                    ends = Arrays.copyOf(ends, grown);
                }
                kinds[count] = kind;
                letters[count] = letter;
                starts[count] = start;
                ends[count] = end;
                count++;
            }
        }

        return new NormalizedText(
                Arrays.copyOf(kinds, count),
                Arrays.copyOf(letters, count),
                Arrays.copyOf(starts, count),
                Arrays.copyOf(ends, count)
        );
    }

    private static String normalizeCluster(String input, int start, int end, int firstCodePoint) {
        if (end - start == 1 && firstCodePoint < 128) {
            // Pojedynczy znak ASCII jest już w postaci NFKC — pomijamy kosztowną normalizację.
            return String.valueOf(Character.toLowerCase((char) firstCodePoint));
        }
        return Normalizer.normalize(input.substring(start, end), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean isLeetSymbol(int codePoint) {
        return codePoint < 128 && LEET_SYMBOLS.indexOf((char) codePoint) >= 0;
    }

    private static int leetSubstitute(int codePoint) {
        if (codePoint >= 128) {
            return codePoint;
        }
        Character substitute = LEET_ALPHABET.get((char) codePoint);
        return substitute == null ? codePoint : substitute;
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /**
     * Wpis blacklisty w tej samej normalizacji, w jakiej analizujemy wiadomości.
     * Separatory pomijamy, a symbole leet zapisujemy jako ich literę — w konfiguracji
     * należy więc wpisywać czyste słowa, np. "shit" (wariant "$hit" wykryje samo dopasowanie).
     */
    private static int[] normalizeWord(String input, boolean leet) {
        NormalizedText text = normalize(input, leet);
        int[] letters = new int[text.size()];
        int count = 0;
        for (int unit = 0; unit < text.size(); unit++) {
            if (text.kinds()[unit] != UnitKind.SEPARATOR) {
                letters[count++] = text.letters()[unit];
            }
        }
        return Arrays.copyOf(letters, count);
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
            boolean blacklistIgnoreSeparators,
            boolean blacklistWordEndings,
            Map<Integer, List<int[]>> blacklistWords,
            int blacklistMaxWordLength,
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

            // Wpisy blacklisty normalizujemy raz — przy tworzeniu stanu i przy reloadzie —
            // i grupujemy po pierwszym punkcie kodowym, aby dopasowanie wiadomości było tanie
            // (brak kompilowania wyrażeń regularnych per wiadomość).
            Set<String> uniqueWords = new LinkedHashSet<>();
            Map<Integer, List<int[]>> wordsByFirstCharacter = new LinkedHashMap<>();
            int maxWordLength = 0;
            for (String rawWord : config.blacklist().words()) {
                if (rawWord == null) {
                    continue;
                }
                int[] normalized = normalizeWord(rawWord.trim(), config.blacklist().matchLeetspeak());
                if (normalized.length == 0 || !uniqueWords.add(new String(normalized, 0, normalized.length))) {
                    continue;
                }
                wordsByFirstCharacter
                        .computeIfAbsent(normalized[0], character -> new ArrayList<>())
                        .add(normalized);
                maxWordLength = Math.max(maxWordLength, normalized.length);
            }
            Map<Integer, List<int[]>> words = wordsByFirstCharacter.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));

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
                    config.blacklist().ignoreSeparators(),
                    config.blacklist().matchWordEndings(),
                    words,
                    maxWordLength,
                    config.antiSpam().enabled(),
                    config.antiSpam().blockMessage(),
                    config.antiSpam().maxRepeatedMessages(),
                    config.antiSpam().maxCapsPercentage(),
                    config.antiSpam().minLengthForCapsCheck()
            );
        }
    }
}
