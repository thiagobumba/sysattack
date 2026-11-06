package br.com.sysattack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * API JSON do SysAttack.
 *
 *  POST   /api/login | /api/logout        GET /api/me | /api/dashboard
 *  GET|POST /api/projects                 GET|PUT|DELETE /api/projects/{id}
 *  GET    /api/projects/{id}/tasks
 *  GET|POST /api/tasks                    PUT|DELETE /api/tasks/{id}
 */
@WebServlet(urlPatterns = "/api/*")
public class ApiServlet extends HttpServlet {
    private static final Gson GSON = new Gson();
    private static final Set<String> PROJECT_PRIORITY = Set.of("CRITICA", "ALTA", "MEDIA", "BAIXA");
    private static final Set<String> PROJECT_STATUS = Set.of("PLANEJAMENTO", "EM_ANDAMENTO", "CONCLUIDO", "PAUSADO");
    private static final Set<String> TASK_STATUS = Set.of("A_FAZER", "EM_ANDAMENTO", "CONCLUIDA", "BLOQUEADA", "CANCELADA");

    private static final Set<String> FIN_KIND = Set.of("NF_SERVICO", "FATURA");
    private static final Set<String> FIN_STATUS = Set.of("PENDENTE", "PAGO");
    private static final Set<String> DOC_CATEGORY = Set.of("ESPECIFICACAO", "CONTRATO", "PROPOSTA", "DESENHO", "NOTA_FISCAL", "OUTRO");
    static final Object FILE_SENT = new Object();
    static final long MAX_UPLOAD = 25L * 1024 * 1024;
    /** Tipos de arquivo aceitos nos documentos (extensão -> tipo MIME). Sem HTML/SVG/scripts por segurança. */
    static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("pdf", "application/pdf"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("txt", "text/plain; charset=UTF-8"), Map.entry("csv", "text/csv; charset=UTF-8"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip"), Map.entry("dwg", "application/octet-stream"),
            Map.entry("dxf", "application/octet-stream"));
    static final Set<String> INLINE = Set.of("pdf", "png", "jpg", "jpeg", "gif", "webp", "txt");

    private static final String NO_CHILDREN = "NOT EXISTS (SELECT 1 FROM tasks c WHERE c.parent_id = t.id)";

    private static final String PROJECT_SQL =
            "SELECT p.*,"
          + " (SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.id AND t.status <> 'CANCELADA' AND " + NO_CHILDREN + ") task_count,"
          + " (SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.id AND " + NO_CHILDREN
          + "   AND t.status NOT IN ('CONCLUIDA','CANCELADA') AND t.end_date < CURRENT_DATE) late_count,"
          + " CAST(COALESCE((SELECT AVG(t.progress) FROM tasks t WHERE t.project_id = p.id AND t.parent_id IS NULL AND t.status <> 'CANCELADA'), 0) AS INT) progress,"
          + " (SELECT COUNT(*) FROM documents d WHERE d.project_id = p.id) doc_count,"
          + " (SELECT COUNT(*) FROM finance_entries f WHERE f.project_id = p.id) fin_count"
          + " FROM projects p";

    private static final String TASK_SQL =
            "SELECT t.*, p.name project_name,"
          + " (SELECT COUNT(*) FROM tasks c WHERE c.parent_id = t.id) child_count"
          + " FROM tasks t JOIN projects p ON p.id = t.project_id";

    /** Controle de tentativas de login: chave "email|ip" -> [falhas, bloqueado até (ms)]. */
    private static final Map<String, long[]> ATTEMPTS = new ConcurrentHashMap<>();
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 10 * 60 * 1000L;

    /** Código de confirmação por e-mail, guardado na sessão do usuário. */
    static final class PwCode implements java.io.Serializable {
        final String hash; final long expires; int attempts;
        PwCode(String hash, long expires) { this.hash = hash; this.expires = expires; }
    }
    private static final SecureRandom RANDOM = new SecureRandom();

    static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    // ------------------------------------------------------------------ entrada

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        try {
            String m = req.getMethod();
            // proteção simples contra CSRF: escritas só com o cabeçalho enviado pelo nosso front-end
            if (!m.equals("GET") && !"SysAttack".equals(req.getHeader("X-Requested-With"))) {
                throw new ApiException(403, "Requisição não permitida.");
            }
            Object out;
            try {
                // toda alteração (POST/PUT/DELETE) é atômica: grava tudo ou nada
                final List<String> segs = segments(req);
                out = m.equals("GET") ? route(m, segs, req, resp) : Db.tx(() -> route(m, segs, req, resp));
            } finally {
                EmailTriggers.ACTOR.remove();
            }
            if (out != FILE_SENT) resp.getWriter().write(GSON.toJson(out));
        } catch (ApiException e) {
            resp.setStatus(e.status);
            resp.getWriter().write(GSON.toJson(Map.of("error", e.getMessage())));
        } catch (NumberFormatException | IllegalStateException | UnsupportedOperationException | JsonParseException e) {
            resp.setStatus(400);
            resp.getWriter().write(GSON.toJson(Map.of("error", "Dados inválidos.")));
        } catch (Exception e) {
            log("Erro na API", e);
            resp.setStatus(500);
            resp.getWriter().write(GSON.toJson(Map.of("error", "Erro interno no servidor.")));
        }
    }

