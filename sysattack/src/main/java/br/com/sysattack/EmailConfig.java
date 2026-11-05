package br.com.sysattack;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/** Configuração do e-mail de saída (SMTP) feita pela tela. A senha é guardada criptografada (mesma chave mestra da IA). */
final class EmailConfig {
    private static final Logger LOG = Logger.getLogger("sysattack");
    private EmailConfig() {}

    private static final String COLS = "host, port, security, username, from_email, from_name, enabled, updated_by, updated_at,"
            + " last_test_at, last_test_ok, last_test_msg, (password_enc <> '') has_password";

    static Map<String, Object> status() throws SQLException {
        Map<String, Object> row = Db.one("SELECT " + COLS + " FROM email_settings WHERE id = 1");
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) {
            out.put("configured", false);
            Properties f = fileConfig();
            out.put("file_fallback", !f.getProperty("smtp.host", "").isBlank());
            out.put("host", ""); out.put("port", 587); out.put("security", "starttls"); out.put("username", "");
            out.put("from_email", ""); out.put("from_name", "SysAttack"); out.put("enabled", true); out.put("has_password", false);
            return out;
        }
        out.put("configured", true);
        out.putAll(row);
        return out;
    }

    private static Properties fileConfig() {
        Properties p = new Properties();
        java.io.File f = new java.io.File(Db.dataDir(), "config.properties");
        if (f.isFile()) {
            try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (java.io.IOException ignored) {
                // sem arquivo legível: segue sem fallback
            }
        }
        return p;
    }

    /** Propriedades smtp.* para o Mailer; null se não há configuração ativa no banco. */
    static Properties effective() {
        try {
            Map<String, Object> r = Db.one("SELECT * FROM email_settings WHERE id = 1");
            if (r == null || !Boolean.TRUE.equals(r.get("enabled")) || ((String) r.get("host")).isBlank()) return null;
            return toProps(r);
        } catch (Exception e) {
            LOG.warning("Não foi possível ler a configuração de e-mail do banco: " + e.getMessage());
            return null;
        }
    }

    private static Properties toProps(Map<String, Object> r) throws Exception {
        Properties p = new Properties();
        p.setProperty("smtp.host", (String) r.get("host"));
        p.setProperty("smtp.port", String.valueOf(r.get("port")));
        p.setProperty("smtp.security", (String) r.get("security"));
        p.setProperty("smtp.user", (String) r.get("username"));
        String enc = (String) r.get("password_enc");
        p.setProperty("smtp.password", enc == null || enc.isEmpty() ? "" : AiConfig.decrypt(enc));
        String user = (String) r.get("username");
        String from = (String) r.get("from_email");
        p.setProperty("smtp.from", from == null || from.isBlank() ? user : from);
        p.setProperty("smtp.fromname", ((String) r.get("from_name")).isBlank() ? "SysAttack" : (String) r.get("from_name"));
        return p;
    }

    /** Salva (cria ou atualiza). Senha em branco mantém a já guardada. */
    static Map<String, Object> save(String host, int port, String security, String username, String password,
                                    String fromEmail, String fromName, boolean enabled, String by) throws Exception {
        Map<String, Object> old = Db.one("SELECT password_enc FROM email_settings WHERE id = 1");
        String enc = old == null ? "" : (String) old.get("password_enc");
        if (password != null && !password.isEmpty()) enc = AiConfig.encrypt(password);
        else if (username.isEmpty()) enc = "";
        Db.update("DELETE FROM email_settings WHERE id = 1");
        Db.update("INSERT INTO email_settings(id,host,port,security,username,password_enc,from_email,from_name,enabled,updated_by)"
                + " VALUES(1,?,?,?,?,?,?,?,?,?)", host, port, security, username, enc, fromEmail, fromName, enabled, by);
        return status();
    }

    static Map<String, Object> remove() throws SQLException {
        Db.update("DELETE FROM email_settings WHERE id = 1");
        return status();
    }

    /** Envia um e-mail de teste com a configuração salva e registra o resultado. */
    static Map<String, Object> test(String to, String by) throws Exception {
        Map<String, Object> r = Db.one("SELECT * FROM email_settings WHERE id = 1");
        if (r == null) throw new ApiServlet.ApiException(400, "Salve a configuração antes de testar.");
        boolean ok;
        String msg;
        try {
            boolean sent = Mailer.sendHtml(toProps(r), List.of(to), "SysAttack · teste de e-mail",
                    "<p style=\"font-family:Arial,sans-serif\">Este é um e-mail de teste do <b>SysAttack</b>, enviado por " + EmailTriggers.h(by)
                    + ".<br>Se você recebeu, o e-mail de saída está funcionando.</p>", null);
            ok = sent;
            msg = sent ? "Enviado para " + to + "." : "Modo de teste: nada foi enviado.";
        } catch (Exception e) {
            ok = false;
            msg = shorten(rootMessage(e));
            LOG.warning("Teste de e-mail falhou: " + e);
        }
        Db.update("UPDATE email_settings SET last_test_at = CURRENT_TIMESTAMP, last_test_ok = ?, last_test_msg = ? WHERE id = 1", ok, msg);
        return status();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (e instanceof Mailer.NotConfigured) return "SMTP não configurado.";
        return m;
    }

    private static String shorten(String s) {
        s = s.replaceAll("[\\r\\n]+", " ").trim();
        return s.length() > 280 ? s.substring(0, 279) + "…" : s;
    }
}
