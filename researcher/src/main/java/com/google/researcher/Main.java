package com.google.researcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.GoogleSearchTool;
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
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8002"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/a2a/remote/v1/message:send", new ResearchHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Researcher server started on port " + port);
    }

    static class ResearchHandler implements HttpHandler {
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
                
                String topic = "";
                JsonNode parts = messageNode.path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    topic = parts.get(0).path("text").asText("");
                }

                String instruction = "You are an expert researcher. Your goal is to find comprehensive and accurate information on the user's topic.\n" +
                                     "Summarize your findings clearly.\n" +
                                     "If you receive feedback that your research is insufficient, use the feedback to refine your next search.\n" +
                                     "DO NOT output any function calls. Provide your research directly as text.";

                String responseText = "";
                try {
                    LlmAgent researcher = LlmAgent.builder()
                            .name("researcher")
                            .description("Gathers information on a topic using Google Search.")
                            .model("gemini-2.0-flash")
                            .instruction(instruction)
                            .tools(Collections.singletonList(new GoogleSearchTool()))
                            .build();

                    Runner runner = new Runner(researcher, "researcher_app", new InMemoryArtifactService(), new InMemorySessionService(), null);
                    Content inputContent = Content.builder().role("user").parts(Collections.singletonList(Part.builder().text(topic).build())).build();
                    
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
                    responseText = "Error performing research: " + e.getMessage();
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
