package com.google.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.a2a.Tracing;
import com.google.a2a.CorsFilter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class Main {
    private static final String ORCHESTRATOR_URL = System.getenv().getOrDefault("AGENT_URL", "http://localhost:8001");
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static Tracer tracer;

    public static void main(String[] args) throws IOException {
        tracer = Tracing.init("app");

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new StaticHandler()).getFilters().add(new CorsFilter());
        server.createContext("/api/chat_stream", new ChatStreamHandler()).getFilters().add(new CorsFilter());

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("App server started on port " + port);
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            try (InputStream is = getClass().getResourceAsStream("/frontend" + path)) {
                if (is == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = is.readAllBytes();
                String contentType = "text/plain";
                if (path.endsWith(".html")) contentType = "text/html";
                else if (path.endsWith(".js")) contentType = "application/javascript";
                else if (path.endsWith(".css")) contentType = "text/css";
                
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    private static HttpRequest buildAuthRequest(String url, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"));
        } else {
            builder.GET();
        }
        builder.header("Content-Type", "application/json");

        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            if (credentials instanceof IdTokenProvider) {
                IdTokenCredentials idTokenCreds = IdTokenCredentials.newBuilder()
                        .setIdTokenProvider((IdTokenProvider) credentials)
                        .setTargetAudience(ORCHESTRATOR_URL)
                        .build();
                idTokenCreds.refreshIfExpired();
                builder.header("Authorization", "Bearer " + idTokenCreds.getIdToken().getTokenValue());
            }
        } catch (Exception e) {}

        GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), builder, HttpRequest.Builder::header);
        return builder.build();
    }

    static class ChatStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Span span = tracer.spanBuilder("POST /api/chat_stream").startSpan();
            try (Scope scope = span.makeCurrent()) {
                JsonNode reqNode = mapper.readTree(exchange.getRequestBody());
                String message = reqNode.path("message").asText();
                String requestedSessionId = reqNode.path("session_id").asText(null);
                String userId = reqNode.path("user_id").asText("test_user");

                // Get Agent name
                String agentName = "orchestrator_app";
                try {
                    HttpRequest listReq = buildAuthRequest(ORCHESTRATOR_URL + "/list-apps", "GET", null);
                    HttpResponse<String> listRes = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
                    if (listRes.statusCode() == 200) {
                        JsonNode agents = mapper.readTree(listRes.body());
                        if (agents.isArray() && agents.size() > 0) agentName = agents.get(0).asText();
                    }
                } catch (Exception e) {}

                // Resolve Session ID
                String sessionId = requestedSessionId;
                try {
                    if (sessionId != null && !sessionId.isEmpty() && !"null".equals(sessionId)) {
                        HttpRequest checkReq = buildAuthRequest(ORCHESTRATOR_URL + "/apps/" + agentName + "/users/" + userId + "/sessions/" + sessionId, "GET", null);
                        HttpResponse<String> res = httpClient.send(checkReq, HttpResponse.BodyHandlers.ofString());
                        if (res.statusCode() == 404) sessionId = null;
                    }
                    if (sessionId == null || sessionId.isEmpty() || "null".equals(sessionId)) {
                        HttpRequest createReq = buildAuthRequest(ORCHESTRATOR_URL + "/apps/" + agentName + "/users/" + userId + "/sessions", "POST", "{}");
                        HttpResponse<String> res = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
                        if (res.statusCode() == 200) {
                            sessionId = mapper.readTree(res.body()).path("id").asText();
                        } else {
                            sessionId = "session_fallback";
                        }
                    }
                } catch (Exception e) {
                    sessionId = "session_fallback";
                }

                com.fasterxml.jackson.databind.node.ObjectNode runReq = mapper.createObjectNode();
                runReq.put("appName", agentName);
                runReq.put("userId", userId);
                runReq.put("sessionId", sessionId);
                com.fasterxml.jackson.databind.node.ObjectNode newMessage = mapper.createObjectNode();
                newMessage.put("role", "user");
                com.fasterxml.jackson.databind.node.ObjectNode part = mapper.createObjectNode();
                part.put("text", message);
                newMessage.putArray("parts").add(part);
                runReq.set("newMessage", newMessage);

                HttpRequest request = buildAuthRequest(ORCHESTRATOR_URL + "/run_sse", "POST", mapper.writeValueAsString(runReq));

                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);

                try (OutputStream os = exchange.getResponseBody()) {
                    StringBuilder finalText = new StringBuilder();
                    CompletableFuture<Void> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                            .thenAccept(response -> {
                                response.body().forEach(line -> {
                                    try {
                                        if (line.trim().isEmpty()) return;
                                        
                                        JsonNode event = mapper.readTree(line);
                                        String author = event.path("author").asText("");
                                        
                                        if ("researcher".equals(author)) {
                                            os.write((mapper.writeValueAsString(mapper.createObjectNode().put("type", "progress").put("text", "🔍 Researcher is gathering information...")) + "\n").getBytes());
                                        } else if ("judge".equals(author)) {
                                            os.write((mapper.writeValueAsString(mapper.createObjectNode().put("type", "progress").put("text", "⚖️ Judge is evaluating findings...")) + "\n").getBytes());
                                        } else if ("content_builder".equals(author)) {
                                            os.write((mapper.writeValueAsString(mapper.createObjectNode().put("type", "progress").put("text", "✍️ Content Builder is writing the course...")) + "\n").getBytes());
                                        }
                                        
                                        JsonNode contentNode = event.path("content");
                                        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                                            JsonNode parts = contentNode.path("parts");
                                            if (parts.isArray()) {
                                                for (JsonNode p : parts) {
                                                    String text = p.path("text").asText("");
                                                    if (!text.isEmpty()) {
                                                        finalText.append(text);
                                                    }
                                                }
                                            }
                                        }
                                        os.flush();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                });
                            });

                    future.join();
                    os.write((mapper.writeValueAsString(mapper.createObjectNode().put("type", "result").put("text", finalText.toString().trim())) + "\n").getBytes());
                    os.flush();
                }

            } catch (Exception e) {
                span.recordException(e);
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            } finally {
                span.end();
            }
        }
    }
}
