package br.com.sysattack;

import java.io.InputStream;
import java.util.Base64;

/** Logo da Momesso no cabeçalho dos relatórios Spot (navegador/impressão: data URI; e-mail: imagem embutida via cid). */
final class ReportLogo {
    static final String CID = "momesso-logo";
    private static byte[] bytes;
    private static String dataUri;

    private ReportLogo() {}

    /** PNG da logo (recurso do WAR) ou null se não estiver disponível. */
    static synchronized byte[] png() {
        if (bytes == null) {
            try (InputStream in = ReportLogo.class.getResourceAsStream("/report-logo.png")) {
                if (in != null) bytes = in.readAllBytes();
            } catch (Exception ignored) { }
        }
        return bytes;
    }

    private static synchronized String src(boolean email) {
        if (email) return "cid:" + CID;
        byte[] b = png();
        if (b == null) return null;
        if (dataUri == null) dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(b);
        return dataUri;
    }

    /** Faixa de cabeçalho: logo à esquerda, rótulo/data à direita e linha fina embaixo. */
    static String header(boolean email, String right1, String right2) {
        String s = src(email);
        if (s == null) return "";
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td valign=\"middle\" style=\"padding:0 0 14px\"><img src=\"" + s + "\" alt=\"Momesso\" width=\"150\" height=\"43\" style=\"display:block;border:0;width:150px;height:auto\"></td>"
                + "<td valign=\"middle\" align=\"right\" style=\"padding:0 0 14px;font-size:12px;line-height:1.4;color:#888888\">" + SpotReportHtml.h(right1)
                + (right2 == null || right2.isEmpty() ? "" : "<br>" + SpotReportHtml.h(right2)) + "</td></tr>"
                + "<tr><td colspan=\"2\" height=\"1\" bgcolor=\"#e3e3e3\" style=\"background-color:#e3e3e3;font-size:1px;line-height:1px\">&nbsp;</td></tr>"
                + "<tr><td colspan=\"2\" height=\"18\" style=\"font-size:1px;line-height:1px\">&nbsp;</td></tr></table>";
    }
}
