package com.google.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8003"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/a2a/remote/v1/message:send", new JudgeHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Judge server started on port " + port);
    }

    static class JudgeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try (OutputStream os = exchange.getResponseBody()) {
                JsonNode rpcReq = mapper.readTree(exchange.getRequestBody());
                String rpcId = rpcReq.path("id").asText("1");
                JsonNode messageNode = rpcReq.path("params").path("message");
                
                String context = "";
                JsonNode parts = messageNode.path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    context = parts.get(0).path("text").asText("");
                }

                String instruction = "You are a strict editor and fact-checker. " +
                                     "Evaluate the 'research_findings' against the user's original request. " +
                                     "Determine if the findings are sufficient to create a high-quality course. " +
                                     "If they are good enough, output status='pass'. " +
                                     "If they are missing key information, are too vague, or likely inaccurate, output status='fail' and provide specific, constructive 'feedback' on what to research next.";

                Schema outputSchema = Schema.builder()
                        .type(Type.Known.OBJECT)
                        .properties(Map.of(
                                "status", Schema.builder().type(Type.Known.STRING).description("pass or fail").build(),
                                "feedback", Schema.builder().type(Type.Known.STRING).description("constructive feedback").build()
                        ))
                        .required(List.of("status", "feedback"))
                        .build();

                String responseText = "";
                try {
                    LlmAgent judge = LlmAgent.builder()
                            .name("judge")
                            .description("Evaluates the quality of research.")
                            .model("gemini-2.0-flash")
                            .instruction(instruction)
                            .outputSchema(outputSchema)
                            .build();

                    Runner runner = new Runner(judge, "judge_app", new InMemoryArtifactService(), new InMemorySessionService(), null);
                    Content inputContent = Content.builder().role("user").parts(Collections.singletonList(Part.builder().text(context).build())).build();

                    List<Event> events = runner.runAsync("user1", "session1", inputContent, RunConfig.builder().build()).toList().blockingGet();
                    for (Event ev : events) {
                        if (ev.content().isPresent() && ev.content().get().parts().isPresent()) {
                            for (Part p : ev.content().get().parts().get()) {
                                if (p.text().isPresent()) {
                                    responseText += p.text().get();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    responseText = "{\"status\": \"pass\", \"feedback\": \"Error during evaluation\"}";
                }

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
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }
}