    private static List<String> segments(HttpServletRequest req) {
        List<String> out = new ArrayList<>();
        String info = req.getPathInfo();
        if (info != null) for (String s : info.split("/")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private Object route(String m, List<String> s, HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (s.isEmpty()) throw new ApiException(404, "Rota inexistente.");
        String r = s.get(0);
        if (r.equals("setup-status") && m.equals("GET")) return Map.of("needed", Db.one("SELECT id FROM users LIMIT 1") == null);
        if (r.equals("setup") && m.equals("POST")) return setup(req);
        if (r.equals("login") && m.equals("POST")) return s.size() == 2 && s.get(1).equals("2fa") ? login2fa(req) : login(req);
        if (r.equals("invite")) return invite(m, s, req);
        if (r.equals("logout") && m.equals("POST")) {
            HttpSession ses = req.getSession(false);
            if (ses != null) {
                Object u = ses.getAttribute("user");
                if (u instanceof Map) Audit.log(req, String.valueOf(((Map<?, ?>) u).get("email")), "LOGOUT", "", true);
                ses.invalidate();
            }
            return Map.of("ok", true);
        }
        Map<String, Object> user = currentUser(req);
        EmailTriggers.ACTOR.set((String) user.get("name"));
        // pendências obrigatórias do primeiro acesso (trocar a senha temporária / ativar o 2FA exigido): só "me" funciona até resolver
        boolean mustChange = Boolean.TRUE.equals(user.get("must_change"));
        boolean mustTwofa = Boolean.TRUE.equals(user.get("totp_required")) && !Boolean.TRUE.equals(user.get("totp_enabled"));
        if ((mustChange || mustTwofa) && !r.equals("me")) {
            throw new ApiException(403, mustChange ? "Troque a senha temporária para continuar." : "Ative a verificação em duas etapas para continuar.");
        }
        switch (r) {
            case "me":
                if (s.size() == 2 && m.equals("POST")) {
                    if (s.get(1).equals("password-code")) return sendPasswordCode(req, user);
                    if (s.get(1).equals("password")) return changePassword(req, user);
                    if (s.get(1).equals("password-first")) return firstPassword(req, user);
                }
                if (s.size() >= 2 && s.get(1).equals("2fa")) return twofa(m, s, req, user);
                return user;
            case "users":
                // quem não é administrador enxerga apenas o próprio usuário
                if (m.equals("GET") && s.size() == 1 && !"ADMIN".equals(user.get("role"))) {
                    return Db.query(USER_SQL + " WHERE id = ?", user.get("id"));
                }
                requireAdmin(user);
                return users(m, s, req, user);
            case "dashboard": return dashboard();
            case "projects": return projects(m, s, req, user);
            case "tasks": return tasks(m, s, req);
            case "finance": return finance(m, s, req);
            case "documents": return documents(m, s, req, resp, user);
            case "ai": requireAdmin(user); return ai(m, s, req, user);
            case "ai-status": return AiConfig.availability();
            case "backup": requireAdmin(user); return backupApi(m, s, req, resp, user);
            case "security": requireAdmin(user); return Map.of("audit", Audit.recent(200));
            case "email": return EmailService.handle(m, s, req, resp, user);
            case "changes": return ChangeService.handle(m, s, req, user);
            case "reports": return ChangeService.reports(m);
            case "spot": return SpotService.handle(m, s, req, resp, user);
            default: throw new ApiException(404, "Rota inexistente.");
        }
    }

    // ------------------------------------------------------------------ autenticação

    private static Map<String, Object> currentUser(HttpServletRequest req) throws SQLException {
        HttpSession ses = req.getSession(false);
        Object u = ses == null ? null : ses.getAttribute("user");
        if (u == null) throw new ApiException(401, "Sessão expirada. Entre novamente.");
        // confere no banco a cada requisição: usuário bloqueado ou excluído perde o acesso imediatamente
        Map<String, Object> row = Db.one("SELECT id, username email, name, role, active, job_title, must_change, totp_enabled, totp_required FROM users WHERE id = ?",
                ((Map<?, ?>) u).get("id"));
        if (row == null || !Boolean.TRUE.equals(row.get("active"))) {
            ses.invalidate();
            throw new ApiException(401, "Sessão expirada. Entre novamente.");
        }
        row.remove("active");
        return row;
    }

    /** Falhas por (conta+IP), por conta (qualquer IP) e por IP (qualquer conta): trava temporária ao passar do limite. */
    private static long lockedUntil(String key, long now) {
        long[] a = ATTEMPTS.get(key);
        return a == null ? 0 : a[1] > now ? a[1] : 0;
    }

    private static void registerFail(String key, int max, long lockMs, long now) {
        long[] a = ATTEMPTS.computeIfAbsent(key, k -> new long[2]);
        synchronized (a) {
            if (a[1] != 0 && a[1] <= now) { a[0] = 0; a[1] = 0; } // trava anterior já passou
            if (++a[0] >= max) { a[0] = 0; a[1] = now + lockMs; }
        }
        if (ATTEMPTS.size() > 5000) { // limpeza: evita crescimento sem fim sob ataque
            ATTEMPTS.entrySet().removeIf(e -> e.getValue()[1] < now && e.getValue()[0] == 0);
            if (ATTEMPTS.size() > 20000) ATTEMPTS.clear();
        }
    }

    private Object login(HttpServletRequest req) throws Exception {
        JsonObject b = body(req);
        String email = str(b, "email", "E-mail", 120, true).toLowerCase();
        String password = str(b, "password", "Senha", 200, true);
        String ip = req.getRemoteAddr();
        String kPair = email + "|" + ip, kAcct = "acct|" + email, kIp = "ip|" + ip;
        long now = System.currentTimeMillis();
        long until = Math.max(lockedUntil(kPair, now), Math.max(lockedUntil(kAcct, now), lockedUntil(kIp, now)));
        if (until > 0) {
            Audit.log(req, email, "LOGIN_BLOQUEADO", "tentativa durante a trava temporária", false);
            throw new ApiException(429, "Muitas tentativas. Aguarde " + ((until - now) / 60000 + 1) + " minuto(s) e tente novamente.");
        }
        Map<String, Object> row = Db.one("SELECT * FROM users WHERE username = ?", email);
        boolean ok = row != null && Auth.verify(password, (String) row.get("salt"), (String) row.get("password_hash"));
        if (row == null) Auth.hash(password, Auth.newSalt()); // mesmo custo de tempo, exista o e-mail ou não
        if (!ok) {
            registerFail(kPair, MAX_FAILS, LOCK_MS, now);
            registerFail(kAcct, 20, 15 * 60 * 1000L, now);
            registerFail(kIp, 40, LOCK_MS, now);
            Audit.log(req, email, "LOGIN_FALHA", row == null ? "e-mail desconhecido" : "senha incorreta", false);
            throw new ApiException(401, "E-mail ou senha incorretos.");
        }
        ATTEMPTS.remove(kPair);
        ATTEMPTS.remove(kAcct);
        if (!Boolean.TRUE.equals(row.get("active"))) {
            Audit.log(req, email, "LOGIN_BLOQUEADO", "usuário bloqueado", false);
            throw new ApiException(403, "Usuário bloqueado. Procure o administrador do sistema.");
        }
        if (Auth.needsRehash((String) row.get("password_hash"))) { // atualiza hashes antigos para o parâmetro atual
            String salt = Auth.newSalt();
            Db.update("UPDATE users SET password_hash = ?, salt = ? WHERE id = ?", Auth.hash(password, salt), salt, row.get("id"));
        }
        if (Boolean.TRUE.equals(row.get("totp_enabled"))) {
            // senha certa, mas falta o segundo fator: ainda NÃO há sessão de usuário, só um estado pendente de 5 minutos
            HttpSession old = req.getSession(false);
            if (old != null) old.invalidate();
            HttpSession ses = req.getSession(true);
            ses.setMaxInactiveInterval(300);
            ses.setAttribute("pending2fa", new long[] {((Number) row.get("id")).longValue(), now + 5 * 60_000L, 0});
            Audit.log(req, email, "LOGIN_SENHA_OK", "aguardando o código de verificação", true);
            return Map.of("need_2fa", true);
        }
        Audit.log(req, email, "LOGIN", "", true);
        return startSession(req, row);
    }

    /** Segundo passo do login: código do aplicativo autenticador (6 dígitos) ou um código de recuperação. */
    private Object login2fa(HttpServletRequest req) throws Exception {
        HttpSession ses = req.getSession(false);
        long[] p = ses == null ? null : (long[]) ses.getAttribute("pending2fa");
        long now = System.currentTimeMillis();
        if (p == null || p[1] < now) throw new ApiException(401, "A verificação expirou. Entre novamente com e-mail e senha.");
        String key = "2fa|" + p[0];
        long until = lockedUntil(key, now);
        if (until > 0) throw new ApiException(429, "Muitas tentativas. Aguarde " + ((until - now) / 60000 + 1) + " minuto(s) e tente novamente.");
        Map<String, Object> row = Db.one("SELECT * FROM users WHERE id = ?", p[0]);
        String who = row == null ? "" : (String) row.get("username");
        String code = str(body(req), "code", "Código", 40, true);
        if (row == null || !Boolean.TRUE.equals(row.get("active")) || !checkSecondFactor(p[0], code)) {
            registerFail(key, 5, LOCK_MS, now);
            Audit.log(req, who, "LOGIN_2FA_FALHA", "código incorreto", false);
            if (++p[2] >= 5) { ses.invalidate(); throw new ApiException(401, "Muitas tentativas. Entre novamente com e-mail e senha."); }
            throw new ApiException(401, "Código incorreto. Confira o aplicativo e tente de novo.");
        }
        ATTEMPTS.remove(key);
        Audit.log(req, who, "LOGIN", "com verificação em duas etapas", true);
        return startSession(req, row);
    }

    /** Aceita um código TOTP (consumindo o passo) ou um código de recuperação (uso único). */
    private static boolean checkSecondFactor(long userId, String raw) throws SQLException {
        String code = raw.replaceAll("[\\s-]", "");
        if (code.matches("\\d{6}")) {
            Map<String, Object> r = Db.one("SELECT totp_secret_enc, totp_last_step FROM users WHERE id = ?", userId);
            String enc = r == null ? "" : (String) r.get("totp_secret_enc");
            if (enc.isEmpty()) return false;
            String secret;
            try { secret = AiConfig.decrypt(enc); } catch (Exception e) { throw new ApiException(500, "Não foi possível ler a chave de verificação (o arquivo ai.key mudou?). Peça ao administrador para redefinir o 2FA."); }
            long step = Totp.verify(secret, code, ((Number) r.get("totp_last_step")).longValue());
            if (step < 0) return false;
            Db.update("UPDATE users SET totp_last_step = ? WHERE id = ?", step, userId);
            return true;
        }
        if (code.length() != 10) return false;
        return Db.update("DELETE FROM recovery_codes WHERE user_id = ? AND code_hash = ?", userId, Totp.recoveryHash(code)) > 0;
    }

    private static List<String> newRecoveryCodes(long userId) throws SQLException {
        Db.update("DELETE FROM recovery_codes WHERE user_id = ?", userId);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String c = Totp.newRecoveryCode();
            out.add(c);
            Db.update("INSERT INTO recovery_codes(user_id, code_hash) VALUES(?,?)", userId, Totp.recoveryHash(c));
        }
        return out;
    }

    /** Troca da senha temporária no primeiro acesso: confirma a senha atual (não depende de e-mail). */
    private Object firstPassword(HttpServletRequest req, Map<String, Object> user) throws SQLException, IOException {
        JsonObject b = body(req);
        String cur = str(b, "current", "Senha atual", 200, true), next = str(b, "password", "Nova senha", 200, true);
        Map<String, Object> row = Db.one("SELECT * FROM users WHERE id = ?", user.get("id"));
        if (!Boolean.TRUE.equals(row.get("must_change"))) throw new ApiException(400, "A troca obrigatória não está pendente.");
        String key = "pwf|" + user.get("id");
        long now = System.currentTimeMillis();
        if (lockedUntil(key, now) > 0) throw new ApiException(429, "Muitas tentativas. Aguarde alguns minutos.");
        if (!Auth.verify(cur, (String) row.get("salt"), (String) row.get("password_hash"))) {
            registerFail(key, 5, LOCK_MS, now);
            throw new ApiException(400, "A senha atual (temporária) não confere.");
        }
        if (cur.equals(next)) throw new ApiException(400, "A nova senha precisa ser diferente da temporária.");
        String bad = Auth.checkPassword(next, (String) user.get("email"));
        if (bad != null) throw new ApiException(400, bad);
        String salt = Auth.newSalt();
        Db.update("UPDATE users SET password_hash = ?, salt = ?, must_change = FALSE WHERE id = ?", Auth.hash(next, salt), salt, user.get("id"));
        Audit.log(req, (String) user.get("email"), "SENHA_ALTERADA", "troca obrigatória do primeiro acesso", true);
        return Map.of("ok", true);
    }

    /** Verificação em duas etapas da própria conta: status, ativar (start/enable), desativar e novos códigos de recuperação. */
    private Object twofa(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        long id = ((Number) user.get("id")).longValue();
        String act = s.size() > 2 ? s.get(2) : "";
        Map<String, Object> row = Db.one("SELECT * FROM users WHERE id = ?", id);
        boolean enabled = Boolean.TRUE.equals(row.get("totp_enabled")), required = Boolean.TRUE.equals(row.get("totp_required"));
        if (m.equals("GET") && act.isEmpty()) {
            long left = ((Number) Db.one("SELECT COUNT(*) c FROM recovery_codes WHERE user_id = ?", id).get("c")).longValue();
            return Map.of("enabled", enabled, "required", required, "recovery_left", left);
        }
        if (!m.equals("POST")) throw new ApiException(405, "Operação não suportada.");
        HttpSession ses = req.getSession(false);
        String key = "2fa|" + id;
        long now = System.currentTimeMillis();
        if (lockedUntil(key, now) > 0) throw new ApiException(429, "Muitas tentativas. Aguarde alguns minutos e tente novamente.");
        String email = (String) user.get("email");
        switch (act) {
            case "start": {
                if (enabled) throw new ApiException(400, "A verificação em duas etapas já está ativa.");
                String secret = Totp.newSecret();
                ses.setAttribute("totp_pending", secret);
                return Map.of("secret", secret, "uri", Totp.uri("SysAttack", email, secret));
            }
            case "enable": {
                if (enabled) throw new ApiException(400, "A verificação em duas etapas já está ativa.");
                String secret = (String) ses.getAttribute("totp_pending");
                if (secret == null) throw new ApiException(400, "Comece a ativação de novo (a chave expirou).");
                long step = Totp.verify(secret, str(body(req), "code", "Código", 10, true).replaceAll("\\s", ""), 0);
                if (step < 0) {
                    registerFail(key, 8, LOCK_MS, now);
                    throw new ApiException(400, "Código incorreto. Confira a hora do celular e tente de novo.");
                }
                Db.update("UPDATE users SET totp_secret_enc = ?, totp_enabled = TRUE, totp_last_step = ? WHERE id = ?", AiConfig.encrypt(secret), step, id);
                ses.removeAttribute("totp_pending");
                Audit.log(req, email, "2FA_ATIVADO", "", true);
                return Map.of("ok", true, "recovery_codes", newRecoveryCodes(id));
            }
            case "disable":
            case "recovery": {
                if (!enabled) throw new ApiException(400, "A verificação em duas etapas não está ativa.");
                if (act.equals("disable") && required) throw new ApiException(403, "O administrador exige a verificação em duas etapas para a sua conta. Não é possível desativá-la.");
                JsonObject b = body(req);
                String pw = str(b, "password", "Senha", 200, true), code = str(b, "code", "Código", 40, true);
                if (!Auth.verify(pw, (String) row.get("salt"), (String) row.get("password_hash")) || !checkSecondFactor(id, code)) {
                    registerFail(key, 5, LOCK_MS, now);
                    throw new ApiException(400, "Senha ou código incorretos.");
                }
                if (act.equals("recovery")) {
                    Audit.log(req, email, "2FA_CODIGOS_NOVOS", "", true);
                    return Map.of("ok", true, "recovery_codes", newRecoveryCodes(id));
                }
                Db.update("UPDATE users SET totp_secret_enc = '', totp_enabled = FALSE, totp_last_step = 0 WHERE id = ?", id);
                Db.update("DELETE FROM recovery_codes WHERE user_id = ?", id);
                Audit.log(req, email, "2FA_DESATIVADO", "", true);
                return Map.of("ok", true);
            }
            default: throw new ApiException(404, "Rota inexistente.");
        }
    }

    private static Map<String, Object> startSession(HttpServletRequest req, Map<String, Object> row) {
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate(); // evita fixação de sessão
        HttpSession ses = req.getSession(true);
        ses.setMaxInactiveInterval(4 * 3600);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", row.get("id"));
        user.put("email", row.get("username"));
        user.put("name", row.get("name"));
        user.put("role", row.get("role"));
        ses.setAttribute("user", user);
        return user;
    }

    /** Primeiro acesso: só funciona enquanto não existe nenhum usuário. */
    private synchronized Object setup(HttpServletRequest req) throws SQLException, IOException {
        if (Db.one("SELECT id FROM users LIMIT 1") != null) {
            throw new ApiException(403, "A configuração inicial já foi concluída. Entre com o seu e-mail.");
        }
        if (Db.wasInitialized()) {
            // o sistema já teve administrador, mas o banco apareceu vazio: provável pasta de dados errada ou banco perdido
            Audit.log(req, "", "SETUP_BLOQUEADO", "banco sem usuários em instalação já inicializada", false);
            throw new ApiException(403, "O banco de dados está vazio, mas este sistema já foi configurado antes. "
                    + "Não crie um novo administrador: verifique a pasta de dados (-Dsysattack.data) ou restaure um backup. "
                    + "Veja a seção Dados do README.");
        }
        Map<String, Object> row = createUser(body(req), "ADMIN");
        Db.afterCommit(Db::markInitialized);
        Audit.log(req, (String) row.get("username"), "SETUP", "administrador inicial criado", true);
        return startSession(req, row);
    }

    /** Cadastro pelo administrador: sem senha (a pessoa cria a própria pelo link enviado por e-mail). */
    private static Map<String, Object> createInvitedUser(JsonObject b, String role) throws SQLException {
        String name = str(b, "name", "Nome", 120, true);
        String email = str(b, "email", "E-mail", 120, true).toLowerCase();
        String job = str(b, "job_title", "Cargo", 80, false);
        boolean need2fa = bool(b, "totp_required");
        if (!Auth.isEmail(email)) throw new ApiException(400, "Informe um e-mail válido.");
        if (Db.one("SELECT id FROM users WHERE username = ?", email) != null) {
            throw new ApiException(400, "Já existe um usuário com este e-mail.");
        }
        byte[] r = new byte[24];
        RANDOM.nextBytes(r);
        String salt = Auth.newSalt();
        long id = Db.insert("INSERT INTO users(username,name,password_hash,salt,role,job_title,invite_pending,totp_required) VALUES(?,?,?,?,?,?,TRUE,?)",
                email, name, Auth.hash(java.util.Base64.getEncoder().encodeToString(r), salt), salt, role, job, need2fa);
        return Db.one("SELECT * FROM users WHERE id = ?", id);
    }

    /** Público (sem sessão): valida o link de criação de senha (GET) e grava a senha escolhida (POST). */
    private Object invite(String m, List<String> s, HttpServletRequest req) throws Exception {
        if (s.size() != 2) throw new ApiException(404, "Rota inexistente.");
        String key = "inv|" + req.getRemoteAddr();
        long now = System.currentTimeMillis();
        if (lockedUntil(key, now) > 0) throw new ApiException(429, "Muitas tentativas. Aguarde alguns minutos e tente novamente.");
        Map<String, Object> u = Invites.find(s.get(1));
        if (u == null || !Boolean.TRUE.equals(u.get("active"))) {
            registerFail(key, 15, LOCK_MS, now);
            throw new ApiException(410, "Este link não é mais válido (expirou ou já foi usado). Peça um novo convite ao administrador.");
        }
        if (m.equals("GET")) return Map.of("name", u.get("name"), "email", u.get("email"));
        if (!m.equals("POST")) throw new ApiException(405, "Operação não suportada.");
        String pw = str(body(req), "password", "Senha", 200, true);
        String bad = Auth.checkPassword(pw, (String) u.get("email"));
        if (bad != null) throw new ApiException(400, bad);
        String salt = Auth.newSalt();
        Db.update("UPDATE users SET password_hash = ?, salt = ?, invite_pending = FALSE, must_change = FALSE WHERE id = ?", Auth.hash(pw, salt), salt, u.get("id"));
        Db.update("DELETE FROM user_tokens WHERE user_id = ?", u.get("id"));
        ATTEMPTS.remove("acct|" + u.get("email")); // zera travas de login da conta
        Audit.log(req, (String) u.get("email"), "SENHA_DEFINIDA", "pelo link do convite", true);
        return Map.of("ok", true);
    }

    private static Map<String, Object> createUser(JsonObject b, String role) throws SQLException {
        String name = str(b, "name", "Nome", 120, true);
        String email = str(b, "email", "E-mail", 120, true).toLowerCase();
        String password = str(b, "password", "Senha", 200, true);
        if (!Auth.isEmail(email)) throw new ApiException(400, "Informe um e-mail válido.");
        String bad = Auth.checkPassword(password, email);
        if (bad != null) throw new ApiException(400, bad);
        if (Db.one("SELECT id FROM users WHERE username = ?", email) != null) {
            throw new ApiException(400, "Já existe um usuário com este e-mail.");
        }
        String salt = Auth.newSalt();
        long id = Db.insert("INSERT INTO users(username,name,password_hash,salt,role) VALUES(?,?,?,?,?)",
                email, name, Auth.hash(password, salt), salt, role);
        return Db.one("SELECT * FROM users WHERE id = ?", id);
    }

    /** Passo 1: envia um código de 6 dígitos para o e-mail do usuário logado. */
    private Object sendPasswordCode(HttpServletRequest req, Map<String, Object> user) {
        HttpSession ses = req.getSession(false);
        long now = System.currentTimeMillis();
        Long last = (Long) ses.getAttribute("pwcode_sent");
        if (last != null && now - last < 60_000) {
            throw new ApiException(429, "Aguarde um minuto para pedir um novo código.");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String email = (String) user.get("email");
        try {
            Mailer.sendCode(email, (String) user.get("name"), code);
        } catch (Mailer.NotConfigured e) {
            throw new ApiException(503, "O envio de e-mail ainda não está configurado. Avise o administrador do sistema.");
        } catch (Exception e) {
            log("Falha ao enviar e-mail de confirmação", e);
            throw new ApiException(502, "Não foi possível enviar o e-mail agora. Tente novamente ou avise o administrador.");
        }
        ses.setAttribute("pwcode", new PwCode(Auth.sha256(code), now + 15 * 60_000L));
        ses.setAttribute("pwcode_sent", now);
        return Map.of("ok", true, "email", email);
    }

    /** Passo 2: confere o código e grava a nova senha. */
    private Object changePassword(HttpServletRequest req, Map<String, Object> user) throws SQLException, IOException {
        JsonObject b = body(req);
        String code = str(b, "code", "Código", 10, true);
        String next = str(b, "password", "Nova senha", 200, true);
        HttpSession ses = req.getSession(false);
        PwCode pc = (PwCode) ses.getAttribute("pwcode");
        if (pc == null || pc.expires < System.currentTimeMillis()) {
            ses.removeAttribute("pwcode");
            throw new ApiException(400, "Código expirado ou não solicitado. Peça um novo código.");
        }
        if (++pc.attempts > 5) {
            ses.removeAttribute("pwcode");
            throw new ApiException(400, "Muitas tentativas. Peça um novo código.");
        }
        if (!MessageDigest.isEqual(Auth.sha256(code).getBytes(), pc.hash.getBytes())) {
            throw new ApiException(400, "Código incorreto.");
        }
        String bad = Auth.checkPassword(next, (String) user.get("email"));
        if (bad != null) throw new ApiException(400, bad);
        String salt = Auth.newSalt();
        Db.update("UPDATE users SET password_hash = ?, salt = ? WHERE id = ?", Auth.hash(next, salt), salt, user.get("id"));
        ses.removeAttribute("pwcode");
        Audit.log(req, (String) user.get("email"), "SENHA_ALTERADA", "pelo próprio usuário", true);
        return Map.of("ok", true);
    }

    private static Object backupApi(String m, List<String> s, HttpServletRequest req, HttpServletResponse resp, Map<String, Object> user) throws Exception {
        String what = s.size() > 1 ? s.get(1) : "";
        if (m.equals("POST") && what.equals("run")) Audit.log(req, (String) user.get("email"), "BACKUP_MANUAL", "", true);
        if (what.equals("download")) Audit.log(req, (String) user.get("email"), "BACKUP_DOWNLOAD", s.size() > 2 ? s.get(2) : "", true);
        return BackupService.handle(m, s, req, resp, user);
    }

    static void requireAdmin(Map<String, Object> user) {
        if (!"ADMIN".equals(user.get("role"))) throw new ApiException(403, "Apenas administradores podem gerenciar usuários.");
    }

    // ------------------------------------------------------------------ usuários (administrador)

    /** Pares rótulo/valor em ordem, para o corpo dos e-mails de gatilho. */
    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static void taskEvents(Map<String, Object> before, Map<String, Object> after) {
        try {
            long pid = ((Number) after.get("project_id")).longValue();
            String name = (String) after.get("name"), who = (String) after.get("responsible");
            boolean ms = Boolean.TRUE.equals(after.get("is_milestone"));
            Map<String, String> info = mapOf("Tarefa", name, "Tipo", ms ? "Marco" : "Tarefa", "Responsável", who,
                    "Prazo", String.valueOf(after.get("end_date")));
            String key = "task:" + after.get("id");
            if (before == null) EmailTriggers.fire("TASK_CREATED", pid, who, key, info);
            boolean wasDone = before != null && "CONCLUIDA".equals(before.get("status"));
            if ("CONCLUIDA".equals(after.get("status")) && !wasDone) {
                EmailTriggers.fire(ms ? "MILESTONE_COMPLETED" : "TASK_COMPLETED", pid, who, key, info);
            }
        } catch (RuntimeException ignored) {
            // e-mail nunca derruba a operação
        }
    }

    private static final String USER_SQL = "SELECT id, username email, name, role, active, job_title, invite_pending, totp_enabled, totp_required FROM users";

    private static boolean pwSet(JsonObject b) {
        JsonElement p = b.get("password");
        return p != null && !p.isJsonNull() && !p.getAsString().isEmpty();
    }

    private Object users(String m, List<String> s, HttpServletRequest req, Map<String, Object> me) throws SQLException, IOException {
        if (s.size() == 1) {
            if (m.equals("GET")) return Db.query(USER_SQL + " ORDER BY name");
            if (m.equals("POST")) {
                JsonObject b = body(req);
                String role = str(b, "role", "Perfil", 10, true);
                if (!role.equals("ADMIN") && !role.equals("USER")) throw new ApiException(400, "Perfil inválido.");
                Map<String, Object> row = createInvitedUser(b, role);
                Map<String, Object> created = Db.one(USER_SQL + " WHERE id = ?", row.get("id"));
                Map<String, Object> inv = Invites.sendInvite(req, created, (String) me.get("name"), true);
                Audit.log(req, (String) me.get("email"), "USUARIO_CRIADO", row.get("username") + " (" + role + "), convite "
                        + (Boolean.TRUE.equals(inv.get("sent")) ? "enviado" : "NÃO enviado"), true);
                Map<String, Object> out = new LinkedHashMap<>(created);
                out.put("invite", inv);
                return out;
            }
        } else if (s.size() == 2) {
            long id = Long.parseLong(s.get(1));
            Map<String, Object> target = Db.one(USER_SQL + " WHERE id = ?", id);
            if (target == null) throw new ApiException(404, "Usuário não encontrado.");
            boolean lastAdmin = "ADMIN".equals(target.get("role"))
                    && ((Number) Db.one("SELECT COUNT(*) c FROM users WHERE role = 'ADMIN'").get("c")).longValue() <= 1;
            if (m.equals("PUT")) {
                JsonObject b = body(req);
                String name = str(b, "name", "Nome", 120, true);
                String role = str(b, "role", "Perfil", 10, true);
                if (!role.equals("ADMIN") && !role.equals("USER")) throw new ApiException(400, "Perfil inválido.");
                if (lastAdmin && !role.equals("ADMIN")) throw new ApiException(400, "Deve existir ao menos um administrador.");
                String pw = str(b, "password", "Senha", 200, false);
                String job = str(b, "job_title", "Cargo", 80, false);
                Db.update("UPDATE users SET name = ?, role = ?, job_title = ?, totp_required = ? WHERE id = ?", name, role, job, bool(b, "totp_required"), id);
                Audit.log(req, (String) me.get("email"), "USUARIO_ALTERADO", target.get("email") + " perfil=" + role + (pwSet(b) ? ", senha redefinida" : ""), true);
                if (!pw.isEmpty()) {
                    String bad = Auth.checkPassword(pw, (String) target.get("email"));
                    if (bad != null) throw new ApiException(400, bad);
                    String salt = Auth.newSalt();
                    Db.update("UPDATE users SET password_hash = ?, salt = ?, invite_pending = FALSE, must_change = TRUE WHERE id = ?", Auth.hash(pw, salt), salt, id);
                }
                return Db.one(USER_SQL + " WHERE id = ?", id);
            }
            if (m.equals("DELETE")) {
                if (id == ((Number) me.get("id")).longValue()) throw new ApiException(400, "Você não pode excluir o seu próprio usuário.");
                if (lastAdmin) throw new ApiException(400, "Deve existir ao menos um administrador.");
                Db.update("DELETE FROM users WHERE id = ?", id);
                Audit.log(req, (String) me.get("email"), "USUARIO_EXCLUIDO", String.valueOf(target.get("email")), true);
                return Map.of("ok", true);
            }
        } else if (s.size() == 3 && s.get(2).equals("invite") && m.equals("POST")) {
            long id = Long.parseLong(s.get(1));
            Map<String, Object> target = Db.one(USER_SQL + " WHERE id = ?", id);
            if (target == null) throw new ApiException(404, "Usuário não encontrado.");
            if (!Boolean.TRUE.equals(target.get("active"))) throw new ApiException(400, "Ative o usuário antes de enviar o convite.");
            boolean welcome = Boolean.TRUE.equals(target.get("invite_pending"));
            Map<String, Object> inv = Invites.sendInvite(req, target, (String) me.get("name"), welcome);
            Audit.log(req, (String) me.get("email"), welcome ? "CONVITE_REENVIADO" : "LINK_SENHA_ENVIADO", target.get("email") + (Boolean.TRUE.equals(inv.get("sent")) ? "" : " (NÃO enviado)"), true);
            return inv;
        } else if (s.size() == 3 && s.get(2).equals("2fa-reset") && m.equals("POST")) {
            long id = Long.parseLong(s.get(1));
            Map<String, Object> target = Db.one(USER_SQL + " WHERE id = ?", id);
            if (target == null) throw new ApiException(404, "Usuário não encontrado.");
            Db.update("UPDATE users SET totp_secret_enc = '', totp_enabled = FALSE, totp_last_step = 0 WHERE id = ?", id);
            Db.update("DELETE FROM recovery_codes WHERE user_id = ?", id);
            Audit.log(req, (String) me.get("email"), "2FA_REDEFINIDO", String.valueOf(target.get("email")), true);
            return Db.one(USER_SQL + " WHERE id = ?", id);
        } else if (s.size() == 3 && s.get(2).equals("active") && m.equals("PUT")) {
            long id = Long.parseLong(s.get(1));
            Map<String, Object> target = Db.one(USER_SQL + " WHERE id = ?", id);
            if (target == null) throw new ApiException(404, "Usuário não encontrado.");
            JsonElement e = body(req).get("active");
            if (e == null || e.isJsonNull()) throw new ApiException(400, "Informe se o usuário fica ativo ou bloqueado.");
            boolean active = e.getAsBoolean();
            Audit.log(req, (String) me.get("email"), active ? "USUARIO_DESBLOQUEADO" : "USUARIO_BLOQUEADO", String.valueOf(target.get("email")), true);
            if (!active) {
                if (id == ((Number) me.get("id")).longValue()) throw new ApiException(400, "Você não pode bloquear o seu próprio usuário.");
                long activeAdmins = ((Number) Db.one("SELECT COUNT(*) c FROM users WHERE role = 'ADMIN' AND active = TRUE").get("c")).longValue();
                if ("ADMIN".equals(target.get("role")) && Boolean.TRUE.equals(target.get("active")) && activeAdmins <= 1) {
                    throw new ApiException(400, "Deve existir ao menos um administrador ativo.");
                }
            }
            Db.update("UPDATE users SET active = ? WHERE id = ?", active, id);
            return Db.one(USER_SQL + " WHERE id = ?", id);
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    // ------------------------------------------------------------------ painel

    private Object dashboard() throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpi", Db.one("SELECT"
                + " (SELECT COUNT(*) FROM projects) projects_total,"
                + " (SELECT COUNT(*) FROM projects WHERE status = 'EM_ANDAMENTO') projects_active,"
                + " COUNT(*) tasks_total,"
                + " COALESCE(SUM(CASE WHEN t.status = 'CONCLUIDA' THEN 1 ELSE 0 END), 0) tasks_done,"
                + " COALESCE(SUM(CASE WHEN t.status NOT IN ('CONCLUIDA','CANCELADA') AND t.end_date < CURRENT_DATE THEN 1 ELSE 0 END), 0) tasks_late,"
                + " CAST(COALESCE(AVG(t.progress), 0) AS INT) avg_progress"
                + " FROM tasks t WHERE t.status <> 'CANCELADA' AND " + NO_CHILDREN));
        out.put("attention", Db.query(TASK_SQL + " WHERE t.status NOT IN ('CONCLUIDA','CANCELADA') AND " + NO_CHILDREN
                + " ORDER BY t.end_date, t.id LIMIT 8"));
        out.put("portfolio", Db.query(PROJECT_SQL + " ORDER BY p.start_date, p.id"));
        HealthCalc.attach((List<Map<String, Object>>) out.get("portfolio"));
        out.put("by_responsible", Db.query("SELECT responsible, COUNT(*) total,"
                + " SUM(CASE WHEN status = 'CONCLUIDA' THEN 1 ELSE 0 END) done FROM"
                + " (SELECT COALESCE(NULLIF(t.responsible, ''), 'Sem responsável') responsible, t.status FROM tasks t"
                + "  WHERE " + NO_CHILDREN + " AND t.is_milestone = FALSE AND t.status <> 'CANCELADA')"
                + " GROUP BY responsible ORDER BY total DESC, responsible"));
        return out;
    }

    // ------------------------------------------------------------------ projetos

    private Object projects(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        if (s.size() == 1) {
            if (m.equals("GET")) return withHealth(Db.query(PROJECT_SQL + " ORDER BY p.start_date, p.id"));
            if (m.equals("POST")) return saveProject(null, body(req));
        } else {
            long id = Long.parseLong(s.get(1));
            if (s.size() == 2) {
                switch (m) {
                    case "GET": return projectOr404(id);
                    case "PUT": projectOr404(id); return saveProject(id, body(req));
                    case "DELETE":
                        projectOr404(id);
                        for (Map<String, Object> d : Db.query("SELECT stored_name FROM documents WHERE project_id = ?", id)) {
                            String stored = (String) d.get("stored_name");
                            Db.afterCommit(() -> deleteStored(stored)); // só apaga o arquivo depois que o banco confirmar
                        }
                        Db.update("DELETE FROM projects WHERE id = ?", id);
                        return Map.of("ok", true);
                    default: break;
                }
            } else if (s.size() == 3) {
                String sub = s.get(2);
                if (sub.equals("tasks") && m.equals("GET")) {
                    projectOr404(id);
                    return Db.query(TASK_SQL + " WHERE t.project_id = ? ORDER BY t.start_date, t.id", id);
                }
                if (sub.equals("finance") && m.equals("GET")) return projectFinance(id);
                if (sub.equals("import-preview") && m.equals("POST")) { projectOr404(id); return importPreview(req); }
                if (sub.equals("import-tasks") && m.equals("POST")) { projectOr404(id); return importTasks(id, req, user); }
                if (sub.equals("documents")) {
                    projectOr404(id);
                    if (m.equals("GET")) return Db.query(DOC_SQL + " WHERE project_id = ? ORDER BY uploaded_at DESC, id DESC", id);
                    if (m.equals("POST")) return uploadDocument(id, req, user);
                }
            }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    private static Map<String, Object> projectOr404(long id) throws SQLException {
        Map<String, Object> p = Db.one(PROJECT_SQL + " WHERE p.id = ?", id);
        if (p == null) throw new ApiException(404, "Projeto não encontrado.");
        withHealth(List.of(p));
        return p;
    }

    private static List<Map<String, Object>> withHealth(List<Map<String, Object>> list) throws SQLException {
        HealthCalc.attach(list);
        return list;
    }

    private Object saveProject(Long id, JsonObject b) throws SQLException {
        String name = str(b, "name", "Nome", 160, true);
        String scope = str(b, "scope", "Escopo", 4000, false);
        String owner = str(b, "owner", "Responsável", 120, false);
        String status = str(b, "status", "Situação", 20, true);
        LocalDate start = date(b, "start_date", "Início");
        LocalDate end = date(b, "end_date", "Fim");
        BigDecimal budget = money(b, "budget", "Orçamento", false);
        String priority = b.has("priority") && !b.get("priority").isJsonNull() ? str(b, "priority", "Prioridade", 10, false) : "";
        if (priority.isBlank()) priority = "MEDIA";
        if (!PROJECT_PRIORITY.contains(priority)) throw new ApiException(400, "Prioridade inválida.");
        boolean goal = bool(b, "company_goal"); // Meta Empresa: projeto do plano de desenvolvimento, acima de qualquer prioridade
        if (!PROJECT_STATUS.contains(status)) throw new ApiException(400, "Situação inválida.");
        if (end.isBefore(start)) throw new ApiException(400, "A data final não pode ser anterior à inicial.");
        String oldStatus = null;
        if (id == null) {
            id = Db.insert("INSERT INTO projects(name,scope,owner,status,start_date,end_date,budget,priority,company_goal) VALUES(?,?,?,?,?,?,?,?,?)",
                    name, scope, owner, status, start, end, budget, priority, goal);
            EmailTriggers.fire("PROJECT_CREATED", id, owner, "project:" + id, mapOf("Responsável", owner, "Início", start.toString(), "Fim", end.toString()));
        } else {
            Map<String, Object> before = Db.one("SELECT status FROM projects WHERE id = ?", id);
            oldStatus = before == null ? null : (String) before.get("status");
            Db.update("UPDATE projects SET name=?, scope=?, owner=?, status=?, start_date=?, end_date=?, budget=?, priority=?, company_goal=? WHERE id=?",
                    name, scope, owner, status, start, end, budget, priority, goal, id);
            if (oldStatus != null && !oldStatus.equals(status)) {
                EmailTriggers.fire("PROJECT_STATUS_CHANGED", id, owner, "project:" + id, mapOf("Situação anterior", oldStatus, "Nova situação", status, "Responsável", owner));
            }
        }
        return projectOr404(id);
    }

    // ------------------------------------------------------------------ tarefas

    private Object tasks(String m, List<String> s, HttpServletRequest req) throws Exception {
        if (s.size() == 1) {
            if (m.equals("GET")) return Db.query(TASK_SQL + " ORDER BY t.start_date, t.id");
            if (m.equals("POST")) return saveTask(null, body(req));
        } else if (s.size() >= 2) {
            long id = Long.parseLong(s.get(1));
            Map<String, Object> t = Db.one(TASK_SQL + " WHERE t.id = ?", id);
            if (t == null) throw new ApiException(404, "Tarefa não encontrada.");
            if (s.size() == 2) {
                if (m.equals("GET")) return t;
                if (m.equals("PUT")) return saveTask(t, body(req));
                if (m.equals("DELETE")) {
                    Long parent = optNum(t.get("parent_id"));
                    Db.update("DELETE FROM tasks WHERE id = ?", id); // as filhas saem junto (ON DELETE CASCADE)
                    TaskRules.rollup(parent);
                    return Map.of("ok", true);
                }
            } else if (s.size() == 3 && s.get(2).equals("done") && m.equals("PUT")) {
                return markDone(t, body(req));
            }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    /** Conclui ou reabre uma tarefa/marco com um clique. */
    private Object markDone(Map<String, Object> t, JsonObject b) throws SQLException {
        if (((Number) t.get("child_count")).intValue() > 0) {
            throw new ApiException(400, "Esta tarefa é calculada pelas filhas. Conclua as tarefas filhas.");
        }
        JsonElement e = b.get("done");
        boolean done = e != null && !e.isJsonNull() && e.getAsBoolean();
        long id = ((Number) t.get("id")).longValue();
        Db.update("UPDATE tasks SET status = ?, progress = ? WHERE id = ?", done ? "CONCLUIDA" : "A_FAZER", done ? 100 : 0, id);
        TaskRules.rollup(optNum(t.get("parent_id")));
        Map<String, Object> saved = Db.one(TASK_SQL + " WHERE t.id = ?", id);
        taskEvents(t, saved);
        return saved;
    }

    private Object saveTask(Map<String, Object> existing, JsonObject b) throws SQLException {
        Long id = existing == null ? null : ((Number) existing.get("id")).longValue();
        long projectId = existing == null ? longOf(b, "project_id", "Projeto")
                                          : ((Number) existing.get("project_id")).longValue();
        if (Db.one("SELECT id FROM projects WHERE id = ?", projectId) == null) {
            throw new ApiException(400, "Projeto inexistente.");
        }
        String name = str(b, "name", "Nome da tarefa", 160, true);
        String desc = str(b, "description", "Descrição", 4000, false);
        String who = str(b, "responsible", "Responsável", 120, false);
        boolean milestone = bool(b, "is_milestone");
        int children = existing == null ? 0 : ((Number) existing.get("child_count")).intValue();
        Long oldParent = existing == null ? null : optNum(existing.get("parent_id"));

        // tarefa pai (apenas um nível: pai -> filha)
        Long parent = optLong(b, "parent_id");
        if (parent != null) {
            if (id != null && parent.equals(id)) throw new ApiException(400, "Uma tarefa não pode ser filha dela mesma.");
            if (children > 0) throw new ApiException(400, "Uma tarefa que já tem filhas não pode virar filha de outra.");
            Map<String, Object> p = Db.one("SELECT project_id, parent_id FROM tasks WHERE id = ?", parent);
            if (p == null || ((Number) p.get("project_id")).longValue() != projectId) {
                throw new ApiException(400, "A tarefa pai precisa ser do mesmo projeto.");
            }
            if (p.get("parent_id") != null) throw new ApiException(400, "Só é possível criar filhas de tarefas principais (um nível).");
        }

        LocalDate start, end;
        int progress;
        String status;
        if (children > 0) { // pai com filhas: datas/progresso/situação vêm do cálculo das filhas
            start = LocalDate.parse((String) existing.get("start_date"));
            end = LocalDate.parse((String) existing.get("end_date"));
            progress = ((Number) existing.get("progress")).intValue();
            status = (String) existing.get("status");
        } else {
            start = date(b, "start_date", milestone ? "Data do marco" : "Início");
            end = milestone ? start : date(b, "end_date", "Fim");
            status = str(b, "status", "Situação", 20, true);
            JsonElement pe = b.get("progress");
            progress = (pe == null || pe.isJsonNull()) ? 0 : pe.getAsInt();
            if (!TASK_STATUS.contains(status)) throw new ApiException(400, "Situação inválida.");
            if (end.isBefore(start)) throw new ApiException(400, "A data final não pode ser anterior à inicial.");
            if (progress < 0 || progress > 100) throw new ApiException(400, "O progresso deve estar entre 0 e 100.");
            if (!status.equals("CANCELADA")) { // tarefa cancelada mantém a situação e o progresso informados
                if (progress == 100) status = "CONCLUIDA";
                if (status.equals("CONCLUIDA")) progress = 100;
            }
        }

        Long dep = optLong(b, "depends_on");
        if (dep != null) checkDependency(id, projectId, dep);

        if (id == null) {
            id = Db.insert("INSERT INTO tasks(project_id,name,description,responsible,start_date,end_date,progress,status,depends_on,parent_id,is_milestone)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)", projectId, name, desc, who, start, end, progress, status, dep, parent, milestone);
        } else {
            Db.update("UPDATE tasks SET name=?, description=?, responsible=?, start_date=?, end_date=?, progress=?, status=?, depends_on=?, parent_id=?, is_milestone=? WHERE id=?",
                    name, desc, who, start, end, progress, status, dep, parent, milestone, id);
        }
        TaskRules.rollup(parent);
        if (oldParent != null && !oldParent.equals(parent)) TaskRules.rollup(oldParent);
        Map<String, Object> saved = Db.one(TASK_SQL + " WHERE t.id = ?", id);
        taskEvents(existing, saved);
        return saved;
    }

    // ------------------------------------------------------------------ importação de cronograma do Microsoft Project

    /** Lê o .mpp (ou XML do Project) enviado "cru" no corpo e devolve as linhas para a pré-visualização. Não grava nada. */
    private Object importPreview(HttpServletRequest req) throws IOException {
        long declared = req.getContentLengthLong();
        if (declared > MAX_UPLOAD) throw new ApiException(413, "Arquivo maior que 25 MB.");
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        try (InputStream in = req.getInputStream()) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_UPLOAD) throw new ApiException(413, "Arquivo maior que 25 MB.");
            }
        }
        ProjectFileReader.Result r = ProjectFileReader.parse(buf.toByteArray());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectFileReader.Row x : r.rows) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("uid", x.uid);
            o.put("id", x.id);
            o.put("level", x.level);
            o.put("name", x.name);
            o.put("start", x.start == null ? null : x.start.toLocalDate().toString());
            o.put("end", x.finish == null ? null : x.finish.toLocalDate().toString());
            o.put("percent", x.percent);
            o.put("summary", x.summary);
            o.put("milestone", x.milestone);
            List<Map<String, Object>> preds = new ArrayList<>();
            for (ProjectFileReader.Link l : x.preds) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("uid", l.pred); pl.put("type", l.type); pl.put("lag_days", Math.round(l.lagDays * 10) / 10.0);
                preds.add(pl);
            }
            o.put("preds", preds);
            rows.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", r.format);
        out.put("project_name", r.projectName);
        out.put("rows", rows);
        return out;
    }

