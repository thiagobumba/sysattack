package br.com.sysattack;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.logging.Logger;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * Envio de e-mail via SMTP. Configuração em  &lt;pasta de dados&gt;/config.properties  (lida a cada envio):
 *
 *   smtp.host=smtp.office365.com
 *   smtp.port=587
 *   smtp.security=starttls        (starttls | ssl | none)
 *   smtp.user=sysattack@empresa.com.br
 *   smtp.password=********
 *   smtp.from=sysattack@empresa.com.br
 *   smtp.dev=true                 (somente testes: não envia, escreve o código no log do Tomcat)
 */
public final class Mailer {
    private static final Logger LOG = Logger.getLogger("sysattack");

    public static class NotConfigured extends Exception {
        public NotConfigured() { super("SMTP não configurado"); }
    }

    private Mailer() {}

    private static Properties config() {
        Properties db = EmailConfig.effective(); // configuração feita na tela tem prioridade sobre o arquivo
        if (db != null) return db;
        Properties p = new Properties();
        File f = new File(Db.dataDir(), "config.properties");
        if (f.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                LOG.warning("Não foi possível ler " + f + ": " + e.getMessage());
            }
        }
        return p;
    }

    public static void sendCode(String to, String name, String code) throws Exception {
        Properties c = config();
        String host = c.getProperty("smtp.host", "").trim();
        if (host.isEmpty()) {
            if ("true".equalsIgnoreCase(c.getProperty("smtp.dev", "").trim())) {
                LOG.warning("[SysAttack][MODO TESTE] Código de confirmação para " + to + ": " + code);
                return;
            }
            throw new NotConfigured();
        }
        String user = c.getProperty("smtp.user", "").trim();
        String pass = c.getProperty("smtp.password", "");
        String from = c.getProperty("smtp.from", user).trim();
        String security = c.getProperty("smtp.security", "starttls").trim().toLowerCase();

        Properties pr = new Properties();
        pr.put("mail.smtp.host", host);
        pr.put("mail.smtp.port", c.getProperty("smtp.port", "587").trim());
        pr.put("mail.smtp.connectiontimeout", "10000");
        pr.put("mail.smtp.timeout", "10000");
        pr.put("mail.smtp.writetimeout", "10000");
        if (security.equals("starttls")) {
            pr.put("mail.smtp.starttls.enable", "true");
            pr.put("mail.smtp.starttls.required", "true");
        } else if (security.equals("ssl")) {
            pr.put("mail.smtp.ssl.enable", "true");
        }
        boolean auth = !user.isEmpty();
        pr.put("mail.smtp.auth", String.valueOf(auth));
        Session session = auth
                ? Session.getInstance(pr, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass);
                    }
                })
                : Session.getInstance(pr);

        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress(from, c.getProperty("smtp.fromname", "SysAttack"), "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        m.setSubject("SysAttack · código para alterar a senha", "UTF-8");
        m.setText("Olá, " + name + "!\n\n"
                + "Use o código abaixo para confirmar a troca da sua senha no SysAttack:\n\n"
                + "    " + code + "\n\n"
                + "O código vale por 15 minutos. Se você não pediu a troca, ignore este e-mail "
                + "e avise o administrador.\n\n— SysAttack", "UTF-8");
        Transport.send(m);
    }

    /** Monta a sessão SMTP a partir do config.properties; null se o servidor não estiver configurado. */
    private static Session session(Properties c) {
        String host = c.getProperty("smtp.host", "").trim();
        if (host.isEmpty()) return null;
        String user = c.getProperty("smtp.user", "").trim();
        String pass = c.getProperty("smtp.password", "");
        String security = c.getProperty("smtp.security", "starttls").trim().toLowerCase();
        Properties pr = new Properties();
        pr.put("mail.smtp.host", host);
        pr.put("mail.smtp.port", c.getProperty("smtp.port", "587").trim());
        pr.put("mail.smtp.connectiontimeout", "10000");
        pr.put("mail.smtp.timeout", "20000");
        pr.put("mail.smtp.writetimeout", "20000");
        if (security.equals("starttls")) {
            pr.put("mail.smtp.starttls.enable", "true");
            pr.put("mail.smtp.starttls.required", "true");
        } else if (security.equals("ssl")) {
            pr.put("mail.smtp.ssl.enable", "true");
        }
        boolean auth = !user.isEmpty();
        pr.put("mail.smtp.auth", String.valueOf(auth));
        return auth ? Session.getInstance(pr, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        }) : Session.getInstance(pr);
    }

    /**
     * Envia um e-mail HTML. Retorna true se foi enviado de verdade; false no modo teste (smtp.dev=true),
     * em que só registra no log do Tomcat.
     */
    public static boolean sendHtml(java.util.List<String> to, String subject, String html, String replyTo) throws Exception {
        return sendHtml(config(), to, subject, html, replyTo);
    }

    /** Igual ao anterior, mas com a configuração SMTP informada (usado no teste da tela). */
    static boolean sendHtml(Properties c, java.util.List<String> to, String subject, String html, String replyTo) throws Exception {
        Session session = session(c);
        if (session == null) {
            if ("true".equalsIgnoreCase(c.getProperty("smtp.dev", "").trim())) {
                LOG.warning("[SysAttack][MODO TESTE] E-mail NÃO enviado. Para: " + to + " | Assunto: " + subject);
                return false;
            }
            throw new NotConfigured();
        }
        String user = c.getProperty("smtp.user", "").trim();
        String from = c.getProperty("smtp.from", user).trim();
        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress(from, c.getProperty("smtp.fromname", "SysAttack"), "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(String.join(",", to)));
        if (replyTo != null && !replyTo.isBlank()) m.setReplyTo(InternetAddress.parse(replyTo));
        m.setSubject(subject.replaceAll("[\\r\\n]+", " "), "UTF-8");
        m.setContent(html, "text/html; charset=UTF-8");
        Transport.send(m);
        return true;
    }
}
