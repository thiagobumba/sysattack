package br.com.sysattack;

import br.com.sysattack.ApiServlet.ApiException;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;

/** API de e-mail: configuração SMTP, regras (gatilhos) por projeto e histórico de envios. Apenas administradores. */
final class EmailService {
    private static final Set<String> SECURITY = Set.of("starttls", "ssl", "none");
    private EmailService() {}

    static Object handle(String m, List<String> s, HttpServletRequest req, javax.servlet.http.HttpServletResponse resp, Map<String, Object> user) throws Exception {
        ApiServlet.requireAdmin(user);
        String what = s.size() > 1 ? s.get(1) : "";
        switch (what) {
            case "settings": return settings(m, s, req, resp, user);
            case "events": {
                List<Map<String, String>> out = new ArrayList<>();
                EmailTriggers.EVENTS.forEach((k, v) -> out.add(Map.of("key", k, "label", v)));
                return out;
            }
            case "rules": return rules(m, s, req, user);
            case "log": {
                String pid = req.getParameter("project_id");
                if (pid != null && !pid.isBlank()) {
                    return Db.query("SELECT * FROM email_log WHERE project_id = ? ORDER BY id DESC LIMIT 100", Long.parseLong(pid));
                }
                return Db.query("SELECT l.*, p.name project_name FROM email_log l LEFT JOIN projects p ON p.id = l.project_id ORDER BY l.id DESC LIMIT 100");
            }
            default: throw new ApiException(404, "Rota inexistente.");
        }
    }

