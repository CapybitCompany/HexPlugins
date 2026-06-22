package hex.limbo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Hashes IP addresses with a per-instance pepper so the database never stores raw IPs.
 * SHA-256 is fast and gives 64-hex output; pepper prevents trivial rainbow attacks if the DB leaks.
 */
public final class IpHasher {

    private final byte[] pepper;

    public IpHasher(String pepper) {
        Objects.requireNonNull(pepper, "pepper");
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(pepper);
            md.update(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
