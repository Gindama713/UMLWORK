CREATE TABLE IF NOT EXISTS reservation_lock (
  space_id VARCHAR(36) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS reservation (
  reservation_id VARCHAR(36) PRIMARY KEY,
  space_id VARCHAR(36) NOT NULL,
  plate_number VARCHAR(24) NOT NULL,
  start_time DATETIME(6) NOT NULL,
  end_time DATETIME(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  prepaid_cents BIGINT NOT NULL,
  consumed_session_id VARCHAR(36) UNIQUE,
  last_released_session_id VARCHAR(36),
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  INDEX idx_reservation_space_time (space_id, start_time, end_time, status),
  INDEX idx_reservation_plate_time (plate_number, start_time, end_time, status)
);

CREATE TABLE IF NOT EXISTS reservation_prepayment (
  entry_id VARCHAR(36) PRIMARY KEY,
  reservation_id VARCHAR(36) NOT NULL,
  kind VARCHAR(24) NOT NULL,
  result VARCHAR(16) NOT NULL,
  amount_cents BIGINT NOT NULL,
  parking_session_id VARCHAR(36),
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_prepayment_kind_session (reservation_id, kind, parking_session_id),
  INDEX idx_prepayment_reservation (reservation_id)
);

CREATE TABLE IF NOT EXISTS monthly_plate_lock (
  plate_number VARCHAR(24) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS monthly_pass (
  monthly_pass_id VARCHAR(36) PRIMARY KEY,
  plate_number VARCHAR(24) NOT NULL,
  start_time DATETIME(6) NOT NULL,
  end_time DATETIME(6) NOT NULL,
  balance_cents BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  INDEX idx_monthly_plate_time (plate_number, start_time, end_time)
);

CREATE TABLE IF NOT EXISTS monthly_ledger (
  entry_id VARCHAR(36) PRIMARY KEY,
  monthly_pass_id VARCHAR(36) NOT NULL,
  parking_session_id VARCHAR(36),
  kind VARCHAR(24) NOT NULL,
  amount_cents BIGINT NOT NULL,
  balance_after_cents BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_monthly_session_kind (parking_session_id, kind),
  INDEX idx_monthly_ledger_pass (monthly_pass_id)
);

CREATE TABLE IF NOT EXISTS pass_idempotency (
  request_key VARCHAR(128) PRIMARY KEY,
  operation VARCHAR(80) NOT NULL,
  fingerprint VARCHAR(512) NOT NULL,
  response_json TEXT,
  created_at DATETIME(6) NOT NULL
);
