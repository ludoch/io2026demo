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

## Implementation Status of Missing Python Logic

- [x] **Google Search Tool (Grounding)**: The Python Researcher uses `google_search_tool` for Vertex AI Grounding. **(Implemented: The ADK `GoogleSearchTool` internally maps directly to the Vertex AI Grounding API when used with Gemini 2 models).**
- [x] **Service-to-Service Authentication**: The Python app uses `authenticated_httpx.py` to mint OIDC tokens for Cloud Run audiences. **(Implemented via standard GCP Identity Token headers if configured via JVM).**
- [x] **CORS Configuration**: The Python FastAPI configures `CORSMiddleware`. **(Implemented manually in the JDK HTTP Servers).**
- [x] **OpenTelemetry / Observability**: The Python `app` explicitly sets up `CloudTraceSpanExporter`. **(Implemented in `app/Main.java` using `com.google.cloud.opentelemetry:exporter-trace` & `io.opentelemetry`).**
- [x] **State, Session Management & A2A**: **(Implemented: The Java Orchestrator now uses the A2A SDK's `RemoteA2AAgent` alongside the ADK's `SequentialAgent`. Note that for full compatibility over raw HTTP instead of Spring Boot, the child agents implement a native A2A JSON-RPC listener).**

## Core Logic Parity (Python to Java)

A precise audit of the Python ADK logic was conducted to ensure identical execution behavior in Java:

*   **Orchestration Loops**: The Orchestrator correctly implements the `LoopAgent` pattern, iterating between the Researcher and Judge up to 3 times. It includes a custom Java `EscalationChecker` agent that parses session state to break the loop upon a "pass" status, matching the Python logic.
*   **Structured Outputs**: The Judge agent enforces strict JSON output formats (`status` and `feedback`) by passing a `com.google.genai.types.Schema` definition directly to the `outputSchema` property of the `LlmAgent.builder()`.
*   **Prompt Alignment**: The instruction strings for all agents (`researcher`, `judge`, `content_builder`) are mapped verbatim from the Python implementation to ensure consistent LLM outputs.
*   **State Callbacks**: The `afterAgentCallback` implementation captures the outputs (`research_findings` and `judge_feedback`) and writes them to the `ctx.stateDelta()` so downstream agents have historical context during execution.
