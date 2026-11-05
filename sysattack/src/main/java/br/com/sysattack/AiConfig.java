package br.com.sysattack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Conexão do SysAttack com a Claude API (Anthropic).
 *
 * A Anthropic não permite usar o login de contas Claude.ai (Free/Pro/Max) em sistemas de terceiros:
 * a integração oficial usa uma chave de API criada no Claude Console (platform.claude.com/settings/keys).
 * A chave é guardada CRIPTOGRAFADA (AES-256-GCM) no banco; a chave-mestra fica em &lt;pasta de dados&gt;/ai.key.
 * A chave nunca é devolvida ao navegador (só uma versão mascarada).
 */
public final class AiConfig {
    public static class AiException extends Exception {
        public final int status;
        public AiException(int status, String message) { super(message); this.status = status; }
    }

    private static final String BASE = "https://api.anthropic.com";
    private static final String VERSION = "2023-06-01";
    public static final List<String> DEFAULT_MODELS = List.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-1");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private AiConfig() {}

    // ------------------------------------------------------------------ estado

    public static Map<String, Object> status() throws SQLException {
        Map<String, Object> row = Db.one("SELECT key_hint, model, workspace_id, connected_by, connected_at,"
                + " last_test_at, last_test_ok, last_test_msg FROM ai_settings WHERE id = 1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", row != null);
        out.put("default_models", DEFAULT_MODELS);
        if (row != null) out.putAll(row);
        return out;
    }

    /** Valida a chave na Anthropic (lista de modelos, sem custo) e a guarda criptografada. */
    public static void connect(String apiKey, String model, String workspaceId, String userName, Object userId)
            throws AiException, SQLException {
        if (apiKey.startsWith("sk-ant-admin")) {
            throw new AiException(400, "Essa é uma chave de administração. Crie uma chave de API comum no Claude Console.");
        }
        if (!apiKey.matches("sk-ant-[A-Za-z0-9_\\-]{10,300}")) {
            throw new AiException(400, "Formato de chave inválido. A chave começa com sk-ant- e é copiada do Claude Console.");
        }
        validateModel(model);
        if (!workspaceId.isEmpty() && !workspaceId.matches("wrkspc_[A-Za-z0-9]{6,60}")) {
            throw new AiException(400, "ID de workspace inválido (formato: wrkspc_...). Deixe em branco se a chave já é de um workspace.");
        }
        call(apiKey, workspaceId, "GET", "/v1/models?limit=1", null); // lança erro se a chave não for aceita
        String enc;
        try {
            enc = encrypt(apiKey);
        } catch (GeneralSecurityException | IOException e) {
            throw new AiException(500, "Não foi possível proteger a chave no servidor: " + e.getMessage());
        }
        String hint = apiKey.substring(0, Math.min(12, apiKey.length())) + "…" + apiKey.substring(apiKey.length() - 4);
        Db.update("DELETE FROM ai_settings WHERE id = 1");
        Db.update("INSERT INTO ai_settings(id,api_key_enc,key_hint,model,workspace_id,connected_by,connected_by_id) VALUES(1,?,?,?,?,?,?)",
                enc, hint, model, workspaceId, userName, userId);
    }

    public static void disconnect() throws SQLException {
        Db.update("DELETE FROM ai_settings WHERE id = 1");
    }

    public static void setModel(String model) throws AiException, SQLException {
        validateModel(model);
        if (Db.update("UPDATE ai_settings SET model = ? WHERE id = 1", model) == 0) {
            throw new AiException(409, "O Claude não está conectado.");
        }
    }

    private static void validateModel(String model) throws AiException {
        if (!model.matches("[a-z0-9][a-z0-9._\\-]{2,79}")) throw new AiException(400, "Nome de modelo inválido.");
    }

    // ------------------------------------------------------------------ operações

