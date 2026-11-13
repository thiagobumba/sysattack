package br.com.sysattack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Token OAuth 2.0 para o SMTP do Microsoft 365 (fluxo client credentials, sem usuário interativo).
 * O aplicativo registrado no Entra ID precisa da permissão <b>SMTP.SendAsApp</b> (Office 365 Exchange Online),
 * com consentimento do administrador, estar registrado no Exchange (New-ServicePrincipal) e ter permissão
 * na caixa remetente (Add-MailboxPermission). O token vale cerca de uma hora e fica em memória até expirar.
 */
final class MailOAuth {
    private static final Logger LOG = Logger.getLogger("sysattack");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final String SMTP_SCOPE = "https://outlook.office365.com/.default";
    private static final Map<String, Token> CACHE = new ConcurrentHashMap<>();

    private MailOAuth() {}

    static final class Token {
        final String value; final Instant expires;
        Token(String v, Instant e) { value = v; expires = e; }
        boolean valid() { return Instant.now().isBefore(expires.minusSeconds(120)); } // renova 2 min antes
    }

    /** Erro de autenticação do lado da Microsoft, com a mensagem já pronta para a tela. */
    static class OAuthException extends Exception {
        OAuthException(String msg) { super(msg); }
    }

    /** Mensagens do que aconteceu (sem segredo nem token) para o log do teste de e-mail. */
    static final ThreadLocal<StringBuilder> TRACE = new ThreadLocal<>();

    static void trace(String s) {
        StringBuilder b = TRACE.get();
        if (b != null) b.append(java.time.LocalTime.now().withNano(0)).append("  ").append(s).append('\n');
    }

    static void forget(String tenant, String clientId) {
        CACHE.keySet().removeIf(k -> k.startsWith(tenant + '|' + clientId + '|'));
    }

    /** Devolve um token válido para o SMTP (do cache ou novo). */
    static String token(String tenant, String clientId, String secret) throws OAuthException {
        return token(tenant, clientId, secret, SMTP_SCOPE);
    }

    /** Devolve um token válido para o escopo pedido (SMTP ou Graph), do cache ou novo. */
    static String token(String tenant, String clientId, String secret, String scope) throws OAuthException {
        if (tenant.isEmpty() || clientId.isEmpty() || secret.isEmpty())
            throw new OAuthException("Informe o locatário, o ID do aplicativo e o segredo do cliente.");
        String key = tenant + '|' + clientId + '|' + scope;
        Token t = CACHE.get(key);
        if (t != null && t.valid()) {
            trace("Token OAuth reaproveitado do cache (expira em " + t.expires + ").");
            return t.value;
        }
        trace("Pedindo token OAuth ao Microsoft Entra (locatário " + tenant + ", aplicativo " + clientId + ", escopo " + scope + ").");
        Token novo = request(tenant, clientId, secret, scope);
        CACHE.put(key, novo);
        trace("Token OAuth obtido; vale até " + novo.expires + ".");
        return novo.value;
    }

    private static Token request(String tenant, String clientId, String secret, String scope) throws OAuthException {
        String body = "client_id=" + enc(clientId) + "&scope=" + enc(scope) + "&client_secret=" + enc(secret) + "&grant_type=client_credentials";
        HttpRequest rq = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/" + enc(tenant) + "/oauth2/v2.0/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> rs;
        try {
            rs = HTTP.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            trace("Falha de rede ao falar com o Entra: " + e);
            throw new OAuthException("Não foi possível falar com o Microsoft Entra: " + e.getMessage()
                    + ". Verifique se o servidor tem saída para login.microsoftonline.com na porta 443.");
        }
        JsonObject j;
        try {
            j = JsonParser.parseString(rs.body()).getAsJsonObject();
        } catch (Exception e) {
            trace("Resposta inesperada do Entra (HTTP " + rs.statusCode() + ").");
            throw new OAuthException("Resposta inesperada do Microsoft Entra (HTTP " + rs.statusCode() + ").");
        }
        if (rs.statusCode() != 200 || !j.has("access_token")) {
            String code = j.has("error") ? j.get("error").getAsString() : "HTTP " + rs.statusCode();
            String desc = j.has("error_description") ? j.get("error_description").getAsString().split("\\r?\\n")[0] : "";
            trace("Entra recusou o pedido de token: " + code + " — " + desc);
            throw new OAuthException("O Microsoft Entra recusou o pedido de token (" + code + "): " + hint(code, desc));
        }
        long exp = j.has("expires_in") ? j.get("expires_in").getAsLong() : 3600;
        return new Token(j.get("access_token").getAsString(), Instant.now().plusSeconds(exp));
    }

    /** Traduz os erros mais comuns do Entra para algo acionável. */
    private static String hint(String code, String desc) {
        String d = desc == null ? "" : desc;
        if (d.contains("AADSTS7000215")) return "o segredo do cliente não confere. O engano mais comum é colar o \"ID secreto\""
                + " em vez do \"Valor\" do segredo: em Certificados e segredos, copie a coluna Valor (o botão de copiar traz o texto inteiro, mesmo aparecendo cortado)."
                + " Se o valor não estiver mais visível, crie um novo segredo e copie na hora.";
        if (d.contains("AADSTS7000222")) return "o segredo do cliente expirou. Gere um novo em Certificados e segredos.";
        if (d.contains("AADSTS700016")) return "o ID do aplicativo não existe neste locatário. Confira o ID do aplicativo (cliente).";
        if (d.contains("AADSTS90002")) return "o locatário informado não existe. Confira o ID do diretório (locatário).";
        if (d.contains("AADSTS500011") || d.contains("AADSTS650057"))
            return "o aplicativo não tem a permissão SMTP.SendAsApp do Office 365 Exchange Online, ou falta o consentimento do administrador.";
        return d.isEmpty() ? "verifique o registro do aplicativo no Entra ID." : d;
    }

    /** Explica o 535 do Exchange quando o token é válido mas a caixa não aceita o aplicativo. */
    static String smtpHint(String message) {
        if (message == null) return null;
        if (message.contains("535") && message.contains("5.7.3"))
            return "O token foi aceito pelo Entra, mas o Exchange recusou o login. Em geral falta registrar o aplicativo no Exchange"
                 + " (New-ServicePrincipal, usando o ID do objeto da Aplicação Empresarial, não o do Registro de aplicativo),"
                 + " dar permissão na caixa (Add-MailboxPermission) ou liberar o SMTP autenticado nela (Set-CASMailbox).";
        if (message.contains("5.7.139") && message.contains("Tenant"))
            return "O token foi aceito, mas o SMTP autenticado está desligado no locatário. No PowerShell do Exchange, libere só a caixa"
                 + " (Set-CASMailbox -Identity <caixa> -SmtpClientAuthenticationDisabled $false), que sobrepõe a trava do locatário; se ainda assim falhar,"
                 + " libere o locatário (Set-TransportConfig -SmtpClientAuthenticationDisabled $false) e confira os Padrões de Segurança no Entra ID."
                 + " A mudança pode levar até uma hora para valer.";
        if (message.contains("5.7.139") && message.contains("Mailbox"))
            return "O SMTP autenticado está desligado nesta caixa. No Centro de administração, abra o usuário em E-mail → Gerenciar aplicativos de e-mail"
                 + " e marque SMTP autenticado, ou rode Set-CASMailbox -Identity <caixa> -SmtpClientAuthenticationDisabled $false.";
        if (message.contains("550") && message.contains("5.7.60"))
            return "O aplicativo não tem permissão para enviar como esta caixa. Confira o Add-MailboxPermission para o ID do aplicativo.";
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
