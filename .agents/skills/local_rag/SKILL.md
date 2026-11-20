---
name: local-rag-notes
description: On-device private knowledge retrieval using SQLite FTS and MiniLM vector embeddings without sending user notes to the cloud.
version: 1.0.0
---

# Local RAG & Personal Notes Skill

## Tools & Capabilities
1. `search_notes(query: string, max_results: int)`:
   - Queries local SQLite `knowledge_entries` database using full-text and semantic keyword matching.
   - 100% offline, private, zero token cost.

2. `add_note(title: string, content: string, category: string)`:
   - Persists a new note, summary, or audio transcription directly to on-device storage.

3. `get_notes_summary()`:
   - Returns aggregated statistics: total notes, categories, and recent voice memos.

## Execution Guardrails
- Keep retrieved context window under 512 tokens when feeding into local quantized models (`smollm2-360m`).
- Never leak private notes or local SQLite data into outbound cloud prompts without user confirmation.
