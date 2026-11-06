package br.com.sysattack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Relatório tradicional de projeto de escopo fechado, preenchido na tela (sem Claude): visão do projeto,
 * demandas com os apontamentos relacionados e financeiro. Estilos inline (navegador, PDF e e-mail); todo texto é escapado.
 */
final class ScopeReportHtml {
    private ScopeReportHtml() {}

    private static final String INK = "#1d1d1b", MUTED = "#6b6b66", LINE = "#e6e4de", YELLOW = "#e8b400", GREEN = "#2e8b57", RED = "#b8332f";
    private static final String CARD = "background:#fff;border:1px solid " + LINE + ";border-radius:10px;padding:16px 18px;";

    private static String h(String s) { return SpotReportHtml.h(s); }

    private static String str(JsonObject o, String k, int max) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        String v = e.getAsString().trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray();
    }

    /** Mantém só os campos conhecidos, com limite de tamanho. */
    static JsonObject normalize(JsonObject in) {
        JsonObject o = new JsonObject();
        o.addProperty("escopo", true);
        for (String k : new String[] {"titulo", "meta", "destaques", "rodape"}) o.addProperty(k, str(in, k, 1000));
        String[][] shapes = {{"kpis", "n", "l"}, {"info", "t", "d"}, {"fin", "n", "l"}, {"demandas", "d", "s", "e", "ap"}, {"notas", "n", "e", "v", "a", "s", "d"}};
        for (String[] sh : shapes) {
            JsonArray src = arr(in, sh[0]), out = new JsonArray();
            if (src.size() > 80) throw new ApiServlet.ApiException(400, "Relatório com linhas demais em “" + sh[0] + "”.");
            for (JsonElement e : src) {
                if (!e.isJsonObject()) continue;
                JsonObject r = new JsonObject();
                for (int i = 1; i < sh.length; i++) r.addProperty(sh[i], str(e.getAsJsonObject(), sh[i], sh[i].equals("ap") ? 5000 : 1000));
                out.add(r);
            }
            o.add(sh[0], out);
        }
        return o;
    }

    private static String color(String label) {
        String l = label == null ? "" : label.toLowerCase();
        if (l.startsWith("conclu") || l.startsWith("entregue") || l.startsWith("paga")) return GREEN;
        if (l.startsWith("cancel") || l.startsWith("bloque")) return RED;
        if (l.contains("andamento") || l.contains("aprovad") || l.contains("homolog") || l.startsWith("emitida")) return "#3b6fd4";
        return "#b7791f";
    }

    private static String pill(String text) {
        if (text.isEmpty()) return "";
        String c = color(text);
        return "<span style=\"display:inline-block;font-size:12px;font-weight:500;padding:1px 10px;border-radius:999px;border:1px solid " + c + ";color:" + c
                + ";margin-right:6px\">" + h(text) + "</span>";
    }

    private static String title(String t) {
        return "<div style=\"margin:0 0 12px;font-size:12.5px;font-weight:700;letter-spacing:.07em;text-transform:uppercase\">" + h(t) + "</div>";
    }

    /** Cartões em linhas de até 5. */
    private static String cards(JsonArray kpis) {
        StringBuilder b = new StringBuilder();
        for (int start = 0; start < kpis.size(); start += 5) {
            int end = Math.min(start + 5, kpis.size());
            b.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:12px;table-layout:fixed\"><tr>");
            for (int i = start; i < start + 5; i++) {
                b.append("<td valign=\"top\" style=\"padding:0 ").append(i % 5 == 4 ? 0 : 6).append("px 0 ").append(i % 5 == 0 ? 0 : 6).append("px\">");
                if (i < end) {
                    JsonObject k = kpis.get(i).getAsJsonObject();
                    b.append("<div style=\"").append(CARD).append("padding:12px 14px;border-top:3px solid ").append(i == 0 ? YELLOW : INK).append("\"><div style=\"font-size:11.5px;color:")
                            .append(MUTED).append(";text-transform:uppercase;letter-spacing:.05em\">").append(h(str(k, "l", 1000))).append("</div><div style=\"font-size:20px;font-weight:700;margin-top:4px\">")
                            .append(h(str(k, "n", 1000))).append("</div></div>");
                }
                b.append("</td>");
            }
            b.append("</tr></table>");
        }
        return b.toString();
    }

    static String render(JsonObject d) {
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>")
                .append(h(str(d, "titulo", 200))).append("</title></head><body style=\"margin:0;background:#f6f6f4;color:").append(INK)
                .append(";font:14.5px/1.5 Inter,Arial,sans-serif\"><div style=\"max-width:1060px;margin:0 auto;padding:22px 18px 40px\">");
        b.append("<div style=\"border-left:4px solid ").append(YELLOW).append(";padding:2px 0 2px 16px;margin-bottom:16px\">")
                .append("<h1 style=\"margin:0 0 4px;font-size:25px;font-weight:700;line-height:1.25\">").append(h(str(d, "titulo", 200))).append("</h1>")
                .append("<div style=\"color:").append(MUTED).append(";font-size:14px\">").append(h(str(d, "meta", 1000))).append("</div>");
        if (!str(d, "destaques", 1000).isEmpty()) {
            b.append("<div style=\"color:").append(MUTED).append(";font-size:14px\"><b style=\"color:").append(INK).append("\">Destaques:</b> ").append(h(str(d, "destaques", 1000))).append("</div>");
        }
        b.append("</div>");

        // visão do projeto
        b.append("<div style=\"").append(CARD).append("margin-bottom:16px\">").append(title("Visão do projeto")).append(cards(arr(d, "kpis")));
        JsonArray info = arr(d, "info");
        if (info.size() > 0) {
            b.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:13.5px;margin-top:6px\">");
            for (JsonElement e : info) {
                JsonObject r = e.getAsJsonObject();
                b.append("<tr><td valign=\"top\" style=\"width:190px;padding:7px 8px 7px 0;border-top:1px solid ").append(LINE).append(";color:").append(MUTED)
                        .append(";font-weight:600\">").append(h(str(r, "t", 1000))).append("</td><td valign=\"top\" style=\"padding:7px 0;border-top:1px solid ").append(LINE).append("\">")
                        .append(h(str(r, "d", 1000))).append("</td></tr>");
            }
            b.append("</table>");
        }
        b.append("</div>");

        // demandas e apontamentos relacionados
        JsonArray dem = arr(d, "demandas");
        if (dem.size() > 0) {
            b.append("<div style=\"").append(CARD).append("margin-bottom:16px\">").append(title("Demandas e apontamentos"));
            int n = 0;
            for (JsonElement e : dem) {
                JsonObject r = e.getAsJsonObject();
                b.append("<div style=\"padding:10px 0;").append(n++ == 0 ? "" : "border-top:1px solid " + LINE + ";").append("\"><div><b>").append(h(str(r, "d", 1000))).append("</b> &nbsp;")
                        .append(pill(str(r, "s", 1000))).append(pill(str(r, "e", 1000))).append("</div>");
                String ap = str(r, "ap", 5000);
                if (!ap.isEmpty()) {
                    b.append("<ul style=\"margin:6px 0 0;padding-left:20px;font-size:13.5px;color:").append(MUTED).append("\">");
                    for (String line : ap.split("\\r?\\n")) if (!line.isBlank()) b.append("<li style=\"padding:1px 0\">").append(h(line.trim())).append("</li>");
                    b.append("</ul>");
                }
                b.append("</div>");
            }
            b.append("</div>");
        }

        // financeiro
        JsonArray fin = arr(d, "fin"), notas = arr(d, "notas");
        if (fin.size() > 0 || notas.size() > 0) {
            b.append("<div style=\"").append(CARD).append("margin-bottom:16px\">").append(title("Financeiro")).append(cards(fin));
            if (notas.size() > 0) {
                b.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:13.5px\"><tr>");
                String[] heads = {"Nota", "Emissão", "Vencimento", "Valor", "Situação"};
                for (int i = 0; i < heads.length; i++) {
                    b.append("<th align=\"").append(i == 3 ? "right" : "left").append("\" style=\"font-size:13px;color:").append(MUTED).append(";padding:4px 8px 8px 0;border-bottom:1px solid ").append(LINE).append("\">").append(heads[i]).append("</th>");
                }
                b.append("</tr>");
                for (JsonElement e : notas) {
                    JsonObject r = e.getAsJsonObject();
                    String td = "<td valign=\"top\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid " + LINE + "\">";
                    b.append("<tr>").append(td).append("<b>").append(h(str(r, "n", 1000))).append("</b>");
                    if (!str(r, "d", 1000).isEmpty()) b.append("<div style=\"font-size:12.5px;color:").append(MUTED).append("\">").append(h(str(r, "d", 1000))).append("</div>");
                    b.append("</td>").append(td).append(h(str(r, "e", 1000))).append("</td>").append(td).append(h(str(r, "v", 1000)))
                            .append("</td><td valign=\"top\" align=\"right\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid ").append(LINE).append(";white-space:nowrap\">")
                            .append(h(str(r, "a", 1000))).append("</td>").append(td).append(pill(str(r, "s", 1000))).append("</td></tr>");
                }
                b.append("</table>");
            }
            b.append("</div>");
        }
        if (!str(d, "rodape", 1000).isEmpty()) b.append("<div style=\"font-size:12.5px;color:").append(MUTED).append(";margin-top:4px\">").append(h(str(d, "rodape", 1000))).append("</div>");
        return b.append("</div></body></html>").toString();
    }
}
