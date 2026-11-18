package com.payflow.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A tiny fake of another service (fraud-service, ledger-service), built on the JDK's own HTTP server.
 * Tests decide per path what it answers, how slowly, and can count the calls.
 */
final class StubHttpServer {

    record Response(int status, String body, long delayMs) {
        static Response json(String body) {
            return new Response(200, body, 0);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<String, Response>> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Answer requests to this path (query string ignored) with fn(requestBody). */
    void on(String path, Function<String, Response> fn) {
        routes.put(path, fn);
    }

    int calls(String path) {
        return calls.getOrDefault(path, new AtomicInteger()).get();
    }

    void reset() {
        routes.clear();
        calls.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        calls.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        Function<String, Response> route = routes.get(path);
        Response response = route == null ? new Response(404, "{}", 0) : route.apply(requestBody);
        if (response.delayMs() > 0) {
            try {
                Thread.sleep(response.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException clientGaveUp) {
            // the caller timed out and closed the connection: expected in timeout tests
        } finally {
            exchange.close();
        }
    }
}
