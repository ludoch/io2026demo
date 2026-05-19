# Codelab: Building a Production-Ready Multi-Agent System (Java)

Welcome to the Java edition of the **Building a Multi-Agent System** codelab!

This codelab teaches you how to transition from a monolithic LLM application to a distributed, microservice-based architecture using the **Agent Development Kit (ADK)** and Java 25.

You will build a system that can iteratively research a topic and write a high-quality educational course.

---

## Prerequisites & Google Cloud Setup

Before diving into the code, ensure you have **Java 25** (or a compatible LTS) and **Maven 3.8+** installed. Then, configure your Google Cloud environment using the following one-shot commands:

```bash
# 1. Login to Google Cloud (CLI and Application Default Credentials for local testing)
gcloud auth login
gcloud auth application-default login

# 2. Set your Project ID
export PROJECT_ID="your-google-cloud-project-id"
gcloud config set project $PROJECT_ID

# 3. Enable Required APIs
gcloud services enable \
  aiplatform.googleapis.com \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  cloudtrace.googleapis.com

# 4. Grant IAM permissions to the default Compute Engine Service Account
# (This is the default identity used by Cloud Run instances)
PROJECT_NUM=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")
COMPUTE_SA="${PROJECT_NUM}-compute@developer.gserviceaccount.com"

# Allow Cloud Run to call Vertex AI (Gemini)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${COMPUTE_SA}" \
  --role="roles/aiplatform.user"

# Allow Cloud Run to write Distributed Traces
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${COMPUTE_SA}" \
  --role="roles/cloudtrace.agent"
```

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
        .instruction("You are an expert course creator.\n" +
                     "Take the research findings and transform them into a well-structured, engaging course module.\n" +
                     "Formatting Rules:\n" +
                     "1. Start with a main title using a single `#` (H1).\n" +
                     "2. Use `##` (H2) for the Table of Contents.\n" +
                     "3. Use bullet points and clear paragraphs.\n" +
                     "4. Maintain a professional but engaging tone.\n\n" +
                     "Ensure the content directly addresses the user's original request.")
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

## Step 6: The Frontend App (Session Management & Reactive Streaming)

**Location in Code:** `app/src/main/java/com/google/app/Main.java`

The user-facing web server handles the request. To provide a responsive and stateful UI:

1.  **Session Lifecycle**: Before querying the LLM, the frontend app fetches an OIDC-authenticated `sessionId` from the Orchestrator's `/sessions` API to maintain history.
2.  **SSE Streaming**: We intercept the ADK Server-Sent Events (SSE) from the Orchestrator via the `/run_sse` endpoint as they arrive. Using Java 11's asynchronous `HttpResponse.BodyHandlers.ofLines()` combined with Virtual Threads, we map the events into structured NDJSON (e.g., `{"type": "progress", "text": "..."}`) so the UI can show loading states (e.g., "🔍 Researcher is gathering information...").


---

## Step 7: Production Observability & Auth (Java Enhancements)

Our Java implementation goes further than a basic tutorial by implementing production patterns:

*   **Service-to-Service OIDC Auth**: The `HeaderInjectingA2AHttpClient` automatically mints Google Cloud Identity Tokens to secure inter-service communication.
*   **Distributed OpenTelemetry Tracing**: The `Tracing` utility configures a `W3CTraceContextPropagator` to cascade span IDs across all microservices, allowing you to trace the entire workflow in Google Cloud Trace.

---

## Step 8: Running Locally

To test the multi-agent system on your machine, you must run each microservice on a distinct port. Because they communicate via HTTP, they act exactly as they would in production.

Open 5 separate terminal windows and run the following Maven commands:

1.  **Researcher:** `PORT=8002 mvn exec:java -pl researcher -Dexec.mainClass="com.google.researcher.Main"`
2.  **Judge:** `PORT=8003 mvn exec:java -pl judge -Dexec.mainClass="com.google.judge.Main"`
3.  **Content Builder:** `PORT=8004 mvn exec:java -pl content-builder -Dexec.mainClass="com.google.contentbuilder.Main"`
4.  **Orchestrator:** `PORT=8001 RESEARCHER_URL="http://localhost:8002" JUDGE_URL="http://localhost:8003" CONTENT_BUILDER_URL="http://localhost:8004" mvn exec:java -pl orchestrator -Dexec.mainClass="com.google.orchestrator.Main"`
5.  **Frontend App:** `PORT=8000 AGENT_URL="http://localhost:8001" mvn exec:java -pl app -Dexec.mainClass="com.google.app.Main"`

Visit `http://localhost:8000` in your browser to interact with the system!

---

## Step 9: Deploying to Cloud Run

The true power of the A2A protocol is that these agents can be deployed as independent, auto-scaling serverless containers.

We will use Cloud Native Buildpacks (`gcloud run deploy --source .`) to automatically containerize and deploy our Java 25 application without needing a Dockerfile. Run these commands sequentially to deploy the entire system:

```bash
# Set your target region
export REGION="us-central1"

# 1. Deploy the Researcher Agent
gcloud run deploy researcher \
  --source . \
  --command "java,-cp,researcher/target/classes:researcher/target/dependency/*,com.google.researcher.Main" \
  --set-env-vars="MAVEN_OPTS=-DskipTests" \
  --region $REGION \
  --allow-unauthenticated

# 2. Deploy the Judge Agent
gcloud run deploy judge \
  --source . \
  --command "java,-cp,judge/target/classes:judge/target/dependency/*,com.google.judge.Main" \
  --set-env-vars="MAVEN_OPTS=-DskipTests" \
  --region $REGION \
  --allow-unauthenticated

# 3. Deploy the Content Builder Agent
gcloud run deploy content-builder \
  --source . \
  --command "java,-cp,content-builder/target/classes:content-builder/target/dependency/*,com.google.contentbuilder.Main" \
  --set-env-vars="MAVEN_OPTS=-DskipTests" \
  --region $REGION \
  --allow-unauthenticated

# 4. Fetch the deployed URLs for the child agents
RESEARCHER_URL=$(gcloud run services describe researcher --region $REGION --format 'value(status.url)')
JUDGE_URL=$(gcloud run services describe judge --region $REGION --format 'value(status.url)')
CONTENT_BUILDER_URL=$(gcloud run services describe content-builder --region $REGION --format 'value(status.url)')

# 5. Deploy the Orchestrator, linking the child agent URLs
gcloud run deploy orchestrator \
  --source . \
  --command "java,-cp,orchestrator/target/classes:orchestrator/target/dependency/*,com.google.orchestrator.Main" \
  --region $REGION \
  --set-env-vars="RESEARCHER_URL=${RESEARCHER_URL},JUDGE_URL=${JUDGE_URL},CONTENT_BUILDER_URL=${CONTENT_BUILDER_URL},MAVEN_OPTS=-DskipTests" \
  --allow-unauthenticated

# 6. Fetch the Orchestrator URL
ORCHESTRATOR_URL=$(gcloud run services describe orchestrator --region $REGION --format 'value(status.url)')

# 7. Deploy the Frontend App, linking the Orchestrator URL
gcloud run deploy app \
  --source . \
  --command "java,-cp,app/target/classes:app/target/dependency/*,com.google.app.Main" \
  --region $REGION \
  --set-env-vars="AGENT_URL=${ORCHESTRATOR_URL},MAVEN_OPTS=-DskipTests" \
  --allow-unauthenticated
```

**Congratulations!** You have successfully built and deployed a production-ready, multi-agent system in Java.
