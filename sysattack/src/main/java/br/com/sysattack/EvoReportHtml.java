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

    static String render(JsonObject sd, boolean email) {
        String body = sd.get("html").getAsString();
        String inner = "<div style=\"max-width:780px;margin:0 auto;font-family:'Segoe UI',Inter,Roboto,Helvetica,Arial,sans-serif;color:#1a1a1a\">" + body + "</div>";
        if (email) return inner;
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><title>" + SpotReportHtml.h(sd.get("titulo").getAsString())
                + "</title><style>@page{margin:14mm}</style></head><body style=\"margin:0;padding:24px 30px;background:#fff\">" + inner + "</body></html>";
    }
}
