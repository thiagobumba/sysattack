package br.com.sysattack;

import java.io.IOException;
import java.net.URI;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Cabeçalhos de segurança em todas as respostas e verificação de origem nas requisições que alteram dados.
 * A política de conteúdo (CSP) só permite scripts do próprio sistema: mesmo que algum texto digitado escape do
 * tratamento, o navegador não executa script injetado.
 */
@WebFilter(urlPatterns = "/*")
public class SecurityFilter implements Filter {
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com data:",
            "img-src 'self' data: blob:",
            "connect-src 'self'",
            "frame-src 'self'",
            "object-src 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'self'");

    @Override
    public void doFilter(ServletRequest rq, ServletResponse rs, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) rq;
        HttpServletResponse resp = (HttpServletResponse) rs;
        resp.setHeader("Content-Security-Policy", CSP);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("Referrer-Policy", "same-origin");
        resp.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=(), payment=(), usb=()");
        resp.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        resp.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        if (req.isSecure() || "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"))) {
            resp.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        String m = req.getMethod();
        if (!m.equals("GET") && !m.equals("HEAD") && !m.equals("OPTIONS") && !sameOrigin(req)) {
            resp.setStatus(403);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"Requisição de origem não permitida.\"}");
            return;
        }
        chain.doFilter(rq, rs);
    }

    /** Origin (ou Referer) de requisições de escrita precisa ser o próprio servidor; sem nenhum dos dois, só o cabeçalho X-Requested-With vale. */
    private static boolean sameOrigin(HttpServletRequest req) {
        String src = req.getHeader("Origin");
        if (src == null || src.isEmpty() || src.equals("null")) src = req.getHeader("Referer");
        if (src == null || src.isEmpty()) return true;
        try {
            String host = URI.create(src).getAuthority();
            String mine = req.getHeader("X-Forwarded-Host");
            if (mine == null || mine.isEmpty()) mine = req.getHeader("Host");
            return host != null && mine != null && host.equalsIgnoreCase(mine.split(",")[0].trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
