package br.com.sysattack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gera o HTML do relatório (mesmo layout dos modelos de follow-up). Usa tabelas e estilos inline para funcionar
 * no navegador, na impressão/PDF e dentro do corpo de e-mail. Todo texto é escapado.
 */
final class SpotReportHtml {
    private SpotReportHtml() {}

    private static final String ORANGE = "#f15400", INK = "#1a1a1a", MUTED = "#6d6d6d", LINE = "#e1e1e1";
    private static final Locale PT = Locale.forLanguageTag("pt-BR");

    static final Map<String, String[]> DEMAND_STATUS = new HashMap<>();
    static final Map<String, String[]> ACTION_STATUS = new HashMap<>();
    static {
        DEMAND_STATUS.put("EM_ANALISE", new String[] {"Em análise", "#8a5a00", "#fff4dc"});
        DEMAND_STATUS.put("APROVADO", new String[] {"Aprovado", "#1f5fa8", "#e3eefb"});
        DEMAND_STATUS.put("EM_ANDAMENTO", new String[] {"Em andamento", "#a84300", "#fde6c8"});
        DEMAND_STATUS.put("EM_HOMOLOGACAO", new String[] {"Em homologação", "#6a3fa0", "#efe6fa"});
        DEMAND_STATUS.put("EM_ESPERA", new String[] {"Em espera", "#555555", "#eaeaea"});
        DEMAND_STATUS.put("CONCLUIDA", new String[] {"Concluída", "#1f7a43", "#e3f3e8"});
        ACTION_STATUS.put("PENDENTE", new String[] {"Pendente", "#8a5a00", "#fff4dc"});
        ACTION_STATUS.put("A_CONFIRMAR", new String[] {"A confirmar", "#8a5a00", "#fff4dc"});
        ACTION_STATUS.put("PARCIAL", new String[] {"Parcial", "#1f5fa8", "#e3eefb"});
        ACTION_STATUS.put("CONCLUIDO", new String[] {"Concluído", "#1f7a43", "#e3f3e8"});
    }

