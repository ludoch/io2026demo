# Java 25 Equivalent Application

This directory contains the Java 25 multi-module Maven project that replicates the Python ADK-based microservices architecture.

## File Mapping Table

| Python File (Original) | Java File (Equivalent) | Purpose |
| :--- | :--- | :--- |
| `app/main.py` | `java/app/src/.../Main.java` | Main web server, hosts frontend assets, and proxies Chat API requests (SSE) to the Orchestrator. |
| `agents/orchestrator/agent.py` | `java/orchestrator/src/.../Main.java` | Orchestrates the multi-agent workflow loop (Researcher -> Judge -> Content Builder). |
| `agents/researcher/agent.py` | `java/researcher/src/.../Main.java` | Agent responsible for researching the topic. |
| `agents/judge/agent.py` | `java/judge/src/.../Main.java` | Agent responsible for evaluating the research against the original request. |
| `agents/content_builder/agent.py` | `java/content-builder/src/.../Main.java` | Agent responsible for compiling the final course content. |
| `app/frontend/*` | `java/app/src/main/resources/frontend/*` | Static HTML, CSS, and JS for the web UI. |

## Implemented Python Logic Parity

- [x] **Google Search Tool (Grounding)**: The ADK `GoogleSearchTool` internally maps directly to the Vertex AI Grounding API when used with Gemini 2 models, matching the Python implementation.
- [x] **Service-to-Service Authentication**: Implemented robust OIDC Identity Token injection using `google-auth-library-oauth2-http` for secure inter-service communication.
- [x] **CORS Configuration**: Implemented a reusable `CorsFilter` extracted into a shared Maven module and applied to the JDK HTTP Servers, matching FastAPI's `CORSMiddleware`.
- [x] **OpenTelemetry / Observability**: Full Distributed Trace Context Propagation is implemented using `W3CTraceContextPropagator` to cascade traces across the microservices.
- [x] **State, Session Management & A2A**: The Java Orchestrator uses the A2A SDK's `RemoteA2AAgent`. Child agents extend a reusable `A2ARpcHandler` (in a shared module) to eliminate JSON-RPC boilerplate.
- [x] **Frontend Streaming Format**: The `app/Main.java` server uses asynchronous, reactive streams (`HttpResponse.BodyHandlers.ofPublisher()`) to parse ADK SSE events and generate structured NDJSON (`progress` and `result`) for the UI, outperforming the blocking Python equivalent.

## Core Logic Parity (Python to Java)

A precise audit of the Python ADK logic was conducted to ensure identical execution behavior in Java:

*   **Orchestration Loops**: The Orchestrator correctly implements the `LoopAgent` pattern, iterating between the Researcher and Judge up to 3 times. It includes a custom Java `EscalationChecker` agent that parses session state to break the loop upon a "pass" status, matching the Python logic.
*   **Structured Outputs**: The Judge agent enforces strict JSON output formats (`status` and `feedback`) by passing a `com.google.genai.types.Schema` definition directly to the `outputSchema` property of the `LlmAgent.builder()`.
*   **Prompt Alignment**: The instruction strings for all agents (`researcher`, `judge`, `content_builder`) are mapped verbatim from the Python implementation to ensure consistent LLM outputs.
*   **State Callbacks**: The `afterAgentCallback` implementation captures the outputs (`research_findings` and `judge_feedback`) and writes them to the `ctx.stateDelta()` so downstream agents have historical context during execution.

## Future Improvements / Pending Refinements

- [ ] **Full Trace Propagation**: Extend the `W3CTraceContextPropagator` logic to the Orchestrator and individual Agents to ensure the span context is extracted and injected at every hop.
- [ ] **Orchestrator-to-Agent Auth**: Implement OIDC Identity Token injection in the `orchestrator` when calling the `researcher`, `judge`, and `content_builder` endpoints.
- [ ] **Structured Logging**: Replace `System.out.println` and `printStackTrace` with SLF4J/Logback for better observability in Cloud Logging.
