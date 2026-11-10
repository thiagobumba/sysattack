package br.com.sysattack;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relatório do projeto Spot evolutivo: o HTML é montado na tela (e pode ser ajustado à mão) e guardado aqui.
 * Como o texto é livre, passa por uma lista de permissões: só tags de formatação e tabela, só alguns atributos,
 * sem script, link, imagem externa ou estilo com url(). Serve ao navegador, ao PDF e ao corpo do e-mail.
 */
final class EvoReportHtml {
    private EvoReportHtml() {}

    static final int MAX = 150000;
    private static final Set<String> TAGS = Set.of("h1", "h2", "h3", "h4", "p", "div", "span", "b", "strong", "i", "em", "u", "br", "ul", "ol", "li",
            "table", "thead", "tbody", "tr", "th", "td", "hr", "small");
    private static final Set<String> VOID = Set.of("br", "hr");
    private static final Pattern BLOCKS = Pattern.compile("(?is)<(script|style|iframe|object|embed|svg|math|form|textarea|title|head)\\b.*?</\\1\\s*>");
    private static final Pattern TOKEN = Pattern.compile("(?s)<!--.*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>");
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    static String sanitize(String in) {
        String s = BLOCKS.matcher(in == null ? "" : in).replaceAll("");
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(text(s.substring(last, m.start())));
            last = m.end();
            String tag = m.group(2);
            if (tag == null) continue; // comentário
            tag = tag.toLowerCase(Locale.ROOT);
            if (!TAGS.contains(tag)) continue;
            if (!m.group(1).isEmpty()) { if (!VOID.contains(tag)) out.append("</").append(tag).append('>'); continue; }
            out.append('<').append(tag);
            Matcher a = ATTR.matcher(m.group(3));
            while (a.find()) {
                String k = a.group(1).toLowerCase(Locale.ROOT), v = a.group(2) != null ? a.group(2) : a.group(3);
                String ok = attr(k, v);
                if (ok != null) out.append(' ').append(k).append("=\"").append(ok).append('"');
            }
            out.append('>');
        }
        out.append(text(s.substring(last)));
        return out.toString();
    }

    /** Texto solto: o que sobrar de "<" vira entidade; "&" já vem codificado do editor. */
    private static String text(String t) {
        return t.replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String attr(String k, String v) {
        String l = v.toLowerCase(Locale.ROOT);
        switch (k) {
            case "style":
                if (l.contains("url(") || l.contains("expression") || l.contains("@import") || l.contains("javascript") || l.contains("&#") || l.contains("\\") || l.contains("behavior")) return null;
                return v.replace("\"", "&quot;").replace("<", "").replace(">", "");
            case "colspan": case "rowspan": case "width": case "height":
                return v.matches("\\d{1,4}%?") ? v : null;
            case "bgcolor":
                return v.matches("#[0-9a-fA-F]{3,6}") ? v : null;
            case "cellpadding": case "cellspacing": case "border":
                return v.matches("\\d{1,2}") ? v : null;
            case "role":
                return l.equals("presentation") ? l : null;
            case "class":
                return v.matches("[A-Za-z0-9 _-]{1,40}") ? v : null;
            case "align": case "valign":
                return v.matches("(?i)left|right|center|justify|top|middle|bottom") ? l : null;
            default:
                return null;
        }
    }

    static JsonObject normalize(JsonObject in) {
        String title = ApiServlet.str(in, "titulo", "Título", 200, true);
        String html = sanitize(ApiServlet.str(in, "html", "Conteúdo do relatório", MAX, true));
        if (html.trim().isEmpty()) throw new ApiServlet.ApiException(400, "O relatório está vazio.");
        JsonObject o = new JsonObject();
        o.addProperty("evo", true);
        o.addProperty("titulo", title);
        o.addProperty("html", html);
        return o;
    }

    /** Moldura do relatório: tabelas (Outlook), faixa branca centralizada, logo no topo e ajuste para telas estreitas. */
    static String render(JsonObject sd, boolean email) {
        String t0 = " role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"";
        return "<!DOCTYPE html><html lang=\"pt-BR\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><!--[if gte mso 9]><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->"
                + "<title>" + SpotReportHtml.h(sd.get("titulo").getAsString()) + "</title><style>" + STYLE + "</style></head>"
                + "<body bgcolor=\"#f6f6f4\" style=\"margin:0;background-color:#f6f6f4;font-size:14px\"><table" + t0 + " width=\"100%\" bgcolor=\"#f6f6f4\"><tr><td align=\"center\" class=\"pad\" style=\"padding:22px 18px 40px\">"
                + "<!--[if mso]><table role=\"presentation\" width=\"760\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->"
                + "<table" + t0 + " width=\"100%\" style=\"max-width:760px\"><tr><td bgcolor=\"#ffffff\" class=\"cp\" style=\"background-color:#ffffff;padding:24px 28px\">"
                + ReportLogo.header(email, "Relatório do projeto", "") + sd.get("html").getAsString()
                + "</td></tr></table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>";
    }

    /** Mesmo CSS no servidor (navegador e e-mail); o preview da tela usa o mesmo texto. */
    static final String STYLE = "body,table,td,th,p{font-family:'Segoe UI',Arial,Helvetica,sans-serif;color:#1a1a1a}table{border-collapse:collapse;mso-table-lspace:0;mso-table-rspace:0}img{border:0}"
            + "@media only screen and (max-width:620px){.stc{display:inline-block!important;width:48%!important;box-sizing:border-box!important;vertical-align:top}.pad{padding:12px 8px 24px!important}.cp{padding:16px 14px!important}}"
            + "@media print{*{-webkit-print-color-adjust:exact;print-color-adjust:exact}@page{margin:14mm}}";
}
