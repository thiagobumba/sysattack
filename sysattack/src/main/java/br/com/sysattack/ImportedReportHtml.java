package br.com.sysattack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * Renderiza o Status Report da Fábrica importado de um HTML (mesmo conteúdo e organização do arquivo original).
 * Tabelas e estilos inline, para funcionar no navegador, na impressão/PDF e no corpo de e-mail. Todo texto é escapado.
 */
final class ImportedReportHtml {
    private ImportedReportHtml() {}

    private static final String INK = "#1d1d1b", MUTED = "#6b6b66", LINE = "#e6e4de", YELLOW = "#e8b400", RED = "#b8332f";
    private static final String T0 = " role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"";

    /** Caixa compatível com o Outlook: tabela com bgcolor e borda/espaçamento na célula (div com padding é ignorado pelo Word). */
    private static String box(String inner, String leftBar) {
        return "<table" + T0 + " width=\"100%\"><tr>" + (leftBar == null ? "" : "<td width=\"4\" bgcolor=\"" + leftBar + "\" style=\"background-color:" + leftBar + ";font-size:1px\">&nbsp;</td>")
                + "<td bgcolor=\"#ffffff\" style=\"background-color:#ffffff;border:1px solid " + LINE + ";" + (leftBar == null ? "" : "border-left:0;") + "border-radius:10px;padding:16px 18px\">" + inner + "</td></tr></table>";
    }

    private static String gap(int px) {
        return "<table" + T0 + " width=\"100%\"><tr><td height=\"" + px + "\" style=\"height:" + px + "px;font-size:1px;line-height:1px\">&nbsp;</td></tr></table>";
    }
    private static final int[] LIMITS = {1000, 400};

