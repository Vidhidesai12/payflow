package com.payflow.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns "Authorization: Bearer &lt;token&gt;" into an authenticated client with its scopes.
 * Also rejects requests from web pages on other sites (Origin check), which the MCP spec requires
 * to stop a malicious page in the user's browser from talking to a local MCP server.
 */
class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final McpClients clients;

    TokenAuthenticationFilter(McpClients clients) {
        this.clients = clients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !clients.allowedOrigins().contains(origin)) {
            // Written directly: sendError() would go through the error page and come back as a 401.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain");
            response.getWriter().write("Origin not allowed");
            return;
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            byte[] presented = sha256(header.substring(7).trim());
            for (McpClients.Client client : clients.clients()) {
                // Constant-time comparison, so response timing doesn't leak how much of a token matched.
                if (MessageDigest.isEqual(presented, HexFormat.of().parseHex(client.tokenSha256()))) {
                    var authorities = client.scopes().stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList();
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(client.name(), null, authorities));
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
