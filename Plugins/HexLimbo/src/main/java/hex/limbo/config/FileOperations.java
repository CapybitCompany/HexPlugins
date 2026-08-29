package hex.limbo.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The filesystem operations {@link ConfigMigrator} performs, behind an interface so tests can make
 * exactly one of them fail and assert on the recovery.
 *
 * <p>The permission-carrying creation methods exist because {@code config.yml} holds a database
 * password and a Velocity forwarding secret. Creating a file and chmodding it afterwards leaves a
 * window - however short - in which those secrets sit on disk with the process umask, typically
 * {@code 0644}. Every method here that produces a new file therefore takes the intended mode and
 * applies it <em>as a creation attribute</em>, so the file never exists with wider permissions than
 * the original, not even for an instant.
 */
public interface FileOperations {

    /** Whether {@code file}'s filesystem exposes POSIX permissions at all. */
    boolean supportsPosixPermissions(Path file);

    /**
     * The current POSIX mode of {@code file}.
     *
     * @throws IOException if the mode cannot be read on a filesystem that claims POSIX support -
     *                     the caller must abort rather than guess
     */
    Set<PosixFilePermission> readPermissions(Path file) throws IOException;

    /**
     * Creates a uniquely named temporary file in {@code directory} that already carries
     * {@code permissions}, before any content is written to it.
     *
     * @param permissions the mode to create with, or {@code null} on a non-POSIX filesystem
     */
    Path createTemporaryFile(Path directory, String prefix, Set<PosixFilePermission> permissions) throws IOException;

    /**
     * Creates {@code file}, which must not exist, already carrying {@code permissions}.
     *
     * @param permissions the mode to create with, or {@code null} on a non-POSIX filesystem
     */
    void createFile(Path file, Set<PosixFilePermission> permissions) throws IOException;

    /** Writes UTF-8 content into an existing file, truncating it. */
    void writeString(Path file, String content) throws IOException;

    /** Reads UTF-8 content. */
    String readString(Path file) throws IOException;

    /**
     * Copies the bytes of {@code source} <em>into</em> the already-created {@code target}, keeping
     * that exact file.
     *
     * <p>The distinction is the whole point of the method. {@code target} was created by
     * {@link #createFile} carrying the original's mode, precisely so that a config holding a
     * database password never exists on disk under the process umask. A copy that is allowed to
     * replace the destination would throw that file away and put a fresh, default-mode one in its
     * place, and the guarantee would only hold by luck. Implementations must therefore open the
     * existing file for writing and truncate it - never create, never replace - so that a missing
     * {@code target} is an error rather than something quietly created.
     */
    void copyContent(Path source, Path target) throws IOException;

    /**
     * Moves {@code source} onto {@code target}, atomically where the filesystem supports it.
     * Implementations must fall back to a plain replace <em>only</em> for
     * {@link AtomicMoveNotSupportedException}; every other {@link IOException} is a real failure and
     * has to propagate.
     */
    void move(Path source, Path target) throws IOException;

    void deleteIfExists(Path file) throws IOException;

    boolean exists(Path file);

    /** The real filesystem. */
    final class Default implements FileOperations {

        @Override
        public boolean supportsPosixPermissions(Path file) {
            return Files.getFileAttributeView(file, PosixFileAttributeView.class) != null;
        }

        @Override
        public Set<PosixFilePermission> readPermissions(Path file) throws IOException {
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view == null) {
                return null;
            }
            try {
                return view.readAttributes().permissions();
            } catch (UnsupportedOperationException | SecurityException ex) {
                throw new IOException("Cannot read the POSIX permissions of " + file, ex);
            }
        }

        @Override
        public Path createTemporaryFile(Path directory, String prefix, Set<PosixFilePermission> permissions)
                throws IOException {
            try {
                return Files.createTempFile(directory, prefix, ".migrating", attributes(permissions));
            } catch (UnsupportedOperationException | SecurityException ex) {
                throw new IOException("Cannot create a temporary file in " + directory
                        + " with the required permissions", ex);
            }
        }

        @Override
        public void createFile(Path file, Set<PosixFilePermission> permissions) throws IOException {
            try {
                Files.createFile(file, attributes(permissions));
            } catch (UnsupportedOperationException | SecurityException ex) {
                throw new IOException("Cannot create " + file + " with the required permissions", ex);
            }
        }

        private static FileAttribute<?>[] attributes(Set<PosixFilePermission> permissions) {
            return permissions == null
                    ? new FileAttribute<?>[0]
                    : new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(permissions)};
        }

        @Override
        public void writeString(Path file, String content) throws IOException {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        @Override
        public String readString(Path file) throws IOException {
            return Files.readString(file, StandardCharsets.UTF_8);
        }

        @Override
        public void copyContent(Path source, Path target) throws IOException {
            // WRITE + TRUNCATE_EXISTING and no CREATE: this writes through to the inode createFile
            // already made with the right mode. Files.copy(..., REPLACE_EXISTING) would be free to
            // unlink that file and create a new one under the umask instead.
            try (InputStream in = Files.newInputStream(source);
                 OutputStream out = Files.newOutputStream(
                         target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public void deleteIfExists(Path file) throws IOException {
            Files.deleteIfExists(file);
        }

        @Override
        public boolean exists(Path file) {
            return Files.exists(file);
        }
    }
}
