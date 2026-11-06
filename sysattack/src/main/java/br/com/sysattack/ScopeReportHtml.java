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

    private static final String INK = "#1a1a1a", MUTED = "#5f5f66", LINE = "#dddde0", SOFT = "#f6f6f7", ORANGE = "#f45200";
    private static final String FONT = "'Segoe UI',Inter,-apple-system,Roboto,Helvetica,Arial,sans-serif";
    private static final String CARD = "background:#fff;border:1px solid " + LINE + ";border-radius:6px;";

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

    /** {texto, fundo} da etiqueta conforme o significado do status. */
    private static String[] tone(String label) {
        String l = label == null ? "" : label.toLowerCase();
        if (l.startsWith("conclu") || l.startsWith("entregue") || l.startsWith("paga") || l.startsWith("ativo")) return new String[] {"#17653a", "#e3f2e9"};
        if (l.startsWith("cancel") || l.startsWith("bloque")) return new String[] {"#a3211b", "#fbe8e6"};
        if (l.contains("andamento") || l.contains("aprovad") || l.contains("homolog") || l.startsWith("emitida")) return new String[] {"#1f4fa3", "#e5edfa"};
        if (l.startsWith("sem ") || l.contains("espera") || l.startsWith("encerr") || l.startsWith("paus")) return new String[] {"#6a5200", "#fbf1d3"};
        return new String[] {"#444", "#ececee"};
    }

    private static String pill(String text) {
        if (text.isEmpty()) return "";
        String[] c = tone(text);
        return "<span style=\"display:inline-block;font-size:12px;font-weight:600;line-height:1;padding:5px 10px;border-radius:999px;color:" + c[0] + ";background:" + c[1]
                + ";white-space:nowrap\">" + h(text) + "</span>";
    }

    private static String section(String title, String note) {
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 14px\"><tr><td style=\"border-left:4px solid " + ORANGE + ";padding:1px 0 1px 10px;font-size:17px;font-weight:700;line-height:1.2\">"
                + h(title) + "</td><td align=\"right\" style=\"font-size:12.5px;color:" + MUTED + "\">" + h(note) + "</td></tr></table>";
    }

    private static int pct(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,3})\\s*%\\s*$").matcher(v);
        return m.matches() ? Math.min(100, Integer.parseInt(m.group(1))) : -1;
    }

    /** Cartões em linhas de até 5; valores em porcentagem ganham barra de progresso. */
    private static String cards(JsonArray kpis) {
        StringBuilder b = new StringBuilder();
        for (int start = 0; start < kpis.size(); start += 5) {
            int end = Math.min(start + 5, kpis.size());
            b.append("<table class=\"cards\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:10px;table-layout:fixed\"><tr>");
            for (int i = start; i < start + 5; i++) {
                b.append("<td valign=\"top\" style=\"padding:0 ").append(i % 5 == 4 ? 0 : 5).append("px 0 ").append(i % 5 == 0 ? 0 : 5).append("px\">");
                if (i < end) {
                    JsonObject k = kpis.get(i).getAsJsonObject();
                    String n = str(k, "n", 1000);
                    int p = pct(n);
                    b.append("<div style=\"background:").append(SOFT).append(";border-radius:6px;padding:12px 14px;min-height:62px\"><div style=\"font-size:12.5px;color:").append(MUTED)
                            .append(";line-height:1.3\">").append(h(str(k, "l", 1000))).append("</div><div style=\"font-size:" + (n.length() > 13 ? 16 : n.length() > 11 ? 18 : 21) + "px;font-weight:700;margin-top:5px;font-variant-numeric:tabular-nums;line-height:1.2;word-break:break-word\">")
                            .append(h(n)).append("</div>");
                    if (p >= 0) {
                        b.append("<div style=\"height:6px;background:#dcdce0;border-radius:3px;margin-top:9px\"><div style=\"height:6px;width:").append(p).append("%;background:")
                                .append(p >= 100 ? "#17653a" : ORANGE).append(";border-radius:3px\"></div></div>");
                    }
                    b.append("</div>");
                }
                b.append("</td>");
            }
            b.append("</tr></table>");
        }
        return b.toString();
    }

    private static final java.util.regex.Pattern AP_LINE = java.util.regex.Pattern.compile("^(\\d{2}/\\d{2}/\\d{4})\\s*·\\s*(.+)$");

    static String render(JsonObject d) {
        StringBuilder b = new StringBuilder();
        String situacao = "";
        for (JsonElement e : arr(d, "info")) if ("Situação".equalsIgnoreCase(str(e.getAsJsonObject(), "t", 100))) situacao = str(e.getAsJsonObject(), "d", 100);
        b.append("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>")
                .append(h(str(d, "titulo", 200))).append("</title><style>")
                .append("@media print{body{background:#fff!important}.sheet{padding:0!important}.keep{page-break-inside:avoid}*{-webkit-print-color-adjust:exact;print-color-adjust:exact}}")
                .append("@media(max-width:720px){.cards td{display:block!important;width:100%!important;padding:0 0 8px!important}.grid td{display:block!important;width:100%!important;padding-right:0!important}}")
                .append("</style></head><body style=\"margin:0;background:#f2f2f2;color:").append(INK).append(";font:14.5px/1.5 ").append(FONT).append("\"><div class=\"sheet\" style=\"max-width:1000px;margin:0 auto;padding:24px 18px 44px\">");

        // cabeçalho
        b.append("<div class=\"keep\" style=\"").append(CARD).append("border-top:6px solid ").append(ORANGE).append(";padding:22px 26px 20px;margin-bottom:14px\">")
                .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td valign=\"top\"><h1 style=\"margin:0;font-size:28px;font-weight:700;line-height:1.2;letter-spacing:-.01em\">")
                .append(h(str(d, "titulo", 200))).append("</h1></td>");
        if (!situacao.isEmpty()) b.append("<td valign=\"top\" align=\"right\" style=\"padding-left:14px\">").append(pill(situacao)).append("</td>");
        b.append("</tr></table><div style=\"color:").append(MUTED).append(";font-size:14px;margin-top:6px\">").append(h(str(d, "meta", 1000))).append("</div>");
        if (!str(d, "destaques", 1000).isEmpty()) {
            b.append("<div style=\"margin-top:14px;padding:11px 14px;background:").append(SOFT).append(";border-left:3px solid ").append(ORANGE).append(";font-size:14px\"><b>Destaques</b><br>")
                    .append(h(str(d, "destaques", 1000))).append("</div>");
        }
        b.append("</div>");

        // visão do projeto
        b.append("<div class=\"keep\" style=\"").append(CARD).append("padding:22px 26px;margin-bottom:14px\">").append(section("Visão do projeto", "")).append(cards(arr(d, "kpis")));
        JsonArray info = arr(d, "info");
        if (info.size() > 0) {
            b.append("<table class=\"grid\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:14px;border-top:1px solid ").append(LINE).append("\">");
            boolean open = false;
            int col = 0;
            for (JsonElement e : info) {
                JsonObject r = e.getAsJsonObject();
                String v = str(r, "d", 1000);
                boolean wide = v.length() > 46 || "Descrição".equalsIgnoreCase(str(r, "t", 100));
                if (wide && open) { b.append("</tr>"); open = false; col = 0; }
                if (!open) { b.append("<tr>"); open = true; col = 0; }
                b.append("<td valign=\"top\" width=\"").append(wide ? "100" : "33").append("%\" ").append(wide ? "colspan=\"3\" " : "").append("style=\"padding:12px 16px 4px 0\"><div style=\"font-size:12.5px;color:")
                        .append(MUTED).append("\">").append(h(str(r, "t", 1000))).append("</div><div style=\"font-size:14.5px;font-weight:600;margin-top:1px\">").append(h(v)).append("</div></td>");
                col += wide ? 3 : 1;
                if (wide || col >= 3) { b.append("</tr>"); open = false; col = 0; }
            }
            if (open) b.append("</tr>");
            b.append("</table>");
        }
        b.append("</div>");

        // demandas e apontamentos relacionados
        JsonArray dem = arr(d, "demandas");
        if (dem.size() > 0) {
            int entregues = 0;
            for (JsonElement e : dem) if (str(e.getAsJsonObject(), "e", 100).toLowerCase().startsWith("entregue")) entregues++;
            b.append("<div style=\"").append(CARD).append("padding:22px 26px 14px;margin-bottom:14px\">")
                    .append(section("Demandas e apontamentos", dem.size() + " demanda(s) · " + entregues + " entregue(s)"));
            for (JsonElement e : dem) {
                JsonObject r = e.getAsJsonObject();
                b.append("<table class=\"keep\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:12px;border:1px solid ").append(LINE).append(";border-radius:6px;border-collapse:separate;overflow:hidden\">")
                        .append("<tr><td style=\"background:").append(SOFT).append(";padding:10px 14px;font-weight:700;font-size:14.5px\">").append(h(str(r, "d", 1000)))
                        .append("</td><td align=\"right\" style=\"background:").append(SOFT).append(";padding:10px 14px;white-space:nowrap\">").append(pill(str(r, "s", 1000))).append("&nbsp; ").append(pill(str(r, "e", 1000))).append("</td></tr>");
                String ap = str(r, "ap", 5000);
                boolean any = false;
                for (String line : ap.split("\\r?\\n")) {
                    if (line.isBlank()) continue;
                    any = true;
                    java.util.regex.Matcher m = AP_LINE.matcher(line.trim());
                    b.append("<tr><td colspan=\"2\" style=\"padding:8px 14px;border-top:1px solid ").append(LINE).append(";font-size:13.5px\">");
                    if (m.matches()) {
                        b.append("<span style=\"display:inline-block;width:92px;color:").append(MUTED).append(";font-variant-numeric:tabular-nums\">").append(h(m.group(1))).append("</span>").append(h(m.group(2)));
                    } else b.append(h(line.trim()));
                    b.append("</td></tr>");
                }
                if (!any) b.append("<tr><td colspan=\"2\" style=\"padding:8px 14px;border-top:1px solid ").append(LINE).append(";font-size:13px;color:").append(MUTED).append("\">Nenhum apontamento vinculado.</td></tr>");
                b.append("</table>");
            }
            b.append("</div>");
        }

        // financeiro
        JsonArray fin = arr(d, "fin"), notas = arr(d, "notas");
        if (fin.size() > 0 || notas.size() > 0) {
            b.append("<div class=\"keep\" style=\"").append(CARD).append("padding:22px 26px 18px;margin-bottom:14px\">").append(section("Financeiro", notas.size() + " nota(s) lançada(s)")).append(cards(fin));
            if (notas.size() > 0) {
                b.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:13.5px;margin-top:8px\"><tr>");
                String[] heads = {"Nota", "Emissão", "Vencimento", "Valor", "Situação"};
                for (int i = 0; i < heads.length; i++) {
                    b.append("<th align=\"").append(i == 3 ? "right" : "left").append("\" style=\"font-size:12.5px;font-weight:600;color:").append(MUTED).append(";padding:8px 10px;background:").append(SOFT).append("\">").append(heads[i]).append("</th>");
                }
                b.append("</tr>");
                for (JsonElement e : notas) {
                    JsonObject r = e.getAsJsonObject();
                    String td = "<td valign=\"top\" style=\"padding:9px 10px;border-bottom:1px solid " + LINE + ";font-variant-numeric:tabular-nums\">";
                    b.append("<tr>").append(td).append("<b>").append(h(str(r, "n", 1000))).append("</b>");
                    if (!str(r, "d", 1000).isEmpty()) b.append("<div style=\"font-size:12.5px;color:").append(MUTED).append("\">").append(h(str(r, "d", 1000))).append("</div>");
                    b.append("</td>").append(td).append(h(str(r, "e", 1000))).append("</td>").append(td).append(str(r, "v", 1000).isEmpty() ? "—" : h(str(r, "v", 1000)))
                            .append("</td><td valign=\"top\" align=\"right\" style=\"padding:9px 10px;border-bottom:1px solid ").append(LINE).append(";white-space:nowrap;font-weight:600;font-variant-numeric:tabular-nums\">")
                            .append(h(str(r, "a", 1000))).append("</td>").append(td).append(pill(str(r, "s", 1000))).append("</td></tr>");
                }
                b.append("</table>");
            }
            b.append("</div>");
        }
        b.append("<div style=\"font-size:12.5px;color:").append(MUTED).append(";padding:2px 4px\">").append(h(str(d, "rodape", 1000))).append("</div>");
        return b.append("</div></body></html>").toString();
    }
}
