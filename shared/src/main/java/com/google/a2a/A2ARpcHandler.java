package com.google.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;

public abstract class A2ARpcHandler implements HttpHandler {
    protected static final ObjectMapper mapper = new ObjectMapper();

    private static final TextMapGetter<HttpExchange> getter = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpExchange carrier) {
            return carrier.getRequestHeaders().keySet();
        }

        @Override
        public String get(HttpExchange carrier, String key) {
            if (carrier.getRequestHeaders().containsKey(key)) {
                return carrier.getRequestHeaders().getFirst(key);
            }
            return null;
        }
    };

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Extract trace context from headers
        Context parentContext = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), exchange, getter);

        Tracer tracer = Tracing.getTracer();
        Span span = tracer.spanBuilder("RPC " + exchange.getRequestURI().getPath())
                .setParent(parentContext)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            try (OutputStream os = exchange.getResponseBody()) {
                JsonNode rpcReq = mapper.readTree(exchange.getRequestBody());
                String rpcId = rpcReq.path("id").asText("1");
                JsonNode messageNode = rpcReq.path("params").path("message");
                
                String contextText = "";
                JsonNode parts = messageNode.path("parts");
                if (parts != null && parts.isArray() && parts.size() > 0) {
                    contextText = parts.get(0).path("text").asText("");
                }

                // Let the subclass process the LLM logic
                String responseText = processAgentLogic(contextText);

                ObjectNode resultNode = mapper.createObjectNode();
                resultNode.put("kind", "message");
                resultNode.put("role", "agent");
                
                ObjectNode textPart = mapper.createObjectNode();
                textPart.put("kind", "text");
                textPart.put("text", responseText);
                
                resultNode.putArray("parts").add(textPart);

                ObjectNode rpcRes = mapper.createObjectNode();
                rpcRes.put("jsonrpc", "2.0");
                rpcRes.put("id", rpcId);
                rpcRes.set("result", resultNode);

                byte[] bytes = mapper.writeValueAsBytes(rpcRes);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                os.write(bytes);
            } catch (Exception e) {
                span.recordException(e);
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        } finally {
            span.end();
        }
    }

    /**
     * Executes the agent logic based on the incoming message text.
     * @param inputMessage The extracted user/agent text.
     * @return The final text response to be wrapped in the JSON-RPC reply.
     */
    protected abstract String processAgentLogic(String inputMessage);
}