    /** Modelos disponíveis para a chave conectada (cai para a lista padrão se a consulta falhar). */
    public static Map<String, Object> models() throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonArray data = JsonParser.parseString(request("GET", "/v1/models?limit=100", null)).getAsJsonObject().getAsJsonArray("data");
            List<String> ids = new ArrayList<>();
            for (JsonElement e : data) ids.add(e.getAsJsonObject().get("id").getAsString());
            if (!ids.isEmpty()) { out.put("models", ids); out.put("source", "api"); return out; }
        } catch (AiException | RuntimeException e) {
            out.put("warning", e.getMessage());
        }
        out.put("models", DEFAULT_MODELS);
        out.put("source", "default");
        return out;
    }

    /** Teste real: envia uma mensagem mínima ao modelo escolhido (valida chave, modelo e saldo). */
    public static Map<String, Object> test() throws AiException, SQLException {
        Map<String, Object> row = Db.one("SELECT model FROM ai_settings WHERE id = 1");
        if (row == null) throw new AiException(409, "O Claude não está conectado.");
        String model = (String) row.get("model");
        String json = GSON.toJson(Map.of("model", model, "max_tokens", 16,
                "messages", List.of(Map.of("role", "user", "content", "Responda apenas com a palavra: ok"))));
        try {
            String reply = "";
            JsonObject o = JsonParser.parseString(request("POST", "/v1/messages", json)).getAsJsonObject();
            JsonArray content = o.getAsJsonArray("content");
            if (content != null && content.size() > 0 && content.get(0).getAsJsonObject().has("text")) {
                reply = content.get(0).getAsJsonObject().get("text").getAsString();
            }
            Db.update("UPDATE ai_settings SET last_test_at = CURRENT_TIMESTAMP, last_test_ok = TRUE, last_test_msg = ? WHERE id = 1",
                    "Resposta recebida de " + model);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("model", model);
            out.put("reply", reply.length() > 100 ? reply.substring(0, 100) : reply);
            return out;
        } catch (AiException e) {
            String msg = e.getMessage().length() > 290 ? e.getMessage().substring(0, 290) : e.getMessage();
            Db.update("UPDATE ai_settings SET last_test_at = CURRENT_TIMESTAMP, last_test_ok = FALSE, last_test_msg = ? WHERE id = 1", msg);
            throw e;
        }
    }

    /** Ponto de entrada para as próximas etapas (assistente nos projetos): chamada autenticada à API. */
    public static String request(String method, String path, String json) throws AiException, SQLException {
        Map<String, Object> row = Db.one("SELECT api_key_enc, workspace_id FROM ai_settings WHERE id = 1");
        if (row == null) throw new AiException(409, "O Claude não está conectado. Conecte em Configurações IA Agent.");
        String key;
        try {
            key = decrypt((String) row.get("api_key_enc"));
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new AiException(500, "Não foi possível ler a chave salva (o arquivo ai.key mudou?). Desconecte e conecte novamente.");
        }
        return call(key, (String) row.get("workspace_id"), method, path, json);
    }

    // ------------------------------------------------------------------ HTTP

    private static String call(String apiKey, String workspace, String method, String path, String json) throws AiException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(45))
                    .header("x-api-key", apiKey).header("anthropic-version", VERSION).header("content-type", "application/json");
            if (workspace != null && !workspace.isEmpty()) b.header("anthropic-workspace-id", workspace);
            b = json == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 == 2) return r.body();
            throw mapError(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new AiException(502, "O servidor não conseguiu acessar api.anthropic.com. Verifique a internet, o firewall ou o proxy do servidor.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(502, "Conexão interrompida. Tente novamente.");
        }
    }

    private static AiException mapError(int http, String body) {
        String detail = "";
        try {
            JsonObject err = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (err != null && err.has("message")) detail = err.get("message").getAsString();
        } catch (RuntimeException ignored) {
            // corpo sem JSON
        }
        switch (http) {
            case 401: return new AiException(400, "Chave de API inválida, expirada ou revogada.");
            case 403: return new AiException(400, "A chave não tem permissão para esta operação." + (detail.isEmpty() ? "" : " (" + detail + ")"));
            case 404: return new AiException(400, "Modelo não encontrado ou sem acesso para esta chave." + (detail.isEmpty() ? "" : " (" + detail + ")"));
            case 429: return new AiException(429, "Limite de uso da conta Anthropic atingido. Tente novamente em instantes.");
            default:
                if (http >= 500) return new AiException(502, "O serviço da Anthropic está indisponível ou sobrecarregado agora. Tente novamente.");
                return new AiException(400, "A Anthropic recusou a requisição" + (detail.isEmpty() ? " (HTTP " + http + ")." : ": " + detail));
        }
    }

    // ------------------------------------------------------------------ criptografia da chave

    private static synchronized SecretKeySpec masterKey() throws IOException {
        File f = new File(Db.dataDir(), "ai.key");
        byte[] raw;
        if (f.isFile()) {
            raw = Base64.getDecoder().decode(Files.readString(f.toPath()).trim());
        } else {
            raw = new byte[32];
            RANDOM.nextBytes(raw);
            Files.writeString(f.toPath(), Base64.getEncoder().encodeToString(raw));
            try {
                Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
                // Windows: sem permissões POSIX; proteja a pasta de dados pelo sistema operacional
            }
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static String encrypt(String plain) throws GeneralSecurityException, IOException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, masterKey(), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(all);
    }

    private static String decrypt(String b64) throws GeneralSecurityException, IOException {
        byte[] all = Base64.getDecoder().decode(b64);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, masterKey(), new GCMParameterSpec(128, all, 0, 12));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }
}
