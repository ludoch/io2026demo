package com.google.contentbuilder;

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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

public class Main {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8004"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/a2a/remote/v1/message:send", new BuildHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Content Builder server started on port " + port);
    }

    static class BuildHandler implements HttpHandler {
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

                String instruction = "You are an expert course creator.\n" +
                                     "Take the research findings and transform them into a well-structured, engaging course module.\n" +
                                     "Formatting Rules:\n" +
                                     "1. Start with a main title using a single `#` (H1).\n" +
                                     "2. Use `##` (H2) for the Table of Contents.\n" +
                                     "3. Use bullet points and clear paragraphs.\n" +
                                     "4. Maintain a professional but engaging tone.\n\n" +
                                     "Ensure the content directly addresses the user's original request.";

                String responseText = "";
                try {
                    LlmAgent builder = LlmAgent.builder()
                            .name("content_builder")
                            .description("Builds course content from research.")
                            .model("gemini-3-flash-preview")
                            .instruction(instruction)
                            .build();

                    Runner runner = new Runner(builder, "builder_app", new InMemoryArtifactService(), new InMemorySessionService(), null);
                    Content inputContent = Content.builder().role("user").parts(Collections.singletonList(Part.builder().text("Build the course.").build())).build();

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
                    responseText = "# Error building course\n" + e.getMessage();
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
