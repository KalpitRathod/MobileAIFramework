# 🏛️ MobileAI Framework Architecture & Systems Design

This document details the architectural principles, subsystems, and low-level mechanics of the **MobileAI Framework**.

---

## 1. System Topology & Layers

MobileAI is organized into decoupled layers to separate high-level Android UI presentation from hardware-dependent runtime kernels:

```mermaid
flowchart TD
    subgraph UI ["Layer 1: Application & UI"]
        Chat["Streaming Chat UI"]
        Sensory["Sensory View (Camera/Audio/Screen)"]
        AgentUI["Autonomous Agent UI"]
        KnowledgeUI["Knowledge Base & RAG UI"]
    end

    subgraph Facade ["Layer 2: MobileAI Facade"]
        MobileAIFacade["MobileAI.kt (Facade & Builder)"]
        Options["InferenceOptions & Telemetry"]
        ChunkFlow["Flow&lt;GenerationChunk&gt;"]
    end

    subgraph Hardware ["Layer 3: Hardware Adaptation & Safety"]
        CorePinning["HardwareInspector (Big-Core Pinning)"]
        Thermal["ThermalGovernor (PowerManager)"]
        Budget["MemoryBudgetManager (LMK Guard)"]
        NPUProbe["NpuCapabilityProbe (NNAPI/QNN/APU)"]
    end

    subgraph Engines ["Layer 4: Runtime Engines"]
        LlamaEngine["LlamaNativeEngine (ARM NEON)"]
        MediaPipeEngine["MediaPipeEngine (OpenCL)"]
        OcrEngine["OfflineVisionOcrEngine (ML Kit)"]
        VectorDB["VectorStore (SQLite Cosine)"]
    end

    subgraph Native ["Layer 5: OS & Hardware Silicon"]
        ARM_NEON["ARM Cortex-A7x/X Big Cores"]
        GPU_Silicon["Adreno / Mali GPU"]
        NPU_Silicon["Hexagon / NeuroPilot NPU"]
        OS_LMK["Android OS Kernel & LMK"]
    end

    UI --> Facade
    Facade --> Hardware
    Hardware --> Engines
    Engines --> Native
```

---

## 2. Hardware-Aware Inference Mechanics

### A. CPU Cluster Detection & Thread Pinning
Mobile SoCs group heterogeneous CPU cores into clusters:
- **Little Cores (Efficiency):** Low clock speed, low power, small L2 cache (e.g., Cortex-A55).
- **Big Cores (Performance):** High clock speed, large L2/L3 cache, wide SIMD pipelines (e.g., Cortex-A76, A78, X1-X4).

If an inference loop dispatches across all 8 cores uniformly, **A55 straggler threads** hold the barrier lock, slowing the entire model down to the speed of the slowest core.

MobileAI parses `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` at runtime:
```
Cluster 0 (Little): CPU 0-5 @ 2000 MHz
Cluster 1 (Big):    CPU 6-7 @ 2400 MHz
```
MobileAI computes `optimalBigCoreCount = totalBigCores - 1` (leaving 1 big core for Android UI/rendering), pins threads to this optimal set, and sets thread affinities in C++ / pthreads.

---

### B. Dynamic Thermal Governor State Machine
Mobile chips lack active fans. Continuous matrix multiplication causes temperature spikes of up to 1°C per 10 seconds. 

MobileAI listens to `PowerManager.OnThermalStatusChangedListener`:

```mermaid
stateDiagram-v2
    [*] --> NONE_OR_LIGHT: Device at ambient temperature
    NONE_OR_LIGHT --> MODERATE: Chassis temperature rises
    MODERATE --> SEVERE: Heavy token generation
    SEVERE --> CRITICAL: Dangerous heat level

    state NONE_OR_LIGHT {
        [*] --> FullSpeed: Max Big-Core threads pinned, zero sleep
    }
    state MODERATE {
        [*] --> ScaleThreads: Reduce thread count by 1-2
    }
    state SEVERE {
        [*] --> Pacing: Throttle concurrency, add 15ms inter-token pace
    }
    state CRITICAL {
        [*] --> AbortSafely: Gracefully pause inference to protect battery
    }
```

---

### C. Low-Memory-Killer (LMK) Budget Guard
Android forcibly kills apps (`SIGKILL 9`) when available RAM drops below system thresholds. MobileAI calculates safety limits before model load:

$$\text{Budget Overhead} = \text{Model File Size} + \left(2 \times \text{layers} \times \text{heads} \times \text{head\_dim} \times \text{context\_length} \times \text{bytes\_per\_weight}\right)$$

If `systemAvailableMem - BudgetOverhead < 250\text{ MB}`, MobileAI drops context length:
- $2048 \to 1024 \to 512$
This ensures that the app never crashes due to an out-of-memory exception.

---

## 3. Multimodal & 2-Stage Offline Vision Pipeline

