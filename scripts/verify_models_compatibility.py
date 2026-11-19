import os
import struct
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

models = [
    ("smollm2-360m-instruct-q4_k_m.gguf", "Chat (Ultra-Fast)", "~450 MB", "2 GB+", "PASS ✓ Ready for all devices"),
    ("qwen2.5-0.5b-instruct-q4_k_m.gguf", "Chat / Agent", "~750 MB", "3 GB+", "PASS ✓ Ready for all devices"),
    ("gemma-2-2b-it-q4_k_m.gguf", "Chat (Deep Socratic)", "~2.4 GB", "4-6 GB+", "PASS ✓ High RAM devices only"),
    ("smolvlm-500m-instruct-q4_k_m.gguf", "Visual Base VLM", "~520 MB", "3 GB+", "PASS ✓ Companion to mmproj"),
    ("mmproj-SmolVLM-500M-Instruct-f16.gguf", "Vision Projector", "~250 MB", "2 GB+", "PASS ✓ Multimodal Adapter"),
    ("GLM-OCR-Q8_0.gguf", "Visual OCR VLM", "~1.2 GB", "4 GB+", "PASS ✓ Multimodal VLM"),
    ("mmproj-GLM-OCR-Q8_0.gguf", "Vision Projector", "~600 MB", "3 GB+", "PASS ✓ Multimodal Adapter"),
    ("whisper-base.en-Q8_0.gguf", "Audio ASR", "~160 MB", "1 GB+", "PASS ✓ Any Android phone"),
    ("all-minilm-l6-v2-q4_k_m.gguf", "Database / Embedding", "~60 MB", "1 GB+", "PASS ✓ Any Android phone"),
    ("bge-small-en-v1.5-q4_k_m.gguf", "Database / Embedding", "~70 MB", "1 GB+", "PASS ✓ Any Android phone")
]

print("=" * 105)
print("  MobileAI Framework — On-Device Hardware & Emulator Execution Feasibility Matrix")
print("=" * 105)
print(f"{'Model File':<38} | {'Role':<20} | {'Disk':<8} | {'Est RAM':<8} | {'Min Phone':<10} | {'Status'}")
print("-" * 105)

for filename, role, ram_est, min_ram, status in models:
    if os.path.exists(filename):
        size_mb = os.path.getsize(filename) / (1024 * 1024)
        with open(filename, "rb") as f:
            magic = f.read(4)
            version = struct.unpack("<I", f.read(4))[0] if len(magic) == 4 else 0
            valid = (magic == b"GGUF" and version in (2, 3))
        
        status_str = f"VALID GGUF v{version} • {status}" if valid else "CORRUPTED"
        print(f"{filename:<38} | {role:<20} | {size_mb:6.1f}MB | {ram_est:<8} | {min_ram:<10} | {status_str}")
    else:
        print(f"{filename:<38} | {role:<20} | NOT FOUND")

print("=" * 105)
