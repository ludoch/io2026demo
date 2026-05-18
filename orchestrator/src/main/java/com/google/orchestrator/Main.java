package com.google.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.a2a.agent.RemoteA2AAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
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
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import com.sun.net.httpserver.Filter;
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

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8001"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/orchestrate", new OrchestratorHandler()).getFilters().add(new CorsFilter());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Orchestrator server started on port " + port);
    }

    static class CorsFilter extends Filter {
        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            chain.doFilter(exchange);
        }

        @Override
        public String description() {
            return "CORS Filter";
        }
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

        Client a2aClient = Client.builder(mockCard)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                .clientConfig(new ClientConfig.Builder().setStreaming(false).build())
                .build();

        return RemoteA2AAgent.builder()
                .name(name)
                .a2aClient(a2aClient)
                .agentCard(mockCard)
                .afterAgentCallback(Collections.singletonList(ctx -> saveOutputCallback(stateKey, ctx)))
                .build();
    }

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

    static class OrchestratorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                JsonNode reqNode = mapper.readTree(exchange.getRequestBody());
                String topic = reqNode.path("message").asText();

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
                
                List<Event> events = runner.runAsync("user1", "session1", inputContent, RunConfig.builder().build()).toList().blockingGet();

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
                e.printStackTrace();
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
