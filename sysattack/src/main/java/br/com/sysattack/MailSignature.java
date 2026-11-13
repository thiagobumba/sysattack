package br.com.sysattack;

import java.sql.SQLException;
import java.util.Map;

/**
 * Assinatura acrescentada ao fim dos e-mails (relatórios e avisos). A imagem vai <b>embutida</b> na mensagem,
 * por <code>cid:</code>, para aparecer mesmo com o bloqueio de imagens externas do Outlook.
 * O e-mail do código de troca de senha não leva assinatura: é mensagem de segurança, curta e impessoal.
 */
final class MailSignature {
    static final String CID = "sysattack-assinatura";
    private static final int MAX_W = 1000; // largura máxima; a imagem nunca passa da largura da mensagem

    private MailSignature() {}

    static final class Data {
        final String html; final byte[] image; final String type;
        Data(String html, byte[] image, String type) { this.html = html; this.image = image; this.type = type; }
    }

    /** Monta a assinatura a partir da configuração; null quando não há nada a acrescentar. */
    static Data of(Map<String, Object> r, byte[] img) {
        if (r == null || !Boolean.TRUE.equals(r.get("sign_on"))) return null;
        String text = r.get("sign_text") == null ? "" : String.valueOf(r.get("sign_text")).trim();
        String name = r.get("sign_img_name") == null ? "" : String.valueOf(r.get("sign_img_name"));
        if (text.isEmpty() && (img == null || img.length == 0)) return null;
        int w = r.get("sign_img_w") == null ? 0 : ((Number) r.get("sign_img_w")).intValue();
        if (w <= 0 || w > MAX_W) w = MAX_W;
        StringBuilder b = new StringBuilder();
        b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:24px\"><tr><td style=\"padding:16px 0 0;border-top:1px solid #dddde0\">");
        if (!text.isEmpty())
            b.append("<div style=\"font-family:'Segoe UI',Arial,Helvetica,sans-serif;font-size:13px;line-height:20px;color:#5f5f66;padding-bottom:").append(img == null || img.length == 0 ? "0" : "12").append("px\">")
             .append(SpotReportHtml.h(text).replace("\n", "<br>")).append("</div>");
        if (img != null && img.length > 0)
            b.append("<img src=\"cid:").append(CID).append("\" alt=\"Assinatura\" width=\"").append(w)
             .append("\" style=\"display:block;border:0;width:100%;max-width:").append(w).append("px;height:auto\">");
        b.append("</td></tr></table>");
        return new Data(b.toString(), img, ApiServlet.MIME.getOrDefault(ApiServlet.extOf(name), "image/png"));
    }

    /** Lê a configuração ativa; null se não houver. */
    static Data current() {
        try {
            Map<String, Object> r = Db.one("SELECT sign_on, sign_text, sign_img_name, sign_img_w, sign_img_size FROM email_settings WHERE id = 1");
            if (r == null || !Boolean.TRUE.equals(r.get("sign_on"))) return null;
            long size = r.get("sign_img_size") == null ? 0 : ((Number) r.get("sign_img_size")).longValue();
            return of(r, size > 0 ? image() : null);
        } catch (Exception e) {
            return null; // assinatura nunca pode impedir o envio
        }
    }

    /**
     * Bytes da imagem. Precisa de consulta direta: o Db.query converte para texto tudo que não é
     * número, booleano ou String, e um byte[] viraria "[B@...".
     */
    static byte[] image() throws SQLException {
        try (java.sql.Connection c = Db.get();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT sign_img FROM email_settings WHERE id = 1");
             java.sql.ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBytes(1) : null;
        }
    }

    /** Insere a assinatura antes do fecho do corpo (serve para documento inteiro e para trecho solto). */
    static String append(String html, Data sig) {
        if (sig == null) return html;
        int i = html.lastIndexOf("</body>");
        return i < 0 ? html + sig.html : html.substring(0, i) + sig.html + html.substring(i);
    }
}