MobileAI pairs online **Gemini 2.5 Flash Vision** with a **2-Stage Offline Vision Repair** pipeline (`ML Kit → On-Device Gemma 2 2B`) so messy handwritten documents and inspection logs remain usable even in Airplane Mode:

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Lens as Google-Lens UI (MainActivity)
    participant Validator as InputValidator / validators.py
    participant OCR as OfflineVisionOcrEngine (ML Kit)
    participant Gemma as LocalAIEngine (Gemma 2 2B + Mutex)
    participant Cloud as Cloud Run Gateway (Gemini 2.5 Flash)
    participant BQ as Google BigQuery

    User->>Lens: Capture Document / Handwriting / QC Sheet
    alt Online (Wi-Fi / 5G)
        Lens->>Cloud: POST /v1/docs/scan-to-table or /v1/qc/scan-and-update
        Cloud->>Validator: Reject gibberish / empty / impossible bounds
        Cloud->>BQ: Stream structured row (scanned_documents / production_inspections)
        Cloud-->>Lens: Dynamic Table JSON + Status
    else Offline / Airplane Mode ($0 Cloud Cost)
        Lens->>OCR: Multi-pass contrast-enhanced ML Kit OCR (~30-390ms)
        OCR-->>Lens: Raw extracted text
        Lens->>Gemma: Stage-2 OCR Repair & Pipe-Table Structuring (Mutex-guarded)
        Gemma-->>Lens: Denoised text / clean table preview
    end
```

---

## 4. Edge-Cloud Hybrid Control Plane & Tokenomics Router

`HybridRouter.kt` makes deterministic, hardware-aware routing decisions on every request to reduce cloud token spend by **60–80%** while preventing Android OOM and thermal failures:

```mermaid
flowchart TD
    Req["Incoming Prompt + Telemetry"] --> Val["InputValidator.kt<br/>Anti-Hallucination Guard"]
    Val -->|Invalid / Gibberish| Reject["Fast Reject (0 Tokens Spent)"]
    Val -->|Valid| Net{"Offline / Airplane Mode?"}
    Net -->|Yes| Local["LOCAL_EDGE<br/>On-Device Gemma 2 2B ($0)"]
    Net -->|No| HW{"LMK RAM < 900 MB OR<br/>Thermal >= SEVERE OR<br/>Battery <= 15%?"}
    HW -->|Yes| Cloud["CLOUD_GEMINI<br/>Gemini 2.5 Flash / Flash-Lite"]
    HW -->|No| Score{"Complexity / Intent Score >= 60?<br/>(Coding, Live World, Heavy Vision)"}
    Score -->|Yes| Cloud
    Score -->|No| Local
    Local --> Telemetry["Async Telemetry → Cloud Run → BigQuery (routing_events)"]
    Cloud --> Telemetry
```

### Key Cloud Control Plane Components (`server/`)
- **`server/main.py`**: FastAPI gateway deployed on **Google Cloud Run** (`--concurrency 40`, stress-tested 20/20 simultaneous HTTP 200 requests).
- **`server/doc_table_engine.py`**: Schema-agnostic document digitizer converting arbitrary documents into `columns_json` + `rows_json` in BigQuery `scanned_documents`.
- **`server/qc_engine.py`**: Thread-safe (`threading.Lock`) ShopFloor CNC quality inspection engine + natural-language **Query & Update** Copilot backed by BigQuery `production_inspections`.
- **`server/validators.py`**: Strict anti-hallucination guardrail rejecting empty, gibberish, keyboard-mash, non-dimensional, and physically impossible inputs with zero BigQuery pollution.
- **`server/analytics_agent.py`**: Conversational Fleet Tokenomics agent answering natural-language queries over `routing_events`.
- **Google Cloud Storage Model CDN**: Public bucket serving `models_manifest.json` and quantized `.gguf` weights consumed by `ModelDownloadManager.kt`.

### Native Concurrency & UI Stability
- **`LocalAIEngine.kt`**: Serializes `llama.cpp` JNI initialization and token streaming with `kotlinx.coroutines.sync.Mutex` plus active job guards in `MainActivity.kt` so rapid concurrent taps never cause native `SIGSEGV` crashes.
- **`ChatAdapter.kt`**: Uses payload-based in-place binding (`onBindViewHolder(..., payloads)`) with `itemAnimator = null` for zero-jitter token streaming.

---

## 5. On-Device SQLite Vector Store & RAG

MobileAI's `VectorStore` uses an embedded SQLite database storing text chunks alongside serialized float arrays:

- **Distance Metric:** Cosine similarity:
$$\text{Cosine}(\vec{u}, \vec{v}) = \frac{\sum_{i=1}^n u_i v_i}{\sqrt{\sum_{i=1}^n u_i^2} \sqrt{\sum_{i=1}^n v_i^2}}$$
- **Query Pipeline:**
  1. User enters natural language query.
  2. Query is converted to vector embedding via `mobileAI.embed(query)`.
  3. Vectors in namespace are queried with top-K cosine ranking.
  4. Top-ranked text chunks are formatted into an augmented prompt:
     `Context: [Retrieved Text] \n\n Question: [User Query]`.
  5. SLM generates accurate answer grounded in local documents.

---

## 6. Autonomous Device Agent

The agent is powered by `PhoneControllerAccessibilityService`:
1. **Perception:** Traverses active `AccessibilityNodeInfo` tree to extract clickable nodes, bounds, text labels, and input fields.
2. **Reasoning:** Local SLM (or Cloud model) evaluates current screen state against user's goal.
3. **Action:** Dispatches low-level Android gestures:
   - `performAction(ACTION_CLICK)`
   - `dispatchGesture(GestureDescription)` for swipes and scrolls
   - `performAction(ACTION_SET_TEXT)` for input entry
   - `performGlobalAction(GLOBAL_ACTION_HOME / BACK)`
