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
 *
 * Microsoft 365 com OAuth 2.0 (sem senha), configurado pela tela:
 *   smtp.authmode=OAUTH2          (token XOAUTH2 no SMTP; permissão SMTP.SendAsApp)
 *   smtp.authmode=GRAPH           (POST no Microsoft Graph, sem SMTP; permissão Mail.Send)
 *   smtp.tenant=<ID do diretório>   smtp.clientid=<ID do aplicativo>   smtp.clientsecret=<segredo>
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
        Properties p = AppConfig.load();
        return p;
    }

    public static void sendCode(String to, String name, String code) throws Exception {
        Properties c = config();
        String host = c.getProperty("smtp.host", "").trim();
        if (host.isEmpty() && !isGraph(c)) {
            if ("true".equalsIgnoreCase(c.getProperty("smtp.dev", "").trim())) {
                LOG.warning("[SysAttack][MODO TESTE] Código de confirmação para " + to + ": " + code);
                return;
            }
            throw new NotConfigured();
        }
        String user = c.getProperty("smtp.user", "").trim();
        String from = c.getProperty("smtp.from", user).trim();
        Session session = session(c);
        if (session == null) throw new NotConfigured();

        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress(from, c.getProperty("smtp.fromname", "SysAttack"), "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        m.setSubject("SysAttack · código para alterar a senha", "UTF-8");
        m.setText("Olá, " + name + "!\n\n"
                + "Use o código abaixo para confirmar a troca da sua senha no SysAttack:\n\n"
                + "    " + code + "\n\n"
                + "O código vale por 15 minutos. Se você não pediu a troca, ignore este e-mail "
                + "e avise o administrador.\n\n— SysAttack", "UTF-8");
        deliver(c, m);
    }

    /** Quando preenchido (na thread atual), a conversa SMTP é gravada aqui (sem credenciais) para o log do teste. */
    static final ThreadLocal<java.io.PrintStream> DEBUG_OUT = new ThreadLocal<>();

    /** Envio pelo Microsoft Graph, sem SMTP. */
    private static boolean isGraph(Properties c) {
        return "GRAPH".equalsIgnoreCase(c.getProperty("smtp.authmode", "BASICO").trim());
    }

    /** Caixa que envia: no Graph é o dono da mensagem; no SMTP é quem autentica. */
    private static String mailbox(Properties c) {
        String user = c.getProperty("smtp.user", "").trim();
        return user.isEmpty() ? c.getProperty("smtp.from", "").trim() : user;
    }

    /** Entrega a mensagem pronta pelo caminho configurado. */
    private static void deliver(Properties c, MimeMessage m) throws Exception {
        if (isGraph(c)) MailGraph.send(c, m, mailbox(c));
        else Transport.send(m);
    }

    /** Monta a sessão de envio; null se o envio não estiver configurado. */
    private static Session session(Properties c) throws Exception {
        String user = c.getProperty("smtp.user", "").trim();
        if (isGraph(c)) { // o Graph não usa servidor SMTP: só a caixa remetente e o aplicativo
            if (user.isEmpty()) return null;
            return Session.getInstance(new Properties());
        }
        String host = c.getProperty("smtp.host", "").trim();
        if (host.isEmpty()) return null;
        boolean oauth = "OAUTH2".equalsIgnoreCase(c.getProperty("smtp.authmode", "BASICO").trim());
        String pass = oauth
                ? MailOAuth.token(c.getProperty("smtp.tenant", "").trim(), c.getProperty("smtp.clientid", "").trim(), c.getProperty("smtp.clientsecret", ""))
                : c.getProperty("smtp.password", "");
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
        if (oauth) { // XOAUTH2: o token entra no lugar da senha; nenhum outro mecanismo pode ser tentado
            pr.put("mail.smtp.auth.mechanisms", "XOAUTH2");
            pr.put("mail.smtp.auth.login.disable", "true");
            pr.put("mail.smtp.auth.plain.disable", "true");
            pr.put("mail.smtp.auth.ntlm.disable", "true");
        }
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
        return sendHtml(c, to, java.util.List.of(), java.util.List.of(), subject, html, replyTo);
    }

    /** Envio com cópia (CC) e cópia oculta (CCO). */
    public static boolean sendHtml(java.util.List<String> to, java.util.List<String> cc, java.util.List<String> bcc, String subject, String html, String replyTo) throws Exception {
        return sendHtml(config(), to, cc, bcc, subject, html, replyTo);
    }

    static boolean sendHtml(Properties c, java.util.List<String> to, java.util.List<String> cc, java.util.List<String> bcc, String subject, String html, String replyTo) throws Exception {
        Session session = session(c);
        if (session != null && DEBUG_OUT.get() != null) {
            session.setDebug(true);
            session.setDebugOut(DEBUG_OUT.get());
        }
        if (session == null) {
            if ("true".equalsIgnoreCase(c.getProperty("smtp.dev", "").trim())) {
                LOG.warning("[SysAttack][MODO TESTE] E-mail NÃO enviado. Para: " + to + (cc.isEmpty() ? "" : " | CC: " + cc) + (bcc.isEmpty() ? "" : " | CCO: " + bcc) + " | Assunto: " + subject);
                return false;
            }
            throw new NotConfigured();
        }
        String user = c.getProperty("smtp.user", "").trim();
        String from = c.getProperty("smtp.from", user).trim();
        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress(from, c.getProperty("smtp.fromname", "SysAttack"), "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(String.join(",", to)));
        if (!cc.isEmpty()) m.setRecipients(Message.RecipientType.CC, InternetAddress.parse(String.join(",", cc)));
        if (!bcc.isEmpty()) m.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(String.join(",", bcc)));
        if (replyTo != null && !replyTo.isBlank()) m.setReplyTo(InternetAddress.parse(replyTo));
        m.setSubject(subject.replaceAll("[\\r\\n]+", " "), "UTF-8");
        byte[] logo = html.contains("cid:" + ReportLogo.CID) ? ReportLogo.png() : null;
        if (logo != null) { // logo da Momesso embutida (aparece mesmo com imagens externas bloqueadas)
            javax.mail.internet.MimeMultipart rel = new javax.mail.internet.MimeMultipart("related");
            javax.mail.internet.MimeBodyPart body = new javax.mail.internet.MimeBodyPart();
            body.setContent(html, "text/html; charset=UTF-8");
            rel.addBodyPart(body);
            javax.mail.internet.InternetHeaders ih = new javax.mail.internet.InternetHeaders();
            ih.addHeader("Content-Type", "image/png; name=\"momesso.png\"");
            ih.addHeader("Content-Transfer-Encoding", "base64");
            ih.addHeader("Content-ID", "<" + ReportLogo.CID + ">");
            ih.addHeader("Content-Disposition", "inline; filename=\"momesso.png\"");
            javax.mail.internet.MimeBodyPart img = new javax.mail.internet.MimeBodyPart(ih,
                    java.util.Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encode(logo)); // já codificada: sem depender de javax.activation
            rel.addBodyPart(img);
            m.setContent(rel);
        } else {
            m.setContent(html, "text/html; charset=UTF-8");
        }
        deliver(c, m);
        return true;
    }
}
