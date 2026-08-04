package com.aiengineering.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Two responsibilities, both about refresh tokens:
 *
 * 1. generateOpaqueToken() - a cryptographically random string handed to the
 *    client. UUID.randomUUID() (what this replaces) is fine for uniqueness
 *    but isn't specified to be cryptographically unpredictable - SecureRandom
 *    is the right primitive when the value doubles as a bearer credential.
 *
 * 2. sha256Hex() - hashes that token before it's stored in the database.
 *    We store ONLY the hash (see RefreshToken entity). On refresh/logout,
 *    the incoming raw token is hashed again and compared against the stored
 *    hash - same pattern as password storage, applied to a bearer token.
 */
public final class TokenUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TokenUtil() {}

    public static String generateOpaqueToken() {
        byte[] bytes = new byte[32]; // 256 bits of entropy
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every standard JVM - this
            // branch existing is a formality the compiler requires.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Constant-time string comparison for secrets (OAuth state, internal
     * service secret, etc). A plain String.equals() returns as soon as it
     * finds a mismatched character, which means comparison time correlates
     * with how many leading characters matched - in principle, an attacker
     * measuring response times could use that to guess a secret one
     * character at a time. MessageDigest.isEqual is specified to run in
     * time that depends only on the length of the inputs, not their content.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
