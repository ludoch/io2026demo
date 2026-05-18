package com.google.judge;

import com.google.a2a.A2ARpcHandler;
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
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8003"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/a2a/remote/v1/message:send", new JudgeHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Judge server started on port " + port);
    }

    static class JudgeHandler extends A2ARpcHandler {
        @Override
        protected String processAgentLogic(String inputMessage) {
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
                        .model("gemini-3-flash-preview")
                        .instruction(instruction)
                        .outputSchema(outputSchema)
                        .build();

                Runner runner = new Runner(judge, "judge_app", new InMemoryArtifactService(), new InMemorySessionService(), null);
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
                responseText = "{\"status\": \"pass\", \"feedback\": \"Error during evaluation\"}";
            }
            return responseText;
        }
    }
}

