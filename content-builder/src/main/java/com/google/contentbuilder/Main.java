package com.google.contentbuilder;

import com.google.a2a.A2ARpcHandler;
import com.google.a2a.Tracing;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws IOException {
        Tracing.init("content-builder");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8004"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/a2a/remote/v1/message:send", new BuildHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Content Builder server started on port " + port);
    }

    /**
     * The Content Builder Agent: Context-Aware Generation
     * 
     * This agent takes the validated 'research_findings' from the session state
     * and formats them into a final, user-ready markdown course. Because the
     * Judge already guaranteed the quality of the facts, this agent only focuses
     * on tone, formatting, and structure.
     */
    static class BuildHandler extends A2ARpcHandler {
        @Override
        protected String processAgentLogic(String inputMessage) {
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
            return responseText;
        }
    }
}
