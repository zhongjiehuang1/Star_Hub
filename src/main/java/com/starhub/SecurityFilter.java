package com.starhub;

import java.io.IOException;
import java.util.function.Supplier;
import javax.servlet.*;
import javax.servlet.http.*;

public final class SecurityFilter implements Filter {
    private final Supplier<String> password;
    public SecurityFilter() { this(() -> System.getenv("STARHUB_TESTER_PASSWORD")); }
    SecurityFilter(Supplier<String> password) { this.password = password; }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        request.setCharacterEncoding("UTF-8");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Permissions-Policy", "camera=(), microphone=(self), geolocation=()");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: blob:; media-src 'self' data: https: blob:; connect-src 'self' wss://starhub.zhongjiehuang.com; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        res.setHeader("Cache-Control", "no-store");
        if ("/access.css".equals(req.getServletPath()) || RegionAccess.allowed(GeoCountry.forRequest(req.getRemoteAddr(), req.getHeader("X-Real-IP")), req.getSession(false))) {
            chain.doFilter(request, response);
            return;
        }
        String configured = password.get();
        if (configured == null || configured.isEmpty()) {
            res.sendError(503, "Tester access is not configured");
            return;
        }
        HttpSession session = req.getSession(true);
        boolean submitted = "/_access".equals(req.getServletPath()) && "POST".equals(req.getMethod()) && req.getContentType() != null
                && req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")
                && req.getParameter("starhub_access_token") != null;
        boolean invalid = false;
        if (submitted) {
            if (RegionAccess.matches(req.getParameter("starhub_access_token"), (String) session.getAttribute(RegionAccess.TOKEN))
                    && RegionAccess.matches(req.getParameter("starhub_access_password"), configured)) {
                req.changeSessionId();
                session.setAttribute(RegionAccess.VERIFIED, Boolean.TRUE);
                session.removeAttribute(RegionAccess.TOKEN);
                // The target comes only from the current request, not a user-supplied return URL.
                String target = (String) session.getAttribute(RegionAccess.RETURN_TO);
                session.removeAttribute(RegionAccess.RETURN_TO);
                if (target == null || !target.startsWith(req.getContextPath() + "/") || target.startsWith("//") || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0 || target.indexOf('\\') >= 0) target = req.getContextPath() + "/";
                res.setStatus(HttpServletResponse.SC_SEE_OTHER);
                res.setHeader("Location", target);
                return;
            }
            invalid = true;
        }
        if (!submitted && "GET".equals(req.getMethod()) && !"/_access".equals(req.getServletPath())) {
            String target = req.getRequestURI();
            String query = req.getQueryString();
            if (query != null && query.indexOf('\r') < 0 && query.indexOf('\n') < 0) target += "?" + query;
            session.setAttribute(RegionAccess.RETURN_TO, target);
        }
        String country = GeoCountry.forRequest(req.getRemoteAddr(), req.getHeader("X-Real-IP"));
        boolean knownRestricted = country != null && (country.trim().equalsIgnoreCase("CN") || country.trim().equalsIgnoreCase("HK") || country.trim().equalsIgnoreCase("MO"));
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("text/html;charset=UTF-8");
        // Relative stylesheet works for both public root and direct Tomcat context, with proxy path rewriting.
        String depthPath = req.getRequestURI().substring(req.getContextPath().length());
        int slashCount = Math.max(0, depthPath.lastIndexOf('/'));
        StringBuilder cssPath = new StringBuilder();
        for (int i = 0; i < slashCount; i++) if (depthPath.charAt(i) == '/') cssPath.append("../");
        String action = cssPath.toString() + "_access";
        cssPath.append("access.css");
        res.getWriter().write("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>测试人员验证 · Star Hub</title><link rel=\"stylesheet\" href=\"" + RegionAccess.escape(cssPath.toString()) + "\"></head><body><main class=\"card\"><div class=\"brand\">STAR HUB</div><h1>测试人员验证</h1><p>" + (knownRestricted ? "您当前位于中国大陆、澳门或香港地区。本系统不向这些地区的公众开放。" : "暂时无法确认您的访问地区。") + "如您是本系统的测试人员，请输入密码继续。</p><form method=\"post\" action=\"" + RegionAccess.escape(action) + "\"><input type=\"hidden\" name=\"starhub_access_token\" value=\"" + RegionAccess.escape(RegionAccess.token(session)) + "\"><label for=\"tester-password\">测试人员密码</label><input id=\"tester-password\" name=\"starhub_access_password\" type=\"password\" autocomplete=\"current-password\" required autofocus>" + (invalid ? "<p class=\"error\" role=\"alert\">验证失败，请检查密码后重试。</p>" : "") + "<button type=\"submit\">验证并继续</button></form><p class=\"credit\">IP 地理位置数据：<a href=\"https://db-ip.com\">DB-IP</a></p></main></body></html>");
    }
}
