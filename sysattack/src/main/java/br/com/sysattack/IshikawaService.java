package br.com.sysattack;

import br.com.sysattack.ApiServlet.ApiException;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;

/**
 * Diagramas de Ishikawa (espinha de peixe) dos projetos: um problema (o "efeito") e as causas agrupadas nas seis
 * categorias clássicas (6M), com até um nível de subcausas.
 *
 *  GET|POST /api/ishikawa[?project_id=]      PUT|DELETE /api/ishikawa/{id}
 *  POST /api/ishikawa/{id}/causes            PUT|DELETE /api/ishikawa/causes/{causeId}
 */
final class IshikawaService {
    static final Set<String> CATEGORIES = Set.of("METODO", "MATERIAL", "MAO_DE_OBRA", "MAQUINA", "MEIO_AMBIENTE", "MEDICAO");
    private static final int MAX_CAUSES = 300;

    private IshikawaService() {}

    static Object handle(String m, List<String> s, HttpServletRequest req, Map<String, Object> user) throws Exception {
        if (s.size() == 1) {
            if (m.equals("GET")) return list(req.getParameter("project_id"));
            if (m.equals("POST")) return saveDiagram(null, ApiServlet.body(req), user);
        } else if (s.size() == 2) {
            long id = Long.parseLong(s.get(1));
            diagramOr404(id);
            if (m.equals("PUT")) return saveDiagram(id, ApiServlet.body(req), user);
            if (m.equals("DELETE")) { Db.update("DELETE FROM ishikawa WHERE id = ?", id); return Map.of("ok", true); }
        } else if (s.size() == 3 && s.get(2).equals("causes") && m.equals("POST")) {
            long id = Long.parseLong(s.get(1));
            diagramOr404(id);
            return addCause(id, ApiServlet.body(req));
        } else if (s.size() == 3 && s.get(1).equals("causes")) {
            long cid = Long.parseLong(s.get(2));
            Map<String, Object> c = Db.one("SELECT * FROM ishikawa_causes WHERE id = ?", cid);
            if (c == null) throw new ApiException(404, "Causa não encontrada.");
            if (m.equals("PUT")) {
                String text = ApiServlet.str(ApiServlet.body(req), "text", "Causa", 200, true);
                Db.update("UPDATE ishikawa_causes SET text = ? WHERE id = ?", text, cid);
                return Db.one("SELECT * FROM ishikawa_causes WHERE id = ?", cid);
            }
            if (m.equals("DELETE")) {
                Map<String, Object> n = Db.one("SELECT COUNT(*) n FROM ishikawa_causes WHERE parent_id = ?", cid);
                long subs = n == null ? 0 : ((Number) n.get("n")).longValue();
                if (subs > 0) throw new ApiException(409, "Esta causa tem " + subs + " subcausa(s). Exclua as subcausas antes de excluir a causa.");
                Db.update("DELETE FROM ishikawa_causes WHERE id = ?", cid);
                return Map.of("ok", true);
            }
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    private static Map<String, Object> diagramOr404(long id) throws SQLException {
        Map<String, Object> d = Db.one("SELECT * FROM ishikawa WHERE id = ?", id);
        if (d == null) throw new ApiException(404, "Diagrama não encontrado.");
        return d;
    }

    private static Object list(String projectId) throws SQLException {
        List<Map<String, Object>> diagrams = projectId == null || projectId.isBlank()
                ? Db.query("SELECT * FROM ishikawa ORDER BY id DESC")
                : Db.query("SELECT * FROM ishikawa WHERE project_id = ? ORDER BY id DESC", Long.parseLong(projectId));
        for (Map<String, Object> d : diagrams) {
            d.put("causes", Db.query("SELECT id, category, text, parent_id FROM ishikawa_causes WHERE ishikawa_id = ? ORDER BY id", d.get("id")));
        }
        return diagrams;
    }

    private static Object saveDiagram(Long id, JsonObject b, Map<String, Object> user) throws SQLException {
        String problem = ApiServlet.str(b, "problem", "Problema", 300, true);
        if (id == null) {
            long project = ApiServlet.longOf(b, "project_id", "Projeto");
            if (Db.one("SELECT id FROM projects WHERE id = ?", project) == null) throw new ApiException(400, "Projeto inexistente.");
            id = Db.insert("INSERT INTO ishikawa(project_id, problem, created_by) VALUES(?,?,?)", project, problem, String.valueOf(user.get("name")));
        } else {
            Db.update("UPDATE ishikawa SET problem = ? WHERE id = ?", problem, id);
        }
        Map<String, Object> d = Db.one("SELECT * FROM ishikawa WHERE id = ?", id);
        d.put("causes", Db.query("SELECT id, category, text, parent_id FROM ishikawa_causes WHERE ishikawa_id = ? ORDER BY id", id));
        return d;
    }

    private static Object addCause(long diagramId, JsonObject b) throws SQLException {
        String text = ApiServlet.str(b, "text", "Causa", 200, true);
        String category = ApiServlet.str(b, "category", "Categoria", 20, true);
        Long parent = null;
        if (b.has("parent_id") && !b.get("parent_id").isJsonNull()) {
            parent = b.get("parent_id").getAsLong();
            Map<String, Object> p = Db.one("SELECT * FROM ishikawa_causes WHERE id = ? AND ishikawa_id = ?", parent, diagramId);
            if (p == null) throw new ApiException(400, "Causa de origem inexistente neste diagrama.");
            if (p.get("parent_id") != null) throw new ApiException(400, "Use no máximo dois níveis: causa e subcausa.");
            category = (String) p.get("category");
        }
        if (!CATEGORIES.contains(category)) throw new ApiException(400, "Categoria inválida.");
        Map<String, Object> n = Db.one("SELECT COUNT(*) n FROM ishikawa_causes WHERE ishikawa_id = ?", diagramId);
        if (n != null && ((Number) n.get("n")).longValue() >= MAX_CAUSES) throw new ApiException(400, "Limite de " + MAX_CAUSES + " causas por diagrama.");
        long id = Db.insert("INSERT INTO ishikawa_causes(ishikawa_id, category, text, parent_id) VALUES(?,?,?,?)", diagramId, category, text, parent);
        return Db.one("SELECT id, category, text, parent_id FROM ishikawa_causes WHERE id = ?", id);
    }
}
