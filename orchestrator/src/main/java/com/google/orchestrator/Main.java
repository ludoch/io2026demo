package com.google.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.a2a.Tracing;
import com.google.a2a.CorsFilter;
import com.google.a2a.HeaderInjectingA2AHttpClient;
import com.google.adk.a2a.agent.RemoteA2AAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.client.Client;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    private static final String RESEARCHER_URL = System.getenv().getOrDefault("RESEARCHER_URL", "http://localhost:8002");
    private static final String JUDGE_URL = System.getenv().getOrDefault("JUDGE_URL", "http://localhost:8003");
    private static final String BUILDER_URL = System.getenv().getOrDefault("CONTENT_BUILDER_URL", "http://localhost:8004");
    
    private static final ObjectMapper mapper = new ObjectMapper();
    private static Tracer tracer;

    public static void main(String[] args) throws IOException {
        tracer = Tracing.init("orchestrator");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8001"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/list-apps", exchange -> {
            byte[] resp = "[\"orchestrator_app\"]".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        }).getFilters().add(new CorsFilter());

        server.createContext("/apps/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (exchange.getRequestMethod().equalsIgnoreCase("POST") && path.endsWith("/sessions")) {
                String newId = java.util.UUID.randomUUID().toString();
                byte[] resp = ("{\"id\":\"" + newId + "\"}").getBytes();
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } else if (exchange.getRequestMethod().equalsIgnoreCase("GET") && path.contains("/sessions/")) {
                String id = path.substring(path.lastIndexOf("/") + 1);
                byte[] resp = ("{\"id\":\"" + id + "\"}").getBytes();
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        }).getFilters().add(new CorsFilter());

        server.createContext("/run_sse", new OrchestratorHandler()).getFilters().add(new CorsFilter());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Orchestrator server started on port " + port);
    }

    private static Maybe<Content> saveOutputCallback(String key, CallbackContext ctx) {
        List<Event> events = ctx.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            Event ev = events.get(i);
            if (ev.author().equals(ctx.agentName()) && ev.content().isPresent()) {
                Content content = ev.content().get();
                if (content.parts().isPresent() && !content.parts().get().isEmpty()) {
                    String text = content.parts().get().get(0).text().orElse("");
                    if (key.equals("judge_feedback") && text.trim().startsWith("{")) {
                        try {
                            ctx.eventActions().stateDelta().put(key, mapper.readTree(text));
                        } catch (Exception e) {
                            ctx.eventActions().stateDelta().put(key, text);
                        }
                    } else {
                        ctx.eventActions().stateDelta().put(key, text);
                    }
                    break;
                }
            }
        }
        return Maybe.empty();
    }

    private static RemoteA2AAgent createRemoteAgent(String name, String url, String stateKey) {
        AgentCard mockCard = new AgentCard.Builder()
                .name(name)
                .url(url + "/a2a/remote/v1")
                .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                .build();

        Map<String, String> headers = new java.util.HashMap<>();
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), headers, (carrier, k, v) -> carrier.put(k, v));

        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            if (credentials instanceof IdTokenProvider) {
                IdTokenCredentials idTokenCreds = IdTokenCredentials.newBuilder()
                        .setIdTokenProvider((IdTokenProvider) credentials)
                        .setTargetAudience(url)
                        .build();
                idTokenCreds.refreshIfExpired();
                headers.put("Authorization", "Bearer " + idTokenCreds.getIdToken().getTokenValue());
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not fetch OIDC Identity Token for agent " + name + ": " + e.getMessage());
        }

        HeaderInjectingA2AHttpClient authenticatedHttpClient = new HeaderInjectingA2AHttpClient(new JdkA2AHttpClient(), headers);

        Client a2aClient = Client.builder(mockCard)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(authenticatedHttpClient))
                .clientConfig(new ClientConfig.Builder().setStreaming(false).build())
                .build();

        return RemoteA2AAgent.builder()
                .name(name)
                .a2aClient(a2aClient)
                .agentCard(mockCard)
                .afterAgentCallback(Collections.singletonList(ctx -> saveOutputCallback(stateKey, ctx)))
                .build();
    }

    /**
     * The Escalation Checker: Custom Logic for Loop Termination
     * 
     * In the ADK, a LoopAgent will repeat its sub-agents indefinitely until maxIterations
     * is reached OR an agent escalates. This EscalationChecker runs right after the Judge.
     * It reads the Judge's structured output from the session state and, if status="pass",
     * it emits an Event with escalate=true, breaking the loop early.
     */
    static class EscalationChecker extends BaseAgent {
        public EscalationChecker(String name) {
            super(name, "Checks if the judge passed.", null, null, null);
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            Object feedback = invocationContext.session().state().get("judge_feedback");
            boolean pass = false;
            if (feedback instanceof Map) {
                pass = "pass".equals(((Map<?, ?>) feedback).get("status"));
            } else if (feedback instanceof JsonNode) {
                pass = "pass".equals(((JsonNode) feedback).path("status").asText());
            } else if (feedback instanceof String) {
                pass = ((String) feedback).contains("\"status\": \"pass\"");
            }

            Event.Builder builder = Event.builder().author(name());
            if (pass) {
                builder.actions(EventActions.builder().escalate(true).build());
            }
            return Flowable.just(builder.build());
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return runAsyncImpl(invocationContext);
        }
    }

    /**
     * The Orchestrator: Managing Distributed Communication
     * 
     * This handler builds the hierarchical graph of agents. 
     * 1. The Research Loop: Iterates [Researcher -> Judge -> EscalationChecker].
     * 2. The Final Pipeline: Sequences [Research Loop -> Content Builder].
     * 
     * It uses RemoteA2AAgent to treat the child agents as independent, distributed 
     * microservices over HTTP JSON-RPC, rather than in-memory objects.
     */
    static class OrchestratorHandler implements HttpHandler {
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

            Context parentContext = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                    .extract(Context.current(), exchange, getter);

            Span span = tracer.spanBuilder("POST /orchestrate")
                    .setParent(parentContext)
                    .startSpan();

            try (Scope scope = span.makeCurrent()) {
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);

                try (OutputStream os = exchange.getResponseBody()) {
                    JsonNode reqNode = mapper.readTree(exchange.getRequestBody());
                    String topic = reqNode.path("newMessage").path("parts").get(0).path("text").asText();
                    String userId = reqNode.path("userId").asText("user1");
                    String sessionId = reqNode.path("sessionId").asText("session1");

                    sendEvent(os, "progress", "🚀 Orchestrator started full logic pipeline...");

                    RemoteA2AAgent researcher = createRemoteAgent("researcher", RESEARCHER_URL, "research_findings");
                    RemoteA2AAgent judge = createRemoteAgent("judge", JUDGE_URL, "judge_feedback");
                    EscalationChecker escalationChecker = new EscalationChecker("escalation_checker");

                    LoopAgent researchLoop = LoopAgent.builder()
                            .name("research_loop")
                            .description("Iteratively researches and judges.")
                            .subAgents(ImmutableList.of(researcher, judge, escalationChecker))
                            .maxIterations(3)
                            .build();

                    RemoteA2AAgent contentBuilder = createRemoteAgent("content_builder", BUILDER_URL, "final_content");

                    SequentialAgent pipelineAgent = SequentialAgent.builder()
                            .name("course_creation_pipeline")
                            .description("Full pipeline with loops.")
                            .subAgents(ImmutableList.of(researchLoop, contentBuilder))
                            .build();

                    Runner runner = new Runner(pipelineAgent, "orchestrator_app", new InMemoryArtifactService(), new InMemorySessionService(), null);
                    Content inputContent = Content.builder().role("user").parts(Collections.singletonList(Part.builder().text(topic).build())).build();
                    
                    List<Event> events = runner.runAsync(userId, sessionId, inputContent, RunConfig.builder().build()).toList().blockingGet();

                    String finalContent = "";
                    for (Event ev : events) {
                        if (ev.content().isPresent() && ev.content().get().parts().isPresent()) {
                            for (Part p : ev.content().get().parts().get()) {
                                if (p.text().isPresent()) {
                                    finalContent += p.text().get();
                                }
                            }
                        }
                    }

                    sendEvent(os, "result", finalContent);
                } catch (Exception e) {
                    span.recordException(e);
                    e.printStackTrace();
                }
            } finally {
                span.end();
            }
        }

        private void sendEvent(OutputStream os, String type, String text) throws IOException {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", type);
            node.put("text", text);
            os.write((mapper.writeValueAsString(node) + "\n").getBytes());
            os.flush();
        }
    }
}
