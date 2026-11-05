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
    private static final int ITERATIONS = 120_000;

    private Auth() {}

    public static String newSalt() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    public static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String password, String salt, String expectedHash) {
        byte[] a = hash(password, salt).getBytes(StandardCharsets.UTF_8);
        byte[] b = expectedHash.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
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
