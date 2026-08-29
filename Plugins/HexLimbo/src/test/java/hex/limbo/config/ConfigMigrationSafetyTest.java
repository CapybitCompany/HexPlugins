package hex.limbo.config;

import hex.limbo.testsupport.RecordingFileOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Data-safety regressions for {@link ConfigMigrator}: what it writes has to survive a real
 * {@link ConfigLoader} round-trip byte for byte, and it must not widen the permissions of a file
 * that holds a database password or a forwarding secret.
 */
class ConfigMigrationSafetyTest {

    private ConfigLoader loader(Path dir) {
        return new ConfigLoader(dir, LoggerFactory.getLogger(ConfigMigrationSafetyTest.class));
    }

    private ConfigMigrator migrator() {
        return new ConfigMigrator(LoggerFactory.getLogger(ConfigMigrationSafetyTest.class));
    }

    private ConfigMigrator migrator(FileOperations files) {
        return new ConfigMigrator(LoggerFactory.getLogger(ConfigMigrationSafetyTest.class), files);
    }

    private void installV1(Path dir) throws IOException {
        Files.createDirectories(dir);
        copyResource("legacy/config-v1.yml", dir.resolve("config.yml"));
        copyResource("legacy/messages-v1.yml", dir.resolve("messages.yml"));
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "bundled resource missing: " + resource);
            Files.write(target, in.readAllBytes());
        }
    }

    private void edit(Path file, String from, String to) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains(from), "precondition: v1 file must contain " + from);
        Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------- numeric-looking strings

    @Test
    void numericLookingSecretsKeepTheirLeadingZeros(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        edit(config, "  password: \"\"", "  password: \"001234\"");
        edit(config, "    secret: \"\"", "    secret: \"0000123456\"");

        migrator().migrateConfig(config);
        PluginConfig loaded = loader(dir).loadConfig();

        assertEquals("001234", loaded.database().password(),
                "a database password that looks numeric must not be reparsed as a number");
        assertEquals("0000123456", loaded.limbo().forwarding().secret(),
                "a forwarding secret that looks numeric must not lose its leading zeros");
    }

    @Test
    void numericAndBooleanLookingStringsKeepTheirYamlType(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        edit(config, "  password: \"\"", "  password: \"1.0\"");
        edit(config, "    secret: \"\"", "    secret: \"false\"");
        edit(config, "  target: \"lobby\"", "  target: \"0042\"");

        migrator().migrateConfig(config);

        Map<String, Object> typed = ConfigMigrator.flattenFileTyped(config);
        assertInstanceOf(String.class, typed.get("database.password"),
                "\"1.0\" must stay a string, not become a double");
        assertInstanceOf(String.class, typed.get("limbo.forwarding.secret"),
                "\"false\" must stay a string, not become a boolean");
        assertInstanceOf(String.class, typed.get("servers.target"),
                "\"0042\" must stay a string, not become an integer");

        PluginConfig loaded = loader(dir).loadConfig();
        assertEquals("1.0", loaded.database().password());
        assertEquals("false", loaded.limbo().forwarding().secret());
        assertEquals("0042", loaded.targetServer());
    }

    @Test
    void numericLookingMessagesSurviveTheMigration(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path messages = dir.resolve("messages.yml");
        edit(messages, "login.wrong-password: \"Nieprawidłowe hasło.\"",
                "login.wrong-password: \"000123\"");
        // ...and an operator-invented key, which travels through the preserved-keys block instead.
        Files.writeString(messages, Files.readString(messages, StandardCharsets.UTF_8)
                + "\nmoj.kod: \"000123\"\nmoj.przelacznik: \"false\"\nmoj.wersja: \"1.0\"\n",
                StandardCharsets.UTF_8);

        migrator().migrateMessages(messages);
        MessagesConfig loaded = loader(dir).loadMessages();

        assertEquals("000123", loaded.raw("login.wrong-password"));
        assertEquals("000123", loaded.raw("moj.kod"));
        assertEquals("false", loaded.raw("moj.przelacznik"));
        assertEquals("1.0", loaded.raw("moj.wersja"));

        Map<String, Object> typed = ConfigMigrator.flattenFileTyped(messages);
        assertInstanceOf(String.class, typed.get("moj.przelacznik"));
        assertInstanceOf(String.class, typed.get("moj.wersja"));
    }

    @Test
    void realNumbersAndBooleansStayParsableAsNumbersAndBooleans(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        edit(config, "  login-timeout-seconds: 60", "  login-timeout-seconds: 45");
        edit(config, "  bossbar-progress: 1.0", "  bossbar-progress: 0.5");
        edit(config, "  fail-fast: true", "  fail-fast: false");
        edit(config, "  bind-port: 25580", "  bind-port: 25599");

        migrator().migrateConfig(config);
        PluginConfig loaded = loader(dir).loadConfig();

        assertEquals(45L, loaded.loginTimeoutSeconds());
        assertEquals(0.5f, loaded.prompts().bossbarProgress(), 0.0001f);
        assertFalse(loaded.database().failFast());
        assertEquals(25599, loaded.limbo().bindPort());

        Map<String, Object> typed = ConfigMigrator.flattenFileTyped(config);
        assertInstanceOf(Number.class, typed.get("auth.login-timeout-seconds"));
        assertInstanceOf(Number.class, typed.get("prompts.bossbar-progress"));
        assertInstanceOf(Boolean.class, typed.get("database.fail-fast"));
    }

    @Test
    void awkwardCharactersSurviveVerbatim(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        Path messages = dir.resolve("messages.yml");

        String nastyPassword = "p#ss: \"wo\\rd\" #not-a-comment";
        edit(config, "  password: \"\"", "  password: \"p#ss: \\\"wo\\\\rd\\\" #not-a-comment\"");
        Files.writeString(messages, Files.readString(messages, StandardCharsets.UTF_8)
                + "\nmoj.tekst: \"linia1\\nlinia2 # nie komentarz: dwukropek \\\"cytat\\\" \\\\ukośnik\"\n",
                StandardCharsets.UTF_8);

        migrator().migrateConfig(config);
        migrator().migrateMessages(messages);

        assertEquals(nastyPassword, loader(dir).loadConfig().database().password());
        assertEquals("linia1\nlinia2 # nie komentarz: dwukropek \"cytat\" \\ukośnik",
                loader(dir).loadMessages().raw("moj.tekst"));
    }

    @Test
    void migratingTwiceAndReloadingNeverChangesAValue(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        Path messages = dir.resolve("messages.yml");
        edit(config, "  password: \"\"", "  password: \"001234\"");
        edit(config, "    secret: \"\"", "    secret: \"0000123456\"");
        edit(messages, "login.wrong-password: \"Nieprawidłowe hasło.\"", "login.wrong-password: \"000123\"");

        ConfigLoader loader = loader(dir);
        PluginConfig first = loader.loadConfig();
        MessagesConfig firstMessages = loader.loadMessages();
        String configText = Files.readString(config, StandardCharsets.UTF_8);
        String messagesText = Files.readString(messages, StandardCharsets.UTF_8);

        // /hexlimbo reload: the migrator is a no-op and the values must be identical.
        PluginConfig second = loader.loadConfig();
        MessagesConfig secondMessages = loader.loadMessages();

        assertEquals(configText, Files.readString(config, StandardCharsets.UTF_8),
                "a second pass must not rewrite the file");
        assertEquals(messagesText, Files.readString(messages, StandardCharsets.UTF_8));
        assertEquals(first.database().password(), second.database().password());
        assertEquals("001234", second.database().password());
        assertEquals("0000123456", second.limbo().forwarding().secret());
        assertEquals(firstMessages.raw("login.wrong-password"), secondMessages.raw("login.wrong-password"));
        assertEquals("000123", secondMessages.raw("login.wrong-password"));
    }

    // ------------------------------------------------------------ file permissions

    @Test
    void migrationKeepsRestrictiveFilePermissions(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");

        installV1(dir);
        Path config = dir.resolve("config.yml");
        edit(config, "  password: \"\"", "  password: \"top-secret\"");
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(config, ownerOnly);

        ConfigMigrator.Result result = migrator().migrateConfig(config);

        assertTrue(result.migrated(), "precondition: the file must actually have been rewritten");
        assertEquals(ownerOnly, Files.getPosixFilePermissions(config),
                "a config holding a database password must not become readable to others");
        assertEquals("top-secret", loader(dir).loadConfig().database().password());
    }

    @Test
    void theBackupIsNoMoreReadableThanTheOriginal(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");

        installV1(dir);
        Path config = dir.resolve("config.yml");
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(config, ownerOnly);

        migrator().migrateConfig(config);

        Path backup = dir.resolve("config.yml.v1.bak");
        assertTrue(Files.exists(backup));
        assertEquals(ownerOnly, Files.getPosixFilePermissions(backup),
                "the backup carries the same secrets and must carry the same mode");
    }

    @Test
    void noTemporaryFileIsLeftBehind(@TempDir Path dir) throws IOException {
        installV1(dir);

        migrator().migrateConfig(dir.resolve("config.yml"));
        migrator().migrateMessages(dir.resolve("messages.yml"));

        try (var entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".migrating")),
                    "the .migrating scratch file must never survive a migration");
        }
    }

    // ------------------------------------------------- failure injection at each step

    /** Snapshot of everything a failed migration must leave exactly as it was. */
    private record DirectorySnapshot(String config, java.util.List<String> names) {}

    private DirectorySnapshot snapshot(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return new DirectorySnapshot(
                    Files.readString(dir.resolve("config.yml"), StandardCharsets.UTF_8),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    private void assertUntouched(Path dir, DirectorySnapshot before) throws IOException {
        DirectorySnapshot after = snapshot(dir);
        assertEquals(before.config(), after.config(), "the original config must be byte-identical");
        assertEquals(before.names(), after.names(),
                "no file may be added or removed by a failed migration");
        assertTrue(after.names().stream().noneMatch(n -> n.endsWith(".migrating")),
                "no temporary file may be left behind");
    }

    @Test
    void aFailureReadingThePermissionsAbortsWithoutTouchingAnything(@TempDir Path dir) throws IOException {
        installV1(dir);
        DirectorySnapshot before = snapshot(dir);
        RecordingFileOperations files = new RecordingFileOperations().failAt("read", "cannot stat");

        IOException thrown = assertThrows(IOException.class,
                () -> migrator(files).migrateConfig(dir.resolve("config.yml")));

        assertEquals("cannot stat", thrown.getMessage());
        assertUntouched(dir, before);
        assertFalse(files.calls.contains("createTemp"), "nothing may be created after a failed mode read");
    }

    @Test
    void aFailureCreatingTheTemporaryFileAbortsWithoutTouchingAnything(@TempDir Path dir) throws IOException {
        installV1(dir);
        DirectorySnapshot before = snapshot(dir);
        RecordingFileOperations files = new RecordingFileOperations().failAt("createTemp", "no space");

        assertThrows(IOException.class, () -> migrator(files).migrateConfig(dir.resolve("config.yml")));

        assertUntouched(dir, before);
        assertFalse(files.calls.contains("createBackup"), "no backup may be written after a failed temp create");
    }

    @Test
    void aFailureWritingTheTemporaryFileAbortsAndRemovesIt(@TempDir Path dir) throws IOException {
        installV1(dir);
        DirectorySnapshot before = snapshot(dir);
        RecordingFileOperations files = new RecordingFileOperations().failAt("write", "disk full");

        assertThrows(IOException.class, () -> migrator(files).migrateConfig(dir.resolve("config.yml")));

        assertUntouched(dir, before);
        assertTrue(files.calls.contains("delete"), "the temporary file must be cleaned up in finally");
        assertFalse(files.calls.contains("move"), "nothing may be moved into place");
    }

    @Test
    void aFailureCreatingTheBackupAbortsAndLeavesTheOriginalAlone(@TempDir Path dir) throws IOException {
        installV1(dir);
        DirectorySnapshot before = snapshot(dir);
        RecordingFileOperations files = new RecordingFileOperations().failAt("createBackup", "cannot create backup");

        assertThrows(IOException.class, () -> migrator(files).migrateConfig(dir.resolve("config.yml")));

        assertUntouched(dir, before);
        assertFalse(files.calls.contains("move"),
                "the config must not be replaced when it could not be backed up first");
    }

    /**
     * The step the failure chain was missing: the backup file was created, and filling it fails.
     *
     * <p>A backup that exists but holds nothing - or half the file - is worse than no backup at
     * all, because it looks like a usable copy. The one this attempt created must therefore be gone
     * again, leaving the directory exactly as it was found.
     */
    @Test
    void aFailedBackupCopyRemovesTheEmptyBackupItJustCreated(@TempDir Path dir) throws IOException {
        installV1(dir);
        DirectorySnapshot before = snapshot(dir);
        RecordingFileOperations files = new RecordingFileOperations().failAt("copy", "read error mid-copy");

        IOException thrown = assertThrows(IOException.class,
                () -> migrator(files).migrateConfig(dir.resolve("config.yml")));

        assertEquals("read error mid-copy", thrown.getMessage());
        assertTrue(files.calls.contains("createBackup"), "precondition: the backup was created first");
        assertFalse(Files.exists(dir.resolve("config.yml.v1.bak")),
                "an empty or partial backup must not be left behind");
        assertFalse(files.calls.contains("move"),
                "the config must not be replaced when it could not be backed up");
        assertUntouched(dir, before);
    }

    /** The same failure with an older backup already present: that one must not be touched. */
    @Test
    void aFailedBackupCopyLeavesAPreExistingBackupAlone(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path olderBackup = dir.resolve("config.yml.v1.bak");
        Files.writeString(olderBackup, "an older backup nobody may clobber", StandardCharsets.UTF_8);
        DirectorySnapshot before = snapshot(dir);
        RecordingFileOperations files = new RecordingFileOperations().failAt("copy", "read error mid-copy");

        assertThrows(IOException.class, () -> migrator(files).migrateConfig(dir.resolve("config.yml")));

        assertEquals("an older backup nobody may clobber", Files.readString(olderBackup, StandardCharsets.UTF_8),
                "the pre-existing backup is closer to the operator's original and must survive");
        assertUntouched(dir, before);
    }

    /**
     * The case a read-only directory can never reach: everything succeeded up to and including the
     * backup, and the final rename fails. The backup exists but must be intact, and the original
     * must still be the pre-migration file.
     *
     * <p>Keeping it is a deliberate decision, not an oversight: it is a complete, faithful copy of a
     * file that still exists unchanged, and it is exactly what the operator wants on the retry.
     */
    @Test
    void aFailedMoveLeavesTheOriginalAndTheFreshBackupIntact(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        String originalText = Files.readString(config, StandardCharsets.UTF_8);
        RecordingFileOperations files = new RecordingFileOperations().failAt("move", "rename failed");

        assertThrows(IOException.class, () -> migrator(files).migrateConfig(config));

        assertEquals(originalText, Files.readString(config, StandardCharsets.UTF_8),
                "the original must survive a failed move");
        Path backup = dir.resolve("config.yml.v1.bak");
        assertTrue(Files.exists(backup), "the backup was already written at this point");
        assertEquals(originalText, Files.readString(backup, StandardCharsets.UTF_8),
                "and it must hold the untouched original");
        try (var entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".migrating")),
                    "the temporary file must be cleaned up even when the move fails");
        }
    }

    @Test
    void anExistingBackupIsNeverOverwrittenByAFailedOrRepeatedMigration(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        Path backup = dir.resolve("config.yml.v1.bak");
        Files.writeString(backup, "an older backup nobody may clobber", StandardCharsets.UTF_8);

        RecordingFileOperations files = new RecordingFileOperations().failAt("move", "rename failed");
        assertThrows(IOException.class, () -> migrator(files).migrateConfig(config));

        assertEquals("an older backup nobody may clobber", Files.readString(backup, StandardCharsets.UTF_8),
                "an existing backup is closer to the operator's original and must be preserved");
    }

    // -------------------------------------------------- permissions before content

    @Test
    void theTemporaryFileCarriesTheOriginalModeBeforeAnyContentIsWritten(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");

        installV1(dir);
        Path config = dir.resolve("config.yml");
        edit(config, "  password: \"\"", "  password: \"top-secret\"");
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(config, ownerOnly);

        RecordingFileOperations files = new RecordingFileOperations();
        migrator(files).migrateConfig(config);

        assertEquals(1, files.permissionsWhenContentWritten.size(), "exactly one file received content");
        Set<PosixFilePermission> atWriteTime = files.permissionsWhenContentWritten.values().iterator().next();
        assertEquals(ownerOnly, atWriteTime,
                "the secret must never touch the disk under the process umask, not even briefly");
        // ...and the ordering that guarantees it.
        assertTrue(files.calls.indexOf("createTemp") < files.calls.indexOf("write"),
                "the file is created with its mode before it is written");
        assertEquals(ownerOnly, Files.getPosixFilePermissions(config));
    }

    @Test
    void theBackupIsCreatedWithTheOriginalModeBeforeItReceivesTheSecrets(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");

        installV1(dir);
        Path config = dir.resolve("config.yml");
        edit(config, "  password: \"\"", "  password: \"top-secret\"");
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(config, ownerOnly);

        RecordingFileOperations files = new RecordingFileOperations();
        migrator(files).migrateConfig(config);

        assertTrue(files.calls.indexOf("createBackup") < files.calls.indexOf("copy"),
                "the backup is created with its mode before the original is copied into it");
        // ...and the mode it actually had at the instant before the first copied byte, not merely
        // the mode it happens to have once everything is over.
        assertEquals(ownerOnly, files.permissionsWhenCopyStarted.get("config.yml.v1.bak"),
                "the secret must never touch the disk under the process umask, not even briefly");
        assertEquals(ownerOnly, Files.getPosixFilePermissions(dir.resolve("config.yml.v1.bak")),
                "the backup holds the same secrets and must carry the same mode");
    }

    /**
     * The structural half of the same guarantee. Carrying the right mode is only meaningful if the
     * bytes land in the file that was <em>created</em> with that mode; a copy free to replace its
     * destination would unlink that file and make a new one under the umask, and the mode would then
     * be right only by accident. The file's identity on disk must be unchanged across the copy.
     */
    @Test
    void theBackupCopyWritesThroughToTheFileItWasCreatedAsRatherThanReplacingIt(@TempDir Path dir)
            throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        String originalText = Files.readString(config, StandardCharsets.UTF_8);

        RecordingFileOperations files = new RecordingFileOperations();
        migrator(files).migrateConfig(config);

        Object created = files.fileKeyAtCreate.get("config.yml.v1.bak");
        Object afterCopy = files.fileKeyAfterCopy.get("config.yml.v1.bak");
        assertNotNull(created, "precondition: the backup was created through createFile");
        assertNotNull(afterCopy, "precondition: the backup was filled through copyContent");
        assertEquals(created, afterCopy,
                "the prepared backup file must be written through, never replaced by a fresh one");
        assertEquals(originalText, Files.readString(dir.resolve("config.yml.v1.bak"), StandardCharsets.UTF_8),
                "and it must still hold a faithful copy of the original");
    }

    /**
     * The API-level proof of the same thing: {@code copyContent} refuses a destination that does not
     * exist. It can only ever write into a file somebody already created with the intended mode, so
     * the ordering guarantee cannot be lost to a later refactor of the caller.
     */
    @Test
    void copyContentRefusesToCreateItsTargetAndSoCannotReplaceAPreparedInode(@TempDir Path dir)
            throws IOException {
        Path source = dir.resolve("source.yml");
        Files.writeString(source, "secret: value\n", StandardCharsets.UTF_8);
        Path missingTarget = dir.resolve("not-created-yet.bak");

        assertThrows(NoSuchFileException.class,
                () -> new FileOperations.Default().copyContent(source, missingTarget));

        assertFalse(Files.exists(missingTarget), "no file may be conjured up by a copy");
    }
}
