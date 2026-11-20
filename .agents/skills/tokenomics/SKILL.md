---
name: tokenomics-router
description: Rules and guardrails for edge-cloud hybrid tokenomics, reducing cloud spend by 60-80% through intelligent on-device local model routing.
version: 1.0.0
---

# Tokenomics Hybrid Routing Guardrails

## Core Objective
Maximize on-device execution for simple queries, OCR, personal knowledge retrieval, and real-time audio tasks to eliminate unnecessary cloud token consumption, while reserving Cloud Run / Google Gemini for high-complexity analytical reasoning and large-context synthesis.

## Routing Rules
1. **Offline & Low Connectivity**:
   - If network is unavailable, disconnected, or high latency (>1500ms), route 100% to edge models (`smollm2-360m`, `gemma-2-2b`).
   - Never block user interaction on remote API timeouts when local fallback is available.

2. **Prompt Complexity Scoring (0 to 100)**:
   - Score < 45: Route to Local Edge (Zero cloud cost, ~18 tok/s on MediaTek/Snapdragon).
   - Score >= 45: Route to Cloud Run Hybrid Gateway / Gemini 1.5.
   - Code generation, multi-step math reasoning, deep document comparison -> Cloud Run.
   - Summarization (<500 words), notes search, translation, OCR extraction -> Local Edge.

3. **Multimodal Routing**:
   - Single image + text extraction -> ML Kit On-Device OCR + Local LLM.
   - Live continuous video / complex visual scene understanding -> Gemini Live WebSocket.

4. **Thermal & Battery Protection**:
   - Thermal state `SEVERE` or `CRITICAL` -> Escalate compute to Cloud to prevent device overheating, or drop batch size.
   - Battery < 15% -> Favor cloud if Wi-Fi connected to conserve device battery drain from NPU/CPU inference.

## Telemetry & Logging
Every decision produces a structured `RoutingEvent` streamed to Google BigQuery:
- `query_id`, `device_id`, `device_model`, `chipset`
- `route_target`: `EDGE_LOCAL` vs `CLOUD_RUN` vs `GEMINI_LIVE`
- `input_tokens`, `output_tokens`, `estimated_cloud_cost_saved_usd`
- `latency_ms`, `battery_level`, `thermal_state`
