package br.com.sysattack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verificação em duas etapas (TOTP, RFC 6238): 6 dígitos, passo de 30 s, HMAC-SHA1 — compatível com Microsoft/Google Authenticator, Authy etc. */
final class Totp {
    private Totp() {}

    private static final SecureRandom RND = new SecureRandom();
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sem 0/O/1/I

    static String newSecret() {
        byte[] b = new byte[20];
        RND.nextBytes(b);
        return base32(b);
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : data) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char c : s.toUpperCase().replace("=", "").replace(" ", "").toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("base32");
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) { out.write((buf >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }

    static String code(String secret, long step) {
        try {
            byte[] msg = new byte[8];
            for (int i = 7, v = 0; i >= 0; i--) msg[i] = (byte) (step >>> (8 * (7 - i)));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static long currentStep() { return Instant.now().getEpochSecond() / 30; }

    /**
     * Confere o código nos passos atual ±1 (tolera relógio levemente fora). Devolve o passo aceito ou -1.
     * Passos menores ou iguais a {@code lastUsed} são recusados (um código não vale duas vezes).
     */
    static long verify(String secret, String code, long lastUsed) {
        return verifyAt(secret, code, lastUsed, currentStep());
    }

    static long verifyAt(String secret, String code, long lastUsed, long now) {
        if (code == null || !code.matches("\\d{6}")) return -1;
        long found = -1;
        for (long s = now - 1; s <= now + 1; s++) { // percorre os três sempre (tempo constante)
            boolean eq = MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
            if (eq && s > lastUsed) found = s;
        }
        return found;
    }

    static String uri(String issuer, String account, String secret) {
        return "otpauth://totp/" + enc(issuer) + ":" + enc(account) + "?secret=" + secret + "&issuer=" + enc(issuer) + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    /** Código de recuperação no formato XXXXX-XXXXX. */
    static String newRecoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(RECOVERY_ALPHABET.charAt(RND.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    static String recoveryHash(String code) {
        return Auth.sha256(code.toUpperCase().replaceAll("[^A-Z0-9]", ""));
    }
}
