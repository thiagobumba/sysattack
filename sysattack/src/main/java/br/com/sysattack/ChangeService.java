package br.com.sysattack;

import br.com.sysattack.ApiServlet.ApiException;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;

/** Changes (solicitações de mudança) dos projetos e os dados agregados dos relatórios. */
final class ChangeService {
    private static final Set<String> STATUS = Set.of("PENDENTE", "APROVADA", "REJEITADA");
    private static final String SQL = "SELECT c.*, p.name project_name FROM project_changes c JOIN projects p ON p.id = c.project_id";

    private ChangeService() {}

    static Object handle(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        if (s.size() == 1) {
            if (m.equals("GET")) {
                String pid = req.getParameter("project_id");
                if (pid != null && !pid.isBlank()) return Db.query(SQL + " WHERE c.project_id = ? ORDER BY c.change_date DESC, c.id DESC", Long.parseLong(pid));
                return Db.query(SQL + " ORDER BY c.change_date DESC, c.id DESC");
            }
            if (m.equals("POST")) return save(null, ApiServlet.body(req), user);
        } else if (s.size() == 2) {
            long id = Long.parseLong(s.get(1));
            if (Db.one("SELECT id FROM project_changes WHERE id = ?", id) == null) throw new ApiException(404, "Change não encontrada.");
            if (m.equals("PUT")) return save(id, ApiServlet.body(req), user);
            if (m.equals("DELETE")) { Db.update("DELETE FROM project_changes WHERE id = ?", id); return Map.of("ok", true); }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    private static Object save(Long id, JsonObject b, Map<String, Object> user) throws SQLException {
        String title = ApiServlet.str(b, "title", "Título", 200, true);
        String reason = ApiServlet.str(b, "reason", "Motivo", 2000, false);
        String who = ApiServlet.str(b, "requested_by", "Solicitante", 120, false);
        LocalDate date = ApiServlet.date(b, "change_date", "Data");
        String status = ApiServlet.str(b, "status", "Situação", 12, true);
        if (!STATUS.contains(status)) throw new ApiException(400, "Situação inválida.");
        long days = b.has("impact_days") && !b.get("impact_days").isJsonNull() && !b.get("impact_days").getAsString().isBlank()
                ? b.get("impact_days").getAsLong() : 0;
        if (days < 0 || days > 3650) throw new ApiException(400, "O impacto em dias deve ficar entre 0 e 3650.");
        BigDecimal cost = ApiServlet.money(b, "impact_cost", "Impacto em R$", false);
        if (id == null) {
            long project = ApiServlet.longOf(b, "project_id", "Projeto");
            if (Db.one("SELECT id FROM projects WHERE id = ?", project) == null) throw new ApiException(400, "Projeto inexistente.");
            id = Db.insert("INSERT INTO project_changes(project_id,title,reason,requested_by,change_date,impact_days,impact_cost,status,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                    project, title, reason, who, date, (int) days, cost, status, user.get("name"));
        } else {
            Db.update("UPDATE project_changes SET title=?, reason=?, requested_by=?, change_date=?, impact_days=?, impact_cost=?, status=? WHERE id=?",
                    title, reason, who, date, (int) days, cost, status, id);
        }
        return Db.one(SQL + " WHERE c.id = ?", id);
    }

    /** Dados brutos e compactos para os relatórios (os cálculos de exibição são feitos na tela). */
    static Object reports(String m) throws SQLException {
        if (!m.equals("GET")) throw new ApiException(405, "Operação não suportada.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", LocalDate.now().toString());
        List<Map<String, Object>> projects = Db.query("SELECT id, name, status, owner, start_date, end_date, budget FROM projects ORDER BY start_date, id");
        HealthCalc.attach(projects); // acrescenta "health" e "evm" (indicadores de custo e prazo)
        out.put("projects", projects);
        out.put("tasks", Db.query("SELECT t.id, t.project_id, t.name, t.parent_id, t.is_milestone, t.responsible, t.start_date, t.end_date, t.progress, t.status,"
                + " (SELECT COUNT(*) FROM tasks c WHERE c.parent_id = t.id) child_count FROM tasks t ORDER BY t.start_date, t.id"));
        out.put("finance", Db.query("SELECT project_id, kind, status, COALESCE(SUM(amount), 0) total, COUNT(*) qty FROM finance_entries GROUP BY project_id, kind, status"));
        out.put("changes", Db.query(SQL + " ORDER BY c.change_date, c.id"));
        return out;
    }
}
