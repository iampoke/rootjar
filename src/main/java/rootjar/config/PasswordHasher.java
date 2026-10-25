package rootjar.config;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2WithHmacSHA256, 210,000 iterations, random 16-byte salt per hash.
 * Stored format: pbkdf2$sha256$210000$<saltBase64>$<hashBase64>
 * The iteration count is stored in the hash, so it can be raised later without breaking old hashes.
 */
public final class PasswordHasher {
    public static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private PasswordHasher() {}

    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$sha256$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(key);
    }

    public static boolean verify(char[] password, String stored) {
        Parsed p = parse(stored);
        if (p == null) return false;
        byte[] actual = derive(password, p.salt, p.iterations);
        return MessageDigest.isEqual(actual, p.hash); // constant-time compare
    }

    public static boolean isWellFormed(String stored) {
        return parse(stored) != null;
    }

    private record Parsed(int iterations, byte[] salt, byte[] hash) {}

    private static Parsed parse(String stored) {
        if (stored == null) return null;
        String[] f = stored.split("\\$");
        if (f.length != 5 || !f[0].equals("pbkdf2") || !f[1].equals("sha256")) return null;
        try {
            int it = Integer.parseInt(f[2]);
            if (it < 1) return null;
            byte[] salt = Base64.getDecoder().decode(f[3]);
            byte[] hash = Base64.getDecoder().decode(f[4]);
            if (salt.length == 0 || hash.length == 0) return null;
            return new Parsed(it, salt, hash);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available in this JVM", e);
        } finally {
            spec.clearPassword();
        }
    }
}