    // ------------------------------------------------------------------ SMTP
    private static Object settings(String m, List<String> s, HttpServletRequest req, javax.servlet.http.HttpServletResponse resp, Map<String, Object> user) throws Exception {
        if (s.size() == 2) {
            if (m.equals("GET")) return EmailConfig.status();
            if (m.equals("DELETE")) return EmailConfig.remove();
            if (m.equals("PUT")) {
                JsonObject b = ApiServlet.body(req);
                String mode0 = ApiServlet.str(b, "auth_mode", "Autenticação", 10, false).toUpperCase();
                boolean viaGraph = mode0.equals("GRAPH"); // o Graph não usa servidor SMTP
                String host = ApiServlet.str(b, "host", "Servidor SMTP", 200, !viaGraph);
                if (!host.isEmpty() && !host.matches("[A-Za-z0-9.\\-]+")) throw new ApiException(400, "Servidor SMTP inválido.");
                long port = b.has("port") && !b.get("port").getAsString().isBlank() ? ApiServlet.longOf(b, "port", "Porta") : 587;
                if (port < 1 || port > 65535) throw new ApiException(400, "Porta inválida.");
                String security = ApiServlet.str(b, "security", "Segurança", 10, true);
                if (!SECURITY.contains(security)) throw new ApiException(400, "Segurança inválida.");
                String username = ApiServlet.str(b, "username", "Usuário", 200, false);
                String password = ApiServlet.str(b, "password", "Senha", 400, false);
                String from = ApiServlet.str(b, "from_email", "E-mail de saída", 200, false).toLowerCase();
                if (from.isEmpty()) from = username.toLowerCase();
                if (!Auth.isEmail(from)) throw new ApiException(400, "Informe um e-mail de saída válido.");
                String fromName = ApiServlet.str(b, "from_name", "Nome do remetente", 120, false);
                boolean enabled = !b.has("enabled") || ApiServlet.bool(b, "enabled");
                String authMode = ApiServlet.str(b, "auth_mode", "Autenticação", 10, false).toUpperCase();
                if (authMode.isEmpty()) authMode = "BASICO";
                if (!authMode.equals("BASICO") && !authMode.equals("OAUTH2") && !authMode.equals("GRAPH"))
                    throw new ApiException(400, "Tipo de autenticação inválido.");
                String tenantId = ApiServlet.str(b, "tenant_id", "ID do diretório (locatário)", 100, false);
                String clientId = ApiServlet.str(b, "client_id", "ID do aplicativo (cliente)", 100, false);
                String clientSecret = ApiServlet.str(b, "client_secret", "Segredo do cliente", 400, false);
                if (!authMode.equals("BASICO")) {
                    if (!tenantId.matches("[A-Za-z0-9.\\-]*")) throw new ApiException(400, "ID do diretório (locatário) inválido.");
                    if (!clientId.matches("[A-Za-z0-9\\-]*")) throw new ApiException(400, "ID do aplicativo (cliente) inválido.");
                }
                return EmailConfig.save(host, (int) port, security, username, password, from,
                        fromName.isEmpty() ? "SysAttack" : fromName.replaceAll("[\\r\\n]+", " "), enabled,
                        authMode, tenantId, clientId, clientSecret, (String) user.get("name"));
            }
        } else if (s.size() == 3 && s.get(2).equals("signature")) {
            if (m.equals("PUT")) {
                JsonObject b = ApiServlet.body(req);
                boolean on = ApiServlet.bool(b, "sign_on");
                String text = ApiServlet.str(b, "sign_text", "Texto da assinatura", 4000, false);
                long w = b.has("sign_img_w") && !b.get("sign_img_w").getAsString().isBlank() ? ApiServlet.longOf(b, "sign_img_w", "Largura") : 0;
                return EmailConfig.saveSignature(on, text, (int) w);
            }
            if (m.equals("DELETE")) return EmailConfig.removeSignatureImage();
            if (m.equals("GET")) { // prévia na tela
                byte[] img = MailSignature.image();
                if (img == null || img.length == 0) throw new ApiException(404, "Sem imagem de assinatura.");
                String name = EmailConfig.signatureName();
                resp.setContentType(ApiServlet.MIME.getOrDefault(ApiServlet.extOf(name), "image/png"));
                resp.setHeader("X-Content-Type-Options", "nosniff");
                resp.setHeader("Cache-Control", "no-store");
                resp.setContentLengthLong(img.length);
                resp.getOutputStream().write(img);
                return ApiServlet.FILE_SENT;
            }
            if (m.equals("POST")) { // bytes crus, como nos demais anexos
                String fileName = ApiServlet.header(req, "X-File-Name").replace('\\', '/');
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
                if (fileName.length() > 200) fileName = fileName.substring(fileName.length() - 200);
                String ext = ApiServlet.extOf(fileName);
                if (!Set.of("png", "jpg", "jpeg", "gif", "webp").contains(ext))
                    throw new ApiException(400, "A assinatura precisa ser uma imagem PNG, JPG, GIF ou WEBP.");
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = req.getInputStream()) {
                    byte[] chunk = new byte[16384];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        if (buf.size() + n > 2 * 1024 * 1024) throw new ApiException(413, "A imagem da assinatura passa de 2 MB.");
                        buf.write(chunk, 0, n);
                    }
                }
                if (buf.size() == 0) throw new ApiException(400, "O arquivo está vazio.");
                return EmailConfig.saveSignatureImage(fileName, buf.toByteArray());
            }
            throw new ApiException(405, "Operação não suportada.");
        } else if (s.size() == 3 && s.get(2).equals("test-logs") && m.equals("GET")) {
            return Db.query("SELECT id, created_at, created_by, recipient, ok, summary, file_name FROM email_test_log ORDER BY id DESC LIMIT 50");
        } else if (s.size() == 5 && s.get(2).equals("test-logs") && s.get(4).equals("download") && m.equals("GET")) {
            Map<String, Object> l = Db.one("SELECT file_name, log FROM email_test_log WHERE id = ?", Long.parseLong(s.get(3)));
            if (l == null) throw new ApiException(404, "Log não encontrado.");
            resp.setContentType("text/plain; charset=UTF-8");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + l.get("file_name") + "\"");
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.getWriter().write((String) l.get("log"));
            return ApiServlet.FILE_SENT;
        } else if (s.size() == 3 && s.get(2).equals("test") && m.equals("POST")) {
            String to = ApiServlet.str(ApiServlet.body(req), "to", "Enviar teste para", 200, false).toLowerCase();
            if (to.isEmpty()) to = (String) user.get("email");
            if (!Auth.isEmail(to)) throw new ApiException(400, "E-mail de destino inválido.");
            return EmailConfig.test(to, (String) user.get("name"));
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    // ------------------------------------------------------------------ regras
    private static String normalizeRecipients(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            String e = part.trim().toLowerCase();
            if (!Auth.isEmail(e)) throw new ApiException(400, "E-mail inválido nos destinatários: " + part);
            if (!out.contains(e)) out.add(e);
        }
        if (out.size() > 20) throw new ApiException(400, "No máximo 20 destinatários por regra.");
        return String.join(", ", out);
    }

    private static final String RULE_SQL = "SELECT r.*, p.name project_name,"
            + " (SELECT MAX(l.created_at) FROM email_log l WHERE l.rule_id = r.id) last_sent"
            + " FROM email_rules r LEFT JOIN projects p ON p.id = r.project_id";

    private static Object rules(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws SQLException, java.io.IOException {
        if (s.size() == 2) {
            if (m.equals("GET")) {
                String pid = req.getParameter("project_id");
                if (pid != null && !pid.isBlank()) {
                    // regras do projeto + regras globais (valem para todos os projetos)
                    return Db.query(RULE_SQL + " WHERE r.project_id = ? OR r.project_id IS NULL ORDER BY r.project_id IS NULL, r.id", Long.parseLong(pid));
                }
                return Db.query(RULE_SQL + " ORDER BY r.id");
            }
            if (m.equals("POST")) return save(null, ApiServlet.body(req), user);
        } else if (s.size() == 3) {
            long id = Long.parseLong(s.get(2));
            if (Db.one("SELECT id FROM email_rules WHERE id = ?", id) == null) throw new ApiException(404, "Regra não encontrada.");
            if (m.equals("PUT")) return save(id, ApiServlet.body(req), user);
            if (m.equals("DELETE")) { Db.update("DELETE FROM email_rules WHERE id = ?", id); return Map.of("ok", true); }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    private static Object save(Long id, JsonObject b, Map<String, Object> user) throws SQLException {
        String event = ApiServlet.str(b, "event", "Evento", 30, true);
        if (!EmailTriggers.EVENTS.containsKey(event)) throw new ApiException(400, "Evento inválido.");
        Long project = ApiServlet.optLong(b, "project_id");
        if (project != null && Db.one("SELECT id FROM projects WHERE id = ?", project) == null) throw new ApiException(400, "Projeto inexistente.");
        String recipients = normalizeRecipients(ApiServlet.str(b, "recipients", "Destinatários", 1000, false));
        boolean resp = ApiServlet.bool(b, "include_responsible");
        if (recipients.isEmpty() && !resp) throw new ApiException(400, "Informe ao menos um destinatário ou marque o responsável.");
        String subject = ApiServlet.str(b, "subject", "Assunto", 200, false);
        String message = ApiServlet.str(b, "message", "Mensagem", 1000, false);
        boolean enabled = !b.has("enabled") || ApiServlet.bool(b, "enabled");
        if (id == null) {
            id = Db.insert("INSERT INTO email_rules(project_id,event,recipients,include_responsible,subject,message,enabled,created_by) VALUES(?,?,?,?,?,?,?,?)",
                    project, event, recipients, resp, subject, message, enabled, user.get("name"));
        } else {
            Db.update("UPDATE email_rules SET project_id=?, event=?, recipients=?, include_responsible=?, subject=?, message=?, enabled=? WHERE id=?",
                    project, event, recipients, resp, subject, message, enabled, id);
        }
        return Db.one(RULE_SQL + " WHERE r.id = ?", id);
    }
}
