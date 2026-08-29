package hex.limbo.testsupport;

import hex.limbo.config.FileOperations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps the real {@link FileOperations} so a test can observe the exact order of filesystem calls
 * and make precisely one of them fail.
 *
 * <p>Failing a specific step is what lets a test prove the recovery path it means to prove. A
 * read-only directory, by contrast, fails whichever step happens to come first, so it cannot show
 * that a failure at the <em>move</em> leaves the backup intact.
 *
 * <p>{@link #permissionsWhenContentWritten} answers the ordering question at the heart of the
 * finding: it records what the temporary file's mode was at the moment the first content byte was
 * written. If the mode is only applied after writing, this is the process umask and the secret was
 * briefly world-readable.
 *
 * <p>{@link #permissionsWhenCopyStarted} does the same for the backup, and the {@code fileKey}
 * pair - {@link #fileKeyAtCreate} against {@link #fileKeyAfterCopy} - answers the sharper question
 * behind it: not just "did the mode look right", but "was this the same file at all". A copy that
 * is allowed to replace its destination produces the right mode only by luck, and shows up here as
 * two different keys.
 */
public final class RecordingFileOperations implements FileOperations {

    /** Operation names a test can fail: read, createTemp, write, createBackup, copy, move. */
    private final Map<String, IOException> failures = new LinkedHashMap<>();
    private final FileOperations delegate = new FileOperations.Default();

    public final List<String> calls = new ArrayList<>();
    /** Mode the temporary file had when its content was first written, keyed by file name. */
    public final Map<String, Set<PosixFilePermission>> permissionsWhenContentWritten = new LinkedHashMap<>();
    /** Mode the copy target had immediately before its first content byte, keyed by file name. */
    public final Map<String, Set<PosixFilePermission>> permissionsWhenCopyStarted = new LinkedHashMap<>();
    /** Filesystem identity of each file at the moment {@code createFile} produced it. */
    public final Map<String, Object> fileKeyAtCreate = new LinkedHashMap<>();
    /** Filesystem identity of each copy target once the copy returned. */
    public final Map<String, Object> fileKeyAfterCopy = new LinkedHashMap<>();

    public RecordingFileOperations failAt(String operation, String message) {
        failures.put(operation, new IOException(message));
        return this;
    }

    private void gate(String operation) throws IOException {
        calls.add(operation);
        IOException failure = failures.get(operation);
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean supportsPosixPermissions(Path file) {
        return delegate.supportsPosixPermissions(file);
    }

    @Override
    public Set<PosixFilePermission> readPermissions(Path file) throws IOException {
        gate("read");
        return delegate.readPermissions(file);
    }

    @Override
    public Path createTemporaryFile(Path directory, String prefix, Set<PosixFilePermission> permissions)
            throws IOException {
        gate("createTemp");
        return delegate.createTemporaryFile(directory, prefix, permissions);
    }

    @Override
    public void createFile(Path file, Set<PosixFilePermission> permissions) throws IOException {
        gate("createBackup");
        delegate.createFile(file, permissions);
        fileKeyAtCreate.put(file.getFileName().toString(), fileKey(file));
    }

    /** The file's identity on disk, so a test can tell "written through" from "replaced". */
    private static Object fileKey(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    }

    @Override
    public void writeString(Path file, String content) throws IOException {
        gate("write");
        if (delegate.supportsPosixPermissions(file)) {
            permissionsWhenContentWritten.put(file.getFileName().toString(),
                    Files.getPosixFilePermissions(file));
        }
        delegate.writeString(file, content);
    }

    @Override
    public String readString(Path file) throws IOException {
        return delegate.readString(file);
    }

    @Override
    public void copyContent(Path source, Path target) throws IOException {
        gate("copy");
        String name = target.getFileName().toString();
        if (delegate.supportsPosixPermissions(target)) {
            permissionsWhenCopyStarted.put(name, Files.getPosixFilePermissions(target));
        }
        delegate.copyContent(source, target);
        fileKeyAfterCopy.put(name, fileKey(target));
    }

    @Override
    public void move(Path source, Path target) throws IOException {
        gate("move");
        delegate.move(source, target);
    }

    @Override
    public void deleteIfExists(Path file) throws IOException {
        calls.add("delete");
        delegate.deleteIfExists(file);
    }

    @Override
    public boolean exists(Path file) {
        return delegate.exists(file);
    }
}
