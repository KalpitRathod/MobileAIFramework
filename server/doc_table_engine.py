"""
Abstract Document-to-Table Engine for MobileAIFramework / ShopFloor AI.
Converts ANY scanned document (dirty handwriting, printed notes, invoices, CNC logs,
checklists, lab tables, ledgers) into dynamic tabular data (columns + rows) and
persists it into Google BigQuery (`mobileai_telemetry.scanned_documents`).
Schema is completely abstract/flexible: each scanned page can have its own columns & rows.
"""

import os
import re
import json
import uuid
import datetime
import logging
import threading
from typing import Dict, Any, List, Optional

import requests
from validators import validate_general_text

logger = logging.getLogger("mobileai.doctables")


def _clean_project_id(raw: str) -> str:
    if not raw:
        return ""
    raw = raw.strip()
    return raw.split("/", 1)[1] if raw.startswith("projects/") else raw


class DocTableEngine:
    def __init__(self):
        self.project_id = _clean_project_id(os.environ.get("GCP_PROJECT", ""))
        self.dataset_id = os.environ.get("BIGQUERY_DATASET", "mobileai_telemetry")
        self.table_id = "scanned_documents"
        self.bq_client = None
        self.documents: List[Dict[str, Any]] = []
        self._lock = threading.Lock()

        self._init_bigquery()
        self._load_or_seed_documents()

    def _init_bigquery(self):
        try:
            from google.cloud import bigquery

            if self.project_id and not self.project_id.isdigit():
                self.bq_client = bigquery.Client(project=self.project_id)
            else:
                self.bq_client = bigquery.Client()
                self.project_id = self.bq_client.project or self.project_id

            dataset_ref = f"{self.project_id}.{self.dataset_id}"
            ds = bigquery.Dataset(dataset_ref)
            ds.location = "US"
            self.bq_client.create_dataset(ds, exists_ok=True)

            table_ref = f"{dataset_ref}.{self.table_id}"
            schema = [
                bigquery.SchemaField("doc_id", "STRING", mode="REQUIRED"),
                bigquery.SchemaField("timestamp", "TIMESTAMP", mode="NULLABLE"),
                bigquery.SchemaField("title", "STRING", mode="NULLABLE"),
                bigquery.SchemaField("doc_type", "STRING", mode="NULLABLE"),
                bigquery.SchemaField("columns_json", "STRING", mode="NULLABLE"),
                bigquery.SchemaField("rows_json", "STRING", mode="NULLABLE"),
                bigquery.SchemaField("row_count", "INTEGER", mode="NULLABLE"),
                bigquery.SchemaField("summary", "STRING", mode="NULLABLE"),
                bigquery.SchemaField("raw_text", "STRING", mode="NULLABLE"),
                bigquery.SchemaField("source", "STRING", mode="NULLABLE"),
            ]
            table = bigquery.Table(table_ref, schema=schema)
            table.time_partitioning = bigquery.TimePartitioning(field="timestamp")
            self.bq_client.create_table(table, exists_ok=True)
            logger.info("BigQuery abstract documents table ready: %s", table_ref)
        except Exception as e:
            logger.warning("BigQuery scanned_documents fallback to in-memory: %s", e)
            self.bq_client = None

    def _load_or_seed_documents(self):
        if self.bq_client and self.project_id:
            try:
                fqn = f"`{self.project_id}.{self.dataset_id}.{self.table_id}`"
                rows = list(self.bq_client.query(f"SELECT * FROM {fqn} ORDER BY timestamp DESC LIMIT 50").result())
                if rows:
                    loaded = []
                    for r in rows:
                        d = dict(r.items())
                        if isinstance(d.get("timestamp"), datetime.datetime):
                            d["timestamp"] = d["timestamp"].isoformat()
                        try:
                            d["columns"] = json.loads(d.get("columns_json") or "[]")
                            d["rows"] = json.loads(d.get("rows_json") or "[]")
                        except Exception:
                            d["columns"], d["rows"] = [], []
                        loaded.append(d)
                    self.documents = loaded
                    return
            except Exception as e:
                logger.warning("Could not load scanned_documents from BigQuery: %s", e)

        # Seed 2 diverse abstract document tables (proving schema-agnostic flexibility!)
        now = datetime.datetime.now(datetime.timezone.utc)
        seeds = [
            {
                "doc_id": "DOC-2001",
                "timestamp": (now - datetime.timedelta(minutes=25)).isoformat(),
                "title": "Handwritten CNC Shift-A Inspection Log",
                "doc_type": "Handwritten Inspection Sheet",
                "columns": ["Part No", "Feature", "Nominal (mm)", "Measured (mm)", "Status"],
                "rows": [
                    ["SHAFT-505-12", "Inner Bore Ø", "50.000 ±0.020", "50.011", "PASS"],
                    ["SHAFT-505-13", "Inner Bore Ø", "50.000 ±0.020", "50.032", "FAIL"],
                    ["FLG-112-04", "Thickness", "24.500 ±0.030", "24.518", "PASS"],
                ],
                "summary": "3 handwritten shift measurements extracted into 5-column table (1 out-of-tolerance).",
                "raw_text": "Shift A log: SHAFT-505-12 bore 50.011 ok, SHAFT-505-13 50.032 fail, FLG-112-04 thick 24.518 ok",
                "source": "ANDROID_LENS_DOC_SCAN",
            },
            {
                "doc_id": "DOC-2002",
                "timestamp": (now - datetime.timedelta(minutes=12)).isoformat(),
                "title": "Vendor Tooling & Carbide Insert Bill",
                "doc_type": "Invoice / Material Receipt",
                "columns": ["Item Code", "Description", "Qty", "Unit Price (INR)", "Total (INR)"],
                "rows": [
                    ["CNMG-120408", "Carbide Turning Insert", "20", "420", "8,400"],
                    ["ENDM-12-4F", "Solid Carbide 12mm Endmill", "6", "1,850", "11,100"],
                    ["COOL-SYN-20L", "Synthetic CNC Coolant 20L", "2", "3,200", "6,400"],
                ],
                "summary": "3 vendor tooling line items totaling ₹25,900 converted into structured table.",
                "raw_text": "CNMG-120408 x20 @420, ENDM-12-4F x6 @1850, Coolant 20L x2 @3200",
                "source": "WEB_DOC_SCAN",
            },
        ]
        self.documents = list(reversed(seeds))
        if self.bq_client and self.project_id:
            try:
                bq_rows = [self._to_bq_row(s) for s in seeds]
                self.bq_client.insert_rows_json(f"{self.project_id}.{self.dataset_id}.{self.table_id}", bq_rows)
            except Exception as e:
                logger.warning("Seed scanned_documents skipped: %s", e)

    def _to_bq_row(self, doc: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "doc_id": doc["doc_id"],
            "timestamp": doc["timestamp"],
            "title": doc["title"],
            "doc_type": doc["doc_type"],
            "columns_json": json.dumps(doc["columns"], ensure_ascii=False),
            "rows_json": json.dumps(doc["rows"], ensure_ascii=False),
            "row_count": len(doc["rows"]),
            "summary": doc["summary"],
            "raw_text": doc.get("raw_text", "")[:2000],
            "source": doc.get("source", "ANDROID_LENS_DOC_SCAN"),
        }

    def _call_gemini_vision(self, prompt: str, image_base64: Optional[str] = None, mime_type: str = "image/jpeg") -> Optional[str]:
        gemini_key = os.environ.get("GEMINI_API_KEY", "").strip()
        if not gemini_key:
            return None
        parts: List[Dict[str, Any]] = []
        if image_base64:
            clean_b64 = image_base64.split(",", 1)[1] if "," in image_base64 else image_base64
            parts.append({"inline_data": {"mime_type": mime_type, "data": clean_b64}})
        parts.append({"text": prompt})
        payload = {
            "contents": [{"parts": parts}],
            "generationConfig": {"temperature": 0.15, "maxOutputTokens": 1800},
        }
        headers = {"Content-Type": "application/json", "X-goog-api-key": gemini_key}
        for model_name in ["gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite"]:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent"
            try:
                r = requests.post(url, headers=headers, json=payload, timeout=28)
                if r.status_code == 200:
                    cands = r.json().get("candidates", [])
                    if cands:
                        txt = "".join(p.get("text", "") for p in cands[0].get("content", {}).get("parts", [])).strip()
                        if txt:
                            return txt
            except Exception as e:
                logger.warning("Gemini Doc->Table failed on %s: %s", model_name, e)
        return None

    def _fallback_parse_table(self, raw_text: str) -> Optional[Dict[str, Any]]:
        lines = [ln.strip() for ln in (raw_text or "").splitlines() if ln.strip()]
        if not lines:
            return None
        # Detect key:value pairs or pipe/comma/tab separated rows
        kv_rows = []
        for ln in lines:
            if ":" in ln:
                k, v = ln.split(":", 1)
                if k.strip() and v.strip():
                    kv_rows.append([k.strip(), v.strip()])
        if len(kv_rows) >= 2:
            return {
                "title": "Scanned Key-Value Document",
                "doc_type": "Structured Notes",
                "columns": ["Field", "Value"],
                "rows": kv_rows[:30],
                "summary": f"Extracted {len(kv_rows)} field-value entries into tabular form.",
            }
        # Delimited lines
        delim_rows = [re.split(r"\s*[|\t,;]\s*", ln) for ln in lines]
        max_cols = max(len(r) for r in delim_rows)
        if max_cols >= 2:
            cols = [f"Column {i + 1}" for i in range(max_cols)]
            norm_rows = [r + [""] * (max_cols - len(r)) for r in delim_rows[:30]]
            return {
                "title": "Scanned Tabular Document",
                "doc_type": "Abstract Table",
                "columns": cols,
                "rows": norm_rows,
                "summary": f"Converted {len(norm_rows)} rows × {max_cols} columns from scanned text.",
            }
        # Plain multi-line notes -> Line # | Content table
        return {
            "title": "Scanned Document Notes",
            "doc_type": "Document Page",
            "columns": ["Line", "Extracted Content"],
            "rows": [[str(i + 1), ln] for i, ln in enumerate(lines[:30])],
            "summary": f"Indexed {len(lines)} lines from scanned page.",
        }

    def scan_to_table(
        self,
        ocr_text: str = "",
        image_base64: Optional[str] = None,
        mime_type: str = "image/jpeg",
        source: str = "ANDROID_LENS_DOC_SCAN",
    ) -> Dict[str, Any]:
        """Converts ANY printed or dirty handwritten document into an abstract table and persists to BigQuery."""
        if not image_base64:
            ok, err = validate_general_text(ocr_text, min_len=4)
            if not ok:
                return {
                    "accepted": False,
                    "error": err,
                    "message": f"🚫 REJECTED — {err}",
                    "bigquery_streamed": False,
                }

        prompt = (
            "You are an expert document-to-table digitization engine capable of reading BOTH messy/dirty/cursive handwriting AND clean printed documents.\n"
            "The document can be completely ABSTRACT and ANY domain (handwritten notes, inspection sheet, receipt, invoice, timetable, BOM, inventory, checklist, lab table, ledger, etc.).\n"
            "CRITICAL RULE: If the image/text is blank, gibberish, random noise, or an unrelated photo with NO readable text/data (like a selfie, wall, or blank page), "
            'you MUST return ONLY: {"valid": false, "reason": "No readable document text or data found"}.\n'
            "NEVER hallucinate or invent fake rows.\n"
            "Otherwise, organize ALL extracted information cleanly into the most natural dynamic table (any number of columns & rows that best fits the page) and return ONLY valid JSON (no markdown fences):\n"
            '{"valid": true, "title": "Short Descriptive Title", "doc_type": "e.g. Handwritten Notes / Invoice / Inspection Table / Checklist", '
            '"columns": ["Col1", "Col2", ...], "rows": [["val1", "val2", ...], ...], "summary": "1-sentence summary of what was digitized"}\n'
            f"ADDITIONAL OCR TEXT (may be partial or noisy):\n{ocr_text or '(Read directly from image)'}"
        )

        extracted = None
        gemini_used = False
        raw = self._call_gemini_vision(prompt, image_base64=image_base64, mime_type=mime_type)
        if raw:
            try:
                m = re.search(r"\{.*\}", raw, re.S)
                if m:
                    parsed = json.loads(m.group(0))
                    if parsed.get("valid") is False:
                        reason = str(parsed.get("reason") or "No readable document data found in scan.")
                        return {
                            "accepted": False,
                            "error": reason,
                            "message": f"🚫 REJECTED — {reason}",
                            "gemini_parsed": True,
                            "bigquery_streamed": False,
                        }
                    cols = parsed.get("columns") or []
                    rows = parsed.get("rows") or []
                    if isinstance(cols, list) and isinstance(rows, list) and len(cols) >= 1 and len(rows) >= 1:
                        extracted = parsed
                        gemini_used = True
            except Exception as e:
                logger.warning("Doc->Table JSON parse fallback: %s", e)

        if not extracted:
            if not ocr_text.strip():
                return {
                    "accepted": False,
                    "error": "No readable text found in scanned image.",
                    "message": "🚫 REJECTED — No readable text found in scanned image.",
                    "gemini_parsed": gemini_used,
                    "bigquery_streamed": False,
                }
            extracted = self._fallback_parse_table(ocr_text)

        if not extracted or not extracted.get("rows"):
            return {
                "accepted": False,
                "error": "Could not extract any tabular rows from the scan.",
                "message": "🚫 REJECTED — Could not extract tabular rows.",
                "gemini_parsed": gemini_used,
                "bigquery_streamed": False,
            }

        cols = [str(c) for c in extracted["columns"]]
        norm_rows = []
        for r in extracted["rows"]:
            if isinstance(r, list):
                row_str = [str(cell) for cell in r]
                if len(row_str) < len(cols):
                    row_str += [""] * (len(cols) - len(row_str))
                norm_rows.append(row_str[: len(cols)])

        with self._lock:
            doc_id = f"DOC-{2001 + len(self.documents)}"
            doc = {
                "doc_id": doc_id,
                "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "title": str(extracted.get("title") or "Scanned Document Table"),
                "doc_type": str(extracted.get("doc_type") or "Abstract Document"),
                "columns": cols,
                "rows": norm_rows,
                "row_count": len(norm_rows),
                "summary": str(extracted.get("summary") or f"Digitized {len(norm_rows)} rows × {len(cols)} columns."),
                "raw_text": ocr_text[:2000],
                "source": source,
            }
            self.documents.insert(0, doc)

        bq_ok = False
        if self.bq_client and self.project_id:
            try:
                errs = self.bq_client.insert_rows_json(
                    f"{self.project_id}.{self.dataset_id}.{self.table_id}",
                    [self._to_bq_row(doc)],
                )
                bq_ok = not errs
            except Exception as e:
                logger.error("BigQuery scanned_documents insert error: %s", e)

        return {
            "accepted": True,
            "document": doc,
            "gemini_parsed": gemini_used,
            "bigquery_streamed": bq_ok,
            "message": f"✅ Saved {doc['doc_id']} — {doc['title']} ({len(norm_rows)} rows × {len(cols)} cols)",
        }

    def list_documents(self, limit: int = 30) -> List[Dict[str, Any]]:
        with self._lock:
            return list(self.documents[:limit])


doc_table_engine = DocTableEngine()
