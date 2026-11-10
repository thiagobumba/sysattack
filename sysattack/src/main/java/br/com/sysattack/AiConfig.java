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
 * Conexão do SysAttack com as IAs: Claude (Anthropic), ChatGPT (OpenAI) e Copilot (Microsoft, via Azure OpenAI).
 *
 * Nenhuma delas permite usar o login de contas de chat (Claude.ai, ChatGPT Plus/Team) em sistemas de terceiros:
 * a integração oficial usa chave de API. Cada IA ocupa uma linha de ai_settings (1 = claude, 2 = openai, 3 = azure) e uma
 * delas fica "em uso" (in_use) para escrever os relatórios. As chaves são guardadas CRIPTOGRAFADAS (AES-256-GCM) no banco;
 * a chave-mestra fica em &lt;pasta de dados&gt;/ai.key. Nenhuma chave volta ao navegador (só uma versão mascarada).
 */
public final class AiConfig {
    public static class AiException extends Exception {
        public final int status;
        public AiException(int status, String message) { super(message); this.status = status; }
    }

    public static final String CLAUDE = "claude", OPENAI = "openai", AZURE = "azure";
    private static final List<String> PROVIDERS = List.of(CLAUDE, OPENAI, AZURE);
    private static final Map<String, String> LABEL = Map.of(CLAUDE, "Claude", OPENAI, "ChatGPT (OpenAI)", AZURE, "Copilot (Microsoft)");
    private static final Map<String, String> HOST = Map.of(CLAUDE, "api.anthropic.com", OPENAI, "api.openai.com", AZURE, "o recurso Azure");

    private static final String CLAUDE_BASE = "https://api.anthropic.com";
    private static final String OPENAI_BASE = "https://api.openai.com";
    private static final String VERSION = "2023-06-01";
    public static final String AZURE_VERSION = "2024-10-21";
    public static final List<String> DEFAULT_MODELS = List.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-1");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private AiConfig() {}

    static int rowId(String provider) throws AiException {
        int i = PROVIDERS.indexOf(provider);
        if (i < 0) throw new AiException(404, "IA desconhecida.");
        return i + 1;
    }

    // ------------------------------------------------------------------ estado

