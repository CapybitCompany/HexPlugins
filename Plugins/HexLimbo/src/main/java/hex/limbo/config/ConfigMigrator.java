package hex.limbo.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings an existing {@code config.yml} / {@code messages.yml} in the plugin data directory up to
 * the current bundled layout, without throwing away what the server owner changed.
 *
 * <h2>How a file is migrated</h2>
 * The file is rebuilt from the bundled template of the <em>current</em> version, so the operator
 * gets every new key and every updated explanatory comment. Their own values are then written back
 * into that template:
 *
 * <ul>
 *     <li>A value that is byte-for-byte the default of the version the file claims to be is treated
 *     as "never touched" and is replaced by the new default. This is what turns the old uncoloured
 *     Polish strings into the coloured ones and the old {@code RED} BossBar into {@code YELLOW},
 *     without needing a special case for either.</li>
 *     <li>Any other value was changed on purpose and is carried over verbatim into the new
 *     template. The single exception is the brand rename, which is applied inside customised text
 *     as well because the old brand must not survive anywhere.</li>
 *     <li>Keys the operator added themselves, and keys that this release dropped but which they had
 *     customised, are appended in a clearly marked block instead of being deleted. They are also
 *     reported in the log so nothing disappears silently.</li>
 * </ul>
 *
 * <h2>Safety properties</h2>
 * <ul>
 *     <li><b>Idempotent</b> - the rewritten file carries {@code config-version} /
 *     {@code messages-version}. Once it matches {@link #CURRENT_VERSION} the migrator returns
 *     immediately, so restarts and {@code /hexlimbo reload} never touch the file again and never
 *     log again.</li>
 *     <li><b>Backed up</b> - the untouched original is copied to {@code <file>.v<old>.bak} before a
 *     single byte is written, into a file created with the original's own permissions. An existing
 *     backup is never overwritten, and a backup this run could not finish writing is removed rather
 *     than left behind as a truncated decoy.</li>
 *     <li><b>Atomic-ish</b> - the new content is written to a temporary file next to the target and
 *     then moved into place, so an interrupted migration cannot leave a half-written config.</li>
 *     <li><b>UTF-8 throughout</b> - reads and writes are explicitly UTF-8 so Polish diacritics
 *     survive.</li>
 * </ul>
 */
public final class ConfigMigrator {

    /** Layout version of the bundled resources. Bump together with a new {@code legacy/*-vN.yml}. */
    public static final int CURRENT_VERSION = 2;

    /** Key holding the layout version inside {@code config.yml}. */
    public static final String CONFIG_VERSION_KEY = "config-version";
    /** Key holding the layout version inside {@code messages.yml}. */
    public static final String MESSAGES_VERSION_KEY = "messages-version";

    /** Old brand name that must not survive anywhere a player can see it. */
    private static final String OLD_BRAND = "HexagonMC";
    private static final String NEW_BRAND = "Hex";

    /** A {@code key:} or {@code  key: value} line at any indentation. */
    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([A-Za-z0-9_][A-Za-z0-9_.\\-]*):(.*)$");
    private static final Pattern LIST_ITEM_LINE = Pattern.compile("^\\s*-\\s.*$");

    /**
     * Keys that a previous version had under one name and this version splits or renames. The
     * legacy value seeds every replacement key, so an operator's on/off choice survives the split.
     */
    private static final Map<String, List<String>> RENAMED_CONFIG_KEYS = Map.of(
            "prompts.premium-skip-enabled",
            List.of("prompts.premium-success-enabled", "prompts.admin-bypass-success-enabled"));

    /** Outcome of one file migration, for logging and for tests. */
    public record Result(boolean migrated, int fromVersion, int toVersion,
                         List<String> refreshedKeys, List<String> keptCustomKeys,
                         List<String> preservedUnknownKeys, List<String> rebrandedKeys,
                         Path backup) {

        static Result unchanged(int version) {
            return new Result(false, version, version, List.of(), List.of(), List.of(), List.of(), null);
        }
    }

    private final Logger logger;
    private final FileOperations files;

    public ConfigMigrator(Logger logger) {
        this(logger, new FileOperations.Default());
    }

    /** Test seam: lets a test fail exactly one filesystem operation. */
    public ConfigMigrator(Logger logger, FileOperations files) {
        this.logger = logger;
        this.files = files;
    }

    /**
     * Migrates {@code config.yml} in place if it predates {@link #CURRENT_VERSION}. Returns an
     * unchanged result (and writes nothing) when the file is already current.
     */
    public Result migrateConfig(Path file) throws IOException {
        return migrate(file, "config.yml", CONFIG_VERSION_KEY, RENAMED_CONFIG_KEYS);
    }

    /** Migrates {@code messages.yml} in place if it predates {@link #CURRENT_VERSION}. */
    public Result migrateMessages(Path file) throws IOException {
        return migrate(file, "messages.yml", MESSAGES_VERSION_KEY, Map.of());
    }

    private Result migrate(Path file, String resourceName, String versionKey,
                           Map<String, List<String>> renames) throws IOException {
        if (Files.notExists(file)) {
            // A fresh install gets the current template copied verbatim by ConfigLoader; there is
            // nothing to migrate and nothing to back up.
            return Result.unchanged(CURRENT_VERSION);
        }

        Map<String, Object> current = flatten(readYaml(files.readString(file)));
        int fromVersion = versionOf(current, versionKey);
        if (fromVersion >= CURRENT_VERSION) {
            return Result.unchanged(fromVersion);
        }

        String template = readBundled(resourceName);
        if (template == null) {
            logger.warn("Cannot migrate {}: the bundled template is missing from the plugin jar. "
                    + "The existing file is left untouched.", resourceName);
            return Result.unchanged(fromVersion);
        }
        Map<String, Object> newDefaults = flatten(readYaml(template));
        Map<String, Object> oldDefaults = legacyDefaults(resourceName, fromVersion);
        if (oldDefaults == null) {
            // Without the old defaults we cannot tell "untouched" from "customised". Refusing to
            // guess is safer than overwriting somebody's wording.
            logger.warn("Cannot migrate {} from version {}: no bundled reference for that version. "
                            + "The existing file is left untouched; add any new keys manually.",
                    resourceName, fromVersion);
            return Result.unchanged(fromVersion);
        }

        Plan plan = plan(current, oldDefaults, newDefaults, versionKey, renames);
        String rewritten = render(template, plan, versionKey);
        Path backup = replaceContents(file, rewritten, fromVersion);

        Result result = new Result(true, fromVersion, CURRENT_VERSION,
                plan.refreshed, plan.keptCustom, List.copyOf(plan.preserved.keySet()), plan.rebranded, backup);
        log(resourceName, result);
        return result;
    }

    // ------------------------------------------------------------------ planning

    /** What happens to each key, decided before a single character is written. */
    private static final class Plan {
        /** key -> the value that must end up in the file, for keys the template already has. */
        final Map<String, Object> overrides = new LinkedHashMap<>();
        /** key -> value for keys the template no longer has but the operator customised. */
        final Map<String, Object> preserved = new LinkedHashMap<>();
        final List<String> refreshed = new ArrayList<>();
        final List<String> keptCustom = new ArrayList<>();
        final List<String> rebranded = new ArrayList<>();
    }

    private Plan plan(Map<String, Object> current, Map<String, Object> oldDefaults,
                      Map<String, Object> newDefaults, String versionKey,
                      Map<String, List<String>> renames) {
        Plan plan = new Plan();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.equals(versionKey)) {
                continue; // rewritten from the template
            }

            Object oldDefault = oldDefaults.get(key);
            boolean untouched = oldDefault != null && canonical(oldDefault).equals(canonical(value));
            if (untouched) {
                // Still the previous release's wording: let the new default win. This is what
                // recolours the messages and flips the BossBar from RED to YELLOW.
                if (newDefaults.containsKey(key) && !canonical(newDefaults.get(key)).equals(canonical(value))) {
                    plan.refreshed.add(key);
                }
                continue;
            }

            Object migratedValue = value;
            if (value instanceof String text && text.contains(OLD_BRAND)) {
                migratedValue = text.replace(OLD_BRAND, NEW_BRAND);
                plan.rebranded.add(key);
            }

            List<String> replacements = renames.get(key);
            if (replacements != null) {
                // A key this release split: seed every successor the operator has not already set
                // explicitly, so their deliberate on/off choice survives the rename.
                for (String replacement : replacements) {
                    if (!current.containsKey(replacement)) {
                        plan.overrides.put(replacement, migratedValue);
                        plan.keptCustom.add(replacement + " (from " + key + ")");
                    }
                }
                continue;
            }

            if (newDefaults.containsKey(key)) {
                plan.overrides.put(key, migratedValue);
                plan.keptCustom.add(key);
            } else {
                // Either an operator-invented key or one this release dropped. Never delete it.
                plan.preserved.put(key, migratedValue);
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Walks the bundled template line by line, tracking the indentation stack so every scalar line
     * can be addressed by its dotted path, and substitutes the planned values. Comments, ordering
     * and formatting of the template are preserved exactly.
     */
    private String render(String template, Plan plan, String versionKey) {
        List<String> lines = new ArrayList<>(List.of(template.split("\n", -1)));
        List<String> out = new ArrayList<>(lines.size() + plan.preserved.size() + 8);
        // Stack describing the path of the current mapping level.
        List<Integer> indents = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> applied = new LinkedHashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                out.add(line);
                continue;
            }
            int indent = m.group(1).length();
            String key = m.group(2);
            String rest = m.group(3);

            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                names.remove(names.size() - 1);
            }
            String path = names.isEmpty() ? key : String.join(".", names) + "." + key;

            boolean opensBlock = rest.isBlank();
            if (opensBlock && !isListBlock(lines, i)) {
                // A nested mapping: descend and keep the line as is.
                indents.add(indent);
                names.add(key);
                out.add(line);
                continue;
            }

            if (key.equals(versionKey)) {
                out.add(m.group(1) + key + ": " + CURRENT_VERSION);
                continue;
            }

            Object override = plan.overrides.get(path);
            if (override == null) {
                out.add(line);
                if (opensBlock) {
                    i = copyListBlock(lines, i, out);
                }
                continue;
            }
            applied.add(path);

            if (opensBlock) {
                // The operator customised a list: emit their items in the template's style.
                out.add(m.group(1) + key + ":");
                for (Object item : asList(override)) {
                    out.add(m.group(1) + "  - " + quote(item));
                }
                i = skipListBlock(lines, i);
            } else {
                out.add(m.group(1) + key + ": " + quote(override) + trailingComment(rest));
            }
        }

        // Planned overrides for keys the template does not spell out still have to land somewhere.
        Map<String, Object> leftovers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : plan.overrides.entrySet()) {
            if (!applied.contains(e.getKey())) {
                leftovers.put(e.getKey(), e.getValue());
            }
        }
        appendPreserved(out, plan.preserved, leftovers);
        String rendered = String.join("\n", out);
        return rendered.endsWith("\n") ? rendered : rendered + "\n";
    }

    private void appendPreserved(List<String> out, Map<String, Object> preserved,
                                 Map<String, Object> leftovers) {
        Map<String, Object> all = new LinkedHashMap<>(preserved);
        all.putAll(leftovers);
        if (all.isEmpty()) {
            return;
        }
        if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
            out.add("");
        }
        out.add("# ---------------------------------------------------------------------------");
        out.add("# Kept from your previous file by the HexLimbo migration.");
        out.add("# These keys are not part of the current defaults - either you added them yourself");
        out.add("# or this release stopped using them. HexLimbo does not read them; delete the block");
        out.add("# once you are sure you no longer need it.");
        out.add("# ---------------------------------------------------------------------------");
        for (Map.Entry<String, Object> e : all.entrySet()) {
            out.add(e.getKey() + ": " + quote(e.getValue()));
        }
    }

    /** True when the blank-valued key on {@code index} is followed by YAML list items. */
    private static boolean isListBlock(List<String> lines, int index) {
        for (int i = index + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            return LIST_ITEM_LINE.matcher(line).matches();
        }
        return false;
    }

    /** Copies an untouched list block verbatim; returns the index of its last line. */
    private static int copyListBlock(List<String> lines, int index, List<String> out) {
        int i = index + 1;
        while (i < lines.size() && LIST_ITEM_LINE.matcher(lines.get(i)).matches()) {
            out.add(lines.get(i));
            i++;
        }
        return i - 1;
    }

    /** Returns the index of the last line of the list block starting after {@code index}. */
    private static int skipListBlock(List<String> lines, int index) {
        int i = index + 1;
        while (i < lines.size() && LIST_ITEM_LINE.matcher(lines.get(i)).matches()) {
            i++;
        }
        return i - 1;
    }

    // ------------------------------------------------------------------ file plumbing

    /**
     * Backs the file up and replaces it, in an order chosen so that nothing observable changes
     * unless the whole sequence succeeds:
     *
     * <ol>
     *     <li>Read the target's POSIX mode. On a POSIX filesystem a failure here aborts the
     *     migration - guessing a mode for a file holding a database password is not acceptable.</li>
     *     <li>Create the temporary file <em>already carrying that mode</em> and only then write the
     *     new content into it. The secrets therefore never touch the disk under the process umask,
     *     not even briefly.</li>
     *     <li>Create the backup at a name that is still free, already carrying that mode, and copy
     *     the original <em>into that very file</em>. {@link FileOperations#copyContent} opens it for
     *     writing and truncates rather than replacing it, so the mode the file was born with is the
     *     mode the secrets land under - the guarantee is carried by the API, not by a coincidence of
     *     how {@code Files.copy} happens to behave. A pre-existing backup is never opened at all; a
     *     second one gets a unique name.</li>
     *     <li>Move the temporary file into place.</li>
     * </ol>
     *
     * <h3>What each failure leaves behind</h3>
     * The original file is never modified except by the final move, so it is byte-identical after
     * any failure. A backup that already existed before this migration is never touched under any
     * ordering - it is the copy closest to what the operator last had, and losing it would be worse
     * than failing to migrate. Beyond that:
     *
     * <ul>
     *     <li><b>Copy fails</b> - the backup this attempt created is empty or half-written, so it is
     *     deleted again. A truncated backup is worse than none: it looks like a usable copy. The
     *     directory therefore ends up exactly as it started.</li>
     *     <li><b>Move fails</b> - the backup is complete by then and is <em>deliberately kept</em>.
     *     It is a faithful copy of a file that still exists unchanged, it costs nothing, and it is
     *     what the operator wants on the next attempt; deleting a good backup to tidy up after an
     *     unrelated failure would be the wrong trade. The retry sees the name taken and picks a
     *     unique one rather than clobbering it.</li>
     *     <li><b>Anything fails</b> - the temporary file is removed in {@code finally}.</li>
     * </ul>
     *
     * <p>Non-POSIX filesystems (Windows) have no mode to carry: {@code permissions} is null, the
     * files are created with whatever the platform defaults to, and the migration proceeds. Access
     * control there is an ACL question outside this class's remit.
     *
     * @return the path the original was backed up to
     */
    private Path replaceContents(Path file, String content, int fromVersion) throws IOException {
        Set<PosixFilePermission> permissions = files.supportsPosixPermissions(file)
                ? requirePermissions(file)
                : null;

        Path tmp = files.createTemporaryFile(file.getParent(), file.getFileName() + ".", permissions);
        try {
            files.writeString(tmp, content);

            Path backup = freeBackupPath(file, fromVersion);
            files.createFile(backup, permissions);
            boolean backupComplete = false;
            try {
                files.copyContent(file, backup);
                backupComplete = true;
            } finally {
                if (!backupComplete) {
                    discardIncompleteBackup(backup);
                }
            }

            files.move(tmp, file);
            return backup;
        } finally {
            files.deleteIfExists(tmp);
        }
    }

    /**
     * Removes a backup this attempt created but could not fill. Only ever called for the file
     * {@link #freeBackupPath} just proved was free and {@code createFile} just created, so it can
     * never delete an operator's earlier backup. A failure to clean up is attached to the original
     * exception rather than replacing it.
     */
    private void discardIncompleteBackup(Path backup) {
        try {
            files.deleteIfExists(backup);
        } catch (IOException cleanupFailure) {
            logger.warn("Could not remove the incomplete backup {} after a failed copy: {}",
                    backup.getFileName(), cleanupFailure.getMessage());
        }
    }

    private Set<PosixFilePermission> requirePermissions(Path file) throws IOException {
        Set<PosixFilePermission> permissions = files.readPermissions(file);
        if (permissions == null) {
            throw new IOException("Refusing to migrate " + file.getFileName()
                    + ": its POSIX permissions could not be read, and this file may hold a database "
                    + "password or a forwarding secret. Fix the file's ownership and retry.");
        }
        return permissions;
    }

    /** A backup name that is not taken yet: an earlier backup is closer to the original. */
    private Path freeBackupPath(Path file, int fromVersion) {
        Path backup = file.resolveSibling(file.getFileName() + ".v" + fromVersion + ".bak");
        if (files.exists(backup)) {
            backup = file.resolveSibling(file.getFileName() + ".v" + fromVersion + "."
                    + System.currentTimeMillis() + ".bak");
        }
        return backup;
    }

    private void log(String resourceName, Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Migrated ").append(resourceName)
                .append(" from version ").append(result.fromVersion())
                .append(" to ").append(result.toVersion())
                .append(" (backup: ").append(result.backup().getFileName()).append("). ")
                .append(result.refreshedKeys().size()).append(" default(s) refreshed, ")
                .append(result.keptCustomKeys().size()).append(" customised value(s) kept");
        if (!result.rebrandedKeys().isEmpty()) {
            sb.append(", ").append(result.rebrandedKeys().size())
                    .append(" rebranded ").append(OLD_BRAND).append(" to ").append(NEW_BRAND);
        }
        if (!result.preservedUnknownKeys().isEmpty()) {
            sb.append(". Kept but no longer used by HexLimbo: ")
                    .append(String.join(", ", result.preservedUnknownKeys()))
                    .append(" - review them, HexLimbo will not read these");
        }
        sb.append('.');
        // Exactly one line per migrated file, and only on the run that actually migrates.
        logger.info(sb.toString());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> legacyDefaults(String resourceName, int version) {
        String base = resourceName.endsWith(".yml")
                ? resourceName.substring(0, resourceName.length() - 4)
                : resourceName;
        String text = readBundled("legacy/" + base + "-v" + version + ".yml");
        return text == null ? null : flatten(readYaml(text));
    }

    private String readBundled(String resourceName) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int read;
                while ((read = reader.read(buf)) >= 0) {
                    sb.append(buf, 0, read);
                }
                return sb.toString();
            }
        } catch (IOException ex) {
            logger.warn("Could not read bundled resource '{}': {}", resourceName, ex.getMessage());
            return null;
        }
    }

    private static Map<String, Object> readYaml(String text) {
        Object parsed = new Yaml().load(text);
        if (parsed instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        return Collections.emptyMap();
    }

    /**
     * Flattens nested maps into dotted paths, <b>keeping the value's YAML type</b>: leaves stay
     * {@code String}, {@code Boolean}, {@code Number} or {@code List}.
     *
     * <p>The type matters at write time. A configured string that merely looks numeric or boolean -
     * a database password {@code "001234"}, a forwarding secret {@code "0000123456"}, a message
     * {@code "000123"}, a literal {@code "false"} - must be re-emitted quoted, or YAML would parse
     * it back as a number (dropping the leading zeros) or a boolean. Comparing values for "did the
     * operator change this?" uses {@link #canonical(Object)} instead, which is type-insensitive.
     */
    private static Map<String, Object> flatten(Map<String, Object> root) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto("", root, out);
        return out;
    }

    private static void flattenInto(String prefix, Map<String, Object> map, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> child = new LinkedHashMap<>();
                for (Map.Entry<?, ?> ne : nested.entrySet()) {
                    if (ne.getKey() != null) {
                        child.put(String.valueOf(ne.getKey()), ne.getValue());
                    }
                }
                flattenInto(path, child, out);
            } else if (value != null) {
                out.put(path, value);
            }
        }
    }

    /** Type-insensitive rendering used only to compare a current value against a known default. */
    private static String canonical(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                sb.append('\u0000').append(o);
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private static int versionOf(Map<String, Object> flat, String versionKey) {
        Object raw = flat.get(versionKey);
        if (raw == null) {
            return 1; // pre-versioning releases
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    /**
     * Re-emits a scalar with the YAML type it was read as: booleans and numbers bare, <b>every
     * string double-quoted</b> - including strings that happen to look like numbers or booleans.
     *
     * <p>Deciding by shape instead of by type is what would corrupt data: a password
     * {@code "001234"} written bare comes back as the number 1234, and a message {@code "false"}
     * comes back as a boolean. Quoting every string also makes values containing {@code #},
     * {@code :}, backslashes, quotes or newlines safe, because {@link #escape(String)} handles them
     * inside the quotes.
     */
    private static String quote(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of(String.valueOf(value));
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Keeps a trailing {@code # comment} that sat behind the value on the template line. */
    private static String trailingComment(String rest) {
        int hash = indexOfCommentStart(rest);
        return hash < 0 ? "" : " " + rest.substring(hash).trim();
    }

    private static int indexOfCommentStart(String rest) {
        boolean inQuotes = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '#' && !inQuotes && i > 0 && Character.isWhitespace(rest.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /** Diagnostic helper for tests: the flattened, string-rendered view of a YAML file on disk. */
    public static Map<String, String> flattenFile(Path file) throws IOException {
        Map<String, String> out = new TreeMap<>();
        flatten(readYaml(Files.readString(file, StandardCharsets.UTF_8)))
                .forEach((key, value) -> out.put(key, canonical(value)));
        return out;
    }

    /** Diagnostic helper for tests: the flattened view with the YAML types preserved. */
    public static Map<String, Object> flattenFileTyped(Path file) throws IOException {
        return new TreeMap<>(flatten(readYaml(Files.readString(file, StandardCharsets.UTF_8))));
    }
}
