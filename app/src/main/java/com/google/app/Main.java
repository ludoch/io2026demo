package com.google.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.opentelemetry.trace.TraceConfiguration;
import com.google.cloud.opentelemetry.trace.TraceExporter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
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
import java.util.concurrent.Executors;

public class Main {
    private static final String ORCHESTRATOR_URL = System.getenv().getOrDefault("AGENT_URL", "http://localhost:8001");
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static Tracer tracer;

    public static void main(String[] args) throws IOException {
        initOpenTelemetry();

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new StaticHandler());
        server.createContext("/api/chat_stream", new ChatStreamHandler());

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("App server started on port " + port);
    }

    private static void initOpenTelemetry() {
        try {
            SpanExporter exporter = TraceExporter.createWithDefaultConfiguration();
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
            tracer = sdk.getTracer("com.google.app");
        } catch (Exception e) {
            System.err.println("Could not initialize Cloud Trace Exporter: " + e.getMessage());
            tracer = GlobalOpenTelemetry.getTracer("com.google.app");
        }
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

    static class ChatStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Span span = tracer.spanBuilder("POST /api/chat_stream").startSpan();
            try (io.opentelemetry.context.Scope scope = span.makeCurrent()) {
                JsonNode reqNode = mapper.readTree(exchange.getRequestBody());
                String message = reqNode.path("message").asText();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ORCHESTRATOR_URL + "/orchestrate"))
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqNode)))
                        .header("Content-Type", "application/json")
                        .build();

                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);

                try (OutputStream os = exchange.getResponseBody()) {
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
                            .body()
                            .forEach(line -> {
                                try {
                                    os.write((line + "\n").getBytes());
                                    os.flush();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            });
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
