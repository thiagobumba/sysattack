package br.com.sysattack;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import javax.mail.internet.MimeMessage;

/**
 * Envio pelo Microsoft Graph (<code>POST /users/{caixa}/sendMail</code>), com a mensagem MIME pronta em base64.
 * O aplicativo registrado no Entra ID precisa da permissão de aplicativo <b>Mail.Send</b> (Microsoft Graph)
 * com consentimento do administrador. Não usa SMTP: não depende do SMTP autenticado do locatário nem da caixa.
 */
final class MailGraph {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final String SCOPE = "https://graph.microsoft.com/.default";
    private static final int MAX_BYTES = 4 * 1024 * 1024; // limite do sendMail com MIME

    private MailGraph() {}

    static class GraphException extends Exception {
        GraphException(String msg) { super(msg); }
    }

    /** Envia a mensagem já montada. A caixa é a do remetente (a mesma do campo Usuário). */
    static void send(Properties c, MimeMessage m, String mailbox) throws Exception {
        String token = MailOAuth.token(c.getProperty("smtp.tenant", "").trim(), c.getProperty("smtp.clientid", "").trim(),
                c.getProperty("smtp.clientsecret", ""), SCOPE);
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        m.writeTo(raw);
        byte[] mime = raw.toByteArray();
        if (mime.length > MAX_BYTES)
            throw new GraphException("A mensagem tem " + (mime.length / 1024) + " KB e passa do limite de 4 MB do Microsoft Graph.");
        String body = Base64.getEncoder().encodeToString(mime);
        String url = "https://graph.microsoft.com/v1.0/users/" + URLEncoder.encode(mailbox, StandardCharsets.UTF_8) + "/sendMail";
        MailOAuth.trace("Enviando pelo Microsoft Graph (" + mime.length / 1024 + " KB) como " + mailbox + ".");
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.US_ASCII)).build();
        HttpResponse<String> rs;
        try {
            rs = HTTP.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MailOAuth.trace("Falha de rede ao falar com o Graph: " + e);
            throw new GraphException("Não foi possível falar com o Microsoft Graph: " + e.getMessage()
                    + ". Verifique se o servidor tem saída para graph.microsoft.com na porta 443.");
        }
        String reqId = rs.headers().firstValue("request-id").orElse("");
        if (rs.statusCode() == 202) {
            MailOAuth.trace("Graph aceitou a mensagem (HTTP 202" + (reqId.isEmpty() ? "" : ", request-id " + reqId) + ").");
            return;
        }
        String code = "", msg = "";
        try {
            com.google.gson.JsonObject j = com.google.gson.JsonParser.parseString(rs.body()).getAsJsonObject();
            if (j.has("error")) {
                com.google.gson.JsonObject e = j.getAsJsonObject("error");
                code = e.has("code") ? e.get("code").getAsString() : "";
                msg = e.has("message") ? e.get("message").getAsString().split("\\r?\\n")[0] : "";
            }
        } catch (Exception ignored) { }
        MailOAuth.trace("Graph recusou (HTTP " + rs.statusCode() + (code.isEmpty() ? "" : ", " + code) + (reqId.isEmpty() ? "" : ", request-id " + reqId) + "): " + msg);
        throw new GraphException("O Microsoft Graph recusou o envio (HTTP " + rs.statusCode()
                + (code.isEmpty() ? "" : " · " + code) + "): " + hint(rs.statusCode(), code, msg));
    }

    /** Traduz os erros mais comuns do Graph para algo acionável. */
    private static String hint(int status, String code, String msg) {
        if (status == 401)
            return "o token foi recusado. Confira o locatário, o ID do aplicativo e o segredo do cliente.";
        if (status == 403 || "ErrorAccessDenied".equals(code) || "Authorization_RequestDenied".equals(code))
            return "o aplicativo não tem permissão para enviar como esta caixa. No registro do aplicativo, em Permissões de API,"
                 + " adicione a permissão de aplicativo Mail.Send do Microsoft Graph e clique em Conceder consentimento do administrador."
                 + " Se já existir uma política de acesso a aplicativos (New-ApplicationAccessPolicy), confirme que esta caixa está incluída.";
        if (status == 404 || "ErrorInvalidUser".equals(code) || "ResourceNotFound".equals(code))
            return "a caixa informada no campo Usuário não foi encontrada no locatário. Use o endereço completo de uma caixa com licença do Exchange Online.";
        if (status == 413)
            return "a mensagem passou do tamanho aceito pelo Graph (4 MB com anexos).";
        if (status == 429)
            return "o Graph está limitando os envios (muitas mensagens em pouco tempo). Tente de novo em alguns minutos.";
        return msg.isEmpty() ? "veja o request-id acima ao abrir chamado com a Microsoft." : msg;
    }
}