    /** Estado de todas as IAs (sem segredos) e qual está em uso. */
    public static Map<String, Object> status() throws SQLException {
        Map<String, Object> providers = new LinkedHashMap<>();
        String inUse = "";
        for (int i = 0; i < PROVIDERS.size(); i++) {
            String p = PROVIDERS.get(i);
            Map<String, Object> row = Db.one("SELECT key_hint, model, workspace_id, extra, in_use, connected_by, connected_at,"
                    + " last_test_at, last_test_ok, last_test_msg FROM ai_settings WHERE id = ?", i + 1);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", LABEL.get(p));
            m.put("connected", row != null);
            if (row != null) {
                String extra = String.valueOf(row.remove("extra"));
                if (p.equals(AZURE)) {
                    String[] x = extra.split("\n", -1);
                    m.put("endpoint", x[0]);
                    m.put("api_version", x.length > 1 && !x[1].isEmpty() ? x[1] : AZURE_VERSION);
                }
                m.putAll(row);
                if (Boolean.TRUE.equals(row.get("in_use"))) inUse = p;
            }
            providers.put(p, m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providers);
        out.put("in_use", inUse);
        out.put("default_models", DEFAULT_MODELS);
        out.put("azure_version", AZURE_VERSION);
        return out;
    }

    /** Informação mínima para qualquer usuário saber se há IA disponível (sem dados sensíveis). */
    public static Map<String, Object> availability() throws SQLException {
        Map<String, Object> row = Db.one("SELECT id, model FROM ai_settings WHERE in_use = TRUE");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", row != null);
        if (row == null) { out.put("model", ""); out.put("provider", ""); out.put("label", ""); return out; }
        String p = PROVIDERS.get(((Number) row.get("id")).intValue() - 1), model = String.valueOf(row.get("model"));
        out.put("model", model);
        out.put("provider", p);
        out.put("label", p.equals(CLAUDE) ? model : LABEL.get(p) + " · " + model);
        return out;
    }

    // ------------------------------------------------------------------ uso (relatórios)

    /** Pede um texto à IA em uso (a escolhida na visão geral). Usado pelos relatórios dos projetos spot. */
    public static String complete(String system, String userMessage, int maxTokens) throws AiException, SQLException {
        Map<String, Object> row = Db.one("SELECT id FROM ai_settings WHERE in_use = TRUE");
        if (row == null) throw new AiException(409, "Nenhuma IA está em uso. Peça a um administrador para conectar uma em Configurações IA Agent.");
        String p = PROVIDERS.get(((Number) row.get("id")).intValue() - 1);
        String text = chat(load(p), system, userMessage, maxTokens, 120);
        if (text.isEmpty()) throw new AiException(502, "A IA não devolveu texto. Tente novamente.");
        return text;
    }

    // ------------------------------------------------------------------ conexão

    /** Valida a chave na própria IA e a guarda criptografada. extra1: workspace (Claude), organização (OpenAI) ou endereço (Azure); extra2: versão da API (Azure). */
    public static void connect(String provider, String apiKey, String model, String extra1, String extra2, String userName, Object userId)
            throws AiException, SQLException {
        int id = rowId(provider);
        String workspace = "", extra = "";
        if (provider.equals(CLAUDE)) {
            if (apiKey.startsWith("sk-ant-admin")) throw new AiException(400, "Essa é uma chave de administração. Crie uma chave de API comum no Claude Console.");
            if (!apiKey.matches("sk-ant-[A-Za-z0-9_\\-]{10,300}")) throw new AiException(400, "Formato de chave inválido. A chave começa com sk-ant- e é copiada do Claude Console.");
            validateModel(model);
            if (!extra1.isEmpty() && !extra1.matches("wrkspc_[A-Za-z0-9]{6,60}")) throw new AiException(400, "ID de workspace inválido (formato: wrkspc_...). Deixe em branco se a chave já é de um workspace.");
            workspace = extra1;
            callClaude(apiKey, workspace, "GET", "/v1/models?limit=1", null, 30); // lança erro se a chave não for aceita
        } else if (provider.equals(OPENAI)) {
            validateOpenAiKey(apiKey);
            validateModel(model);
            if (!extra1.isEmpty() && !extra1.matches("org-[A-Za-z0-9]{6,60}")) throw new AiException(400, "ID de organização inválido (formato: org-...). Deixe em branco se não usa.");
            workspace = extra1;
            callOpenAi(apiKey, workspace, "GET", "/v1/models", null, 30);
        } else {
            String endpoint = normalizeEndpoint(extra1), version = extra2.isEmpty() ? AZURE_VERSION : extra2;
            if (!apiKey.matches("[A-Za-z0-9_\\-]{16,200}")) throw new AiException(400, "Formato de chave inválido. Copie a chave em Chaves e Ponto de extremidade, no portal Azure.");
            validateDeployment(model);
            if (!version.matches("\\d{4}-\\d{2}-\\d{2}(-preview)?")) throw new AiException(400, "Versão da API inválida (exemplo: " + AZURE_VERSION + ").");
            extra = endpoint + "\n" + version;
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("provider", AZURE); probe.put("_key", apiKey); probe.put("model", model); probe.put("workspace_id", ""); probe.put("extra", extra);
            chat(probe, null, "Responda apenas com a palavra: ok", 200, 45); // valida endereço, chave e implantação de uma vez
        }
        String enc;
        try {
            enc = encrypt(apiKey);
        } catch (GeneralSecurityException | IOException e) {
            throw new AiException(500, "Não foi possível proteger a chave no servidor: " + e.getMessage());
        }
        String hint = apiKey.substring(0, Math.min(provider.equals(AZURE) ? 4 : 12, apiKey.length())) + "…" + apiKey.substring(apiKey.length() - 4);
        boolean noneInUse = Db.one("SELECT id FROM ai_settings WHERE in_use = TRUE AND id <> ?", id) == null;
        Db.update("DELETE FROM ai_settings WHERE id = ?", id);
        Db.update("INSERT INTO ai_settings(id,api_key_enc,key_hint,model,workspace_id,extra,in_use,connected_by,connected_by_id) VALUES(?,?,?,?,?,?,?,?,?)",
                id, enc, hint, model, workspace, extra, noneInUse, userName, userId);
    }

    /** Valida uma chave da OpenAI e devolve os modelos de texto que ela enxerga (para o passo "escolher o modelo"). */
    public static Map<String, Object> probe(String provider, String apiKey, String org) throws AiException {
        if (!provider.equals(OPENAI)) throw new AiException(400, "Esta IA não usa validação em duas etapas.");
        validateOpenAiKey(apiKey);
        if (!org.isEmpty() && !org.matches("org-[A-Za-z0-9]{6,60}")) throw new AiException(400, "ID de organização inválido (formato: org-...).");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", openAiModels(callOpenAi(apiKey, org, "GET", "/v1/models", null, 30)));
        return out;
    }

    public static void disconnect(String provider) throws AiException, SQLException {
        int id = rowId(provider);
        boolean was = Db.one("SELECT id FROM ai_settings WHERE id = ? AND in_use = TRUE", id) != null;
        Db.update("DELETE FROM ai_settings WHERE id = ?", id);
        if (was) Db.update("UPDATE ai_settings SET in_use = TRUE WHERE id = (SELECT MIN(id) FROM ai_settings)"); // outra IA conectada assume
    }

    public static void setModel(String provider, String model) throws AiException, SQLException {
        int id = rowId(provider);
        if (provider.equals(AZURE)) validateDeployment(model); else validateModel(model);
        if (Db.update("UPDATE ai_settings SET model = ? WHERE id = ?", model, id) == 0) throw new AiException(409, LABEL.get(provider) + " não está conectado.");
    }

    /** Define qual IA escreve os relatórios. */
    public static void setInUse(String provider) throws AiException, SQLException {
        int id = rowId(provider);
        if (Db.one("SELECT id FROM ai_settings WHERE id = ?", id) == null) throw new AiException(409, "Conecte " + LABEL.get(provider) + " antes de usá-lo.");
        Db.update("UPDATE ai_settings SET in_use = (id = ?)", id);
    }

    private static void validateModel(String model) throws AiException {
        if (!model.matches("[a-z0-9][a-z0-9._:\\-]{1,79}")) throw new AiException(400, "Nome de modelo inválido.");
    }

    private static void validateDeployment(String name) throws AiException {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._\\-]{0,63}")) throw new AiException(400, "Nome de implantação inválido (letras, números, ponto, hífen e sublinhado).");
    }

