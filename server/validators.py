"""
Input validation & anti-hallucination guardrails for ShopFloor AI & MobileAIFramework.
Rejects empty, gibberish, keyboard-mash, non-dimensional, and physically impossible inputs
so invalid data never pollutes BigQuery or produces fake PASS/FAIL results.
"""

import re
from typing import Tuple

VOWELS = set("aeiouyAEIOUY")
KEYBOARD_MASH_SUBSTRINGS = (
    "asdf", "qwer", "zxcv", "hjkl", "uiop", "jkl;", "ghjk", "dfgh", "fghj",
    "sdfg", "xcvb", "cvbn", "vbnm", "wert", "erty", "rtyu", "tyui", "yuio",
)

QC_DOMAIN_KEYWORDS = (
    "mm", "um", "µm", "inch", "nominal", "nom", "measured", "actual", "reading",
    "tolerance", "tol", "±", "+/-", "bore", "diameter", "od", "id", "radius",
    "thickness", "concentricity", "roughness", "ra", "shaft", "valve", "flange",
    "gear", "housing", "part", "prj-", "cnc-", "ins-", "machine", "lathe", "vmc",
    "inspection", "dimension", "spec", "pass", "fail", "warn", "yield", "fpy",
    "cpk", "scrap", "reject", "drift", "rework", "shift", "project", "batch",
    "caliper", "micrometer", "cmm", "blueprint", "drawing", "torque", "gauge",
    "update", "latest", "last", "quality", "tool", "wear", "deviation",
)


def is_gibberish_word(word: str) -> bool:
    w = re.sub(r"[^a-zA-Z]", "", word)
    if len(w) < 3:
        return False
    low = w.lower()
    # Allow known acronyms / codes like CNC, OCR, RAG, FPY, CMM, VMC, PRJ, INS, LMK, NPU, RAM, SQL, API, SDK, OTA, GCS, GPU, CPU, LLM
    if low in {"cnc", "ocr", "rag", "fpy", "cmm", "vmc", "prj", "ins", "lmk", "npu", "ram", "sql", "api", "sdk", "ota", "gcs", "gpu", "cpu", "llm", "cpk", "cad", "cam", "pdf", "sop", "kv", "ai", "qc", "ws", "cd", "id", "od", "mm"}:
        return False
    if any(m in low for m in KEYBOARD_MASH_SUBSTRINGS):
        return True
    # Repeated character spam like "aaaaaaa" or "xxxxxx"
    if re.search(r"(.)\1{3,}", low):
        return True
    # Long consonant cluster without vowels (e.g. "bcdfghjk", "xkzptw")
    if re.search(r"[bcdfghjklmnpqrstvwxz]{5,}", low):
        return True
    # Zero vowels in a 5+ letter word
    if len(low) >= 5 and not any(c in VOWELS for c in low):
        return True
    return False


def validate_general_text(text: str, min_len: int = 2) -> Tuple[bool, str]:
    """Rejects empty, symbol-only, or obvious gibberish/keyboard-mash prompts."""
    if not text or not text.strip():
        return False, "Input is empty. Please enter a real prompt or query."

    s = text.strip()
    if len(s) < min_len:
        return False, "Input is too short to be a valid request."

    alpha_chars = re.findall(r"[a-zA-Z]", s)
    if len(alpha_chars) < 2:
        return False, "Input contains no recognizable text. Please enter a meaningful request."

    # Single-character repetition across whole string
    non_space = re.sub(r"\s+", "", s).lower()
    if len(non_space) >= 4 and len(set(non_space)) <= 2:
        return False, "Input looks like repeated characters rather than a valid request."

    tokens = re.findall(r"[a-zA-Z0-9_-]+", s)
    alpha_tokens = [t for t in tokens if re.search(r"[a-zA-Z]{3,}", t)]
    if alpha_tokens:
        gib_count = sum(1 for t in alpha_tokens if is_gibberish_word(t))
        if gib_count / len(alpha_tokens) >= 0.5:
            return False, f"Unrecognized or nonsensical input ('{s[:40]}'). Please enter a clear, valid request."

    return True, ""


def _has_qc_domain_cue(text: str) -> bool:
    """Matches QC/machining terms using word boundaries so 'party'/'sodas' don't false-match 'part'/'od'."""
    low = text.lower()
    for kw in QC_DOMAIN_KEYWORDS:
        if kw in ("±", "+/-", "µm") or kw.endswith("-"):
            if kw in low:
                return True
        else:
            if re.search(rf"\b{re.escape(kw)}\b", low):
                return True
    return False


def validate_qc_ocr_text(ocr_text: str) -> Tuple[bool, str]:
    """
    Strictly validates OCR text before allowing it into the production QC pipeline.
    Requires both real text AND at least one plausible numeric measurement + machining/inspection cue.
    """
    ok, reason = validate_general_text(ocr_text, min_len=6)
    if not ok:
        return False, f"Invalid scan input: {reason}"

    numbers = re.findall(r"\b\d+(?:\.\d+)?\b", ocr_text)
    has_domain_cue = _has_qc_domain_cue(ocr_text)

    if not numbers:
        return False, (
            "No dimensional measurement numbers found in scanned text. "
            "A valid inspection report must include numeric measurements (e.g., 'Nominal: 50.000 ±0.020, Measured: 50.012 mm')."
        )

    if not has_domain_cue:
        return False, (
            "Input does not look like a machining or quality inspection report "
            "(missing part/project/dimension/tolerance terms like Nominal, Measured, mm, Bore, PRJ-, or CNC-)."
        )

    return True, ""


def validate_qc_dimensions(nominal: float, tolerance: float, measured: float) -> Tuple[bool, str]:
    """Rejects physically impossible dimensions (negative, zero, NaN, absurd tolerances)."""
    import math
    for name, val in [("nominal_mm", nominal), ("tolerance_mm", tolerance), ("measured_mm", measured)]:
        if val is None or math.isnan(val) or math.isinf(val):
            return False, f"Invalid numeric value for {name}."
    if nominal <= 0 or nominal > 50000:
        return False, f"Nominal dimension ({nominal} mm) is outside valid industrial range (0.01–50,000 mm)."
    if tolerance <= 0 or tolerance > max(50.0, nominal * 0.5):
        return False, f"Tolerance (±{tolerance} mm) is physically invalid for nominal {nominal} mm."
    if measured <= 0 or measured > 50000:
        return False, f"Measured value ({measured} mm) is outside valid industrial range."
    # Guard against grossly mismatched units/garbage like nominal=50mm measured=9999mm
    if abs(measured - nominal) > max(25.0, nominal * 0.5):
        return False, (
            f"Measured value ({measured} mm) differs unrealistically from nominal ({nominal} mm). "
            "Check units or re-scan the inspection sheet."
        )
    return True, ""


def is_qc_domain_query(cmd: str) -> Tuple[bool, str]:
    """Ensures AI QC Copilot queries are actually about manufacturing, dimensions, projects, or quality."""
    ok, reason = validate_general_text(cmd, min_len=3)
    if not ok:
        return False, reason
    if not _has_qc_domain_cue(cmd):
        return False, (
            "Off-topic or non-manufacturing query detected. ShopFloor AI Copilot only queries/updates "
            "CNC production inspections, dimensions, tolerances, machines, and project quality data."
        )
    return True, ""
