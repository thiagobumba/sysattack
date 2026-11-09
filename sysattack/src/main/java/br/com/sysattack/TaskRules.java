package br.com.sysattack;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Regras de negócio das tarefas pai/filha. */
public final class TaskRules {
    private TaskRules() {}

    /**
     * Recalcula progresso e situação de uma tarefa pai a partir das filhas (média simples: cada filha ativa pesa igual,
     * para uma filha de prazo longo não esmagar as várias já concluídas).
     * As datas do pai são do próprio pai: mexer nas filhas não as altera.
     */
    public static void rollup(Long parentId) throws SQLException { rollup(parentId, false); }

    /**
     * Igual a {@link #rollup(Long)}; com {@code withDates} também ajusta o pai ao período das filhas
     * (início = menor início, fim = maior fim). Só para quando o pai acaba de ser criado (importação, dados de exemplo).
     */
    public static void rollup(Long parentId, boolean withDates) throws SQLException {
        if (parentId == null) return;
        List<Map<String, Object>> kids = Db.query(
                "SELECT start_date, end_date, progress, status FROM tasks WHERE parent_id = ?", parentId);
        if (kids.isEmpty()) return;
        LocalDate min = null, max = null;
        long weight = 0, sum = 0;
        boolean allDone = true, started = false;
        boolean anyActive = false;
        for (Map<String, Object> k : kids) {
            LocalDate s = LocalDate.parse((String) k.get("start_date"));
            LocalDate e = LocalDate.parse((String) k.get("end_date"));
            int p = ((Number) k.get("progress")).intValue();
            String st = (String) k.get("status");
            if ("CANCELADA".equals(st)) continue; // filha cancelada não entra no cálculo do pai
            anyActive = true;
            if (min == null || s.isBefore(min)) min = s;
            if (max == null || e.isAfter(max)) max = e;
            weight++;
            sum += p;
            if (!"CONCLUIDA".equals(st)) allDone = false;
            if (p > 0 || "EM_ANDAMENTO".equals(st) || "CONCLUIDA".equals(st)) started = true;
        }
        if (!anyActive) { // todas as filhas canceladas: o pai também é considerado cancelado
            Db.update("UPDATE tasks SET status = 'CANCELADA' WHERE id = ?", parentId);
            return;
        }
        int progress = (int) Math.round((double) sum / Math.max(1, weight));
        String status = allDone ? "CONCLUIDA" : started ? "EM_ANDAMENTO" : "A_FAZER";
        if (allDone) progress = 100;
        if (withDates) {
            Db.update("UPDATE tasks SET start_date = ?, end_date = ?, progress = ?, status = ? WHERE id = ?",
                    min, max, progress, status, parentId);
        } else {
            Db.update("UPDATE tasks SET progress = ?, status = ? WHERE id = ?", progress, status, parentId);
        }
    }
}