    private static void validateOpenAiKey(String key) throws AiException {
        if (!key.matches("sk-[A-Za-z0-9_\\-]{20,300}")) throw new AiException(400, "Formato de chave inválido. A chave começa com sk- e é copiada da plataforma da OpenAI.");
    }

    /** Aceita só endereços https de recursos Azure (evita apontar o servidor para qualquer outro lugar). */
    static String normalizeEndpoint(String raw) throws AiException {
        String t = raw.trim();
        URI u;
        try { u = URI.create(t); } catch (IllegalArgumentException e) { throw new AiException(400, "Endereço do recurso inválido."); }
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (!"https".equalsIgnoreCase(u.getScheme()) || !h.matches("[a-z0-9][a-z0-9\\-]*\\.(openai\\.azure\\.com|cognitiveservices\\.azure\\.com|services\\.ai\\.azure\\.com)")
                || u.getPort() != -1 || u.getUserInfo() != null) {
            throw new AiException(400, "Endereço do recurso inválido. Use o formato https://NOME.openai.azure.com (copiado do portal Azure).");
        }
        return "https://" + h;
    }

    // ------------------------------------------------------------------ operações

    /** Modelos disponíveis para a IA conectada (cai para a lista padrão se a consulta falhar). */
    public static Map<String, Object> models(String provider) throws AiException, SQLException {
        Map<String, Object> row = load(provider);
        Map<String, Object> out = new LinkedHashMap<>();
        if (provider.equals(AZURE)) { out.put("models", List.of(String.valueOf(row.get("model")))); out.put("source", "deployment"); return out; }
        try {
            List<String> ids = provider.equals(OPENAI)
                    ? openAiModels(callOpenAi((String) row.get("_key"), (String) row.get("workspace_id"), "GET", "/v1/models", null, 45))
                    : claudeModels(callClaude((String) row.get("_key"), (String) row.get("workspace_id"), "GET", "/v1/models?limit=100", null, 45));
            if (!ids.isEmpty()) { out.put("models", ids); out.put("source", "api"); return out; }
        } catch (AiException | RuntimeException e) {
            out.put("warning", e.getMessage());
        }
        out.put("models", provider.equals(CLAUDE) ? DEFAULT_MODELS : List.of(String.valueOf(row.get("model"))));
        out.put("source", "default");
        return out;
    }