    /**
     * Cria as tarefas escolhidas na pré-visualização. Cada item: ref (número), parent (ref de outra tarefa da lista ou nulo),
     * name, start_date, end_date, progress, milestone, depends (ref ou nulo). Não dispara e-mails de gatilho (são dezenas de tarefas de uma vez).
     */
    private Object importTasks(long projectId, HttpServletRequest req, Map<String, Object> user) throws SQLException, IOException {
        JsonObject b = body(req);
        JsonElement te = b.get("tasks");
        if (te == null || !te.isJsonArray() || te.getAsJsonArray().size() == 0) throw new ApiException(400, "Nenhuma tarefa selecionada para importar.");
        if (te.getAsJsonArray().size() > 5000) throw new ApiException(400, "Tarefas demais de uma vez (limite de 5000).");
        Map<Long, Long> refToId = new HashMap<>();
        Map<Long, Long> pendingDep = new LinkedHashMap<>(); // id criado -> ref da predecessora
        Set<Long> parents = new java.util.LinkedHashSet<>();
        Set<Long> parentRefs = new HashSet<>();
        LocalDate min = null, max = null;
        int created = 0;
        // 1ª volta: descobre quais refs são pais (precisam de dados vindos das filhas)
        for (JsonElement e : te.getAsJsonArray()) {
            JsonObject t = e.getAsJsonObject();
            if (t.has("parent") && !t.get("parent").isJsonNull()) parentRefs.add(t.get("parent").getAsLong());
        }
        for (JsonElement e : te.getAsJsonArray()) {
            if (!e.isJsonObject()) throw new ApiException(400, "Lista de tarefas inválida.");
            JsonObject t = e.getAsJsonObject();
            long ref = longOf(t, "ref", "Referência");
            String name = str(t, "name", "Nome da tarefa", 160, true);
            LocalDate start = date(t, "start_date", "Início");
            LocalDate end = date(t, "end_date", "Fim");
            boolean milestone = bool(t, "milestone");
            if (milestone) end = start;
            if (end.isBefore(start)) end = start;
            if (start.getYear() < 1990 || end.getYear() > 2100) throw new ApiException(400, "Data fora do intervalo aceito na tarefa: " + name + ".");
            int progress = 0;
            JsonElement pe = t.get("progress");
            if (pe != null && !pe.isJsonNull()) progress = Math.max(0, Math.min(100, pe.getAsInt()));
            String status = progress >= 100 ? "CONCLUIDA" : progress > 0 ? "EM_ANDAMENTO" : "A_FAZER";
            Long parent = null;
            if (t.has("parent") && !t.get("parent").isJsonNull()) {
                parent = refToId.get(t.get("parent").getAsLong());
                if (parent == null) throw new ApiException(400, "A tarefa pai de \"" + name + "\" não veio antes dela na lista.");
            }
            String desc = str(t, "description", "Descrição", 4000, false);
            long newId = Db.insert("INSERT INTO tasks(project_id,name,description,responsible,start_date,end_date,progress,status,depends_on,parent_id,is_milestone)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)", projectId, name, desc, "", start, end, progress, status, null, parent, milestone);
            refToId.put(ref, newId);
            created++;
            if (parent != null) parents.add(parent);
            if (t.has("depends") && !t.get("depends").isJsonNull() && !parentRefs.contains(ref)) pendingDep.put(newId, t.get("depends").getAsLong());
            if (min == null || start.isBefore(min)) min = start;
            if (max == null || end.isAfter(max)) max = end;
        }
        // 2ª volta: predecessoras (só entre tarefas importadas; ciclos e inválidas são ignoradas e contadas)
        int deps = 0, skipped = 0;
        for (Map.Entry<Long, Long> d : pendingDep.entrySet()) {
            Long target = refToId.get(d.getValue());
            if (target == null || target.equals(d.getKey())) { skipped++; continue; }
            try {
                checkDependency(d.getKey(), projectId, target);
                Db.update("UPDATE tasks SET depends_on = ? WHERE id = ?", target, d.getKey());
                deps++;
            } catch (ApiException ex) {
                skipped++;
            }
        }
        for (Long p : parents) TaskRules.rollup(p);
        // opcional: estende as datas do projeto para caber no cronograma importado
        boolean adjusted = false;
        if (bool(b, "adjust_project") && min != null) {
            Map<String, Object> pr = Db.one("SELECT start_date, end_date FROM projects WHERE id = ?", projectId);
            LocalDate ps = LocalDate.parse(String.valueOf(pr.get("start_date"))), pe2 = LocalDate.parse(String.valueOf(pr.get("end_date")));
            LocalDate ns = min.isBefore(ps) ? min : ps, ne = max.isAfter(pe2) ? max : pe2;
            if (!ns.equals(ps) || !ne.equals(pe2)) {
                Db.update("UPDATE projects SET start_date = ?, end_date = ? WHERE id = ?", ns, ne, projectId);
                adjusted = true;
            }
        }
        String src = str(b, "source", "Arquivo", 200, false);
        Audit.log(req, String.valueOf(user.get("email")), "CRONOGRAMA_IMPORTADO", "projeto " + projectId + ": " + created + " tarefa(s) de " + src, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("dependencies", deps);
        out.put("dependencies_skipped", skipped);
        out.put("project_adjusted", adjusted);
        return out;
    }

    /** A predecessora precisa ser do mesmo projeto e não pode formar ciclo. */
    private static void checkDependency(Long selfId, long projectId, long dep) throws SQLException {
        if (selfId != null && selfId == dep) throw new ApiException(400, "Uma tarefa não pode depender dela mesma.");
        Long cur = dep;
        for (int guard = 0; cur != null && guard < 1000; guard++) {
            Map<String, Object> row = Db.one("SELECT project_id, depends_on FROM tasks WHERE id = ?", cur);
            if (row == null) throw new ApiException(400, "Tarefa predecessora inexistente.");
            if (guard == 0 && ((Number) row.get("project_id")).longValue() != projectId) {
                throw new ApiException(400, "A predecessora precisa ser do mesmo projeto.");
            }
            Object next = row.get("depends_on");
            cur = next == null ? null : ((Number) next).longValue();
            if (selfId != null && cur != null && cur.equals(selfId)) {
                throw new ApiException(400, "Dependência circular entre tarefas.");
            }
        }
    }

    // ------------------------------------------------------------------ IA Agent (Claude) — somente administrador

    private Object ai(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        try {
            if (s.size() == 1) {
                if (m.equals("GET")) return AiConfig.status();
                if (m.equals("PUT")) {
                    AiConfig.setModel(str(body(req), "model", "Modelo", 80, true));
                    return AiConfig.status();
                }
                if (m.equals("DELETE")) {
                    AiConfig.disconnect();
                    return AiConfig.status();
                }
            } else if (s.size() == 2) {
                String act = s.get(1);
                if (act.equals("connect") && m.equals("POST")) {
                    JsonObject b = body(req);
                    AiConfig.connect(str(b, "api_key", "Chave de API", 400, true), str(b, "model", "Modelo", 80, true),
                            str(b, "workspace_id", "Workspace", 80, false), (String) user.get("name"), user.get("id"));
                    return AiConfig.status();
                }
                if (act.equals("test") && m.equals("POST")) return AiConfig.test();
                if (act.equals("models") && m.equals("GET")) return AiConfig.models();
            }
        } catch (AiConfig.AiException e) {
            throw new ApiException(e.status, e.getMessage());
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    // ------------------------------------------------------------------ financeiro

    private Object projectFinance(long projectId) throws SQLException {
        Map<String, Object> p = projectOr404(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("budget", p.get("budget"));
        out.put("entries", Db.query("SELECT * FROM finance_entries WHERE project_id = ? ORDER BY issue_date DESC, id DESC", projectId));
        out.put("summary", Db.one("SELECT COALESCE(SUM(amount), 0) total,"
                + " COALESCE(SUM(CASE WHEN status = 'PAGO' THEN amount ELSE 0 END), 0) paid,"
                + " COALESCE(SUM(CASE WHEN kind = 'NF_SERVICO' THEN amount ELSE 0 END), 0) nf_total,"
                + " COALESCE(SUM(CASE WHEN kind = 'FATURA' THEN amount ELSE 0 END), 0) invoice_total"
                + " FROM finance_entries WHERE project_id = ?", projectId));
        return out;
    }

    private Object finance(String m, List<String> s, HttpServletRequest req) throws Exception {
        if (s.size() == 1 && m.equals("POST")) return saveFinance(null, body(req));
        if (s.size() == 2) {
            long id = Long.parseLong(s.get(1));
            Map<String, Object> row = Db.one("SELECT * FROM finance_entries WHERE id = ?", id);
            if (row == null) throw new ApiException(404, "Lançamento não encontrado.");
            if (m.equals("PUT")) return saveFinance(row, body(req));
            if (m.equals("DELETE")) {
                Db.update("DELETE FROM finance_entries WHERE id = ?", id);
                return Map.of("ok", true);
            }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    private Object saveFinance(Map<String, Object> existing, JsonObject b) throws SQLException {
        Long id = existing == null ? null : ((Number) existing.get("id")).longValue();
        long projectId = existing == null ? longOf(b, "project_id", "Projeto") : ((Number) existing.get("project_id")).longValue();
        projectOr404(projectId);
        String kind = str(b, "kind", "Tipo", 20, true);
        String number = str(b, "number", "Número", 60, false);
        String party = str(b, "party", "Prestador / fornecedor", 160, true);
        String desc = str(b, "description", "Descrição", 500, false);
        String status = str(b, "status", "Situação", 10, true);
        LocalDate issue = date(b, "issue_date", "Emissão");
        LocalDate due = optDate(b, "due_date", "Vencimento");
        LocalDate paid = optDate(b, "paid_date", "Data do pagamento");
        BigDecimal amount = money(b, "amount", "Valor", true);
        if (!FIN_KIND.contains(kind)) throw new ApiException(400, "Tipo inválido.");
        if (!FIN_STATUS.contains(status)) throw new ApiException(400, "Situação inválida.");
        if (status.equals("PAGO")) { if (paid == null) paid = LocalDate.now(); } else { paid = null; }
        if (id == null) {
            id = Db.insert("INSERT INTO finance_entries(project_id,kind,number,party,description,issue_date,due_date,amount,status,paid_date)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)", projectId, kind, number, party, desc, issue, due, amount, status, paid);
        } else {
            Db.update("UPDATE finance_entries SET kind=?, number=?, party=?, description=?, issue_date=?, due_date=?, amount=?, status=?, paid_date=? WHERE id=?",
                    kind, number, party, desc, issue, due, amount, status, paid, id);
        }
        Map<String, Object> saved = Db.one("SELECT * FROM finance_entries WHERE id = ?", id);
        if (existing == null) {
            EmailTriggers.fire("FINANCE_ENTRY", projectId, null, "fin:" + id, mapOf("Lançamento", (kind.equals("FATURA") ? "Fatura " : "Nota fiscal ") + number,
                    "Prestador / fornecedor", party, "Valor", "R$ " + amount.toPlainString().replace('.', ','), "Situação", status));
        }
        return saved;
    }

    // ------------------------------------------------------------------ documentos

    private static final String DOC_SQL = "SELECT id, project_id, title, category, description, file_name, size,"
            + " uploaded_by, uploaded_by_id, uploaded_at FROM documents";

    private static File docsDir() {
        File d = new File(Db.dataDir(), "docs");
        d.mkdirs();
        return d;
    }

    private static void deleteStored(String stored) {
        if (stored == null || !stored.matches("[0-9a-f]{32}")) return;
        try {
            Files.deleteIfExists(new File(docsDir(), stored).toPath());
        } catch (IOException ignored) {
            // arquivo já removido
        }
    }

    static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        if (v == null) return "";
        try {
            return URLDecoder.decode(v, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return v.trim();
        }
    }

    static String extOf(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Upload "cru": o corpo da requisição é o arquivo; nome e metadados vão em cabeçalhos X-*. */
    private Object uploadDocument(long projectId, HttpServletRequest req, Map<String, Object> user) throws SQLException, IOException {
        long declared = req.getContentLengthLong();
        if (declared > MAX_UPLOAD) throw new ApiException(413, "Arquivo maior que 25 MB.");
        String fileName = header(req, "X-File-Name").replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (fileName.length() > 200) fileName = fileName.substring(fileName.length() - 200);
        if (fileName.isBlank()) throw new ApiException(400, "Selecione um arquivo.");
        String ext = extOf(fileName);
        if (!MIME.containsKey(ext)) {
            throw new ApiException(400, "Tipo de arquivo não permitido. Aceitos: " + String.join(", ", new java.util.TreeSet<>(MIME.keySet())) + ".");
        }
        String title = header(req, "X-Doc-Title");
        if (title.isEmpty()) title = fileName.substring(0, Math.max(1, fileName.length() - ext.length() - 1));
        if (title.length() > 160) throw new ApiException(400, "O título excede 160 caracteres.");
        String category = header(req, "X-Doc-Category");
        if (!DOC_CATEGORY.contains(category)) category = "OUTRO";
        String description = header(req, "X-Doc-Description");
        if (description.length() > 500) throw new ApiException(400, "A descrição excede 500 caracteres.");

        String stored = UUID.randomUUID().toString().replace("-", "");
        Path target = new File(docsDir(), stored).toPath();
        long total = 0;
        boolean tooBig = false;
        try (InputStream in = req.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_UPLOAD) { tooBig = true; break; }
                out.write(buf, 0, n);
            }
        }
        if (tooBig) { deleteStored(stored); throw new ApiException(413, "Arquivo maior que 25 MB."); }
        if (total == 0) { deleteStored(stored); throw new ApiException(400, "O arquivo está vazio."); }
        Db.onRollback(() -> deleteStored(stored)); // se a gravação for desfeita, não deixa arquivo órfão
        long id = Db.insert("INSERT INTO documents(project_id,title,category,description,file_name,stored_name,size,uploaded_by,uploaded_by_id)"
                + " VALUES(?,?,?,?,?,?,?,?,?)", projectId, title, category, description, fileName, stored, total,
                user.get("name"), user.get("id"));
        EmailTriggers.fire("DOCUMENT_ADDED", projectId, null, "doc:" + id, mapOf("Documento", title, "Categoria", category, "Arquivo", fileName));
        return Db.one(DOC_SQL + " WHERE id = ?", id);
    }

    private Object documents(String m, List<String> s, HttpServletRequest req, HttpServletResponse resp,
                             Map<String, Object> user) throws Exception {
        if (s.size() >= 2) {
            long id = Long.parseLong(s.get(1));
            Map<String, Object> d = Db.one("SELECT * FROM documents WHERE id = ?", id);
            if (d == null) throw new ApiException(404, "Documento não encontrado.");
            if (s.size() == 3 && s.get(2).equals("file") && m.equals("GET")) return serveFile(d, req, resp);
            if (s.size() == 2 && m.equals("DELETE")) {
                boolean owner = d.get("uploaded_by_id") != null
                        && ((Number) d.get("uploaded_by_id")).longValue() == ((Number) user.get("id")).longValue();
                if (!owner && !"ADMIN".equals(user.get("role"))) {
                    throw new ApiException(403, "Só o administrador ou quem enviou o documento pode excluí-lo.");
                }
                Db.update("DELETE FROM documents WHERE id = ?", id);
                Db.afterCommit(() -> deleteStored((String) d.get("stored_name")));
                return Map.of("ok", true);
            }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    private Object serveFile(Map<String, Object> d, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String stored = (String) d.get("stored_name");
        File f = new File(docsDir(), stored);
        if (!stored.matches("[0-9a-f]{32}") || !f.isFile()) throw new ApiException(404, "Arquivo não encontrado no servidor.");
        String fileName = (String) d.get("file_name");
        String ext = extOf(fileName);
        boolean inline = INLINE.contains(ext) && !"1".equals(req.getParameter("download"));
        resp.setContentType(MIME.getOrDefault(ext, "application/octet-stream"));
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Content-Disposition", (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        resp.setContentLengthLong(f.length());
        Files.copy(f.toPath(), resp.getOutputStream());
        return FILE_SENT;
    }

    // ------------------------------------------------------------------ utilidades

    static JsonObject body(HttpServletRequest req) throws IOException {
        JsonElement e = JsonParser.parseReader(req.getReader());
        if (e == null || !e.isJsonObject()) throw new ApiException(400, "Corpo da requisição inválido.");
        return e.getAsJsonObject();
    }

    static String str(JsonObject o, String key, String label, int max, boolean required) {
        JsonElement e = o.get(key);
        String v = (e == null || e.isJsonNull()) ? "" : e.getAsString().trim();
        if (required && v.isEmpty()) throw new ApiException(400, "Preencha o campo: " + label + ".");
        if (v.length() > max) throw new ApiException(400, "O campo " + label + " excede " + max + " caracteres.");
        return v;
    }

    static long longOf(JsonObject o, String key, String label) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw new ApiException(400, "Preencha o campo: " + label + ".");
        return e.getAsLong();
    }

    static LocalDate date(JsonObject o, String key, String label) {
        String v = str(o, key, label, 10, true);
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException ex) {
            throw new ApiException(400, "Data inválida no campo: " + label + ".");
        }
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    static Long optLong(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || e.getAsString().isBlank()) return null;
        return e.getAsLong();
    }

    static Long optNum(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    static LocalDate optDate(JsonObject o, String key, String label) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || e.getAsString().isBlank()) return null;
        return date(o, key, label);
    }

    static BigDecimal money(JsonObject o, String key, String label, boolean required) {
        JsonElement e = o.get(key);
        String v = (e == null || e.isJsonNull()) ? "" : e.getAsString().trim().replace(',', '.');
        if (v.isEmpty()) {
            if (required) throw new ApiException(400, "Preencha o campo: " + label + ".");
            return BigDecimal.ZERO.setScale(2);
        }
        try {
            BigDecimal d = new BigDecimal(v).setScale(2, RoundingMode.HALF_UP);
            if (d.signum() < 0 || d.compareTo(new BigDecimal("99999999999.99")) > 0) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException ex) {
            throw new ApiException(400, "Valor inválido no campo: " + label + ".");
        }
    }
}
