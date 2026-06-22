package hex.limbo.auth;

import org.mindrot.jbcrypt.BCrypt;

/**
 * BCrypt-based password hasher. Per-password salt is generated automatically by {@code gensalt}
 * and stored inside the resulting hash string. Cost factor 12 balances safety with proxy CPU use.
 */
public final class PasswordHasher {

    private static final int DEFAULT_COST = 12;

    private final int cost;

    public PasswordHasher() {
        this(DEFAULT_COST);
    }

    public PasswordHasher(int cost) {
        if (cost < 4 || cost > 16) {
            throw new IllegalArgumentException("Cost factor must be in [4, 16]");
        }
        this.cost = cost;
    }

    public String hash(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Password must not be null");
        }
        return BCrypt.hashpw(plaintext, BCrypt.gensalt(cost));
    }

    public boolean verify(String plaintext, String hash) {
        if (plaintext == null || hash == null || hash.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plaintext, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
