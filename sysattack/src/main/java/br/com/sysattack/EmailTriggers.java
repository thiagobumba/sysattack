package br.com.sysattack;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Gatilhos de e-mail dos projetos: regras (evento → destinatários), envio em segundo plano e histórico. */
final class EmailTriggers {
    private static final Logger LOG = Logger.getLogger("sysattack");
    /** Quem fez a alteração (preenchido pela API em cada requisição). */
    static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    static final Map<String, String> EVENTS = new LinkedHashMap<>();
    static {
        EVENTS.put("PROJECT_CREATED", "Projeto criado");
        EVENTS.put("PROJECT_STATUS_CHANGED", "Situação do projeto alterada");
        EVENTS.put("TASK_CREATED", "Tarefa criada");
        EVENTS.put("TASK_COMPLETED", "Tarefa concluída");
        EVENTS.put("MILESTONE_COMPLETED", "Marco concluído");
        EVENTS.put("TASK_LATE", "Tarefa atrasada (verificação diária)");
        EVENTS.put("FINANCE_ENTRY", "Lançamento financeiro criado");
        EVENTS.put("DOCUMENT_ADDED", "Documento adicionado");
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("PLANEJAMENTO", "Planejamento"), Map.entry("EM_ANDAMENTO", "Em andamento"), Map.entry("CONCLUIDO", "Concluído"),
            Map.entry("PAUSADO", "Pausado"), Map.entry("PENDENTE", "Pendente"), Map.entry("PAGO", "Pago"),
            Map.entry("ESPECIFICACAO", "Especificação"), Map.entry("CONTRATO", "Contrato"), Map.entry("PROPOSTA", "Proposta"),
            Map.entry("DESENHO", "Desenho / projeto"), Map.entry("NOTA_FISCAL", "Nota fiscal"), Map.entry("OUTRO", "Outro"));

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sysattack-email");
        t.setDaemon(true);
        return t;
    });

    private EmailTriggers() {}

    static String h(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }

    /** Dispara o evento para as regras ativas do projeto (ou de todos). Nunca lança: falha de e-mail não pode quebrar a operação. */
    static void fire(String event, long projectId, String responsible, String refKey, Map<String, String> info) {
        try {
            Map<String, Object> p = Db.one("SELECT name FROM projects WHERE id = ?", projectId);
            if (p == null) return;
            String projectName = (String) p.get("name");
            List<Map<String, Object>> rules = Db.query("SELECT * FROM email_rules WHERE enabled = TRUE AND event = ?"
                    + " AND (project_id IS NULL OR project_id = ?)", event, projectId);
            if (rules.isEmpty()) return;
            Map<String, String> data = new LinkedHashMap<>();
            data.put("Projeto", projectName);
            data.putAll(info);
            String actor = ACTOR.get();
            if (actor != null && !actor.isBlank() && !event.equals("TASK_LATE")) data.put("Alterado por", actor);
            for (Map<String, Object> rule : rules) {
                Set<String> to = new LinkedHashSet<>();
                for (String x : ((String) rule.get("recipients")).split("[,;\\s]+")) if (!x.isBlank()) to.add(x.trim().toLowerCase());
                if (Boolean.TRUE.equals(rule.get("include_responsible")) && responsible != null && !responsible.isBlank()) {
                    for (Map<String, Object> u : Db.query("SELECT username FROM users WHERE active = TRUE AND LOWER(name) = LOWER(?)", responsible.trim())) {
                        to.add(((String) u.get("username")).toLowerCase());
                    }
                }
                if (to.isEmpty()) continue;
                long ruleId = ((Number) rule.get("id")).longValue();
                if (event.equals("TASK_LATE") && Db.one("SELECT id FROM email_log WHERE rule_id = ? AND ref_key = ? AND status <> 'FALHA'", ruleId, refKey) != null) continue;
                String subject = subjectOf((String) rule.get("subject"), event, data);
                String html = body(EVENTS.get(event), data, (String) rule.get("message"));
                Long pid = projectId;
                POOL.submit(() -> deliver(ruleId, pid, event, refKey, new ArrayList<>(to), subject, html));
            }
        } catch (Exception e) {
            LOG.warning("Gatilho de e-mail " + event + " não executado: " + e);
        }
    }

    private static String subjectOf(String template, String event, Map<String, String> data) {
        String s = template == null || template.isBlank() ? "[SysAttack] {evento} · {projeto}" : template;
        s = s.replace("{evento}", EVENTS.get(event)).replace("{projeto}", data.getOrDefault("Projeto", ""))
             .replace("{item}", clean(data.getOrDefault("Tarefa", data.getOrDefault("Lançamento", data.getOrDefault("Documento", data.getOrDefault("Projeto", ""))))));
        s = clean(s);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String body(String title, Map<String, String> data, String message) {
        StringBuilder b = new StringBuilder();
        b.append("<div style=\"font-family:Arial,Helvetica,sans-serif;color:#222;max-width:620px\">")
         .append("<div style=\"border-left:6px solid #f26a21;padding:6px 14px;margin-bottom:14px\"><div style=\"font-size:20px;color:#f26a21\">")
         .append(h(title)).append("</div><div style=\"font-size:12px;color:#777\">SysAttack · ").append(LocalDate.now()).append("</div></div>");
        if (message != null && !message.isBlank()) b.append("<p style=\"font-size:14px\">").append(h(message)).append("</p>");
        b.append("<table style=\"border-collapse:collapse;font-size:14px;width:100%\">");
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            b.append("<tr><td style=\"padding:6px 10px;border-bottom:1px solid #eee;color:#666;width:34%\">").append(h(e.getKey()))
             .append("</td><td style=\"padding:6px 10px;border-bottom:1px solid #eee\"><b>").append(h(LABELS.getOrDefault(e.getValue(), e.getValue()))).append("</b></td></tr>");
        }
        b.append("</table><p style=\"font-size:12px;color:#888;margin-top:18px\">E-mail automático do SysAttack, enviado por uma regra configurada no projeto.</p></div>");
        return b.toString();
    }

    private static void deliver(long ruleId, long projectId, String event, String refKey, List<String> to, String subject, String html) {
        String status = "ENVIADO", error = "";
        try {
            if (!Mailer.sendHtml(to, subject, html, null)) status = "TESTE";
        } catch (Mailer.NotConfigured e) {
            status = "FALHA"; error = "SMTP não configurado.";
        } catch (Exception e) {
            status = "FALHA";
            error = clean(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (error.length() > 280) error = error.substring(0, 279) + "…";
            LOG.warning("Falha ao enviar e-mail do gatilho " + event + ": " + e);
        }
        try {
            Db.update("INSERT INTO email_log(rule_id,project_id,event,ref_key,recipients,subject,status,error) VALUES(?,?,?,?,?,?,?,?)",
                    ruleId, projectId, event, refKey, String.join(", ", to), subject, status, error);
        } catch (SQLException e) {
            LOG.warning("Não foi possível registrar o histórico de e-mail: " + e);
        }
    }

    /** Verificação diária: tarefas e marcos vencidos e ainda abertos (uma vez por regra e tarefa). */
    static void scanLate() {
        try {
            Db.update("DELETE FROM email_log WHERE created_at < DATEADD('DAY', -90, CURRENT_TIMESTAMP)");
            if (Db.one("SELECT id FROM email_rules WHERE enabled = TRUE AND event = 'TASK_LATE'") == null) return;
            List<Map<String, Object>> late = Db.query("SELECT t.id, t.project_id, t.name, t.responsible, t.end_date, t.is_milestone FROM tasks t"
                    + " WHERE t.status <> 'CONCLUIDA' AND t.end_date < CURRENT_DATE"
                    + " AND NOT EXISTS (SELECT 1 FROM tasks c WHERE c.parent_id = t.id)");
            for (Map<String, Object> t : late) {
                Map<String, String> info = new LinkedHashMap<>();
                info.put("Tarefa", (String) t.get("name"));
                if (Boolean.TRUE.equals(t.get("is_milestone"))) info.put("Tipo", "Marco");
                info.put("Responsável", (String) t.get("responsible"));
                info.put("Prazo", String.valueOf(t.get("end_date")));
                fire("TASK_LATE", ((Number) t.get("project_id")).longValue(), (String) t.get("responsible"), "task:" + t.get("id"), info);
            }
        } catch (Exception e) {
            LOG.warning("Verificação de tarefas atrasadas falhou: " + e);
        }
    }
}