    private static List<String> claudeModels(String body) {
        List<String> ids = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data")) ids.add(e.getAsJsonObject().get("id").getAsString());
        return ids;
    }

    /** Modelos de texto da OpenAI (tira embeddings, áudio, imagem, moderação etc.). */
    private static List<String> openAiModels(String body) {
        List<String> ids = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data")) {
            String id = e.getAsJsonObject().get("id").getAsString();
            if (id.matches("(gpt-|chatgpt-|o\\d).*") && !id.matches(".*(embed|whisper|tts|audio|realtime|image|transcribe|moderation|search|instruct|dall).*")) ids.add(id);
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    /** Teste real: envia uma mensagem mínima ao modelo escolhido (valida chave, modelo e saldo). */
    public static Map<String, Object> test(String provider) throws AiException, SQLException {
        Map<String, Object> row = load(provider);
        String model = (String) row.get("model");
        try {
            String reply = chat(row, null, "Responda apenas com a palavra: ok", 200, 45);
            Db.update("UPDATE ai_settings SET last_test_at = CURRENT_TIMESTAMP, last_test_ok = TRUE, last_test_msg = ? WHERE id = ?", "Resposta recebida de " + model, rowId(provider));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("model", model);
            out.put("reply", reply.length() > 100 ? reply.substring(0, 100) : reply);
            return out;
        } catch (AiException e) {
            String msg = e.getMessage().length() > 290 ? e.getMessage().substring(0, 290) : e.getMessage();
            Db.update("UPDATE ai_settings SET last_test_at = CURRENT_TIMESTAMP, last_test_ok = FALSE, last_test_msg = ? WHERE id = ?", msg, rowId(provider));
            throw e;
        }
    }

    /** Linha da IA com a chave já descriptografada em "_key". */
    private static Map<String, Object> load(String provider) throws AiException, SQLException {
        Map<String, Object> row = Db.one("SELECT api_key_enc, model, workspace_id, extra FROM ai_settings WHERE id = ?", rowId(provider));
        if (row == null) throw new AiException(409, LABEL.get(provider) + " não está conectado. Conecte em Configurações IA Agent.");
        try {
            row.put("_key", decrypt((String) row.get("api_key_enc")));
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new AiException(500, "Não foi possível ler a chave salva (o arquivo ai.key mudou?). Desconecte e conecte novamente.");
        }
        row.put("provider", provider);
        return row;
    }

    /** Uma pergunta e uma resposta de texto, no formato da IA da linha. */
    private static String chat(Map<String, Object> row, String system, String user, int maxTokens, int timeoutSec) throws AiException {
        String provider = (String) row.get("provider"), key = (String) row.get("_key"), model = (String) row.get("model");
        if (provider.equals(CLAUDE)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            if (system != null) body.put("system", system);
            body.put("messages", List.of(Map.of("role", "user", "content", user)));
            JsonObject o = JsonParser.parseString(callClaude(key, (String) row.get("workspace_id"), "POST", "/v1/messages", GSON.toJson(body), timeoutSec)).getAsJsonObject();
            StringBuilder sb = new StringBuilder();
            JsonArray content = o.getAsJsonArray("content");
            if (content != null) for (JsonElement e : content) { JsonObject b = e.getAsJsonObject(); if (b.has("text")) sb.append(b.get("text").getAsString()); }
            return sb.toString();
        }
        List<Map<String, String>> msgs = new ArrayList<>();
        if (system != null) msgs.add(Map.of("role", "system", "content", system));
        msgs.add(Map.of("role", "user", "content", user));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("max_completion_tokens", Math.max(maxTokens * 3, 1500)); // modelos com raciocínio gastam parte dos tokens pensando
        String resp;
        if (provider.equals(OPENAI)) {
            resp = callOpenAi(key, (String) row.get("workspace_id"), "POST", "/v1/chat/completions", GSON.toJson(body), timeoutSec);
        } else {
            String[] x = String.valueOf(row.get("extra")).split("\n", -1);
            String version = x.length > 1 && !x[1].isEmpty() ? x[1] : AZURE_VERSION;
            body.remove("model"); // no Azure a implantação vai no endereço
            resp = callAzure(key, x[0], model, version, GSON.toJson(body), timeoutSec);
        }
        JsonArray choices = JsonParser.parseString(resp).getAsJsonObject().getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) return "";
        JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return msg != null && msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
    }

