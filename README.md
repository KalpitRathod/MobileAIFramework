# 📱 MobileAI Framework

[![License: MIT](https://img.shields.io/badge/License-MIT-gold.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-blue.svg)](https://developer.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin%20100%25-7F52FF.svg)](https://kotlinlang.org)
[![Engine: llama.cpp](https://img.shields.io/badge/Engine-llama.cpp%20(ARM%20NEON)-orange.svg)](https://github.com/ggerganov/llama.cpp)
[![Vision: ML Kit](https://img.shields.io/badge/Vision%20OCR-100%25%20Offline-success.svg)](https://developers.google.com/ml-kit)
[![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen.svg)](CONTRIBUTING.md)
[![Community: Open](https://img.shields.io/badge/Community-Active%20Discussions-purple.svg)](https://github.com/KalpitRathod/MobileAIFramework/discussions)

**MobileAI Framework** is an open-source, production-grade on-device AI runtime and application framework built exclusively for mobile devices. It empowers developers and researchers to deploy, benchmark, and orchestrate modern Small Language Models (SLMs), Vector Databases, Multimodal Vision OCR, and Autonomous Device Agents directly on Android smartphones—from entry-level budget chipsets to flagship silicon.

---

## 🚀 Why MobileAI Framework?

Running large models on edge mobile hardware presents unique systems challenges: aggressive thermal throttling, Android Low-Memory-Killer (LMK) eviction, heterogenous big.LITTLE core straggler stalls, and battery drain. 

MobileAI solves these fundamental mobile bottlenecks with a deep, hardware-aware architecture:

```
┌────────────────────────────────────────────────────────────────────────┐
│                      Mobile AI Application Layer                       │
│  Streaming Chat  │  Sensory Vision  │  Autonomous Agent │  RAG Studio  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                    MobileAI Central Engine & Facade                    │
│   • Builder Configuration        • Reactive Flow<GenerationChunk>      │
│   • Model Dispatch (.gguf/.bin)  • Hybrid Cloud Fallback Gateway       │
├───────────────────────────────────┬────────────────────────────────────┤
│         Hardware & Systems        │         Multimodal & Agent         │
│  • CPU Big-Core Cluster Pinning   │  • Offline ML Kit Vision & OCR     │
│  • Dynamic Thermal Governor       │  • Autonomous Phone Controller     │
│  • LMK RAM & KV-Cache Budget      │  • SQLite Cosine Vector DB (RAG)   │
│  • NPU / NNAPI Capability Prober  │  • Real-Time Audio / Screen Stream │
└───────────────────────────────────┴────────────────────────────────────┘
```

---

## ✨ Key Pillars & Innovations

### 1. 🏎️ CPU Big-Core Cluster Pinning
Heterogeneous mobile SoCs (big.LITTLE / DynamIQ) suffer from severe thread stragglers when lightweight OS threads execute alongside high-intensity matrix SIMD kernels. MobileAI inspects Linux sysfs (`/sys/devices/system/cpu/`) to detect core frequencies and CPU cache boundaries, automatically pinning `llama.cpp` inference threads strictly to **high-performance Big Cores** (Cortex-A7x/X series), yielding up to **3x higher tokens per second** while leaving Little cores free for Android UI rendering.

### 2. 🌡️ Dynamic Thermal Governor
Long generative inference sessions cause device chassis temperatures to spike. MobileAI integrates directly with Android's hardware `PowerManager.OnThermalStatusChangedListener`. At `THERMAL_STATUS_MODERATE` or `SEVERE`, the governor dynamically throttles thread concurrency and introduces micro-sleep pacing, preventing thermal runaway and harsh OS-level frequency cliffs.

### 3. 🛡️ LMK RAM & KV-Cache Guard
Android's Low Memory Killer kills processes when memory pressure crosses thresholds. MobileAI calculates runtime memory budgets before loading weights:
$$\text{Memory Overhead} = \text{Model Weights (MB)} + \text{Context Size} \times \text{KV Cache Footprint}$$
If headroom is insufficient, MobileAI automatically adjusts context sizes (`2048` → `1024` → `512`) to ensure 100% crash-free stability even on 3GB RAM devices.

### 4. 📷 100% Offline Vision & OCR Engine
Vision models in GGUF format often require 3GB+ weights that budget phones cannot support. MobileAI features an embedded on-device Google ML Kit text recognition engine that extracts structured text from photos and screenshots in **~30ms completely offline**. It supports:
- **Instant Text Extraction:** Instant zero-latency OCR conversion.
- **Vision-Augmented Reasoning:** OCR text is automatically injected into the SLM prompt context, allowing compact models like SmolLM2 to summarize, analyze, and explain camera snapshots offline.

### 5. 🔍 Embedded SQLite Vector Database & On-Device RAG
A zero-dependency, self-contained Vector Store implemented directly on top of Android SQLite. Computes **vector cosine similarity** on high-dimensional embeddings on-device, enabling Retrieval-Augmented Generation (RAG) over local documents and personal knowledge bases without any cloud API dependency.

### 6. 🤖 Autonomous Phone Controller Agent
Equipped with an Android `AccessibilityService` bridge. The agent can perceive the on-screen UI hierarchy, parse interactive elements, and autonomously tap, scroll, input text, and navigate between apps based on high-level natural language instructions.

### 7. ☁️ Seamless Hybrid Cloud Gateway
Switch with a single toggle between local zero-cost edge inference and cloud reasoning. Supports **Google Gemini (1.5 Flash/Pro, 2.0)**, **Anthropic Claude (3.5 Sonnet)**, and **OpenAI (GPT-4o)** when deep multi-turn reasoning or massive 1M context windows are desired.

---

## 📊 Benchmarks & Model Compatibility

Tested on real hardware (**MediaTek Helio G85 / Dimensity 6100+**, **Qualcomm Snapdragon 778G / 8 Gen 2**):

| Model | Format / Quant | Size | Peak RAM | Generation Speed | Best For |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **SmolLM2-360M-Instruct** *(Default)* | `.gguf` (Q4_K_M) | **258 MB** | ~320 MB | **30 – 45 tok/s** | Ultra-responsive chat, edge agent, battery friendly |
| **Qwen2.5-0.5B-Instruct** | `.gguf` (Q4_K_M) | **398 MB** | ~480 MB | **22 – 35 tok/s** | Code snippets, math logic, structured JSON |
| **Gemma-2-2B-Instruct** | `.gguf` (Q4_K_M) | **1.71 GB** | ~1.65 GB | **4 – 8 tok/s** | Deep reasoning, long explanations |
| **SmolLM2-135M-Instruct** | `.gguf` (Q4_K_M) | **90 MB** | ~170 MB | **50 – 70 tok/s** | Ultra-budget devices, background automation |
| **Qwen2.5-1.5B-Instruct** | `.gguf` (Q4_K_M) | **986 MB** | ~1.2 GB | **8 – 14 tok/s** | Balanced multilingual chat |

---

## 🛠️ Quick Start (Integrate MobileAI into Your App)

### 1. Initialize the Runtime
```kotlin
val mobileAI = MobileAI.Builder(context)
    .enableThermalGovernor(true)
    .preferBigCoresOnly(true)
    .setDefaultContextSize(2048)
    .setDefaultMaxTokens(1024)
    .setTemperature(0.7f)
    .build()

// Load GGUF model from device storage
val result = mobileAI.initializeModel("/path/to/smollm2-360m-instruct-q4_k_m.gguf")
```

### 2. Stream Tokens in Real-Time
```kotlin
lifecycleScope.launch {
    mobileAI.generateStream("Explain quantum entanglement concisely.")
        .collect { chunk ->
            print(chunk.text) // Live token accumulation
            println("Speed: ${chunk.tokensPerSecond} tok/s")
        }
}
```

### 3. Extract Text Offline (Vision OCR)
```kotlin
val ocrResult = mobileAI.visionOcrEngine.recognizeTextFromBitmap(bitmap)
val extractedText = ocrResult.getOrNull()
println("Extracted: $extractedText")
```

### 4. Semantic Search & On-Device RAG
```kotlin
// Index documents into SQLite Vector Store
mobileAI.vectorStore.insertVector(
    id = "doc_1",
    namespace = "notes",
    text = "Battery temperature should remain below 42 degrees C.",
    vector = mobileAI.embed("Battery temperature safe limits").getOrThrow()
)

// Search top-K similar entries
val results = mobileAI.semanticSearch.query("How hot can the phone get?", topK = 3)
```

---

## 📂 Project Structure

```
MobileAIFramework/
├── app/
│   ├── src/main/java/com/
│   │   ├── mobileai/framework/
│   │   │   ├── MobileAI.kt                  # Central facade & builder
│   │   │   ├── core/                        # InferenceOptions & GenerationChunk
│   │   │   ├── engine/                      # LlamaNativeEngine (GGUF) & MediaPipe
│   │   │   ├── hardware/                    # HardwareInspector, Big-Core Pinning, ThermalGovernor
│   │   │   ├── npu/                         # NpuCapabilityProbe & NpuBackendSelector
│   │   │   ├── vector/                      # VectorStore (SQLite) & SemanticSearch (RAG)
│   │   │   ├── multimodal/                  # OfflineVisionOcrEngine, Camera, Audio, Screen
│   │   │   ├── agent/                       # DeviceAgent & AccessibilityController
│   │   │   └── cloud/                       # CloudAiClient (Gemini, Claude, OpenAI)
│   │   └── phoneai/localmodel/              # Mobile AI Studio Android App (UI, Adapters, Views)
│   └── src/test/                            # Unit tests (Core info, memory budget, vector math)
├── .github/
│   ├── workflows/android-ci.yml             # Automated CI Build & Test
│   ├── ISSUE_TEMPLATE/                      # Bug report, Feature request, Benchmarks
│   └── PULL_REQUEST_TEMPLATE.md             # Contributor PR checklist
├── CONTRIBUTING.md                          # Contribution guidelines
├── CODE_OF_CONDUCT.md                       # Community Code of Conduct
├── ROADMAP.md                               # Framework Roadmap
└── ARCHITECTURE.md                          # Detailed Architecture & Design
```

---

## 🤝 Join the Community & Contribute

We welcome contributions from developers, researchers, and mobile enthusiasts around the globe!

- **Report a Bug:** Found a crash or hardware issue on a specific SoC? [Open an Issue](https://github.com/KalpitRathod/MobileAIFramework/issues/new?template=bug_report.yml).
- **Submit a Device Benchmark:** Run MobileAI on your smartphone and submit numbers to our [Community Benchmark Database](https://github.com/KalpitRathod/MobileAIFramework/issues/new?template=device_benchmark.yml).
- **Propose a Feature:** Suggest new models, NPU delegates, or quantization modes in [Discussions](https://github.com/KalpitRathod/MobileAIFramework/discussions).
- **Code Contributions:** Read our [Contributing Guide](CONTRIBUTING.md) to set up your environment, write tests, and submit Pull Requests.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE). You are free to use, modify, distribute, and integrate it into private or commercial projects.
