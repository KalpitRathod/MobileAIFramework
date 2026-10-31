# 🗺️ MobileAI Framework Community Roadmap

The community roadmap outlines our goals for turning MobileAI into the leading, universally-compatible on-device AI runtime for mobile hardware. Community feedback, PRs, and architectural proposals are actively welcomed!

---

## 🟢 Phase 1: Core Systems & Engine Foundation *(Completed & Live)*
- [x] **ARM NEON Big-Core Pinning:** CPU cluster detection (`/sys/devices/system/cpu/`) and dynamic thread affinity pinning.
- [x] **Dynamic Thermal Governor:** Integration with Android `PowerManager.OnThermalStatusChangedListener` for thermal-adaptive scaling.
- [x] **RAM & KV-Cache Guard:** Active memory budgeting preventing Android Low Memory Killer (LMK) aborts on 3GB/4GB budget devices.
- [x] **Embedded SQLite Vector Database:** Zero-dependency cosine similarity vector database for on-device RAG.
- [x] **Offline Vision & OCR Engine:** Embedded Google ML Kit text recognition running in ~30ms offline with SLM prompt enrichment.
- [x] **Autonomous Phone Controller Agent:** Android `AccessibilityService` agent executing natural language UI goals.
- [x] **Universal Chipset Prober:** SoC detection for MediaTek, Qualcomm, Exynos, and Tensor.
- [x] **Hybrid Cloud Fallback Gateway:** Zero-friction fallback to Gemini 1.5/2.0, Claude 3.5 Sonnet, and GPT-4o.

---

## 🟡 Phase 2: Dedicated NPU & GPU Acceleration *(Active / Next Up)*
- [ ] **Qualcomm QNN Delegate:** Native execution on Hexagon NPUs (Snapdragon 7 Gen 3, 8 Gen 2/3/4) via Qualcomm Neural Processing SDK.
- [ ] **MediaTek NeuroPilot APU:** Direct binding to MediaTek NeuroPilot NPU runtime for Dimensity 7000/8000/9000 chipsets.
- [ ] **Vulkan Compute Pipeline:** General-purpose Vulkan compute shaders for accelerated prompt evaluation on ARM Mali and Adreno GPUs.
- [ ] **ARMv9 SVE2 Optimizations:** Exploiting 128-bit/256-bit Scalable Vector Extension 2 for 4-bit and 8-bit integer dot products.
- [ ] **Model Auto-Benchmarking Harness:** Built-in benchmarking test suite reporting tokens/sec, time-to-first-token (TTFT), and memory footprint.

---

## 🟣 Phase 3: Edge Multimodal & Low-Latency Voice
- [ ] **On-Device Whisper STT:** Real-time on-device speech transcription using quantized Whisper (Tiny/Base) via native JNI.
- [ ] **On-Device TTS:** Neural voice generation using lightweight ONNX or Piper voice models (<30MB footprint).
- [ ] **Zero-Copy Camera Frame Pipeline:** Direct hardware buffer (`HardwareBuffer` / `ImageReader`) binding to vision projectors without intermediate JPEG compression.
- [ ] **Native GGUF Vision Projector JNI:** Native `libllama` bridge exposing `llava_image_embed` for direct Qwen2-VL and SmolVLM multimodal inference.

---

## 🔵 Phase 4: Agent Reasoning & Structured Outputs
- [ ] **Constrained Decoding & Structured JSON:** Grammar-based sampling (GBNF) ensuring 100% valid JSON outputs for function calling.
- [ ] **Function / Tool Calling Registry:** Ability to declare Kotlin functions with annotated schemas that SLMs can invoke autonomously.
- [ ] **Dual-Agent Architecture:** Collaborative Planner agent (reasoning & breakdown) + Executor agent (accessibility action dispatch).
- [ ] **Offline Kiwix / ZIM Reader Integration:** Fast C++ reader for massive offline Wikipedia archives without unpacking.

---

## ⚪ Phase 5: Ecosystem & Library Distribution
- [ ] **Maven Central SDK:** Standalone AAR artifact published to Maven Central (`io.github.mobileai:core:1.0.0`).
- [ ] **Flutter & React Native Plugins:** Cross-platform wrappers for building on-device AI apps in Flutter and React Native.
- [ ] **Community Model Hub:** Standardized repository for tested, verified, and pre-quantized GGUF models tailored for mobile memory limits.

---

## 💡 How to Propose or Lead a Roadmap Item
Want to implement one of these items or suggest a new feature?
1. Open a discussion under [Feature Ideas](https://github.com/KalpitRathod/MobileAIFramework/discussions).
2. Outline your technical approach, target hardware, and anticipated impact.
3. Collaborate with community maintainers and submit your Pull Request!
