package br.com.sysattack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;

/**
 * Trilha de auditoria: quem fez o quê e de onde (logins, falhas, trocas de senha, usuários, backups, configurações).
 * Grava em conexão própria, de modo que o registro permanece mesmo quando a requisição é desfeita (ex.: login recusado).
 * Nunca guarda senhas, códigos ou chaves.
 */
final class Audit {
    private Audit() {}

    private static final Logger LOG = Logger.getLogger("sysattack");

    static String ip(HttpServletRequest req) {
        return req == null ? "" : String.valueOf(req.getRemoteAddr());
    }

    static void log(HttpServletRequest req, String actor, String action, String detail, boolean ok) {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log(actor, action, detail, ip, ok) VALUES(?,?,?,?,?)")) {
            ps.setString(1, cut(actor, 120));
            ps.setString(2, cut(action, 40));
            ps.setString(3, cut(detail, 400));
            ps.setString(4, cut(ip(req), 60));
            ps.setBoolean(5, ok);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warning("Auditoria: não foi possível gravar (" + action + "): " + e);
        }
        LOG.info("AUDIT " + (ok ? "ok " : "FALHA ") + action + " ator=" + actor + " ip=" + ip(req) + " " + detail);
    }

    static List<Map<String, Object>> recent(int limit) throws java.sql.SQLException {
        return Db.query("SELECT id, at, actor, action, detail, ip, ok FROM audit_log ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 500)));
    }

    /** Mantém 1 ano de histórico. */
    static void prune() {
        try {
            Db.update("DELETE FROM audit_log WHERE at < DATEADD('DAY', -365, CURRENT_TIMESTAMP)");
        } catch (Exception e) {
            LOG.warning("Auditoria: limpeza falhou: " + e);
        }
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}]", " ");
        return s.length() <= n ? s : s.substring(0, n);
    }
}
