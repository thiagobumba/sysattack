package br.com.sysattack;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Regras de negócio das tarefas pai/filha. */
public final class TaskRules {
    private TaskRules() {}

    /**
     * Recalcula início, fim, progresso e situação de uma tarefa pai a partir das filhas:
     * início = menor início, fim = maior fim, progresso = média ponderada pela duração.
     */
    public static void rollup(Long parentId) throws SQLException {
        if (parentId == null) return;
        List<Map<String, Object>> kids = Db.query(
                "SELECT start_date, end_date, progress, status FROM tasks WHERE parent_id = ?", parentId);
        if (kids.isEmpty()) return;
        LocalDate min = null, max = null;
        long weight = 0, sum = 0;
        boolean allDone = true, started = false;
        for (Map<String, Object> k : kids) {
            LocalDate s = LocalDate.parse((String) k.get("start_date"));
            LocalDate e = LocalDate.parse((String) k.get("end_date"));
            int p = ((Number) k.get("progress")).intValue();
            String st = (String) k.get("status");
            if (min == null || s.isBefore(min)) min = s;
            if (max == null || e.isAfter(max)) max = e;
            long dur = ChronoUnit.DAYS.between(s, e) + 1;
            weight += dur;
            sum += dur * p;
            if (!"CONCLUIDA".equals(st)) allDone = false;
            if (p > 0 || "EM_ANDAMENTO".equals(st) || "CONCLUIDA".equals(st)) started = true;
        }
        int progress = (int) Math.round((double) sum / Math.max(1, weight));
        String status = allDone ? "CONCLUIDA" : started ? "EM_ANDAMENTO" : "A_FAZER";
        if (allDone) progress = 100;
        Db.update("UPDATE tasks SET start_date = ?, end_date = ?, progress = ?, status = ? WHERE id = ?",
                min, max, progress, status, parentId);
    }
}
