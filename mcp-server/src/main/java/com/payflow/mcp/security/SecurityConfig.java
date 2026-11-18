package com.payflow.mcp.security;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Spring AI MCP starter exposes an unauthenticated endpoint by default, so all protection is here:
 * a valid client token is required for /mcp, everything else is closed except health and metrics.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    public static final String CLIENT_KEY = "payflow.client";
    public static final String SCOPES_KEY = "payflow.scopes";

    @Bean
    SecurityFilterChain mcpSecurity(HttpSecurity http, McpClients clients) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // token in a header, no cookies: CSRF does not apply
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new TokenAuthenticationFilter(clients), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/mcp", "/mcp/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    /**
     * Our own MCP transport, so each call carries who made it. The extractor runs on the HTTP request
     * thread, where Spring Security knows the caller; the tool code may run on another thread.
     */
    @Bean
    WebMvcStreamableServerTransportProvider mcpTransport(JsonMapper jsonMapper) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth == null || !auth.isAuthenticated()) {
                        return McpTransportContext.EMPTY;
                    }
                    Set<String> scopes = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                            .filter(a -> a.startsWith("SCOPE_")).map(a -> a.substring(6)).collect(Collectors.toSet());
                    return McpTransportContext.create(Map.of(CLIENT_KEY, auth.getName(), SCOPES_KEY, scopes));
                })
                .build();
    }
}
