-- ============================================================================
-- MobileAI Hybrid Control Plane - BigQuery Telemetry & Tokenomics Schema
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS `mobileai_telemetry`
OPTIONS (
  location = 'US',
  description = 'Fleet-wide edge vs cloud routing decisions and tokenomics savings'
);

CREATE TABLE IF NOT EXISTS `mobileai_telemetry.routing_events` (
  event_id STRING NOT NULL,
  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP(),
  device_id STRING,
  device_model STRING,
  os_version STRING,
  chipset STRING,
  route_target STRING,       -- 'EDGE_LOCAL' | 'CLOUD_RUN' | 'GEMINI_LIVE'
  routing_reason STRING,
  model_name STRING,
  prompt_length INT64,
  estimated_tokens INT64,
  tokens_saved INT64,        -- Tokens executed on-device instead of cloud
  dollar_cost_saved_usd FLOAT64, -- Based on $0.075 / 1M tokens edge vs cloud
  latency_ms INT64,
  thermal_state STRING,      -- 'NORMAL' | 'WARM' | 'THROTTLED'
  battery_level INT64,
  is_offline BOOL,
  skill_used STRING
)
PARTITION BY DATE(timestamp)
CLUSTER BY route_target, device_model;
