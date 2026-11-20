-- ============================================================================
-- ShopFloor AI (Powered by MobileAIFramework)
-- BigQuery Production Machining & Quality Inspection Database Schema
-- ============================================================================

CREATE TABLE IF NOT EXISTS `mobileai_telemetry.production_inspections` (
  inspection_id STRING NOT NULL,
  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP(),
  project_code STRING,        -- e.g. 'PRJ-TURB-401', 'PRJ-VALVE-208'
  part_number STRING,         -- e.g. 'SHAFT-505', 'FLG-112-A'
  part_name STRING,           -- e.g. 'Turbine Main Shaft', 'HP Valve Body'
  machine_id STRING,          -- e.g. 'CNC-VMC-01', 'CNC-LATHE-02'
  operation STRING,           -- e.g. 'Precision Bore Finishing'
  dimension_name STRING,      -- e.g. 'Inner Bore Diameter'
  nominal_mm FLOAT64,
  tolerance_mm FLOAT64,
  measured_mm FLOAT64,
  deviation_mm FLOAT64,
  status STRING,              -- 'PASS' | 'WARN' | 'FAIL'
  operator STRING,
  shift STRING,               -- 'Shift-A' | 'Shift-B' | 'Shift-C'
  source STRING,              -- 'ANDROID_LENS_OCR' | 'WEB_SCANNER' | 'CNC_CMM_BATCH'
  notes STRING
)
PARTITION BY DATE(timestamp)
CLUSTER BY project_code, machine_id, status;
