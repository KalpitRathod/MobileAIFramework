"""
Automated unit, guardrail, hybrid-routing, and concurrency tests for the
MobileAI Hybrid Gateway & ShopFloor AI Control Plane (`server/`).

Run with:
    pytest server/tests/test_gateway.py -v
"""

import concurrent.futures
import math
import os
import sys
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

# Ensure `server/` modules resolve cleanly when invoked from repo root or `server/`
SERVER_DIR = Path(__file__).resolve().parent.parent
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

from validators import (  # noqa: E402
    is_gibberish_word,
    is_qc_domain_query,
    validate_general_text,
    validate_qc_dimensions,
    validate_qc_ocr_text,
)
import main as gateway_main  # noqa: E402


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(gateway_main.app)


# ── 1. Anti-Hallucination & Edge-Case Input Validator Tests ──────────────────

@pytest.mark.parametrize(
    "bad_input",
    [
        "",
        "   ",
        "a",
        "1234567890",
        "!!!???@@@",
        "aaaaaaaaaaaa",
        "asdfghjkl",
        "qwertyuiop zxcvbnm",
        "bcdfghjk xkzptw",
    ],
)
def test_validate_general_text_rejects_garbage(bad_input: str):
    ok, reason = validate_general_text(bad_input)
    assert ok is False, f"Expected rejection for {bad_input!r}"
    assert len(reason) > 0


@pytest.mark.parametrize(
    "valid_input",
    [
        "Summarize my field notes",
        "CNC OCR LMK FPY check",
        "Inspect PRJ-TURB-401 bore diameter 50.012 mm",
        "Compare Kotlin coroutines and Java threads",
    ],
)
def test_validate_general_text_accepts_legit_prompts(valid_input: str):
    ok, reason = validate_general_text(valid_input)
    assert ok is True, f"Unexpected rejection for {valid_input!r}: {reason}"


def test_is_gibberish_allows_domain_acronyms():
    for acronym in ("CNC", "OCR", "RAG", "LMK", "NPU", "FPY", "CPK", "PRJ", "VMC"):
        assert is_gibberish_word(acronym) is False


def test_validate_qc_ocr_rejects_non_dimensional_and_accepts_valid():
    ok_no_nums, _ = validate_qc_ocr_text("Turbine shaft bore diameter inspection sheet")
    assert ok_no_nums is False

    ok_off_topic, _ = validate_qc_ocr_text("Order 25 pizzas and 10 sodas for the party")
    assert ok_off_topic is False

    ok_valid, reason = validate_qc_ocr_text(
        "Project: PRJ-TURB-401 | Machine: CNC-VMC-01 | Bore Nominal: 50.000 ±0.020 mm | Measured: 50.012 mm"
    )
    assert ok_valid is True, reason


def test_validate_qc_dimensions_rejects_impossible_physics():
    assert validate_qc_dimensions(-10.0, 0.02, 10.0)[0] is False
    assert validate_qc_dimensions(50.0, 0.0, 50.0)[0] is False
    assert validate_qc_dimensions(50.0, 0.02, 9999.0)[0] is False
    assert validate_qc_dimensions(float("nan"), 0.02, 50.0)[0] is False
    assert validate_qc_dimensions(50.0, 0.02, 50.014)[0] is True


def test_is_qc_domain_query_blocks_off_topic():
    assert is_qc_domain_query("Write a romantic poem about sunset")[0] is False
    assert is_qc_domain_query("asdfghjkl")[0] is False
    assert is_qc_domain_query("Show all failed inspections on CNC-LATHE-03")[0] is True


# ── 2. Hardware-Aware Hybrid Router Tests ────────────────────────────────────

