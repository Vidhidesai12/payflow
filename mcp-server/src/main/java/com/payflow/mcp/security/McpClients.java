package com.payflow.mcp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * The AI clients allowed to connect, each with its own token and permission scopes.
 * Only a SHA-256 hash of each token is stored, so the config file never holds a usable secret.
 */
@ConfigurationProperties("payflow.mcp")
public record McpClients(List<Client> clients, List<String> allowedOrigins) {

    public record Client(String name, String tokenSha256, Set<String> scopes) {
    }

    public McpClients {
        clients = clients == null ? List.of() : List.copyOf(clients);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
