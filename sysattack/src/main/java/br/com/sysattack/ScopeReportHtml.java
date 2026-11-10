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

    // Compatível com o Outlook (motor do Word): só tabelas, cor de fundo em bgcolor, borda e espaçamento em <td>,
    // sem inline-block, sem largura em <span>, sem border-radius obrigatório (nos demais clientes ele só suaviza os cantos).

    private static String[] tone(String label) {
        String l = label == null ? "" : label.toLowerCase();
        if (l.startsWith("conclu") || l.startsWith("entregue") || l.startsWith("paga") || l.startsWith("ativo")) return new String[] {"#17653a", "#e3f2e9"};
        if (l.startsWith("cancel") || l.startsWith("bloque")) return new String[] {"#a3211b", "#fbe8e6"};
        if (l.contains("andamento") || l.contains("aprovad") || l.contains("homolog") || l.startsWith("emitida")) return new String[] {"#1f4fa3", "#e5edfa"};
        if (l.startsWith("sem ") || l.contains("espera") || l.startsWith("encerr") || l.startsWith("paus")) return new String[] {"#6a5200", "#fbf1d3"};
        return new String[] {"#444444", "#ececee"};
    }

    private static final String T0 = " role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"";

    private static String gap(int px) {
        return "<table" + T0 + " width=\"100%\"><tr><td height=\"" + px + "\" style=\"height:" + px + "px;font-size:1px;line-height:1px\">&nbsp;</td></tr></table>";
    }

    /** Etiqueta de status (célula com fundo colorido). */
    private static String pillCell(String text) {
        String[] c = tone(text);
        return "<td bgcolor=\"" + c[1] + "\" style=\"background-color:" + c[1] + ";padding:4px 10px;font-size:12px;font-weight:bold;color:" + c[0] + ";white-space:nowrap;border-radius:10px\">" + h(text) + "</td>";
    }

    private static String pills(String... texts) {
        StringBuilder b = new StringBuilder("<table" + T0 + " align=\"right\"><tr>");
        boolean first = true;
        for (String t : texts) {
            if (t.isEmpty()) continue;
            if (!first) b.append("<td width=\"6\" style=\"font-size:1px\">&nbsp;</td>");
            b.append(pillCell(t));
            first = false;
        }
        return first ? "" : b.append("</tr></table>").toString();
    }

    private static String card(String inner, String topBar) {
        return "<table" + T0 + " width=\"100%\">"
                + (topBar == null ? "" : "<tr><td height=\"6\" bgcolor=\"" + topBar + "\" style=\"background-color:" + topBar + ";height:6px;font-size:1px;line-height:1px;border-radius:6px 6px 0 0\">&nbsp;</td></tr>")
                + "<tr><td bgcolor=\"#ffffff\" style=\"background-color:#ffffff;border:1px solid " + LINE + ";" + (topBar == null ? "" : "border-top:0;") + "padding:22px 26px\">" + inner + "</td></tr></table>" + gap(14);
    }

    private static String section(String title, String note) {
        return "<table" + T0 + " width=\"100%\"><tr><td width=\"4\" bgcolor=\"" + ORANGE + "\" style=\"background-color:" + ORANGE + ";font-size:1px\">&nbsp;</td>"
                + "<td style=\"padding:1px 0 1px 10px;font-size:17px;font-weight:bold;color:" + INK + "\">" + h(title) + "</td>"
                + "<td align=\"right\" style=\"font-size:12.5px;color:" + MUTED + "\">" + h(note) + "</td></tr></table>" + gap(14);
    }

    private static int pct(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,3})\\s*%\\s*$").matcher(v);
        return m.matches() ? Math.min(100, Integer.parseInt(m.group(1))) : -1;
    }

    private static String bar(int p) {
        String fill = p >= 100 ? "#17653a" : ORANGE;
        String cell = "<td height=\"6\" bgcolor=\"%s\" width=\"%d%%\" style=\"background-color:%s;height:6px;font-size:1px;line-height:1px\">&nbsp;</td>";
        return "<table" + T0 + " width=\"100%\"><tr>" + (p > 0 ? String.format(cell, fill, p, fill) : "") + (p < 100 ? String.format(cell, "#dcdce0", 100 - p, "#dcdce0") : "") + "</tr></table>";
    }

    /** Cartões em linhas de até 5; valores em porcentagem ganham barra de progresso. */
    private static String cards(JsonArray kpis) {
        StringBuilder b = new StringBuilder();
        for (int start = 0; start < kpis.size(); start += 5) {
            int end = Math.min(start + 5, kpis.size());
            b.append("<table class=\"cards\"").append(T0).append(" width=\"100%\"><tr>");
            for (int i = start; i < start + 5; i++) {
                if (i > start) b.append("<td class=\"cgap\" width=\"10\" style=\"font-size:1px\">&nbsp;</td>");
                b.append("<td class=\"card\" width=\"19%\" valign=\"top\">");
                if (i < end) {
                    JsonObject k = kpis.get(i).getAsJsonObject();
                    String n = str(k, "n", 1000);
                    int p = pct(n);
                    int size = n.length() > 13 ? 15 : n.length() > 11 ? 17 : 20;
                    b.append("<table").append(T0).append(" width=\"100%\"><tr><td bgcolor=\"").append(SOFT).append("\" style=\"background-color:").append(SOFT)
                            .append(";padding:12px 14px;border-radius:6px\"><table").append(T0).append(" width=\"100%\"><tr><td style=\"font-size:12.5px;color:").append(MUTED).append("\">")
                            .append(h(str(k, "l", 1000))).append("</td></tr><tr><td style=\"padding-top:4px;font-size:").append(size).append("px;font-weight:bold;color:").append(INK).append("\">")
                            .append(h(n)).append("</td></tr>");
                    if (p >= 0) b.append("<tr><td style=\"padding-top:9px\">").append(bar(p)).append("</td></tr>");
                    b.append("</table></td></tr></table>");
                } else b.append("&nbsp;");
                b.append("</td>");
            }
            b.append("</tr></table>").append(gap(10));
        }
        return b.toString();
    }

    private static final java.util.regex.Pattern AP_LINE = java.util.regex.Pattern.compile("^(\\d{2}/\\d{2}/\\d{4})\\s*·\\s*(.+)$");

    static String render(JsonObject d) { return render(d, false); }

    static String render(JsonObject d, boolean email) {
        StringBuilder b = new StringBuilder();
        String situacao = "";
        for (JsonElement e : arr(d, "info")) if ("Situação".equalsIgnoreCase(str(e.getAsJsonObject(), "t", 100))) situacao = str(e.getAsJsonObject(), "d", 100);
        b.append("<!DOCTYPE html><html lang=\"pt-BR\" xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><meta name=\"x-apple-disable-message-reformatting\">")
                .append("<!--[if gte mso 9]><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->")
                .append("<title>").append(h(str(d, "titulo", 200))).append("</title><style>")
                .append("body,table,td,th,p{font-family:'Segoe UI',Arial,Helvetica,sans-serif;color:").append(INK).append("}table{border-collapse:collapse;mso-table-lspace:0;mso-table-rspace:0}td{mso-line-height-rule:exactly;line-height:1.4}")
                .append("@media print{body{background:#fff!important}*{-webkit-print-color-adjust:exact;print-color-adjust:exact}.keep{page-break-inside:avoid}}")
                .append("@media(max-width:720px){.cards td.card{display:block!important;width:100%!important;padding-bottom:8px}.cards td.cgap{display:none!important}.grid td{display:block!important;width:100%!important}}")
                .append("</style></head><body bgcolor=\"#f2f2f2\" style=\"margin:0;padding:0;background-color:#f2f2f2;font-size:14.5px\">")
                .append("<table").append(T0).append(" width=\"100%\" bgcolor=\"#f2f2f2\" style=\"background-color:#f2f2f2\"><tr><td align=\"center\" style=\"padding:22px 12px 40px\">")
                .append("<!--[if mso]><table role=\"presentation\" width=\"1000\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->")
                .append("<table").append(T0).append(" width=\"100%\" style=\"max-width:1000px\"><tr><td>");

        b.append(ReportLogo.header(email, "Relatório do projeto", ""));
        // cabeçalho
        StringBuilder head = new StringBuilder("<table").append(T0).append(" width=\"100%\"><tr><td style=\"font-size:27px;font-weight:bold;line-height:1.2;color:").append(INK).append("\">")
                .append(h(str(d, "titulo", 200))).append("</td>");
        if (!situacao.isEmpty()) head.append("<td align=\"right\" valign=\"top\" width=\"90\">").append(pills(situacao)).append("</td>");
        head.append("</tr><tr><td colspan=\"2\" style=\"padding-top:6px;font-size:14px;color:").append(MUTED).append("\">").append(h(str(d, "meta", 1000))).append("</td></tr></table>");
        if (!str(d, "destaques", 1000).isEmpty()) {
            head.append(gap(14)).append("<table").append(T0).append(" width=\"100%\"><tr><td width=\"3\" bgcolor=\"").append(ORANGE).append("\" style=\"background-color:").append(ORANGE)
                    .append(";font-size:1px\">&nbsp;</td><td bgcolor=\"").append(SOFT).append("\" style=\"background-color:").append(SOFT).append(";padding:11px 14px;font-size:14px;color:").append(INK)
                    .append("\"><b>Destaques</b><br>").append(h(str(d, "destaques", 1000))).append("</td></tr></table>");
        }
        b.append("<div class=\"keep\">").append(card(head.toString(), ORANGE)).append("</div>");

        // visão do projeto
        StringBuilder vis = new StringBuilder(section("Visão do projeto", "")).append(cards(arr(d, "kpis")));
        JsonArray info = arr(d, "info");
        if (info.size() > 0) {
            vis.append(gap(4)).append("<table class=\"grid\"").append(T0).append(" width=\"100%\">");
            boolean open = false;
            int col = 0;
            boolean firstRow = true;
            for (JsonElement e : info) {
                JsonObject r = e.getAsJsonObject();
                String v = str(r, "d", 1000);
                boolean wide = v.length() > 46 || "Descrição".equalsIgnoreCase(str(r, "t", 100));
                if (wide && open) { vis.append("</tr>"); open = false; col = 0; firstRow = false; }
                if (!open) { vis.append("<tr>"); open = true; col = 0; }
                vis.append("<td valign=\"top\" width=\"").append(wide ? "100" : "33").append("%\" ").append(wide ? "colspan=\"3\" " : "").append("style=\"padding:12px 16px 6px 0;")
                        .append(firstRow ? "border-top:1px solid " + LINE + ";" : "").append("\"><table").append(T0).append("><tr><td style=\"font-size:12.5px;color:").append(MUTED).append("\">")
                        .append(h(str(r, "t", 1000))).append("</td></tr><tr><td style=\"font-size:14.5px;font-weight:bold;color:").append(INK).append("\">").append(h(v)).append("</td></tr></table></td>");
                col += wide ? 3 : 1;
                if (wide || col >= 3) { vis.append("</tr>"); open = false; col = 0; firstRow = false; }
            }
            if (open) vis.append("</tr>");
            vis.append("</table>");
        }
        b.append(card(vis.toString(), null));

        // demandas e apontamentos relacionados
        JsonArray dem = arr(d, "demandas");
        if (dem.size() > 0) {
            int entregues = 0;
            for (JsonElement e : dem) if (str(e.getAsJsonObject(), "e", 100).toLowerCase().startsWith("entregue")) entregues++;
            StringBuilder ds = new StringBuilder(section("Demandas e apontamentos", dem.size() + " demanda(s) · " + entregues + " entregue(s)"));
            for (JsonElement e : dem) {
                JsonObject r = e.getAsJsonObject();
                ds.append("<table class=\"keep\"").append(T0).append(" width=\"100%\" style=\"border:1px solid ").append(LINE).append("\"><tr>")
                        .append("<td colspan=\"2\" bgcolor=\"").append(SOFT).append("\" style=\"background-color:").append(SOFT).append(";padding:10px 14px;font-size:14.5px;font-weight:bold;color:").append(INK).append("\">")
                        .append(h(str(r, "d", 1000))).append("</td><td align=\"right\" bgcolor=\"").append(SOFT).append("\" style=\"background-color:").append(SOFT).append(";padding:8px 14px\">")
                        .append(pills(str(r, "s", 1000), str(r, "e", 1000))).append("</td></tr>");
                boolean any = false;
                for (String line : str(r, "ap", 5000).split("\\r?\\n")) {
                    if (line.isBlank()) continue;
                    any = true;
                    java.util.regex.Matcher m = AP_LINE.matcher(line.trim());
                    if (m.matches()) {
                        ds.append("<tr><td width=\"100\" valign=\"top\" style=\"padding:8px 8px 8px 14px;border-top:1px solid ").append(LINE).append(";font-size:13.5px;color:").append(MUTED).append(";white-space:nowrap\">")
                                .append(h(m.group(1))).append("</td><td colspan=\"2\" valign=\"top\" style=\"padding:8px 14px 8px 0;border-top:1px solid ").append(LINE).append(";font-size:13.5px;color:").append(INK).append("\">")
                                .append(h(m.group(2))).append("</td></tr>");
                    } else {
                        ds.append("<tr><td colspan=\"3\" style=\"padding:8px 14px;border-top:1px solid ").append(LINE).append(";font-size:13.5px;color:").append(INK).append("\">").append(h(line.trim())).append("</td></tr>");
                    }
                }
                if (!any) ds.append("<tr><td colspan=\"3\" style=\"padding:8px 14px;border-top:1px solid ").append(LINE).append(";font-size:13px;color:").append(MUTED).append("\">Nenhum apontamento vinculado.</td></tr>");
                ds.append("</table>").append(gap(12));
            }
            b.append(card(ds.toString(), null));
        }

        // financeiro
        JsonArray fin = arr(d, "fin"), notas = arr(d, "notas");
        if (fin.size() > 0 || notas.size() > 0) {
            StringBuilder fs = new StringBuilder(section("Financeiro", notas.size() + " nota(s) lançada(s)")).append(cards(fin));
            if (notas.size() > 0) {
                fs.append("<table").append(T0).append(" width=\"100%\"><tr>");
                String[] heads = {"Nota", "Emissão", "Vencimento", "Valor", "Situação"};
                for (int i = 0; i < heads.length; i++) {
                    fs.append("<td align=\"").append(i == 3 ? "right" : "left").append("\" bgcolor=\"").append(SOFT).append("\" style=\"background-color:").append(SOFT)
                            .append(";padding:8px 10px;font-size:12.5px;font-weight:bold;color:").append(MUTED).append("\">").append(heads[i]).append("</td>");
                }
                fs.append("</tr>");
                for (JsonElement e : notas) {
                    JsonObject r = e.getAsJsonObject();
                    String td = "<td valign=\"top\" style=\"padding:9px 10px;border-bottom:1px solid " + LINE + ";font-size:13.5px;color:" + INK + "\">";
                    fs.append("<tr>").append(td).append("<b>").append(h(str(r, "n", 1000))).append("</b>");
                    if (!str(r, "d", 1000).isEmpty()) fs.append("<br><span style=\"font-size:12.5px;color:").append(MUTED).append("\">").append(h(str(r, "d", 1000))).append("</span>");
                    fs.append("</td>").append(td).append(h(str(r, "e", 1000))).append("</td>").append(td).append(str(r, "v", 1000).isEmpty() ? "—" : h(str(r, "v", 1000)))
                            .append("</td><td valign=\"top\" align=\"right\" style=\"padding:9px 10px;border-bottom:1px solid ").append(LINE).append(";font-size:13.5px;font-weight:bold;color:").append(INK).append(";white-space:nowrap\">")
                            .append(h(str(r, "a", 1000))).append("</td>").append(td).append("<table").append(T0).append("><tr>").append(pillCell(str(r, "s", 1000))).append("</tr></table></td></tr>");
                }
                fs.append("</table>");
            }
            b.append("<div class=\"keep\">").append(card(fs.toString(), null)).append("</div>");
        }
        if (!str(d, "rodape", 1000).isEmpty()) b.append("<table").append(T0).append(" width=\"100%\"><tr><td style=\"padding:2px 4px;font-size:12.5px;color:").append(MUTED).append("\">").append(h(str(d, "rodape", 1000))).append("</td></tr></table>");
        return b.append("</td></tr></table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>").toString();
    }
}
