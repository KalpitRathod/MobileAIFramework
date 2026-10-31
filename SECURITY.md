# Security Policy

## Supported Versions

We provide security updates and patches for the following versions of MobileAI Framework:

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

---

## Reporting a Vulnerability

The MobileAI team takes security and user privacy seriously. Since MobileAI runs models and executes device actions locally on user smartphones, security of data buffers, memory allocations, and accessibility services is paramount.

If you believe you have found a security vulnerability:

1. **Do NOT report security vulnerabilities via public GitHub issues.**
2. Send an email with details, reproduction steps, and impact assessment to:
   **kalpitrathod@outlook.com**
3. Please include:
   - Type of issue (e.g., buffer overflow, memory leak, unauthorized accessibility execution, insecure key storage).
   - Component affected (e.g., `LlamaNativeEngine`, `CloudApiKeyStore`, `PhoneControllerAccessibilityService`).
   - Device model, Android OS version, and SoC chipset.
   - Proof-of-concept code or reproduction steps.

We will acknowledge receipt within 48 hours and work with you to remediate the issue prior to public disclosure.