def test_hybrid_route_offline_always_local(client: TestClient):
    res = client.post(
        "/v1/hybrid/route",
        json={
            "prompt": "Write a distributed microservices architecture in Go and Kubernetes",
            "is_offline": True,
            "thermal_state": "SEVERE",
            "available_ram_mb": 400,
            "battery_level": 5,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["route_target"] == "EDGE_LOCAL"
    assert body["tokens_saved"] > 0


def test_hybrid_route_thermal_throttle_escalates_to_cloud(client: TestClient):
    res = client.post(
        "/v1/hybrid/route",
        json={"prompt": "Hello there", "is_offline": False, "thermal_state": "SEVERE"},
    )
    assert res.status_code == 200
    assert res.json()["route_target"] == "CLOUD_RUN"


def test_hybrid_route_lmk_low_ram_escalates_to_cloud(client: TestClient):
    res = client.post(
        "/v1/hybrid/route",
        json={"prompt": "Summarize note", "is_offline": False, "available_ram_mb": 650},
    )
    assert res.status_code == 200
    assert res.json()["route_target"] == "CLOUD_RUN"


def test_hybrid_route_low_battery_escalates_to_cloud(client: TestClient):
    res = client.post(
        "/v1/hybrid/route",
        json={"prompt": "Quick note", "is_offline": False, "battery_level": 10},
    )
    assert res.status_code == 200
    assert res.json()["route_target"] == "CLOUD_RUN"


def test_hybrid_route_live_stream_uses_gemini_live(client: TestClient):
    res = client.post(
        "/v1/hybrid/route",
        json={"prompt": "Start live video stream inspect", "has_image": True, "is_offline": False},
    )
    assert res.status_code == 200
    assert res.json()["route_target"] == "GEMINI_LIVE"


def test_hybrid_route_simple_healthy_stays_local(client: TestClient):
    res = client.post(
        "/v1/hybrid/route",
        json={
            "prompt": "Hi, summarize this note",
            "is_offline": False,
            "thermal_state": "NORMAL",
            "available_ram_mb": 3200,
            "battery_level": 85,
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["route_target"] == "EDGE_LOCAL"
    assert body["tokens_saved"] > 0


# ── 3. Control Plane Endpoints, Guardrails & Concurrency Safety ──────────────

def test_health_ui_and_skills_endpoints(client: TestClient):
    assert client.get("/").status_code == 200
    health = client.get("/health")
    assert health.status_code == 200
    assert health.json()["status"] == "healthy"

    skills = client.get("/v1/skills")
    assert skills.status_code == 200
    assert len(skills.json()["skills"]) == 4


def test_doc_table_rejects_gibberish_without_db_pollution(client: TestClient):
    before_count = len(gateway_main.doc_table_engine.documents)
    res = client.post(
        "/v1/docs/scan-to-table",
        json={"ocr_text": "asdfghjkl qwertyuiop", "source": "PYTEST"},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["accepted"] is False
    assert len(gateway_main.doc_table_engine.documents) == before_count


def test_doc_table_extracts_abstract_table_offline_fallback(client: TestClient):
    before_count = len(gateway_main.doc_table_engine.documents)
    sample_doc = (
        "Part No | Material | Qty\n"
        "SHAFT-101 | Steel 4140 | 12\n"
        "FLANGE-202 | Aluminum 6061 | 8"
    )
    # Disable cloud calls during unit test so it runs fast & deterministically offline
    with patch.dict(os.environ, {"GEMINI_API_KEY": ""}, clear=False):
        with patch.object(gateway_main.doc_table_engine, "bq_client", None):
            res = client.post(
                "/v1/docs/scan-to-table",
                json={"ocr_text": sample_doc, "source": "PYTEST"},
            )
    assert res.status_code == 200
    body = res.json()
    assert body["accepted"] is True
    doc = body["document"]
    assert len(doc["columns"]) >= 2
    assert len(doc["rows"]) >= 2
    # Clean up in-memory test record so state stays pristine
    with gateway_main.doc_table_engine._lock:
        gateway_main.doc_table_engine.documents = [
            d for d in gateway_main.doc_table_engine.documents if d.get("source") != "PYTEST"
        ]
    assert len(gateway_main.doc_table_engine.documents) == before_count


def test_qc_endpoints_block_invalid_and_off_topic_inputs(client: TestClient):
    scan_res = client.post(
        "/v1/qc/scan-and-update",
        json={"ocr_text": "hello world no measurements here", "source": "PYTEST"},
    )
    assert scan_res.status_code == 200
    assert scan_res.json()["accepted"] is False

    cmd_res = client.post(
        "/v1/qc/ai-command",
        json={"command": "tell me a joke about bananas"},
    )
    assert cmd_res.status_code == 200
    assert cmd_res.json()["accepted"] is False


def test_concurrent_gateway_requests_are_thread_safe(client: TestClient):
    def hit_route(i: int) -> int:
        r = client.post(
            "/v1/hybrid/route",
            json={
                "prompt": f"Check CNC inspection tolerance {i}",
                "is_offline": i % 2 == 0,
                "thermal_state": "SEVERE" if i % 3 == 0 else "NORMAL",
            },
        )
        return r.status_code

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        codes = list(pool.map(hit_route, range(12)))

    assert all(code == 200 for code in codes)
