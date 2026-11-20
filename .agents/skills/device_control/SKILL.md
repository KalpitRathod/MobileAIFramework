---
name: device-control
description: Autonomous device action execution via Android Accessibility Service for UI navigation and phone automation.
version: 1.0.0
---

# Device Control & Automation Skill

## Tools & Capabilities
1. `perform_touch(x: int, y: int)`: Dispatches click gesture to specified screen coordinates.
2. `input_text(text: string)`: Inserts text into currently focused input widget.
3. `press_key(key_action: string)`: Triggers system navigation (`HOME`, `BACK`, `RECENTS`).
4. `scroll_screen(direction: string)`: Scrolls `UP`, `DOWN`, `LEFT`, or `RIGHT`.

## Safety Guardrails
- Requires active `PhoneControllerAccessibilityService` enabled in Android Accessibility Settings.
- Never automate financial transactions, password fields, or system security dialogues without explicit confirmation.
