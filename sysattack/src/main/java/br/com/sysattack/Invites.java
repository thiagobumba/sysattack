package br.com.sysattack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;

/**
 * Convite por e-mail: o administrador cadastra a pessoa e o sistema envia as boas-vindas com o endereço da aplicação, as
 * políticas de uso e um link de uso único (72 h) para a própria pessoa criar a senha. O token só é guardado como SHA-256.
 */
final class Invites {
    private Invites() {}

    private static final Logger LOG = Logger.getLogger("sysattack");
    private static final SecureRandom RND = new SecureRandom();
    static final int HOURS = 72;
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$");

    /** Gera um token novo (invalida os anteriores da pessoa) e devolve o valor em claro, que só vai no e-mail. */
    static String issue(long userId) throws SQLException {
        byte[] b = new byte[32];
        RND.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Db.update("DELETE FROM user_tokens WHERE user_id = ?", userId);
        Db.update("INSERT INTO user_tokens(user_id, token_hash, expires_at) VALUES(?,?,?)", userId, Auth.sha256(token),
                java.sql.Timestamp.valueOf(LocalDateTime.now().plusHours(HOURS)));
        return token;
    }

    /** Linha do usuário dona de um token válido (não expirado), ou null. */
    static Map<String, Object> find(String token) throws SQLException {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,80}")) return null;
        return Db.one("SELECT u.id, u.username email, u.name, u.active FROM user_tokens t JOIN users u ON u.id = t.user_id"
                + " WHERE t.token_hash = ? AND t.expires_at > CURRENT_TIMESTAMP", Auth.sha256(token));
    }

    // ------------------------------------------------------------------ endereço da aplicação
    private static String setting() {
        String v = System.getProperty("sysattack.url");
        if (v == null || v.isBlank()) v = System.getenv("SYSATTACK_URL");
        if (v == null || v.isBlank()) {
            v = AppConfig.load().getProperty("app.url");
        }
        return v == null ? "" : v.trim();
    }

    private static final Pattern LOCAL = Pattern.compile("(?i)^https?://(localhost|127\\.[0-9.]+|\\[::1\\]|0\\.0\\.0\\.0)(:[0-9]+)?(/.*)?$");

    /** O app.url válido configurado (config.properties, -Dsysattack.url ou SYSATTACK_URL), sem barra final; "" se não houver. */
    static String configuredUrl() {
        String v = setting();
        return v.matches("(?i)^https?://[^\\s<>\"']+$") ? v.replaceAll("/+$", "") : "";
    }

    /** Para a tela Segurança e dados: o endereço que vai nos e-mails e se ele é utilizável por outras máquinas. */
    static Map<String, Object> addressInfo() {
        return addressInfo(null);
    }

    /**
     * Com a requisição do administrador, compara o endereço configurado com o que o navegador está usando:
     * é assim que se percebe que o app.url ficou para trás depois de uma troca de endereço.
     */
    static Map<String, Object> addressInfo(HttpServletRequest req) {
        String u = configuredUrl();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("app_url", u);
        o.put("app_url_ok", !u.isEmpty() && !LOCAL.matcher(u).matches());
        o.put("app_url_source", source());
        if (req != null) {
            String now = baseUrl(req);
            o.put("browser_url", now);
            o.put("app_url_matches", u.isEmpty() || u.equalsIgnoreCase(now));
        }
        return o;
    }

    /** De onde veio o endereço configurado, para a tela dizer onde mexer. */
    private static String source() {
        String v = System.getProperty("sysattack.url");
        if (v != null && !v.isBlank()) return "-Dsysattack.url";
        v = System.getenv("SYSATTACK_URL");
        if (v != null && !v.isBlank()) return "SYSATTACK_URL";
        v = AppConfig.load().getProperty("app.url");
        return v != null && !v.isBlank() ? "config.properties" : "";
    }

    /**
     * Confere o endereço configurado em três passos: o nome resolve, a porta responde e o HTTPS completa.
     * Nunca aceita endereço vindo da requisição: testa só o que está configurado no servidor.
     */
    static Map<String, Object> testAddress() {
        Map<String, Object> o = new LinkedHashMap<>();
        String u = configuredUrl();
        o.put("url", u);
        if (u.isEmpty()) {
            o.put("ok", false);
            o.put("step", "config");
            o.put("message", "Nenhum endereço configurado: os e-mails usam o endereço de quem disparou a ação.");
            return o;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(u);
        } catch (Exception e) {
            o.put("ok", false); o.put("step", "config"); o.put("message", "O endereço configurado não é uma URL válida.");
            return o;
        }
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            o.put("resolved", addr.getHostAddress());
        } catch (Exception e) {
            o.put("ok", false); o.put("step", "dns");
            o.put("message", "O nome " + host + " não resolve a partir do servidor. Confira o DNS ou o arquivo hosts.");
            return o;
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 5000);
        } catch (Exception e) {
            o.put("ok", false); o.put("step", "porta");
            o.put("message", "O nome resolve, mas a porta " + port + " não respondeu. Confira o Tomcat e o firewall.");
            return o;
        }
        try {
            java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
            java.net.http.HttpResponse<Void> r = c.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(u + "/version.json"))
                            .timeout(java.time.Duration.ofSeconds(8)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            o.put("ok", r.statusCode() == 200);
            o.put("step", "http");
            o.put("status", r.statusCode());
            o.put("message", r.statusCode() == 200 ? "O endereço responde e o certificado é confiável."
                    : "O endereço respondeu com HTTP " + r.statusCode() + ". Confira se o caminho do sistema está correto.");
        } catch (javax.net.ssl.SSLException e) {
            o.put("ok", false); o.put("step", "certificado");
            o.put("message", "O endereço responde, mas o certificado não é confiável para este nome."
                    + " É o esperado com certificado autoassinado; os links do e-mail vão abrir com aviso de segurança.");
        } catch (Exception e) {
            o.put("ok", false); o.put("step", "http");
            o.put("message", "O endereço não respondeu: " + e.getClass().getSimpleName() + ".");
        }
        return o;
    }

    /** Endereço público da aplicação: app.url (config.properties, -Dsysattack.url ou SYSATTACK_URL) ou o da requisição do administrador. */
    static String baseUrl(HttpServletRequest req) {
        String v = setting();
        if (v.matches("(?i)^https?://[^\\s<>\"']+$")) return v.replaceAll("/+$", "");
        String proto = req.getHeader("X-Forwarded-Proto");
        if (proto == null || !proto.matches("(?i)https?")) proto = req.isSecure() ? "https" : "http";
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = req.getHeader("Host");
        host = host == null ? "" : host.split(",")[0].trim();
        if (!HOST.matcher(host).matches()) host = "localhost:8085";
        String ctx = req.getContextPath();
        if (ctx == null || ctx.equals("/") || !ctx.matches("[A-Za-z0-9_./-]*")) ctx = "";
        return proto.toLowerCase() + "://" + host + ctx;
    }

    // ------------------------------------------------------------------ e-mail
    private static String h(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String[] RULES = {
        "A conta é <b>pessoal e intransferível</b>: não compartilhe a sua senha nem deixe a sessão aberta em computador de uso comum.",
        "Use o sistema apenas para atividades de trabalho da Momesso e dos projetos em que você atua.",
        "As informações de projetos, clientes, valores e documentos são <b>confidenciais</b>: não as divulgue fora do que a sua função permite.",
        "Acessos e alterações ficam <b>registrados</b> (quem, quando e de onde) para a segurança de todos.",
        "Os dados pessoais são tratados conforme a LGPD, como descrito na <a href=\"%PRIV%\" style=\"color:#cf4400\">Política de Privacidade</a>.",
        "O SysAttack <b>nunca pede a sua senha por e-mail</b>. Desconfie de mensagens que peçam: avise o administrador.",
        "Ao notar acesso indevido, perda de equipamento ou qualquer incidente, comunique o administrador imediatamente."
    };

    static String subject(boolean welcome) {
        return welcome ? "Bem-vindo(a) ao SysAttack: crie a sua senha" : "SysAttack: crie uma nova senha";
    }

    /** HTML só com tabelas e atributos (compatível com Outlook). */
    static String html(String name, String email, String jobTitle, String adminName, String base, String token, boolean welcome) {
        String link = base + "/definir-senha.html?token=" + token;
        StringBuilder rules = new StringBuilder();
        for (String r : RULES) {
            rules.append("<tr><td valign=\"top\" width=\"18\" style=\"padding:3px 0;color:#f45200;font-size:14px\">&bull;</td><td style=\"padding:3px 0;font-size:14px;line-height:20px;color:#333333\">")
                    .append(r.replace("%PRIV%", h(base + "/privacidade.html"))).append("</td></tr>");
        }
        String intro = welcome
                ? h(adminName) + " cadastrou o seu acesso ao <b>SysAttack</b>, o sistema de gestão de projetos da Momesso." + (jobTitle.isEmpty() ? "" : " Cargo informado: <b>" + h(jobTitle) + "</b>.")
                : h(adminName) + " pediu que você crie uma nova senha de acesso ao <b>SysAttack</b>.";
        return "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
            + "<body style=\"margin:0;padding:0;background-color:#f4f2f0;font-family:'Segoe UI',Arial,sans-serif\" bgcolor=\"#f4f2f0\">"
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"#f4f2f0\"><tr><td align=\"center\" style=\"padding:24px 12px\">"
            + "<table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:600px\">"
            + "<tr><td bgcolor=\"#f45200\" style=\"background-color:#f45200;padding:26px 28px\"><span style=\"font-size:13px;letter-spacing:2px;color:#ffffff;font-weight:700\">MOMESSO</span><br>"
            + "<span style=\"font-size:30px;color:#ffffff;font-weight:300\">SysAttack</span></td></tr>"
            + "<tr><td bgcolor=\"#ffffff\" style=\"background-color:#ffffff;padding:28px\">"
            + "<p style=\"margin:0 0 14px;font-size:16px;color:#1a1a1a\">Olá, <b>" + h(name) + "</b>!</p>"
            + "<p style=\"margin:0 0 20px;font-size:14px;line-height:21px;color:#333333\">" + intro + "</p>"
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"#faf6f2\" style=\"background-color:#faf6f2\"><tr><td style=\"padding:14px 16px;font-size:14px;line-height:22px;color:#333333\">"
            + "<b>Endereço do sistema:</b> <a href=\"" + h(base) + "\" style=\"color:#cf4400\">" + h(base) + "</a><br><b>Seu login:</b> " + h(email) + "</td></tr></table>"
            + "<p style=\"margin:22px 0 12px;font-size:14px;line-height:21px;color:#333333\"><b>Primeiro passo:</b> crie a sua senha pelo botão abaixo. O link é de uso único e vale por " + HOURS + " horas.</p>"
            + "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td bgcolor=\"#f45200\" style=\"background-color:#f45200;padding:14px 28px\"><a href=\"" + h(link) + "\" style=\"color:#ffffff !important;font-size:14px;font-weight:700;text-decoration:none;letter-spacing:1px\"><font color=\"#ffffff\"><span style=\"color:#ffffff\">CRIAR MINHA SENHA</span></font></a></td></tr></table>"
            + "<p style=\"margin:14px 0 0;font-size:12px;line-height:18px;color:#6d6d6d\">Se o botão não abrir, copie este endereço no navegador:<br><a href=\"" + h(link) + "\" style=\"color:#cf4400;word-break:break-all\">" + h(link) + "</a></p>"
            + "<p style=\"margin:16px 0 0;font-size:13px;line-height:19px;color:#555555\">A senha precisa ter pelo menos 10 caracteres, com letra maiúscula, minúscula, número e símbolo, e não pode conter o seu e-mail.</p>"
            + "<p style=\"margin:26px 0 8px;font-size:15px;color:#1a1a1a\"><b>Políticas de uso</b></p>"
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" + rules + "</table>"
            + "<p style=\"margin:22px 0 0;font-size:12px;line-height:18px;color:#6d6d6d\">Não esperava este e-mail? Ignore-o e avise o administrador do sistema.</p>"
            + "</td></tr>"
            + "<tr><td align=\"center\" style=\"padding:16px;font-size:12px;color:#6d6d6d\">&copy; " + java.time.Year.now() + " - Momesso Ind&uacute;stria de M&aacute;quinas - Todos os direitos reservados</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    /**
     * Gera o token e envia o e-mail. Nunca lança por falha de envio: devolve {sent, error} para o administrador decidir
     * (a pessoa continua cadastrada e o convite pode ser reenviado).
     */
    static Map<String, Object> sendInvite(HttpServletRequest req, Map<String, Object> user, String adminName, boolean welcome) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        String email = (String) user.get("email");
        String base0 = baseUrl(req);
        if (LOCAL.matcher(base0).matches()) { // link para localhost só abre na máquina do administrador: não envia
            out.put("sent", false);
            out.put("error", "O endereço do sistema está como localhost, que só abre na máquina do servidor. Defina app.url no config.properties da pasta de dados (veja Segurança e dados), reinicie o Tomcat e reenvie o convite.");
            return out;
        }
        String token = issue(((Number) user.get("id")).longValue());
        try {
            String html = html((String) user.get("name"), email, String.valueOf(user.get("job_title") == null ? "" : user.get("job_title")),
                    adminName, baseUrl(req), token, welcome);
            boolean delivered = Mailer.sendHtml(List.of(email), subject(welcome), html, null);
            out.put("sent", delivered);
            if (!delivered) out.put("error", "Modo de teste (smtp.dev): o e-mail não foi enviado de fato; o link está no log do servidor.");
            if (!delivered) LOG.warning("[SysAttack][MODO TESTE] Link de criação de senha para " + email + ": " + baseUrl(req) + "/definir-senha.html?token=" + token);
        } catch (Mailer.NotConfigured e) {
            out.put("sent", false);
            out.put("error", "O envio de e-mail ainda não está configurado (menu E-mail).");
        } catch (Exception e) {
            LOG.warning("Convite: falha ao enviar para " + email + ": " + e);
            out.put("sent", false);
            out.put("error", "Falha ao enviar o e-mail: " + e.getMessage());
        }
        return out;
    }
}
