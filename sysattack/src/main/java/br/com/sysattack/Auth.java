package br.com.sysattack;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Hash de senha com PBKDF2 (nunca guardamos a senha em texto). */
public final class Auth {
    private static final SecureRandom RND = new SecureRandom();
    private static final int LEGACY_ITERATIONS = 120_000;
    /** OWASP: PBKDF2-HMAC-SHA256 com 600 mil iterações. Hashes novos levam o prefixo "v2$<iterações>$". */
    private static final int ITERATIONS = 600_000;

    private Auth() {}

    public static String newSalt() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static String pbkdf2(String password, String salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), iterations, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(String password, String salt) {
        return "v2$" + ITERATIONS + "$" + pbkdf2(password, salt, ITERATIONS);
    }

    public static boolean verify(String password, String salt, String expectedHash) {
        String calc;
        if (expectedHash.startsWith("v2$")) {
            String[] p = expectedHash.split("\\$", 3);
            if (p.length != 3) return false;
            int it;
            try { it = Integer.parseInt(p[1]); } catch (NumberFormatException e) { return false; }
            if (it < 100_000 || it > 5_000_000) return false;
            calc = "v2$" + it + "$" + pbkdf2(password, salt, it);
        } else {
            calc = pbkdf2(password, salt, LEGACY_ITERATIONS); // hash antigo (antes do v2)
        }
        return MessageDigest.isEqual(calc.getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** true se o hash guardado usa parâmetros antigos e deve ser refeito no próximo login bem-sucedido. */
    public static boolean needsRehash(String storedHash) {
        return !storedHash.startsWith("v2$" + ITERATIONS + "$");
    }

    public static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isEmail(String s) {
        return s != null && s.length() <= 120 && s.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    }

    /** Política de senha forte. Retorna a mensagem de erro, ou null se a senha for aceita. */
    public static String checkPassword(String pw, String email) {
        if (pw == null || pw.length() < 10) return "A senha deve ter pelo menos 10 caracteres.";
        if (pw.length() > 128) return "A senha deve ter no máximo 128 caracteres.";
        if (!pw.matches(".*[a-z].*")) return "A senha precisa de ao menos uma letra minúscula.";
        if (!pw.matches(".*[A-Z].*")) return "A senha precisa de ao menos uma letra maiúscula.";
        if (!pw.matches(".*\\d.*")) return "A senha precisa de ao menos um número.";
        if (!pw.matches(".*[^A-Za-z0-9].*")) return "A senha precisa de ao menos um símbolo (ex.: ! @ # $ %).";
        String local = email == null ? "" : email.split("@")[0].toLowerCase();
        if (local.length() >= 4 && pw.toLowerCase().contains(local)) return "A senha não pode conter o seu e-mail.";
        return null;
    }
}