    static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
    private static double dbl(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }
    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray();
    }
    static String hrs(double v) {
        return String.format(PT, "%.1f", v).replace("-", "−");
    }
    static String date(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso.substring(0, 10));
            return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
        } catch (RuntimeException e) {
            return iso;
        }
    }
    // Compatível com o Outlook (motor do Word): etiquetas, cartões e barras são tabelas com bgcolor; nada de inline-block,
    // margin em tabela ou largura em <span>. Nos demais clientes o visual é o mesmo.
    private static final String T0 = " role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"";

    static String gap(int px) {
        return "<table" + T0 + " width=\"100%\"><tr><td height=\"" + px + "\" style=\"height:" + px + "px;font-size:1px;line-height:1px\">&nbsp;</td></tr></table>";
    }
    /** Etiqueta de status; a observação (opcional) fica ao lado, na mesma linha. */
    private static String badge(String text, String fg, String bg) { return badge(text, fg, bg, ""); }
    private static String badge(String text, String fg, String bg, String note) {
        return "<table" + T0 + "><tr><td bgcolor=\"" + bg + "\" style=\"background-color:" + bg + ";padding:3px 10px;font-size:12px;font-weight:bold;color:" + fg
                + ";border:1px solid " + fg + ";white-space:nowrap;border-radius:12px\">" + h(text) + "</td>"
                + (note.isBlank() ? "" : "<td style=\"padding-left:8px;font-size:12px;color:#333333\">" + h(note) + "</td>") + "</tr></table>";
    }
    private static String section(String title) {
        return "<table" + T0 + " width=\"100%\"><tr><td style=\"padding:0 0 10px;font-size:12px;font-weight:bold;letter-spacing:1px;text-transform:uppercase;color:" + MUTED + "\">" + h(title) + "</td></tr></table>";
    }
    private static String card(String inner) {
        return "<table" + T0 + " width=\"100%\"><tr><td bgcolor=\"#ffffff\" style=\"background-color:#ffffff;border:1px solid " + INK
                + ";border-radius:10px;padding:18px 20px\">" + inner + "</td></tr></table>" + gap(16);
    }
    private static String th(String t, boolean right) {
        return "<th align=\"" + (right ? "right" : "left") + "\" style=\"padding:8px 6px;border-bottom:2px solid " + INK
                + ";font-size:13px\">" + h(t) + "</th>";
    }
    private static String td(String inner, boolean right, boolean bold) {
        return "<td align=\"" + (right ? "right" : "left") + "\" valign=\"top\" style=\"padding:9px 6px;border-bottom:1px solid " + LINE
                + ";font-size:13px;color:#333" + (bold ? ";font-weight:bold" : "") + "\">" + inner + "</td>";
    }
    private static String kpi(String value, String label, String color) {
        return "<td width=\"25%\" valign=\"top\" style=\"padding:4px\"><table" + T0 + " width=\"100%\"><tr><td style=\"border:1px solid " + INK + ";border-radius:10px;padding:14px 14px 12px\">"
                + "<table" + T0 + " width=\"100%\"><tr><td style=\"font-size:26px;font-weight:bold;color:" + color + "\">" + h(value) + "</td></tr>"
                + "<tr><td style=\"padding-top:2px;font-size:12px;color:#444444\">" + h(label) + "</td></tr></table></td></tr></table></td>";
    }

    /** @param emailIntro mensagem de abertura (somente e-mail); null no navegador */
    static String render(String title, String createdBy, String createdAt, JsonObject snap, JsonObject narr, String emailIntro) {
        return render(title, createdBy, createdAt, snap, narr, emailIntro, false);
    }

    static String render(String title, String createdBy, String createdAt, JsonObject snap, JsonObject narr, String emailIntro, boolean email) {
        JsonObject proj = snap.getAsJsonObject("project"), kpi = snap.getAsJsonObject("kpi");
        boolean monthly = "MENSAL".equals(str(proj, "hours_model"));
        String period = str(snap, "period_label"), ref = date(str(snap, "ref_date"));
        double projected = dbl(kpi, "projected");
        StringBuilder b = new StringBuilder(8000);
        b.append("<!DOCTYPE html><html lang=\"pt-BR\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">")
         .append("<!--[if gte mso 9]><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->")
         .append("<title>").append(h(title)).append("</title><style>body,table,td,th,p{font-family:'Segoe UI',Arial,Helvetica,sans-serif;color:").append(INK).append("}table{border-collapse:collapse;mso-table-lspace:0;mso-table-rspace:0}")
         .append("@media print{*{-webkit-print-color-adjust:exact;print-color-adjust:exact}}</style></head>")
         .append("<body bgcolor=\"#f2f2f2\" style=\"margin:0;background-color:#f2f2f2;font-family:'Segoe UI',Arial,Helvetica,sans-serif;color:").append(INK).append("\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" bgcolor=\"#f2f2f2\"><tr><td align=\"center\" style=\"padding:20px 10px\">")
         .append("<table role=\"presentation\" width=\"760\" cellpadding=\"0\" cellspacing=\"0\" bgcolor=\"#ffffff\" style=\"width:100%;max-width:760px;background-color:#ffffff\"><tr><td style=\"padding:26px 28px\">");

        if (emailIntro != null && !emailIntro.isBlank()) {
            b.append("<p style=\"font-size:14px;line-height:1.5;margin:0 0 22px;color:#333333\">").append(h(emailIntro).replace("\n", "<br>")).append("</p>");
        }

        b.append(ReportLogo.header(email, "Status Report", ref));
        b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
         .append("<td width=\"5\" bgcolor=\"").append(ORANGE).append("\" style=\"background-color:").append(ORANGE).append(";font-size:1px\">&nbsp;</td><td style=\"padding:2px 0 2px 14px\">")
         .append("<table").append(T0).append(" width=\"100%\"><tr><td style=\"font-size:24px;font-weight:bold;color:#333333\">").append(h(title)).append("</td></tr>")
         .append("<tr><td style=\"padding-top:6px;font-size:13px;line-height:1.5\">").append(h(ref));
        String part = str(proj, "participants");
        if (!part.isBlank()) b.append(" · ").append(h(part));
        b.append("</td></tr>");
        String destaques = str(narr, "destaques");
        if (!destaques.isBlank()) {
            b.append("<tr><td style=\"padding-top:6px;font-size:13px;line-height:1.5\"><b style=\"color:").append(MUTED).append("\">Destaques:</b> ").append(h(destaques)).append("</td></tr>");
        }
        b.append("</table></td></tr></table>").append(gap(18));

        JsonArray alerts = arr(narr, "alertas");
        if (alerts.size() > 0) {
            b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ><tr><td width=\"4\" bgcolor=\"#e6a100\" style=\"background-color:#e6a100;font-size:1px\">&nbsp;</td><td bgcolor=\"#fff4dc\" style=\"background-color:#fff4dc;padding:12px 16px;font-size:13px;line-height:1.5\"><b>Pontos de atenção</b><ul style=\"margin:6px 0 0;padding-left:18px\">");
            for (JsonElement e : alerts) b.append("<li>").append(h(e.getAsString())).append("</li>");
            b.append("</ul></td></tr></table>").append(gap(16));
        }

        b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
         .append(kpi(hrs(dbl(kpi, "contracted")) + "h", monthly ? "Franquia mensal" : "Pacote contratado", "#8a8a8a"))
         .append(kpi(hrs(dbl(kpi, "consumed")) + "h", "Consumido " + (monthly ? "em " + period : period) + " (até " + ref.substring(0, 5) + ")", "#8a6a2a"))
         .append(kpi(hrs(dbl(kpi, "reserved")) + "h", "Reservado em demandas aprovadas", "#8a6a2a"))
         .append(kpi(hrs(projected) + "h", projected < 0 ? "Saldo projetado (excedente)" : "Saldo projetado", projected < 0 ? "#8c3a3a" : "#1f7a43"))
         .append("</tr></table>").append(gap(12));

        JsonArray items = arr(snap, "by_item");
        if (items.size() > 0) {
            StringBuilder t = new StringBuilder(section("Consumo " + (monthly ? "de " + period : period) + " por demanda"));
            t.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>").append(th("Demanda", false)).append(th("Frente", false)).append(th("Horas", true)).append("</tr>");
            for (JsonElement e : items) {
                JsonObject o = e.getAsJsonObject();
                t.append("<tr>").append(td(h(str(o, "label")), false, false)).append(td(h(str(o, "front")), false, false)).append(td(hrs(dbl(o, "hours")), true, false)).append("</tr>");
            }
            t.append("<tr>").append(td("Total consumido", false, true)).append(td("", false, false)).append(td(hrs(dbl(kpi, "consumed")), true, true)).append("</tr></table>");
            b.append(card(t.toString()));
        }

        JsonArray approved = arr(snap, "approved");
        if (approved.size() > 0) {
            StringBuilder t = new StringBuilder(section("Demandas com horas aprovadas: realizado × aprovado"));
            for (JsonElement e : approved) {
                JsonObject o = e.getAsJsonObject();
                double diff = dbl(o, "diff");
                t.append("<p style=\"font-size:14px;font-weight:bold;margin:8px 0 4px\">").append(h(str(o, "name"))).append("</p>")
                 .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>").append(th("Alteração / atividade", false)).append(th("Horas", true)).append("</tr>");
                for (JsonElement en : arr(o, "entries")) {
                    JsonObject eo = en.getAsJsonObject();
                    t.append("<tr>").append(td(h(str(eo, "activity")), false, false)).append(td(hrs(dbl(eo, "hours")), true, false)).append("</tr>");
                }
                t.append("<tr>").append(td("Total realizado (aprovado: " + hrs(dbl(o, "approved")) + "h)", false, true)).append(td(hrs(dbl(o, "realized")), true, true)).append("</tr></table>");
                if (diff > 0.001) t.append(gap(8)).append(badge("+" + hrs(diff) + "h sobre o aprovado", "#8a5a00", "#fff4dc")).append(gap(6));
                else if (diff < -0.001) t.append(gap(8)).append(badge(hrs(-diff) + "h ainda dentro do aprovado", "#1f7a43", "#e3f3e8")).append(gap(6));
            }
            String obs = str(narr, "observacao_horas");
            if (!obs.isBlank()) t.append("<p style=\"font-size:13px;line-height:1.5;margin:6px 0 0\">").append(h(obs)).append("</p>");
            b.append(card(t.toString()));
        }

        StringBuilder s = new StringBuilder(section("Saldo " + (monthly ? "de " + period : "do pacote")));
        s.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>").append(th("Item", false)).append(th("Horas", true)).append("</tr>")
         .append("<tr>").append(td(monthly ? "Franquia mensal" : "Pacote contratado", false, false)).append(td(hrs(dbl(kpi, "contracted")), true, false)).append("</tr>")
         .append("<tr>").append(td("(−) Consumido até " + ref.substring(0, 5), false, false)).append(td(hrs(dbl(kpi, "consumed")), true, false)).append("</tr>");
        if (dbl(kpi, "reserved") > 0) s.append("<tr>").append(td("(−) Reservado em demandas aprovadas", false, false)).append(td(hrs(dbl(kpi, "reserved")), true, false)).append("</tr>");
        s.append("<tr>").append(td("Saldo projetado", false, true)).append(td(hrs(projected), true, true)).append("</tr></table>");
        String bill = str(proj, "billing_note");
        if (!bill.isBlank()) s.append("<p style=\"font-size:12px;color:").append(MUTED).append(";line-height:1.5;margin:10px 0 0\">").append(h(bill)).append("</p>");
        b.append(card(s.toString()));

        JsonArray actions = arr(snap, "actions");
        if (actions.size() > 0) {
            StringBuilder t = new StringBuilder(section("Ações do último comitê"));
            t.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>").append(th("Ação", false)).append(th("Responsável", false)).append(th("Status", false)).append("</tr>");
            for (JsonElement e : actions) {
                JsonObject o = e.getAsJsonObject();
                String[] st = ACTION_STATUS.getOrDefault(str(o, "status"), ACTION_STATUS.get("PENDENTE"));
                String note = str(o, "note");
                t.append("<tr>").append(td(h(str(o, "description")), false, false)).append(td(h(str(o, "responsible")), false, false))
                 .append(td(badge(st[0], st[1], st[2], note), false, false)).append("</tr>");
            }
            t.append("</table>");
            b.append(card(t.toString()));
        }

        JsonArray demands = arr(snap, "demands");
        if (demands.size() > 0) {
            Map<Long, String> resumo = new HashMap<>();
            for (JsonElement e : arr(narr, "demandas")) {
                JsonObject o = e.getAsJsonObject();
                try { resumo.put(o.get("id").getAsLong(), str(o, "resumo")); } catch (RuntimeException ignored) { /* id inválido */ }
            }
            StringBuilder t = new StringBuilder(section("Status das demandas"));
            t.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>").append(th("Demanda", false)).append(th("Status", false)).append(th("Próximo passo", false)).append("</tr>");
            for (JsonElement e : demands) {
                JsonObject o = e.getAsJsonObject();
                String[] st = DEMAND_STATUS.getOrDefault(str(o, "status"), DEMAND_STATUS.get("EM_ANALISE"));
                String r = resumo.getOrDefault(o.get("id").getAsLong(), "");
                t.append("<tr>").append(td("<b>" + h(str(o, "name")) + "</b>" + (r.isBlank() ? "" : "<br><span style=\"font-size:12px;color:" + MUTED + "\">" + h(r) + "</span>"), false, false))
                 .append(td(badge(st[0], st[1], st[2]), false, false)).append(td(h(str(o, "next_step")), false, false)).append("</tr>");
            }
            t.append("</table>");
            b.append(card(t.toString()));
        }

        JsonArray pend = arr(snap, "pendencies");
        if (pend.size() > 0) {
            StringBuilder t = new StringBuilder(section("Pendências do cliente"));
            t.append("<ul style=\"margin:0;padding-left:20px;font-size:13px;line-height:1.7\">");
            for (JsonElement e : pend) {
                JsonObject o = e.getAsJsonObject();
                String who = str(o, "responsible");
                t.append("<li>").append(h(str(o, "description"))).append(who.isBlank() ? "" : " <span style=\"color:" + MUTED + "\">(" + h(who) + ")</span>").append("</li>");
            }
            t.append("</ul>");
            b.append(card(t.toString()));
        }

        b.append("<p style=\"font-size:11.5px;color:").append(MUTED).append(";line-height:1.5;margin:4px 0 0\">Fonte: apontamentos de horas registrados no SysAttack até ")
         .append(h(ref)).append(". Horas lançadas depois dessa data entram na próxima posição. Números calculados pelo SysAttack; textos redigidos com apoio de IA (Claude).")
         .append(createdBy == null || createdBy.isBlank() ? "" : " Gerado por " + h(createdBy) + (createdAt == null ? "" : " em " + h(date(createdAt))) + ".")
         .append("</p>");
        b.append("</td></tr></table></td></tr></table></body></html>");
        return b.toString();
    }
}
