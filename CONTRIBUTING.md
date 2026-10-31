# Contributing to MobileAI Framework

Welcome to the **MobileAI Framework** open-source community! 🚀

We are building the definitive runtime and application framework for hardware-accelerated on-device AI on mobile devices. Whether you are optimizing low-level ARM NEON kernels, adding NPU hardware delegates, training lightweight models, or refining UI components, your contributions are invaluable.

---

## 📜 Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md). Please treat all contributors with respect and professionalism.

---

## 🛠️ Development Environment & Setup

### Prerequisites
1. **Java Development Kit (JDK):** JDK 17 (recommended: Eclipse Temurin 17 or Android Studio bundled JBR 17).
2. **Android SDK:** API Level 34 (Android 14) with build-tools `34.0.0`.
3. **Android Studio:** Android Studio Ladybug (2024.2+) or later.
4. **Android NDK:** Required for native JNI and C++ compilation (optional for Kotlin-only changes).
5. **Physical Android Device:** Recommended for validating CPU cluster pinning and thermal behavior (MediaTek, Qualcomm, Exynos, or Tensor).

### Building Locally

1. **Clone the repository:**
   ```bash
   git clone https://github.com/KalpitRathod/MobileAIFramework.git
   cd MobileAIFramework
   ```

2. **Run Unit Tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```

3. **Assemble the Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```
   The compiled APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

4. **Deploy Model Weights to Device:**
   You can push any GGUF model (e.g. `smollm2-360m-instruct-q4_k_m.gguf`) using our helper script:
   ```powershell
   .\push_model.ps1 smollm2-360m-instruct-q4_k_m.gguf
   ```

---

## 🎯 How You Can Contribute

### 1. Hardware & NPU Acceleration
- **Qualcomm QNN:** Implementing Qualcomm Neural Processing SDK delegates.
- **MediaTek NeuroPilot:** Adding APU/NPU execution providers.
- **Vulkan / OpenCL:** Enhancing GPU offloading on Mali and Adreno GPUs.
- **CPU Kernel Optimization:** SIMD tuning (ARM NEON / SVE2) for matrix multiplication.

### 2. Models & Quantization
- Benchmarking new Small Language Models (SLMs) such as SmolLM2, Qwen2.5, Gemma-2, Llama-3.2, and DeepSeek-R1-Distill.
- Testing mixed quantization schemes (IQ2, IQ3, Q4_K_M, Q8_0) for optimal quality vs. RAM trade-offs.

### 3. Vector Database & RAG
- Adding HNSW (Hierarchical Navigable Small World) graph indexing for large embedded vector stores.
- Adding binary vector quantization for 8x memory reduction.
- Implementing hybrid search (lexical BM25 + cosine vector similarity).

### 4. Multimodal & Audio
- Integrating on-device Whisper models for ultra-low latency voice transcription.
- Implementing on-device Text-to-Speech (TTS) using Piper or ONNX runtime.
- Enhancing camera frame sampling efficiency.

### 5. Autonomous Device Agent
- Expanding UI automation actions in `PhoneControllerAccessibilityService`.
- Enhancing accessibility node parsing for complex Flutter/Compose view hierarchies.
- Adding safety guards and human-in-the-loop confirmation dialogs for sensitive actions.

---

## 🔀 Git Workflow & Submitting Pull Requests

1. **Fork the Repository** on GitHub.
2. **Create a Topic Branch:**
   ```bash
   git checkout -b feat/qnn-npu-delegate
   ```
3. **Follow Conventional Commits:**
   - `feat:` New capability or feature
   - `fix:` Bug fix
   - `perf:` Performance or memory optimization
   - `docs:` Documentation improvements
   - `refactor:` Code refactoring without behavior change
   - `test:` Adding or updating tests
4. **Ensure All Tests Pass:**
   ```bash
   ./gradlew testDebugUnitTest
   ```
5. **Push and Open a Pull Request:**
   Submit your PR to the `main` branch. Fill out the [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md) with details on testing and hardware impact.

---

## 🏷️ Reporting Bugs & Suggesting Features

- **Bug Reports:** If you discover a crash, memory leak, or thermal throttling issue, please use our [Bug Report Template](.github/ISSUE_TEMPLATE/bug_report.yml). Be sure to include your SoC chipset model and Android OS version.
- **Device Benchmarks:** If you've run MobileAI on your smartphone, share your results via our [Device Benchmark Template](.github/ISSUE_TEMPLATE/device_benchmark.yml).
- **Feature Requests:** Propose new ideas or architectural enhancements via [Feature Request](.github/ISSUE_TEMPLATE/feature_request.yml) or GitHub Discussions.

Thank you for helping shape the future of accessible, hardware-aware on-device AI! 🌟
