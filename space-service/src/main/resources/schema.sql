CREATE TABLE IF NOT EXISTS parking_space (
    id VARCHAR(36) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    floor VARCHAR(20) NOT NULL,
    zone_code VARCHAR(20) NOT NULL,
    space_number VARCHAR(20) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    parking_session_id VARCHAR(36) UNIQUE,
    last_session_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS charger (
    id VARCHAR(36) PRIMARY KEY,
    space_id VARCHAR(36) NOT NULL UNIQUE,
    active_session_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (space_id) REFERENCES parking_space(id)
);

CREATE TABLE IF NOT EXISTS charging_session (
    id VARCHAR(36) PRIMARY KEY,
    charger_id VARCHAR(36) NOT NULL,
    parking_session_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP NULL,
    energy_kwh DECIMAL(12,3) NULL,
    charging_cents BIGINT NULL,
    rate_version VARCHAR(50) NOT NULL,
    rate_cents_per_kwh BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (charger_id) REFERENCES charger(id),
    INDEX charging_session_parking_idx (parking_session_id, status)
);

CREATE TABLE IF NOT EXISTS charging_settlement (
    parking_session_id VARCHAR(36) PRIMARY KEY,
    space_id VARCHAR(36) NOT NULL,
    charging_cents BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (space_id) REFERENCES parking_space(id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    operation_name VARCHAR(40) NOT NULL,
    request_signature VARCHAR(500) NOT NULL,
    response_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