    private static String h(String s) { return SpotReportHtml.h(s); }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        String v = e.getAsString().trim();
        return v.length() > LIMITS[0] ? v.substring(0, LIMITS[0]) : v;
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray();
    }

    /** Mantém só os campos conhecidos, com limite de tamanho. Devolve o objeto que será gravado. */
    static JsonObject normalize(JsonObject in) {
        JsonObject o = new JsonObject();
        o.addProperty("imported", true);
        for (String k : new String[] {"titulo", "meta", "destaques", "hConsumo", "hChat", "hAcoes", "hAnd", "hPrev", "hDefs", "totLbl1", "totLbl2",
                "pillChat", "notaChat", "notaPrev", "rodape"}) {
            o.addProperty(k, str(in, k));
        }
        String[][] shapes = {{"kpis", "n", "l"}, {"consumo", "d", "h"}, {"chat", "d", "h"}, {"acoes", "a", "r", "s", "t"}, {"andamento", "t", "d"}, {"previsao", "t", "d"}, {"defs", "t", "d"}};
        for (String[] sh : shapes) {
            JsonArray src = arr(in, sh[0]), out = new JsonArray();
            if (src.size() > 60) throw new ApiServlet.ApiException(400, "Relatório com linhas demais em “" + sh[0] + "”.");
            for (JsonElement e : src) {
                if (!e.isJsonObject()) continue;
                JsonObject r = new JsonObject();
                for (int i = 1; i < sh.length; i++) r.addProperty(sh[i], str(e.getAsJsonObject(), sh[i]));
                out.add(r);
            }
            o.add(sh[0], out);
        }
        return o;
    }

    private static double num(String s) {
        s = s == null ? "" : s.trim();
        if (s.contains(",")) s = s.replace(".", "").replace(",", ".");
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String fmt(double v) { return String.format(new Locale("pt", "BR"), "%.1f", v); }

    private static String[] pill(String status) {
        switch (status == null ? "" : status) {
            case "Concluído": return new String[] {"#2e8b57", "#e6f3ec"};
            case "Parcial": case "Em andamento": return new String[] {"#3b6fd4", "#e8eefb"};
            case "Bloqueado": return new String[] {RED, "#f7e7e6"};
            default: return new String[] {"#b7791f", "#f8efdc"};
        }
    }

    private static String pillHtml(String text, String[] c) {
        return "<table" + T0 + " style=\"display:inline-table\"><tr><td bgcolor=\"" + c[1] + "\" style=\"background-color:" + c[1] + ";font-size:12px;font-weight:bold;padding:2px 10px;border:1px solid " + c[0]
                + ";color:" + c[0] + ";white-space:nowrap;border-radius:10px\">" + h(text) + "</td></tr></table>";
    }

    private static String title(String t, String color) {
        return "<table" + T0 + " width=\"100%\"><tr><td style=\"padding:0 0 12px;font-size:12.5px;font-weight:bold;letter-spacing:1px;text-transform:uppercase;color:" + color + "\">" + h(t) + "</td></tr></table>";
    }

    private static String hoursTable(String head, JsonArray rows, String col, String totalLabel) {
        double total = 0;
        StringBuilder b = new StringBuilder("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:13.5px\"><tr>"
                + "<th align=\"left\" style=\"font-size:13px;color:" + MUTED + ";padding:4px 8px 8px 0;border-bottom:1px solid " + LINE + "\">" + h(col) + "</th>"
                + "<th align=\"right\" style=\"font-size:13px;color:" + MUTED + ";padding:4px 6px 8px 0;border-bottom:1px solid " + LINE + ";width:70px\">Horas</th></tr>");
        for (JsonElement e : rows) {
            JsonObject r = e.getAsJsonObject();
            total += num(str(r, "h"));
            b.append("<tr><td valign=\"top\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(h(str(r, "d")))
                    .append("</td><td valign=\"top\" align=\"right\" style=\"padding:8px 6px 8px 0;border-bottom:1px solid ").append(LINE).append(";white-space:nowrap\">")
                    .append(h(str(r, "h"))).append("</td></tr>");
        }
        b.append("<tr><td style=\"padding:8px 8px 0 0;font-weight:700\">").append(h(totalLabel.isEmpty() ? "Total" : totalLabel))
                .append("</td><td align=\"right\" style=\"padding:8px 6px 0 0;font-weight:700\">").append(fmt(total)).append("</td></tr></table>");
        return b.toString();
    }

    private static String list(JsonArray rows) {
        StringBuilder b = new StringBuilder("<ul style=\"margin:0;padding-left:20px\">");
        for (JsonElement e : rows) {
            JsonObject r = e.getAsJsonObject();
            String d = str(r, "d");
            b.append("<li style=\"padding:4px 0\"><b>").append(h(str(r, "t"))).append("</b>").append(d.isEmpty() ? "" : ": " + h(d)).append("</li>");
        }
        return b.append("</ul>").toString();
    }

    private static String twoCols(String left, String right) {
        return "<table" + T0 + " width=\"100%\"><tr><td class=\"col\" width=\"50%\" valign=\"top\" style=\"padding-right:8px\">" + left
                + "</td><td class=\"col\" width=\"50%\" valign=\"top\" style=\"padding-left:8px\">" + right + "</td></tr></table>" + gap(16);
    }

    static String render(JsonObject d) {
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"pt-BR\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><!--[if gte mso 9]><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->")
                .append("<title>").append(h(str(d, "titulo"))).append("</title><style>body,table,td,th,p{font-family:'Segoe UI',Arial,Helvetica,sans-serif;color:").append(INK)
                .append("}table{border-collapse:collapse;mso-table-lspace:0;mso-table-rspace:0}@media print{*{-webkit-print-color-adjust:exact;print-color-adjust:exact}}</style></head>")
                .append("<body bgcolor=\"#f6f6f4\" style=\"margin:0;background-color:#f6f6f4;font-size:14.5px\"><table").append(T0).append(" width=\"100%\" bgcolor=\"#f6f6f4\"><tr><td align=\"center\" style=\"padding:22px 18px 40px\">")
                .append("<!--[if mso]><table role=\"presentation\" width=\"1000\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->")
                .append("<table").append(T0).append(" width=\"100%\" style=\"max-width:1000px\"><tr><td>");
        b.append("<table").append(T0).append(" width=\"100%\"><tr><td width=\"4\" bgcolor=\"").append(YELLOW).append("\" style=\"background-color:").append(YELLOW).append(";font-size:1px\">&nbsp;</td><td style=\"padding:2px 0 2px 16px\">")
                .append("<table").append(T0).append(" width=\"100%\"><tr><td style=\"font-size:25px;font-weight:bold;line-height:1.25\">").append(h(str(d, "titulo"))).append("</td></tr>")
                .append("<tr><td style=\"padding-top:4px;color:").append(MUTED).append(";font-size:14px\">").append(h(str(d, "meta"))).append("</td></tr>");
        if (!str(d, "destaques").isEmpty()) {
            b.append("<tr><td style=\"color:").append(MUTED).append(";font-size:14px\"><b style=\"color:").append(INK).append("\">Destaques:</b> ").append(h(str(d, "destaques"))).append("</td></tr>");
        }
        b.append("</table></td></tr></table>").append(gap(16));

        JsonArray kpis = arr(d, "kpis");
        if (kpis.size() > 0) {
            b.append("<table").append(T0).append(" width=\"100%\" style=\"table-layout:fixed\"><tr>");
            int i = 0;
            for (JsonElement e : kpis) {
                JsonObject k = e.getAsJsonObject();
                String n = str(k, "n"), color = i == 0 ? INK : i == 2 ? (n.matches("^\\s*[-−–].*") ? RED : "#2e8b57") : "#b7791f";
                b.append("<td valign=\"top\" style=\"padding:0 ").append(i == kpis.size() - 1 ? 0 : 7).append("px 0 ").append(i == 0 ? 0 : 7).append("px\">")
                        .append(box("<table" + T0 + " width=\"100%\"><tr><td style=\"font-size:26px;font-weight:bold;line-height:1.2;color:" + color + "\">" + h(n)
                                + "</td></tr><tr><td style=\"padding-top:4px;font-size:12.5px;color:" + MUTED + "\">" + h(str(k, "l")) + "</td></tr></table>", null)).append("</td>");
                i++;
            }
            b.append("</tr></table>").append(gap(16));
        }

        String chatNote = "";
        if (!str(d, "pillChat").isEmpty() || !str(d, "notaChat").isEmpty()) {
            chatNote = gap(14) + "<table" + T0 + "><tr>" + (str(d, "pillChat").isEmpty() ? "" : "<td valign=\"middle\" style=\"padding-right:8px\">" + pillHtml(str(d, "pillChat"), pill("Pendente")) + "</td>")
                    + "<td style=\"font-size:14px\">" + h(str(d, "notaChat")) + "</td></tr></table>";
        }
        b.append(twoCols(
                box(title(str(d, "hConsumo"), INK) + hoursTable("", arr(d, "consumo"), "Demanda", str(d, "totLbl1")), null),
                box(title(str(d, "hChat"), INK) + hoursTable("", arr(d, "chat"), "Alteração", str(d, "totLbl2")) + chatNote, null)));

        JsonArray acoes = arr(d, "acoes");
        if (acoes.size() > 0) {
            StringBuilder t = new StringBuilder(title(str(d, "hAcoes"), INK))
                    .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:13.5px\"><tr>");
            for (String c : new String[] {"Ação", "Responsável", "Status"}) {
                t.append("<th align=\"left\" style=\"font-size:13px;color:").append(MUTED).append(";padding:4px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(c).append("</th>");
            }
            t.append("</tr>");
            for (JsonElement e : acoes) {
                JsonObject r = e.getAsJsonObject();
                String obs = str(r, "t");
                t.append("<tr><td valign=\"top\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(h(str(r, "a")))
                        .append("</td><td valign=\"top\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(h(str(r, "r")))
                        .append("</td><td valign=\"top\" width=\"42%\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\"><table").append(T0).append("><tr><td valign=\"top\">")
                        .append(pillHtml(str(r, "s"), pill(str(r, "s")))).append("</td>").append(obs.isEmpty() ? "" : "<td style=\"padding-left:8px;font-size:13.5px\">" + h(obs) + "</td>").append("</tr></table></td></tr>");
            }
            t.append("</table>");
            b.append(box(t.toString(), null)).append(gap(16));
        }

        String prevNote = str(d, "notaPrev").isEmpty() ? "" : "<p style=\"font-size:12.5px;color:" + MUTED + ";margin:12px 0 0\">" + h(str(d, "notaPrev")) + "</p>";
        b.append(twoCols(
                box(title(str(d, "hAnd"), INK) + list(arr(d, "andamento")), null),
                box(title(str(d, "hPrev"), INK) + list(arr(d, "previsao")) + prevNote, null)));

        if (arr(d, "defs").size() > 0) {
            b.append(box(title(str(d, "hDefs"), INK) + list(arr(d, "defs")), RED)).append(gap(16));
        }
        if (!str(d, "rodape").isEmpty()) b.append("<table").append(T0).append(" width=\"100%\"><tr><td style=\"padding:2px 4px;font-size:12.5px;color:").append(MUTED).append("\">").append(h(str(d, "rodape"))).append("</td></tr></table>");
        return b.append("</td></tr></table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>").toString();
    }
}
