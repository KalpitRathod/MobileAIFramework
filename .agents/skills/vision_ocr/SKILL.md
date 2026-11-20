---
name: vision-ocr
description: Bundled 100% offline ML Kit optical character recognition and viewfinder frame analysis for instant text grounding.
version: 1.0.0
---

# Vision & OCR Skill

## Tools & Capabilities
1. `extract_text_from_image(image_payload: ImagePayload)`:
   - Runs bundled Google ML Kit text recognition on camera snapshots or gallery photos.
   - Execution time: ~20-40ms on device.
   - Zero network dependencies, zero Google Play Services dynamically downloaded modules.

2. `analyze_viewfinder()`:
   - Grabs direct GPU TextureView frame for instant OCR grounding.

## Guardrails
- If extracted text contains structured documents or receipts, synthesize using local SmolLM2 first.
- If text is in non-Latin alphabet requiring advanced multilingual translation, escalate to Cloud Run with user consent.
