CREATE TABLE IF NOT EXISTS bill (
  bill_id CHAR(36) PRIMARY KEY,
  parking_session_id CHAR(36) NOT NULL UNIQUE,
  request_hash CHAR(64) NOT NULL,
  create_key VARCHAR(128) NOT NULL UNIQUE,
  entry_epoch_ms BIGINT NOT NULL,
  exit_epoch_ms BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  parking_base_cents BIGINT NOT NULL,
  discount_cents BIGINT NOT NULL,
  prepaid_cents BIGINT NOT NULL,
  prepaid_refund_cents BIGINT NOT NULL,
  parking_due_cents BIGINT NOT NULL,
  charging_cents BIGINT NOT NULL,
  exception_cents BIGINT NOT NULL,
  amount_due_cents BIGINT NOT NULL,
  rate_version VARCHAR(80) NOT NULL,
  fee_items_json TEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS payment (
  payment_id CHAR(36) PRIMARY KEY,
  bill_id CHAR(36) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  request_hash CHAR(64) NOT NULL,
  payment_status VARCHAR(16) NOT NULL,
  payment_source VARCHAR(24) NOT NULL,
  amount_cents BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_payment_bill FOREIGN KEY (bill_id) REFERENCES bill(bill_id),
  INDEX idx_payment_bill (bill_id)
);

CREATE TABLE IF NOT EXISTS invoice_request (
  invoice_request_id CHAR(36) PRIMARY KEY,
  bill_id CHAR(36) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  request_hash CHAR(64) NOT NULL,
  invoice_title VARCHAR(200) NOT NULL,
  tax_number VARCHAR(64),
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_invoice_bill FOREIGN KEY (bill_id) REFERENCES bill(bill_id),
  INDEX idx_invoice_bill (bill_id)
);
