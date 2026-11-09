package br.com.sysattack;

import br.com.sysattack.ApiServlet.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Imagem (logo) de um projeto ou de um projeto Spot. Fica no banco (entra no backup junto com o resto).
 * Só PNG, JPEG, WEBP e GIF, conferidos pelos primeiros bytes do arquivo (nunca SVG, que pode conter script).
 */
final class LogoService {
    static final long MAX_BYTES = 1024 * 1024; // o navegador já reduz a imagem; 1 MB é folga

    private LogoService() {}

    /** kind: "P" = projeto, "S" = projeto Spot. */
    static Object handle(String kind, long id, String m, HttpServletRequest req, HttpServletResponse resp) throws SQLException, IOException {
        switch (m) {
            case "GET": return serve(kind, id, resp);
            case "POST":
            case "PUT": return save(kind, id, req);
            case "DELETE":
                Db.update("DELETE FROM project_logos WHERE kind = ? AND ref_id = ?", kind, id);
                return Map.of("ok", true);
            default: throw new ApiException(405, "Operação não suportada.");
        }
    }

    private static Object serve(String kind, long id, HttpServletResponse resp) throws SQLException, IOException {
        try (java.sql.Connection c = Db.get();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT content_type, content FROM project_logos WHERE kind = ? AND ref_id = ?")) {
            ps.setString(1, kind);
            ps.setLong(2, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Este projeto não tem imagem.");
                resp.setContentType(rs.getString(1)); // sempre um dos 4 tipos validados ao gravar
                resp.setHeader("X-Content-Type-Options", "nosniff");
                resp.setHeader("Content-Security-Policy", "sandbox");
                resp.setHeader("Content-Disposition", "inline");
                resp.setHeader("Cache-Control", "private, max-age=0, must-revalidate");
                try (InputStream in = rs.getBinaryStream(2)) {
                    in.transferTo(resp.getOutputStream());
                }
            }
        }
        return ApiServlet.FILE_SENT;
    }

    private static Object save(String kind, long id, HttpServletRequest req) throws SQLException, IOException {
        if (req.getContentLengthLong() > MAX_BYTES) throw new ApiException(413, "A imagem é maior que 1 MB.");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = req.getInputStream()) {
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new ApiException(413, "A imagem é maior que 1 MB.");
                bos.write(buf, 0, n);
            }
        }
        byte[] data = bos.toByteArray();
        String type = detectType(data);
        if (type == null) throw new ApiException(400, "Formato de imagem não aceito. Use PNG, JPG, WEBP ou GIF.");
        Db.update("MERGE INTO project_logos(kind, ref_id, content_type, content, size, updated_at) KEY(kind, ref_id) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
                kind, id, type, data, data.length);
        return Map.of("ok", true);
    }

    /** Tipo pela assinatura do arquivo; null se não for uma imagem aceita. */
    static String detectType(byte[] d) {
        if (d.length < 12) return null;
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return "image/gif";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        return null;
    }
}
