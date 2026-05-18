# Java 25 Equivalent Application

This directory contains the Java 25 multi-module Maven project that replicates the Python ADK-based microservices architecture.

**Reference Original Python Code:** [build-with-ai/production-ready-ai](https://github.com/GoogleCloudPlatform/devrel-demos/tree/main/agents/build-with-ai/production-ready-ai/prai-roadshow-lab-1-complete)

**📖 Read the Tutorial:** We have translated the original Google Codelab into a comprehensive Java guide. If you want to understand how this multi-agent architecture is built step-by-step—including Tool Use, Structured Outputs, and Cloud Run deployment—please read the [Java Codelab (codelab.md)](codelab.md).

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
- [x] **Service-to-Service Authentication**: Implemented robust OIDC Identity Token injection across all service boundaries (`app -> orchestrator` and `orchestrator -> agents`).
- [x] **CORS Configuration**: Implemented a reusable `CorsFilter` extracted into a shared Maven module.
- [x] **OpenTelemetry / Observability**: Full Distributed Trace Context Propagation is implemented across the entire stack using the shared `Tracing` utility.
- [x] **State, Session Management & A2A**: Reusable `A2ARpcHandler` and `HeaderInjectingA2AHttpClient` in the `shared` module eliminate boilerplate.
- [x] **Frontend Streaming Format**: Asynchronous, reactive streams for SSE parsing.

## Core Logic Parity (Python to Java)

A precise audit of the Python ADK logic was conducted to ensure identical execution behavior in Java:

*   **Orchestration Loops**: The Orchestrator correctly implements the `LoopAgent` pattern, iterating between the Researcher and Judge up to 3 times. It includes a custom Java `EscalationChecker` agent that parses session state to break the loop upon a "pass" status, matching the Python logic.
*   **Structured Outputs**: The Judge agent enforces strict JSON output formats (`status` and `feedback`) by passing a `com.google.genai.types.Schema` definition directly to the `outputSchema` property of the `LlmAgent.builder()`.
*   **Prompt Alignment**: The instruction strings for all agents (`researcher`, `judge`, `content_builder`) are mapped verbatim from the Python implementation to ensure consistent LLM outputs.
*   **State Callbacks**: The `afterAgentCallback` implementation captures the outputs (`research_findings` and `judge_feedback`) and writes them to the `ctx.stateDelta()` so downstream agents have historical context during execution.

## Future Improvements / Pending Refinements

- [x] **Full Trace Propagation**: Implemented using `Tracing` utility and `W3CTraceContextPropagator`.
- [x] **Orchestrator-to-Agent Auth**: Implemented using `HeaderInjectingA2AHttpClient` and `GoogleCredentials`.
- [ ] **Structured Logging**: Replace `System.out.println` and `printStackTrace` with SLF4J/Logback for better observability in Cloud Logging.
