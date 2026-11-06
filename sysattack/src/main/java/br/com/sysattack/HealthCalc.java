package br.com.sysattack;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saúde do projeto (verde, amarelo, vermelho), calculada na hora a partir dos dados existentes.
 * A cor final é a pior entre quatro dimensões: prazo, custo, marcos e mudanças.
 * Os limites ficam aqui, como constantes, para virarem configuração mais adiante.
 */
final class HealthCalc {
    // Prazo: diferença (em pontos percentuais) entre o previsto pelo cronograma e o progresso informado.
    static final double PRAZO_AMARELO_PP = 5, PRAZO_VERMELHO_PP = 15;
    // Custo: gasto / orçamento.
    static final double CUSTO_AMARELO = 0.90, CUSTO_VERMELHO = 1.0;
    // Marcos atrasados: 1 = amarelo, 2 ou mais = vermelho.
    static final int MARCOS_VERMELHO = 2;
    // Mudanças: pendente há mais de N dias.
    static final int CHANGE_PENDENTE_DIAS = 7;

    private static final String[] ORDER = {"VERDE", "AMARELO", "VERMELHO"};

    private HealthCalc() {}

    /** Acrescenta a chave "health" a cada projeto da lista. */
    static void attach(List<Map<String, Object>> projects) throws SQLException {
        if (projects.isEmpty()) return;
        LocalDate today = LocalDate.now();
        Map<Long, List<Map<String, Object>>> tasks = group(Db.query("SELECT t.project_id, t.start_date, t.end_date, t.progress, t.status, t.is_milestone"
                + " FROM tasks t WHERE t.status <> 'CANCELADA' AND NOT EXISTS (SELECT 1 FROM tasks c WHERE c.parent_id = t.id)"));
        Map<Long, List<Map<String, Object>>> changes = group(Db.query("SELECT project_id, status, change_date, impact_days FROM project_changes"));
        Map<Long, BigDecimal> cost = new HashMap<>();
        for (Map<String, Object> r : Db.query("SELECT project_id, COALESCE(SUM(amount), 0) total FROM finance_entries GROUP BY project_id")) {
            cost.put(((Number) r.get("project_id")).longValue(), new BigDecimal(String.valueOf(r.get("total"))));
        }
        for (Map<String, Object> p : projects) {
            long id = ((Number) p.get("id")).longValue();
            BigDecimal spent = cost.getOrDefault(id, BigDecimal.ZERO);
            p.put("evm", evm(p, tasks.getOrDefault(id, List.of()), spent, today));
            p.put("health", compute(p, tasks.getOrDefault(id, List.of()), changes.getOrDefault(id, List.of()), spent, today));
        }
    }

