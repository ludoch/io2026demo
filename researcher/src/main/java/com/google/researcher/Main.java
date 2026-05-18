package com.google.researcher;

import com.google.a2a.A2ARpcHandler;
import com.google.a2a.Tracing;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.GoogleSearchTool;
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
        Tracing.init("researcher");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8002"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/a2a/remote/v1/message:send", new ResearchHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Researcher server started on port " + port);
    }

    static class ResearchHandler extends A2ARpcHandler {
        @Override
        protected String processAgentLogic(String inputMessage) {
            String instruction = "You are an expert researcher. Your goal is to find comprehensive and accurate information on the user's topic.\n" +
                                 "Summarize your findings clearly.\n" +
                                 "If you receive feedback that your research is insufficient, use the feedback to refine your next search.\n" +
                                 "DO NOT output any function calls. Provide your research directly as text.";

            String responseText = "";
            try {
                LlmAgent researcher = LlmAgent.builder()
                        .name("researcher")
                        .description("Gathers information on a topic using Google Search.")
                        .model("gemini-3-flash-preview")
                        .instruction(instruction)
                        .tools(Collections.singletonList(new GoogleSearchTool()))
                        .build();

                Runner runner = new Runner(researcher, "researcher_app", new InMemoryArtifactService(), new InMemorySessionService(), null);
                Content inputContent = Content.builder().role("user").parts(Collections.singletonList(Part.builder().text(inputMessage).build())).build();
                
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
            return responseText;
        }
    }
}
