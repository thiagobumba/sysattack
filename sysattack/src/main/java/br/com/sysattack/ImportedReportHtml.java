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
    private static final String CARD = "background:#fff;border:1px solid " + LINE + ";border-radius:10px;padding:16px 18px;";
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
        return "<span style=\"display:inline-block;font-size:12px;font-weight:500;padding:1px 10px;border-radius:999px;border:1px solid " + c[0]
                + ";color:" + c[0] + ";background:" + c[1] + ";margin-right:6px\">" + h(text) + "</span>";
    }

    private static String title(String t, String color) {
        return "<div style=\"margin:0 0 12px;font-size:12.5px;font-weight:700;letter-spacing:.07em;text-transform:uppercase;color:" + color + "\">" + h(t) + "</div>";
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
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:16px\"><tr><td width=\"50%\" valign=\"top\" style=\"padding-right:8px\">" + left
                + "</td><td width=\"50%\" valign=\"top\" style=\"padding-left:8px\">" + right + "</td></tr></table>";
    }

    static String render(JsonObject d) {
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>")
                .append(h(str(d, "titulo"))).append("</title></head><body style=\"margin:0;background:#f6f6f4;color:").append(INK)
                .append(";font:14.5px/1.5 Inter,Arial,sans-serif\"><div style=\"max-width:1060px;margin:0 auto;padding:22px 18px 40px\">");
        b.append("<div style=\"border-left:4px solid ").append(YELLOW).append(";padding:2px 0 2px 16px;margin-bottom:16px\">")
                .append("<h1 style=\"margin:0 0 4px;font-size:25px;font-weight:700;line-height:1.25\">").append(h(str(d, "titulo"))).append("</h1>")
                .append("<div style=\"color:").append(MUTED).append(";font-size:14px\">").append(h(str(d, "meta"))).append("</div>");
        if (!str(d, "destaques").isEmpty()) {
            b.append("<div style=\"color:").append(MUTED).append(";font-size:14px\"><b style=\"color:").append(INK).append("\">Destaques:</b> ")
                    .append(h(str(d, "destaques"))).append("</div>");
        }
        b.append("</div>");

        JsonArray kpis = arr(d, "kpis");
        if (kpis.size() > 0) {
            b.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:16px;table-layout:fixed\"><tr>");
            int i = 0;
            for (JsonElement e : kpis) {
                JsonObject k = e.getAsJsonObject();
                String n = str(k, "n"), color = i == 0 ? INK : i == 2 ? (n.matches("^\\s*[-−–].*") ? RED : "#2e8b57") : "#b7791f";
                b.append("<td valign=\"top\" style=\"padding:0 ").append(i == kpis.size() - 1 ? 0 : 7).append("px 0 ").append(i == 0 ? 0 : 7).append("px\"><div style=\"")
                        .append(CARD).append("\"><div style=\"font-size:26px;font-weight:700;line-height:1.2;color:").append(color).append("\">").append(h(n))
                        .append("</div><div style=\"font-size:12.5px;color:").append(MUTED).append(";margin-top:4px\">").append(h(str(k, "l"))).append("</div></div></td>");
                i++;
            }
            b.append("</tr></table>");
        }

        String chatNote = "";
        if (!str(d, "pillChat").isEmpty() || !str(d, "notaChat").isEmpty()) {
            chatNote = "<div style=\"margin-top:14px\">" + (str(d, "pillChat").isEmpty() ? "" : pillHtml(str(d, "pillChat"), pill("Pendente")))
                    + "<span>" + h(str(d, "notaChat")) + "</span></div>";
        }
        b.append(twoCols(
                "<div style=\"" + CARD + "\">" + title(str(d, "hConsumo"), INK) + hoursTable("", arr(d, "consumo"), "Demanda", str(d, "totLbl1")) + "</div>",
                "<div style=\"" + CARD + "\">" + title(str(d, "hChat"), INK) + hoursTable("", arr(d, "chat"), "Alteração", str(d, "totLbl2")) + chatNote + "</div>"));

        JsonArray acoes = arr(d, "acoes");
        if (acoes.size() > 0) {
            b.append("<div style=\"").append(CARD).append("margin-bottom:16px\">").append(title(str(d, "hAcoes"), INK))
                    .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:13.5px\"><tr>");
            for (String c : new String[] {"Ação", "Responsável", "Status"}) {
                b.append("<th align=\"left\" style=\"font-size:13px;color:").append(MUTED).append(";padding:4px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(c).append("</th>");
            }
            b.append("</tr>");
            for (JsonElement e : acoes) {
                JsonObject r = e.getAsJsonObject();
                b.append("<tr><td valign=\"top\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(h(str(r, "a")))
                        .append("</td><td valign=\"top\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(h(str(r, "r")))
                        .append("</td><td valign=\"top\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append(";width:42%\">")
                        .append(pillHtml(str(r, "s"), pill(str(r, "s")))).append(h(str(r, "t"))).append("</td></tr>");
            }
            b.append("</table></div>");
        }

        String prevNote = str(d, "notaPrev").isEmpty() ? "" : "<p style=\"font-size:12.5px;color:" + MUTED + ";margin:12px 0 0\">" + h(str(d, "notaPrev")) + "</p>";
        b.append(twoCols(
                "<div style=\"" + CARD + "\">" + title(str(d, "hAnd"), INK) + list(arr(d, "andamento")) + "</div>",
                "<div style=\"" + CARD + "\">" + title(str(d, "hPrev"), INK) + list(arr(d, "previsao")) + prevNote + "</div>"));

        if (arr(d, "defs").size() > 0) {
            b.append("<div style=\"").append(CARD).append("border-left:4px solid ").append(RED).append(";margin-bottom:16px\">").append(title(str(d, "hDefs"), INK))
                    .append(list(arr(d, "defs"))).append("</div>");
        }
        if (!str(d, "rodape").isEmpty()) b.append("<div style=\"font-size:12.5px;color:").append(MUTED).append(";margin-top:4px\">").append(h(str(d, "rodape"))).append("</div>");
        return b.append("</div></body></html>").toString();
    }
}