    private static Map<Long, List<Map<String, Object>>> group(List<Map<String, Object>> rows) {
        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        for (Map<String, Object> r : rows) {
            out.computeIfAbsent(((Number) r.get("project_id")).longValue(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    static Map<String, Object> compute(Map<String, Object> p, List<Map<String, Object>> tasks, List<Map<String, Object>> changes,
                                       BigDecimal spent, LocalDate today) {
        String status = String.valueOf(p.get("status"));
        Map<String, Object> h = new LinkedHashMap<>();
        if (status.equals("CONCLUIDO")) return neutral(h, "CONCLUIDO", "Concluído");
        if (status.equals("PLANEJAMENTO")) return neutral(h, "NAO_INICIADO", "Não iniciado");
        if (status.equals("PAUSADO")) return neutral(h, "PAUSADO", "Pausado");

        List<Map<String, Object>> dims = new ArrayList<>();
        int worst = 0;

        // ---- prazo
        double pv = 0, rv = 0, w = 0;
        int lateTasks = 0, lateMilestones = 0;
        for (Map<String, Object> t : tasks) {
            LocalDate s = LocalDate.parse(String.valueOf(t.get("start_date"))), e = LocalDate.parse(String.valueOf(t.get("end_date")));
            boolean done = "CONCLUIDA".equals(t.get("status")), ms = Boolean.TRUE.equals(t.get("is_milestone"));
            if (!done && e.isBefore(today)) { if (ms) lateMilestones++; else lateTasks++; }
            if (ms) continue;
            double dur = Math.max(1, ChronoUnit.DAYS.between(s, e) + 1);
            double prev = Math.min(1, Math.max(0, (ChronoUnit.DAYS.between(s, today) + 1) / dur)) * 100;
            w += dur; pv += prev * dur; rv += ((Number) t.get("progress")).doubleValue() * dur;
        }
        String lvl; String detail;
        LocalDate end = LocalDate.parse(String.valueOf(p.get("end_date")));
        if (w == 0) { lvl = "VERDE"; detail = "Sem tarefas cadastradas."; }
        else {
            double gap = pv / w - rv / w;
            lvl = gap > PRAZO_VERMELHO_PP ? "VERMELHO" : (gap > PRAZO_AMARELO_PP || lateTasks > 0) ? "AMARELO" : "VERDE";
            detail = gap > 0.5 ? Math.round(gap) + " p.p. atrás do previsto" : "No ritmo previsto";
            if (lateTasks > 0) detail += "; " + lateTasks + " tarefa(s) atrasada(s)";
            h.put("planned", Math.round(pv / w)); h.put("actual", Math.round(rv / w));
        }
        if (end.isBefore(today)) { lvl = "VERMELHO"; detail = "Prazo final vencido (" + end + ")" + (w > 0 ? "; " + detail : ""); }
        worst = Math.max(worst, add(dims, "prazo", "Prazo", lvl, detail));

        // ---- custo
        BigDecimal budget = p.get("budget") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(p.get("budget")));
        if (budget.signum() > 0) {
            double ratio = spent.doubleValue() / budget.doubleValue();
            lvl = ratio > CUSTO_VERMELHO ? "VERMELHO" : ratio > CUSTO_AMARELO ? "AMARELO" : "VERDE";
            detail = Math.round(ratio * 100) + "% do orçamento gasto";
            h.put("budget_used", Math.round(ratio * 100));
            Map<String, Object> ev = evm(p, tasks, spent, today);
            if (lvl.equals("VERDE") && Boolean.TRUE.equals(ev.get("projects_overrun"))) {
                lvl = "AMARELO";
                detail += "; projeção de término acima do orçamento (EAC " + Math.round(((Number) ev.get("eac")).doubleValue() / budget.doubleValue() * 100) + "% do orçamento)";
            }
            worst = Math.max(worst, add(dims, "custo", "Custo", lvl, detail));
        } else {
            add(dims, "custo", "Custo", "NA", "Sem orçamento definido");
        }

        // ---- marcos
        lvl = lateMilestones >= MARCOS_VERMELHO ? "VERMELHO" : lateMilestones == 1 ? "AMARELO" : "VERDE";
        worst = Math.max(worst, add(dims, "marcos", "Marcos", lvl,
                lateMilestones == 0 ? "Nenhum marco atrasado" : lateMilestones + " marco(s) atrasado(s)"));

        // ---- mudanças
        int pendingOld = 0, pendingOldWithDays = 0, approvedDays = 0;
        for (Map<String, Object> c : changes) {
            long days = c.get("impact_days") == null ? 0 : ((Number) c.get("impact_days")).longValue();
            if ("PENDENTE".equals(c.get("status"))) {
                long age = ChronoUnit.DAYS.between(LocalDate.parse(String.valueOf(c.get("change_date"))), today);
                if (age > CHANGE_PENDENTE_DIAS) { pendingOld++; if (days > 0) pendingOldWithDays++; }
            } else if ("APROVADA".equals(c.get("status")) && days > 0) approvedDays += days;
        }
        lvl = pendingOldWithDays > 0 ? "VERMELHO" : (pendingOld > 0 || approvedDays > 0) ? "AMARELO" : "VERDE";
        detail = pendingOld > 0 ? pendingOld + " change(s) pendente(s) há mais de " + CHANGE_PENDENTE_DIAS + " dias"
                : approvedDays > 0 ? approvedDays + " dia(s) de atraso por changes aprovadas" : "Sem changes pendentes";
        worst = Math.max(worst, add(dims, "mudancas", "Mudanças", lvl, detail));

        h.put("level", ORDER[worst]);
        h.put("label", label(ORDER[worst]));
        h.put("dims", dims);
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> d : dims) if (rank((String) d.get("level")) == worst && worst > 0) reasons.add(d.get("label") + ": " + d.get("detail"));
        h.put("reasons", reasons);
        return h;
    }

    /** Progresso mínimo (em %) para a projeção de custo ser considerada confiável. */
    static final double EVM_MIN_PROGRESS = 10;

    /**
     * Indicadores de valor agregado (EVM). BAC = orçamento; PV = BAC x previsto%; EV = BAC x realizado%; AC = custo lançado.
     * SPI = EV/PV; CPI = EV/AC; EAC = BAC/CPI; VAC = BAC - EAC; ETC = EAC - AC.
     * Previsto% e realizado% usam a mesma regra dos relatórios (média ponderada pela duração das tarefas, sem marcos).
     */
    static Map<String, Object> evm(Map<String, Object> p, List<Map<String, Object>> tasks, BigDecimal spent, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        BigDecimal budget = p.get("budget") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(p.get("budget")));
        m.put("available", false);
        if (budget.signum() <= 0) { m.put("reason", "NO_BUDGET"); return m; }
        double pv = 0, rv = 0, w = 0;
        for (Map<String, Object> t : tasks) {
            if (Boolean.TRUE.equals(t.get("is_milestone"))) continue;
            LocalDate s = LocalDate.parse(String.valueOf(t.get("start_date"))), e = LocalDate.parse(String.valueOf(t.get("end_date")));
            double dur = Math.max(1, ChronoUnit.DAYS.between(s, e) + 1);
            double prev = Math.min(1, Math.max(0, (ChronoUnit.DAYS.between(s, today) + 1) / dur)) * 100;
            w += dur; pv += prev * dur; rv += ((Number) t.get("progress")).doubleValue() * dur;
        }
        if (w == 0) { m.put("reason", "NO_TASKS"); return m; }
        double planned = pv / w, actual = rv / w, bac = budget.doubleValue(), ac = spent.doubleValue();
        double pvMoney = bac * planned / 100, evMoney = bac * actual / 100;
        m.put("available", true);
        m.put("bac", round2(bac)); m.put("pv", round2(pvMoney)); m.put("ev", round2(evMoney)); m.put("ac", round2(ac));
        m.put("planned_pct", Math.round(planned)); m.put("actual_pct", Math.round(actual));
        if (pvMoney > 0) m.put("spi", round2(evMoney / pvMoney));
        boolean reliable = actual >= EVM_MIN_PROGRESS;
        m.put("reliable", reliable);
        if (ac > 0 && evMoney > 0) {
            double cpi = evMoney / ac, eac = bac / cpi;
            m.put("cpi", round2(cpi)); m.put("eac", round2(eac)); m.put("vac", round2(bac - eac)); m.put("etc", round2(eac - ac));
            m.put("projects_overrun", reliable && eac > bac);
        } else if (ac <= 0) {
            m.put("eac", round2(bac)); m.put("vac", 0.0); m.put("etc", round2(bac)); m.put("projects_overrun", false);
        } else { // custo lançado sem progresso: não dá para projetar
            m.put("cpi", 0.0); m.put("projects_overrun", reliable);
        }
        return m;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static Map<String, Object> neutral(Map<String, Object> h, String level, String label) {
        h.put("level", level); h.put("label", label); h.put("dims", List.of()); h.put("reasons", List.of());
        return h;
    }

    private static int add(List<Map<String, Object>> dims, String key, String label, String level, String detail) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("key", key); d.put("label", label); d.put("level", level); d.put("detail", detail);
        dims.add(d);
        return rank(level);
    }

    private static int rank(String level) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(level)) return i;
        return 0;
    }

    private static String label(String level) {
        return level.equals("VERDE") ? "Saudável" : level.equals("AMARELO") ? "Atenção" : "Crítico";
    }
}
