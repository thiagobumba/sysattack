package br.com.sysattack;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cálculo determinístico dos números de um projeto spot (horas fixadas, consumidas, reservadas e saldo).
 * Esses valores NUNCA vêm da IA: o Claude apenas redige o texto em cima deste "retrato" (snapshot).
 *
 * Regras:
 *  - Com data final cadastrada, o período é SEMPRE a vigência do contrato (início até a data final, limitado à data de referência).
 *  - Sem data final: MENSAL = do dia 1º do mês de referência até a data de referência; PACOTE = desde o início do projeto.
 *  - Consumido = soma dos apontamentos do período.
 *  - Reservado = para cada demanda com horas aprovadas e ainda não concluída/cancelada: max(0, aprovado - realizado no período).
 *  - Saldo = fixadas - consumido. Saldo projetado = fixadas - consumido - reservado.
 */
final class SpotCalc {
    private SpotCalc() {}

    static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    /** Vigência do contrato: início e data final (null quando não há). */
    static LocalDate vigStart(Map<String, Object> p) {
        return LocalDate.parse((String) p.get("start_date"));
    }

    static LocalDate vigEnd(Map<String, Object> p) {
        Object e = p.get("end_date");
        return e == null ? null : LocalDate.parse(String.valueOf(e).substring(0, 10));
    }

    /** Mantém a data de referência dentro da vigência (depois do fim, vale o último dia). */
    static LocalDate clampRef(Map<String, Object> p, LocalDate ref) {
        LocalDate end = vigEnd(p);
        if (end != null && ref.isAfter(end)) ref = end;
        if (ref.isBefore(vigStart(p))) ref = vigStart(p);
        return ref;
    }

    private static String br(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    static Map<String, Object> snapshot(Map<String, Object> p, LocalDate ref) throws SQLException {
        long id = ((Number) p.get("id")).longValue();
        boolean monthly = "MENSAL".equals(p.get("hours_model"));
        LocalDate vEnd = vigEnd(p);
        boolean byVig = vEnd != null;
        if (byVig) ref = clampRef(p, ref);
        LocalDate start = byVig ? vigStart(p) : monthly ? ref.withDayOfMonth(1) : vigStart(p);
        if (start.isAfter(ref)) start = ref;
        double contracted = num(p.get("contracted_hours"));

        List<Map<String, Object>> hrs = Db.query(
                "SELECT h.front, h.activity, h.demand_id, h.hours, d.name demand_name FROM spot_hours h"
              + " LEFT JOIN spot_demands d ON d.id = h.demand_id"
              + " WHERE h.spot_id = ? AND h.work_date >= ? AND h.work_date <= ? ORDER BY h.work_date, h.id", id, start, ref);

        double consumed = 0;
        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        Map<Long, Double> realized = new HashMap<>();
        Map<Long, List<Map<String, Object>>> entries = new HashMap<>();
        for (Map<String, Object> h : hrs) {
            double v = num(h.get("hours"));
            consumed += v;
            Long did = h.get("demand_id") == null ? null : ((Number) h.get("demand_id")).longValue();
            String activity = (String) h.get("activity");
            String key = did != null ? "d" + did : "a" + activity.trim().toLowerCase(Locale.ROOT);
            Map<String, Object> it = items.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", did != null ? h.get("demand_name") : activity);
                m.put("front", h.get("front"));
                m.put("hours", 0.0);
                return m;
            });
            it.put("hours", num(it.get("hours")) + v);
            if (did != null) {
                realized.merge(did, v, Double::sum);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("activity", activity);
                e.put("hours", r2(v));
                entries.computeIfAbsent(did, k -> new ArrayList<>()).add(e);
            }
        }
        List<Map<String, Object>> byItem = new ArrayList<>(items.values());
        byItem.sort((a, b) -> Double.compare(num(b.get("hours")), num(a.get("hours"))));
        for (Map<String, Object> it : byItem) it.put("hours", r2(num(it.get("hours"))));

        List<Map<String, Object>> demandRows = Db.query(
                "SELECT id, name, status, next_step, approved_hours, notes, updated_at FROM spot_demands WHERE spot_id = ? ORDER BY id", id);
        List<Map<String, Object>> demands = new ArrayList<>(), approved = new ArrayList<>();
        double reserved = 0;
        for (Map<String, Object> d : demandRows) {
            long did = ((Number) d.get("id")).longValue();
            String st = (String) d.get("status");
            boolean closed = st.equals("CONCLUIDA") || st.equals("CANCELADA");
            double appr = num(d.get("approved_hours")), real = realized.getOrDefault(did, 0.0);
            boolean recent = LocalDate.parse(((String) d.get("updated_at")).substring(0, 10)).isAfter(ref.minusDays(15));
            if (!st.equals("CANCELADA") && (!closed || recent || real > 0)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", did);
                m.put("name", d.get("name"));
                m.put("status", st);
                m.put("next_step", d.get("next_step"));
                m.put("notes", d.get("notes"));
                m.put("approved_hours", r2(appr));
                demands.add(m);
            }
            if (appr > 0 && !st.equals("CANCELADA") && (!st.equals("CONCLUIDA") || real > 0)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("demand_id", did);
                m.put("name", d.get("name"));
                m.put("status", st);
                m.put("approved", r2(appr));
                m.put("realized", r2(real));
                m.put("diff", r2(real - appr));
                m.put("entries", entries.getOrDefault(did, new ArrayList<>()));
                approved.add(m);
            }
            if (appr > 0 && !closed) reserved += Math.max(0, appr - real);
        }

        List<Map<String, Object>> actions = new ArrayList<>(), pendencies = new ArrayList<>();
        for (Map<String, Object> a : Db.query(
                "SELECT kind, description, responsible, status, note, updated_at FROM spot_actions WHERE spot_id = ? ORDER BY id", id)) {
            boolean done = "CONCLUIDO".equals(a.get("status"));
            boolean recent = LocalDate.parse(((String) a.get("updated_at")).substring(0, 10)).isAfter(ref.minusDays(15));
            if (done && !recent) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", a.get("description"));
            m.put("responsible", a.get("responsible"));
            m.put("status", a.get("status"));
            m.put("note", a.get("note"));
            ("PENDENCIA".equals(a.get("kind")) ? pendencies : actions).add(m);
        }

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("contracted", r2(contracted));
        kpi.put("consumed", r2(consumed));
        kpi.put("reserved", r2(reserved));
        kpi.put("balance", r2(contracted - consumed));
        kpi.put("projected", r2(contracted - consumed - reserved));

        Map<String, Object> proj = new LinkedHashMap<>();
        for (String k : new String[] {"name", "client", "provider", "description", "hours_model", "participants", "billing_note"}) proj.put(k, p.get(k));
        proj.put("contracted_hours", r2(contracted));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", proj);
        out.put("ref_date", ref.toString());
        out.put("period_start", start.toString());
        out.put("period_label", byVig ? br(vigStart(p)) + " a " + br(vEnd) : monthly
                ? ref.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR")) + "/" + ref.getYear()
                : "desde " + start.getDayOfMonth() + "/" + start.getMonthValue() + "/" + start.getYear());
        out.put("kpi", kpi);
        out.put("by_item", byItem);
        out.put("approved", approved);
        out.put("demands", demands);
        out.put("actions", actions);
        out.put("pendencies", pendencies);
        return out;
    }
}
