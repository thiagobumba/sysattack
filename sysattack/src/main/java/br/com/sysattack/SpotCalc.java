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
        LocalDate asked = ref; // data pedida, antes de ajustar à vigência (serve para saber se o contrato já começou)
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

        // faturamento do período: franquia (horas fixadas x valor da hora) + excedente (horas acima da franquia x valor da hora excedente)
        double rate = num(p.get("partner_hour_rate")), overRate = num(p.get("partner_overage_rate"));
        double overHours = Math.max(0, consumed - contracted), overHoursProj = Math.max(0, consumed + reserved - contracted);
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("configured", rate > 0 || overRate > 0);
        billing.put("rate", r2(rate));
        billing.put("overage_rate", r2(overRate));
        billing.put("base_value", r2(contracted * rate));
        billing.put("overage_hours", r2(overHours));
        billing.put("overage_value", r2(overHours * overRate));
        billing.put("total", r2(contracted * rate + overHours * overRate));
        billing.put("projected_overage_hours", r2(overHoursProj));
        billing.put("projected_overage_value", r2(overHoursProj * overRate));
        billing.put("projected_total", r2(contracted * rate + overHoursProj * overRate));

        Map<String, Object> proj = new LinkedHashMap<>();
        for (String k : new String[] {"name", "client", "provider", "description", "hours_model", "participants", "billing_note"}) proj.put(k, p.get(k));
        proj.put("contracted_hours", r2(contracted));
        proj.put("partner_hour_rate", r2(rate));
        proj.put("partner_overage_rate", r2(overRate));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", proj);
        out.put("ref_date", ref.toString());
        out.put("period_start", start.toString());
        out.put("period_label", byVig ? br(vigStart(p)) + " a " + br(vEnd) : monthly
                ? ref.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR")) + "/" + ref.getYear()
                : "desde " + start.getDayOfMonth() + "/" + start.getMonthValue() + "/" + start.getYear());
        out.put("kpi", kpi);
        out.put("health", health(p, id, asked, ref, start, byVig ? vEnd : monthly ? ref.withDayOfMonth(ref.lengthOfMonth()) : null, contracted, consumed, reserved));
        out.put("billing", billing);
        out.put("by_item", byItem);
        out.put("approved", approved);
        out.put("demands", demands);
        out.put("actions", actions);
        out.put("pendencies", pendencies);
        return out;
    }

    // ------------------------------------------------------------------ semáforo de horas (ritmo semanal x franquia)

    /** Dias úteis (segunda a sexta, sem feriados) entre as datas, contando as duas pontas. */
    static int businessDays(LocalDate a, LocalDate b) {
        if (b.isBefore(a)) return 0;
        int n = 0;
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
            java.time.DayOfWeek w = d.getDayOfWeek();
            if (w != java.time.DayOfWeek.SATURDAY && w != java.time.DayOfWeek.SUNDAY) n++;
        }
        return n;
    }

    private static LocalDate addBusinessDays(LocalDate from, int n) {
        LocalDate d = from;
        while (n > 0) {
            d = d.plusDays(1);
            java.time.DayOfWeek w = d.getDayOfWeek();
            if (w != java.time.DayOfWeek.SATURDAY && w != java.time.DayOfWeek.SUNDAY) n--;
        }
        return d;
    }

    private static String h1(double v) { return String.format(Locale.forLanguageTag("pt-BR"), "%.1f", v); }
    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    private static Map<String, Object> dim(String key, String label, String level, String detail) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("key", key); d.put("label", label); d.put("level", level); d.put("detail", detail);
        return d;
    }

    private static Map<String, Object> healthNeutral(String level, String label, String reason) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("level", level); h.put("label", label);
        h.put("dims", new ArrayList<>());
        h.put("reasons", reason == null ? new ArrayList<>() : new ArrayList<>(List.of(reason)));
        return h;
    }

    /**
     * Semáforo das horas: divide a franquia pelas semanas úteis do período e compara com o consumo.
     * Três verificações (a cor final é a pior): ritmo semanal, projeção no fim e saldo comprometido.
     * Devolve null quando não há controle de horas (escopo fechado / sem período definido).
     */
    private static Map<String, Object> health(Map<String, Object> p, long id, LocalDate asked, LocalDate ref, LocalDate start, LocalDate end,
            double contracted, double consumed, double reserved) throws SQLException {
        if (contracted <= 0 || end == null) return null;
        boolean closed = "ENCERRADO".equals(p.get("status"));
        if (closed) {
            Map<String, Object> h = consumed > contracted + 0.005
                    ? healthNeutral("CONCLUIDO_ESTOURO", "Encerrado c/ estouro", "Encerrou com " + h1(consumed - contracted) + "h acima da franquia.")
                    : healthNeutral("CONCLUIDO", "Encerrado", "Encerrou dentro da franquia (" + h1(consumed) + "h de " + h1(contracted) + "h).");
            return h;
        }
        if (asked.isBefore(start)) return healthNeutral("NAO_INICIADO", "Não iniciado", "Começa em " + br(start) + ".");

        int total = businessDays(start, end);
        if (total <= 0) return null;
        LocalDate upTo = ref.isAfter(end) ? end : ref;
        int elapsed = Math.min(total, businessDays(start, upTo));
        double weeksTotal = total / 5.0, weeksElapsed = elapsed / 5.0;
        double weeklyBudget = contracted / weeksTotal;
        double expected = contracted * elapsed / total;
        double weeklyActual = elapsed > 0 ? consumed / weeksElapsed : 0;
        double projected = elapsed > 0 ? consumed / elapsed * total : consumed;
        double committed = (consumed + reserved) / contracted;
        boolean exhausted = consumed >= contracted - 0.005 && consumed > 0 && consumed > contracted;
        boolean fewData = elapsed < 5 && !exhausted;

        List<Map<String, Object>> dims = new ArrayList<>();
        int worst = -1; // -1 = sem dados
        String[] order = {"VERDE", "AMARELO", "VERMELHO"};

        if (fewData) {
            dims.add(dim("ritmo", "Ritmo semanal", "NA", "Menos de 1 semana útil decorrida: poucos dados."));
            dims.add(dim("projecao", "Projeção no fim", "NA", "Menos de 1 semana útil decorrida: poucos dados."));
        } else {
            double ratio = expected > 0 ? consumed / expected : 0;
            String l1 = ratio <= 1.05 ? "VERDE" : ratio <= 1.20 ? "AMARELO" : "VERMELHO";
            dims.add(dim("ritmo", "Ritmo semanal", l1, "Consumindo " + h1(weeklyActual) + "h/sem contra " + h1(weeklyBudget) + "h orçadas (" + pct(ratio) + ")."));
            double pr = projected / contracted;
            String l2 = pr <= 0.90 ? "VERDE" : pr <= 1.0 ? "AMARELO" : "VERMELHO";
            dims.add(dim("projecao", "Projeção no fim", l2, h1(projected) + "h de " + h1(contracted) + "h (" + pct(pr) + ")" + (pr > 1.0 ? ": vai estourar." : pr > 0.90 ? ": no limite." : ": dentro da franquia.")));
        }
        String l3 = committed < 0.90 ? "VERDE" : committed < 1.0 ? "AMARELO" : "VERMELHO";
        dims.add(dim("saldo", "Saldo comprometido", l3, h1(consumed) + "h consumidas" + (reserved > 0 ? " + " + h1(reserved) + "h reservadas" : "") + " de " + h1(contracted) + "h (" + pct(committed) + ")."));
        for (Map<String, Object> d : dims) {
            String lv = (String) d.get("level");
            for (int i = 0; i < order.length; i++) if (order[i].equals(lv)) worst = Math.max(worst, i);
        }

        Map<String, Object> h = new LinkedHashMap<>();
        h.put("level", worst < 0 ? "SEM_DADOS" : order[worst]);
        h.put("label", worst < 0 ? "Poucos dados" : worst == 0 ? "Saudável" : worst == 1 ? "Atenção" : "Crítico");
        h.put("dims", dims);

        List<String> reasons = new ArrayList<>();
        double remaining = contracted - consumed;
        double weeksLeft = Math.max(0, (total - elapsed) / 5.0);
        if (consumed > contracted + 0.005) {
            reasons.add("Franquia esgotada: " + h1(consumed - contracted) + "h acima do contratado.");
        } else if (!fewData && elapsed > 0 && consumed > 0) {
            double perDay = consumed / elapsed;
            int daysToZero = (int) Math.ceil(remaining / perDay);
            LocalDate zero = addBusinessDays(upTo, daysToZero);
            if (projected > contracted + 0.005 && !zero.isAfter(end)) {
                int before = businessDays(zero.plusDays(1), end);
                reasons.add("No ritmo atual o saldo acaba por volta de " + br(zero) + (before > 0 ? " (" + before + " dia(s) útil(eis) antes do fim)" : "") + ", e o período fecha com ≈ " + h1(projected) + "h (" + h1(projected - contracted) + "h acima).");
                if (weeksLeft > 0) reasons.add("Para terminar no limite, o consumo precisa cair para no máximo " + h1(remaining / weeksLeft) + "h por semana até " + br(end) + ".");
            } else if (weeksLeft > 0) {
                reasons.add("Dá para consumir até " + h1(remaining / weeksLeft) + "h por semana até " + br(end) + " sem passar da franquia.");
            }
        }
        h.put("reasons", reasons);

        h.put("weeks_total", r2(weeksTotal));
        h.put("weeks_elapsed", r2(weeksElapsed));
        h.put("weekly_budget", r2(weeklyBudget));
        h.put("weekly_actual", r2(weeklyActual));
        h.put("expected", r2(expected));
        h.put("diff", r2(consumed - expected));
        h.put("projected", r2(projected));
        h.put("projected_pct", Math.round(projected / contracted * 100));
        h.put("business_days_total", total);
        h.put("business_days_elapsed", elapsed);
        h.put("start", start.toString());
        h.put("end", end.toString());
        h.put("few_data", fewData);

        // horas acumuladas por dia útil até a data de referência (para o gráfico)
        List<Map<String, Object>> rows = Db.query("SELECT work_date, SUM(hours) hrs FROM spot_hours WHERE spot_id = ? AND work_date >= ? AND work_date <= ? GROUP BY work_date ORDER BY work_date", id, start, upTo);
        Map<String, Double> byDay = new HashMap<>();
        for (Map<String, Object> r : rows) byDay.put(String.valueOf(r.get("work_date")).substring(0, 10), num(r.get("hrs")));
        List<Object[]> series = new ArrayList<>();
        double acc = 0;
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(upTo); d = d.plusDays(1)) {
            double v = byDay.getOrDefault(d.toString(), 0.0);
            acc += v;
            java.time.DayOfWeek w = d.getDayOfWeek();
            boolean bd = w != java.time.DayOfWeek.SATURDAY && w != java.time.DayOfWeek.SUNDAY;
            if (bd) idx++;
            if (bd || v > 0) series.add(new Object[] {idx, r2(acc)});
        }
        h.put("series", series);
        return h;
    }
}
