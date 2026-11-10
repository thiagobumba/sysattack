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
    private static final String SQL = "SELECT c.*, p.name project_name, t.name task_name, t.status task_status, t.progress task_progress, t.start_date task_start, t.end_date task_end,"
            + " t.responsible task_responsible FROM project_changes c JOIN projects p ON p.id = c.project_id LEFT JOIN tasks t ON t.id = c.task_id";

    private ChangeService() {}

    static Object handle(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        if (s.size() == 1) {
            if (m.equals("GET")) {
                String pid = req.getParameter("project_id");
                if (pid != null && !pid.isBlank()) return Db.query(SQL + " WHERE c.project_id = ? ORDER BY c.change_date DESC, c.id DESC", Long.parseLong(pid));
                return Db.query(SQL + " ORDER BY c.change_date DESC, c.id DESC");
            }
        } else if (s.size() == 2) {
            long id = Long.parseLong(s.get(1));
            if (Db.one("SELECT id FROM project_changes WHERE id = ?", id) == null) throw new ApiException(404, "Change não encontrada.");
            if (m.equals("DELETE")) { Db.update("DELETE FROM project_changes WHERE id = ?", id); return Map.of("ok", true); }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    /**
     * Uma change é uma tarefa marcada como change. Chamado ao salvar a tarefa: com is_change = true cria ou atualiza a change
     * ligada à tarefa (campos em "change"; "link_id" liga uma change antiga, sem tarefa, à tarefa nova); com is_change = false
     * remove a tarefa do fluxo de changes (a tarefa continua). Sem o campo is_change nada muda, só o título acompanha o nome da tarefa.
     */
    static void syncWithTask(long taskId, long projectId, String taskName, JsonObject b, Map<String, Object> user) throws SQLException {
        Map<String, Object> linked = Db.one("SELECT * FROM project_changes WHERE task_id = ?", taskId);
        boolean hasFlag = b.has("is_change") && !b.get("is_change").isJsonNull();
        if (!hasFlag) {
            if (linked != null) Db.update("UPDATE project_changes SET title = ? WHERE id = ?", taskName, linked.get("id"));
            return;
        }
        if (!ApiServlet.bool(b, "is_change")) {
            if (linked != null) Db.update("DELETE FROM project_changes WHERE id = ?", linked.get("id"));
            return;
        }
        JsonObject c = b.has("change") && b.get("change").isJsonObject() ? b.getAsJsonObject("change") : new JsonObject();
        String reason = c.has("reason") ? ApiServlet.str(c, "reason", "Motivo da change", 2000, false) : linked != null ? (String) linked.get("reason") : "";
        String who = c.has("requested_by") ? ApiServlet.str(c, "requested_by", "Solicitante", 120, false) : linked != null ? (String) linked.get("requested_by") : "";
        LocalDate date = c.has("change_date") && !c.get("change_date").isJsonNull() && !c.get("change_date").getAsString().isBlank()
                ? ApiServlet.date(c, "change_date", "Data da solicitação") : linked != null ? LocalDate.parse(String.valueOf(linked.get("change_date")).substring(0, 10)) : LocalDate.now();
        String status = c.has("status") ? ApiServlet.str(c, "status", "Situação da change", 12, true) : linked != null ? (String) linked.get("status") : "PENDENTE";
        if (!STATUS.contains(status)) throw new ApiException(400, "Situação da change inválida.");
        long days = c.has("impact_days") && !c.get("impact_days").isJsonNull() && !c.get("impact_days").getAsString().isBlank()
                ? c.get("impact_days").getAsLong() : linked != null ? ((Number) linked.get("impact_days")).longValue() : 0;
        if (days < 0 || days > 3650) throw new ApiException(400, "O impacto em dias deve ficar entre 0 e 3650.");
        BigDecimal cost = c.has("impact_cost") ? ApiServlet.money(c, "impact_cost", "Impacto em R$", false) : linked != null ? new BigDecimal(String.valueOf(linked.get("impact_cost"))) : BigDecimal.ZERO;
        if (linked != null) {
            Db.update("UPDATE project_changes SET title=?, reason=?, requested_by=?, change_date=?, impact_days=?, impact_cost=?, status=? WHERE id=?",
                    taskName, reason, who, date, (int) days, cost, status, linked.get("id"));
            return;
        }
        Long linkId = c.has("link_id") && !c.get("link_id").isJsonNull() ? c.get("link_id").getAsLong() : null;
        if (linkId != null) { // change antiga (sem tarefa) que passa a ter esta tarefa
            Map<String, Object> old = Db.one("SELECT project_id, task_id FROM project_changes WHERE id = ?", linkId);
            if (old == null || ((Number) old.get("project_id")).longValue() != projectId || old.get("task_id") != null) throw new ApiException(400, "Change inválida para vincular.");
            Db.update("UPDATE project_changes SET task_id=?, title=?, reason=?, requested_by=?, change_date=?, impact_days=?, impact_cost=?, status=? WHERE id=?",
                    taskId, taskName, reason, who, date, (int) days, cost, status, linkId);
            return;
        }
        Db.insert("INSERT INTO project_changes(project_id,task_id,title,reason,requested_by,change_date,impact_days,impact_cost,status,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                projectId, taskId, taskName, reason, who, date, (int) days, cost, status, user == null ? "" : user.get("name"));
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
