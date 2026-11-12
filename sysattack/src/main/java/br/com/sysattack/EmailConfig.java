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
            + " last_test_at, last_test_ok, last_test_msg, (password_enc <> '') has_password,"
            + " auth_mode, tenant_id, client_id, (client_secret_enc <> '') has_secret";

    static Map<String, Object> status() throws SQLException {
        Map<String, Object> row = Db.one("SELECT " + COLS + " FROM email_settings WHERE id = 1");
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) {
            out.put("configured", false);
            Properties f = fileConfig();
            out.put("file_fallback", !f.getProperty("smtp.host", "").isBlank());
            out.put("host", ""); out.put("port", 587); out.put("security", "starttls"); out.put("username", "");
            out.put("from_email", ""); out.put("from_name", "SysAttack"); out.put("enabled", true); out.put("has_password", false);
            out.put("auth_mode", "BASICO"); out.put("tenant_id", ""); out.put("client_id", ""); out.put("has_secret", false);
            return out;
        }
        out.put("configured", true);
        out.putAll(row);
        out.put("log_dir", new java.io.File(Db.dataDir(), "email-logs").getAbsolutePath());
        return out;
    }

    private static Properties fileConfig() {
        Properties p = AppConfig.load();
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
        String mode = (String) r.getOrDefault("auth_mode", "BASICO");
        p.setProperty("smtp.authmode", mode == null || mode.isBlank() ? "BASICO" : mode);
        p.setProperty("smtp.tenant", str(r.get("tenant_id")));
        p.setProperty("smtp.clientid", str(r.get("client_id")));
        String sec = (String) r.get("client_secret_enc");
        p.setProperty("smtp.clientsecret", sec == null || sec.isEmpty() ? "" : AiConfig.decrypt(sec));
        String user = (String) r.get("username");
        String from = (String) r.get("from_email");
        p.setProperty("smtp.from", from == null || from.isBlank() ? user : from);
        p.setProperty("smtp.fromname", ((String) r.get("from_name")).isBlank() ? "SysAttack" : (String) r.get("from_name"));
        return p;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** Salva (cria ou atualiza). Senha e segredo em branco mantêm os já guardados. */
    static Map<String, Object> save(String host, int port, String security, String username, String password,
                                    String fromEmail, String fromName, boolean enabled, String authMode,
                                    String tenantId, String clientId, String clientSecret, String by) throws Exception {
        Map<String, Object> old = Db.one("SELECT password_enc, client_secret_enc FROM email_settings WHERE id = 1");
        String enc = old == null ? "" : (String) old.get("password_enc");
        if (password != null && !password.isEmpty()) enc = AiConfig.encrypt(password);
        else if (username.isEmpty()) enc = "";
        String secEnc = old == null ? "" : str(old.get("client_secret_enc"));
        if (clientSecret != null && !clientSecret.isEmpty()) secEnc = AiConfig.encrypt(clientSecret);
        boolean oauth = "OAUTH2".equals(authMode);
        if (oauth) {
            if (tenantId.isEmpty() || clientId.isEmpty()) throw new ApiServlet.ApiException(400, "Informe o locatário e o ID do aplicativo.");
            if (secEnc.isEmpty()) throw new ApiServlet.ApiException(400, "Informe o segredo do cliente.");
            if (username.isEmpty()) throw new ApiServlet.ApiException(400, "Informe a caixa remetente no campo Usuário: é ela que o aplicativo representa.");
            enc = ""; // no OAuth não existe senha
        } else {
            tenantId = ""; clientId = ""; secEnc = "";
        }
        MailOAuth.forget(tenantId, clientId); // configuração nova, token antigo não vale mais
        Db.update("DELETE FROM email_settings WHERE id = 1");
        Db.update("INSERT INTO email_settings(id,host,port,security,username,password_enc,from_email,from_name,enabled,updated_by,"
                + "auth_mode,tenant_id,client_id,client_secret_enc) VALUES(1,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                host, port, security, username, enc, fromEmail, fromName, enabled, by, oauth ? "OAUTH2" : "BASICO", tenantId, clientId, secEnc);
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
        String subject = "SysAttack · teste de e-mail";
        java.time.ZonedDateTime started = java.time.ZonedDateTime.now();
        long t0 = System.nanoTime();
        java.io.ByteArrayOutputStream dialog = new java.io.ByteArrayOutputStream();
        StringBuilder oauthTrace = new StringBuilder();
        Throwable error = null;
        try {
            MailOAuth.TRACE.set(oauthTrace);
            Mailer.DEBUG_OUT.set(new java.io.PrintStream(dialog, true, "UTF-8"));
            boolean sent = Mailer.sendHtml(toProps(r), List.of(to), subject,
                    "<p style=\"font-family:Arial,sans-serif\">Este é um e-mail de teste do <b>SysAttack</b>, enviado por " + EmailTriggers.h(by)
                    + ".<br>Se você recebeu, o e-mail de saída está funcionando.</p>", null);
            ok = sent;
            msg = sent ? "Enviado para " + to + "." : "Modo de teste: nada foi enviado.";
        } catch (Exception e) {
            ok = false;
            error = e;
            String hint = MailOAuth.smtpHint(rootMessage(e));
            msg = shorten(rootMessage(e) + (hint == null ? "" : " " + hint));
            LOG.warning("Teste de e-mail falhou: " + e);
        } finally {
            Mailer.DEBUG_OUT.remove();
            MailOAuth.TRACE.remove();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Db.update("UPDATE email_settings SET last_test_at = CURRENT_TIMESTAMP, last_test_ok = ?, last_test_msg = ? WHERE id = 1", ok, msg);
        try {
            saveTestLog(r, to, by, subject, started, ms, ok, msg, error, dialog.toString("UTF-8"), oauthTrace.toString());
        } catch (Exception e) {
            LOG.warning("Não foi possível gravar o log do teste de e-mail: " + e); // o log nunca pode derrubar o teste
        }
        return status();
    }

    /** Monta o texto do log (sem senha), grava no banco e numa pasta de logs para extração. */
    private static void saveTestLog(Map<String, Object> r, String to, String by, String subject, java.time.ZonedDateTime started, long ms,
                                    boolean ok, String msg, Throwable error, String dialog, String oauthTrace) throws Exception {
        String stamp = started.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        StringBuilder b = new StringBuilder();
        b.append("SysAttack · LOG DE TESTE DE E-MAIL\n").append("=".repeat(60)).append('\n');
        b.append("Data/hora      : ").append(started.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss xxx"))).append('\n');
        b.append("Disparado por  : ").append(by).append('\n');
        b.append("Resultado      : ").append(ok ? "SUCESSO" : "FALHA").append(" — ").append(msg).append('\n');
        b.append("Duração        : ").append(ms).append(" ms\n\n");
        b.append("[Mensagem]\n");
        b.append("Para           : ").append(to).append('\n');
        b.append("Assunto        : ").append(subject).append("\n\n");
        b.append("[Configuração SMTP usada] (a senha nunca é gravada)\n");
        b.append("Servidor       : ").append(r.get("host")).append('\n');
        b.append("Porta          : ").append(r.get("port")).append('\n');
        b.append("Segurança      : ").append(r.get("security")).append('\n');
        b.append("Usuário        : ").append(r.get("username")).append('\n');
        boolean oauth = "OAUTH2".equals(str(r.get("auth_mode")));
        b.append("Autenticação   : ").append(((String) r.get("username")).isEmpty() ? "não"
                : oauth ? "OAuth 2.0 (Microsoft 365)" : "usuário e senha").append('\n');
        if (oauth) { // nunca gravamos o segredo nem o token
            b.append("Locatário      : ").append(str(r.get("tenant_id"))).append('\n');
            b.append("Aplicativo     : ").append(str(r.get("client_id"))).append('\n');
            b.append("Segredo        : ").append(str(r.get("client_secret_enc")).isEmpty() ? "NÃO informado" : "guardado (não é gravado no log)").append('\n');
            b.append("Escopo         : https://outlook.office365.com/.default\n");
        }
        b.append("Remetente      : ").append(r.get("from_name")).append(" <").append(r.get("from_email")).append(">\n");
        b.append("Envio ativo    : ").append(Boolean.TRUE.equals(r.get("enabled")) ? "sim" : "não").append("\n\n");
        b.append("[Ambiente]\n");
        b.append("Java           : ").append(System.getProperty("java.version")).append('\n');
        b.append("Servidor de app: ").append(System.getProperty("catalina.base", "—")).append("\n\n");
        if (oauth) {
            b.append("[OAuth 2.0 — obtenção do token]\n");
            b.append(oauthTrace == null || oauthTrace.isBlank() ? "(sem registro: o token não chegou a ser pedido)\n" : oauthTrace);
            b.append('\n');
        }
        b.append("[Conversa com o servidor SMTP]\n");
        b.append(dialog.isBlank() ? "(sem registro: a conexão não chegou a ser aberta)\n" : dialog.trim() + "\n").append('\n');
        if (error != null) {
            String hint = MailOAuth.smtpHint(rootMessage(error));
            if (hint != null) b.append("[O que costuma resolver]\n").append(hint).append("\n\n");
            b.append("[Erro]\n");
            int depth = 0;
            for (Throwable t = error; t != null && depth < 8; t = t.getCause() == t ? null : t.getCause(), depth++) {
                b.append(depth == 0 ? "Exceção       : " : "Causada por   : ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
            }
            java.io.StringWriter sw = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(sw));
            String st = sw.toString();
            b.append("\n[Stack trace]\n").append(st.length() > 12000 ? st.substring(0, 12000) + "\n…(cortado)" : st).append('\n');
        }
        b.append("\n").append("=".repeat(60)).append("\nFim do log.\n");
        String text = b.toString();
        if (text.length() > 400000) text = text.substring(0, 400000);
        String fileName = "email-teste-" + stamp + (ok ? "-ok" : "-falha") + ".log";
        Db.insert("INSERT INTO email_test_log(created_by,recipient,ok,summary,file_name,log) VALUES(?,?,?,?,?,?)", by, to, ok, msg, fileName, text);
        java.io.File dir = new java.io.File(Db.dataDir(), "email-logs");
        dir.mkdirs();
        java.nio.file.Files.write(new java.io.File(dir, fileName).toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (e instanceof Mailer.NotConfigured) return "SMTP não configurado.";
        if (e instanceof MailOAuth.OAuthException) return e.getMessage();
        return m;
    }

    private static String shorten(String s) {
        s = s.replaceAll("[\\r\\n]+", " ").trim();
        return s.length() > 280 ? s.substring(0, 279) + "…" : s;
    }
}
