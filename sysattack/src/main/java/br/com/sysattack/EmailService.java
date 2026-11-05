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

    static Object handle(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        ApiServlet.requireAdmin(user);
        String what = s.size() > 1 ? s.get(1) : "";
        switch (what) {
            case "settings": return settings(m, s, req, user);
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
    private static Object settings(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        if (s.size() == 2) {
            if (m.equals("GET")) return EmailConfig.status();
            if (m.equals("DELETE")) return EmailConfig.remove();
            if (m.equals("PUT")) {
                JsonObject b = ApiServlet.body(req);
                String host = ApiServlet.str(b, "host", "Servidor SMTP", 200, true);
                if (!host.matches("[A-Za-z0-9.\\-]+")) throw new ApiException(400, "Servidor SMTP inválido.");
                long port = ApiServlet.longOf(b, "port", "Porta");
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
                return EmailConfig.save(host, (int) port, security, username, password, from,
                        fromName.isEmpty() ? "SysAttack" : fromName.replaceAll("[\\r\\n]+", " "), enabled, (String) user.get("name"));
            }
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
