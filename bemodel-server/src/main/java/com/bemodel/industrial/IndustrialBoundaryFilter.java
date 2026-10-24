package com.bemodel.industrial;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Deny hospital-specific engines, sample SQL and MCP fallbacks in the industrial profile. */
@Component
@Profile("forgepilot")
public class IndustrialBoundaryFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED_ROOTS = Set.of(
        "auth", "industrial", "domain", "concept", "ontology", "attribute", "relation",
        "term", "metric", "rule", "action", "axiom", "release", "datasource", "mapping",
        "llm", "knowledge", "instance", "search", "proposal"
    );

    static boolean allowed(String path) {
        if (path.equals("/mcp") || path.startsWith("/mcp/")) return false;
        if (!path.startsWith("/api/")) return true;
        String root = path.substring(5).split("/", 2)[0];
        return ALLOWED_ROOTS.contains(root);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!allowed(req.getRequestURI())) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.getWriter().write("{\"code\":403,\"msg\":\"此应用尚未完成工业适配，工业配置不开放医疗规则、示例SQL及执行器\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