    // ------------------------------------------------------------------ HTTP

    private static String callClaude(String apiKey, String workspace, String method, String path, String json, int timeoutSec) throws AiException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(CLAUDE_BASE + path)).timeout(Duration.ofSeconds(timeoutSec))
                .header("x-api-key", apiKey).header("anthropic-version", VERSION).header("content-type", "application/json");
        if (workspace != null && !workspace.isEmpty()) b.header("anthropic-workspace-id", workspace);
        return send(CLAUDE, b, json);
    }

    private static String callOpenAi(String apiKey, String org, String method, String path, String json, int timeoutSec) throws AiException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(OPENAI_BASE + path)).timeout(Duration.ofSeconds(timeoutSec))
                .header("Authorization", "Bearer " + apiKey).header("content-type", "application/json");
        if (org != null && !org.isEmpty()) b.header("OpenAI-Organization", org);
        return send(OPENAI, b, json);
    }

    private static String callAzure(String apiKey, String endpoint, String deployment, String version, String json, int timeoutSec) throws AiException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + "/openai/deployments/" + deployment + "/chat/completions?api-version=" + version))
                .timeout(Duration.ofSeconds(timeoutSec)).header("api-key", apiKey).header("content-type", "application/json");
        return send(AZURE, b, json);
    }

    private static String send(String provider, HttpRequest.Builder b, String json) throws AiException {
        try {
            b = json == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 == 2) return r.body();
            throw mapError(provider, r.statusCode(), r.body());
        } catch (IOException e) {
            throw new AiException(502, "O servidor não conseguiu acessar " + HOST.get(provider) + ". Verifique a internet, o firewall ou o proxy do servidor.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(502, "Conexão interrompida. Tente novamente.");
        }
    }

    private static AiException mapError(String provider, int http, String body) {
        String detail = "";
        try {
            JsonObject err = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (err != null && err.has("message") && !err.get("message").isJsonNull()) detail = err.get("message").getAsString();
        } catch (RuntimeException ignored) {
            // corpo sem JSON
        }
        String who = LABEL.get(provider), d = detail.isEmpty() ? "" : " (" + detail + ")";
        switch (http) {
            case 401: return new AiException(400, "Chave de API inválida, expirada ou revogada.");
            case 403: return new AiException(400, "A chave não tem permissão para esta operação." + d);
            case 404: return new AiException(400, provider.equals(AZURE)
                    ? "Implantação ou endereço não encontrado. Confira o nome da implantação, o endereço do recurso e a versão da API." + d
                    : "Modelo não encontrado ou sem acesso para esta chave." + d);
            case 429: return new AiException(429, "Limite de uso ou saldo de " + who + " atingido. Tente novamente em instantes." + d);
            default:
                if (http >= 500) return new AiException(502, "O serviço de " + who + " está indisponível ou sobrecarregado agora. Tente novamente.");
                return new AiException(400, who + " recusou a requisição" + (detail.isEmpty() ? " (HTTP " + http + ")." : ": " + detail));
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

    static String encrypt(String plain) throws GeneralSecurityException, IOException {
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

    static String decrypt(String b64) throws GeneralSecurityException, IOException {
        byte[] all = Base64.getDecoder().decode(b64);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, masterKey(), new GCMParameterSpec(128, all, 0, 12));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }
}
