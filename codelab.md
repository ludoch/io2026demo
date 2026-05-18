# Codelab: Building a Production-Ready Multi-Agent System (Java)

Welcome to the Java edition of the **Building a Multi-Agent System** codelab!

This codelab teaches you how to transition from a monolithic LLM application to a distributed, microservice-based architecture using the **Agent Development Kit (ADK)** and Java 25.

You will build a system that can iteratively research a topic and write a high-quality educational course.

---

## Step 1: Introduction to the Architecture

Instead of a single giant prompt ("Write me a 5-page course on Quantum Computing"), we break the problem down into specialized roles:

1.  **Researcher**: Uses Google Search to gather facts.
2.  **Judge**: Evaluates the research. If it fails, the Researcher must try again.
3.  **Content Builder**: Takes the approved facts and writes the final markdown.
4.  **Orchestrator**: Manages the loop and distributed communication.

*Original Python Codelab Reference:* [Production-Ready AI Roadshow](https://codelabs.developers.google.com/codelabs/production-ready-ai-roadshow/1-building-a-multi-agent-system/building-a-multi-agent-system#0)

---

## Step 2: The Researcher Agent (Tool Use & Grounding)

**Location in Code:** `researcher/src/main/java/com/google/researcher/Main.java`

The Researcher agent's job is to gather real-time information. Because LLMs can hallucinate or have outdated information, we equip this agent with a **Tool**.

In the Java ADK, we use the `GoogleSearchTool`. When bound to a Gemini 2 model, this automatically invokes Vertex AI Grounding behind the scenes.

```java
LlmAgent researcher = LlmAgent.builder()
        .name("researcher")
        .description("Gathers information on a topic using Google Search.")
        .model("gemini-3-flash-preview")
        .instruction(instruction)
        // Binds the Vertex AI Grounding capability
        .tools(Collections.singletonList(new GoogleSearchTool()))
        .build();
```

---

## Step 3: The Judge Agent (Structured Output)

**Location in Code:** `judge/src/main/java/com/google/judge/Main.java`

The Judge agent evaluates the research. For the Orchestrator to programmatically understand if the Judge approved the content, the Judge cannot return free text. It must return a strict JSON structure.

In Python, this is done using Pydantic. In Java, we define a strict `com.google.genai.types.Schema` and bind it to the `outputSchema` property.

```java
Schema outputSchema = Schema.builder()
        .type(Type.Known.OBJECT)
        .properties(Map.of(
                "status", Schema.builder().type(Type.Known.STRING).description("pass or fail").build(),
                "feedback", Schema.builder().type(Type.Known.STRING).description("constructive feedback").build()
        ))
        .required(List.of("status", "feedback"))
        .build();

LlmAgent judge = LlmAgent.builder()
        .name("judge")
        .model("gemini-3-flash-preview")
        .instruction(instruction)
        // Enforces structured JSON output
        .outputSchema(outputSchema)
        .build();
```

---

## Step 4: The Content Builder Agent

**Location in Code:** `content-builder/src/main/java/com/google/contentbuilder/Main.java`

The Content Builder is purely generative. It assumes the facts in the `research_findings` are accurate (because the Judge approved them) and focuses solely on formatting them into a beautiful markdown course.

```java
LlmAgent builder = LlmAgent.builder()
        .name("content_builder")
        .model("gemini-3-flash-preview")
        .instruction("You are an expert course creator. Formatting Rules...")
        .build();
```

---

## Step 5: The Orchestrator (Loops, State, & Distributed A2A)

**Location in Code:** `orchestrator/src/main/java/com/google/orchestrator/Main.java`

This is where the magic happens. We wire the agents together into a hierarchical graph. 

Because we are building a *production-ready* system, these agents do not live in the same memory space. They are separate Cloud Run microservices. The Orchestrator uses the A2A SDK (`RemoteA2AAgent`) to treat remote HTTP JSON-RPC endpoints as local ADK components.

### 5.1 Custom Escalation Logic
We define a custom `EscalationChecker` agent. It runs after the Judge, reads the structured JSON from the session state, and decides whether to break the loop.

```java
protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    Object feedback = invocationContext.session().state().get("judge_feedback");
    boolean pass = "pass".equals(((JsonNode) feedback).path("status").asText());

    Event.Builder builder = Event.builder().author(name());
    if (pass) {
        // If passed, escalate to break the LoopAgent early!
        builder.actions(EventActions.builder().escalate(true).build());
    }
    return Flowable.just(builder.build());
}
```

### 5.2 Hierarchical Composition
We wrap the Researcher, Judge, and EscalationChecker in a `LoopAgent`, then sequence that loop with the final Content Builder.

```java
LoopAgent researchLoop = LoopAgent.builder()
        .name("research_loop")
        .subAgents(ImmutableList.of(researcher, judge, escalationChecker))
        .maxIterations(3)
        .build();

SequentialAgent pipelineAgent = SequentialAgent.builder()
        .name("course_creation_pipeline")
        .subAgents(ImmutableList.of(researchLoop, contentBuilder))
        .build();
```

---

## Step 6: The Frontend App (Reactive Streaming)

**Location in Code:** `app/src/main/java/com/google/app/Main.java`

The user-facing web server handles the request. To provide a responsive UI, we intercept the ADK Server-Sent Events (SSE) from the Orchestrator as they arrive. Using Java 11's asynchronous `HttpResponse.BodyHandlers.ofLines()`, we map the events into structured NDJSON (`{"type": "progress"...}`) so the UI can show loading states (e.g., "🔍 Researcher is gathering information...").

---

## Step 7: Production Observability & Auth (Java Enhancements)

Our Java implementation goes further than a basic tutorial by implementing production patterns:

*   **Service-to-Service OIDC Auth**: The `HeaderInjectingA2AHttpClient` automatically mints Google Cloud Identity Tokens to secure inter-service communication.
*   **Distributed OpenTelemetry Tracing**: The `Tracing` utility configures a `W3CTraceContextPropagator` to cascade span IDs across all microservices, allowing you to trace the entire workflow in Google Cloud Trace.
