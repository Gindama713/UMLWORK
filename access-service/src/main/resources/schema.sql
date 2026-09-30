CREATE TABLE IF NOT EXISTS parking_session (
    id CHAR(36) PRIMARY KEY,
    plate_number VARCHAR(24) NOT NULL,
    active_plate VARCHAR(24) NULL,
    space_id CHAR(36) NULL,
    space_type VARCHAR(20) NOT NULL,
    floor_name VARCHAR(20) NULL,
    zone_name VARCHAR(40) NULL,
    space_number VARCHAR(40) NULL,
    entry_time DATETIME(6) NOT NULL,
    exit_time DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL,
    bill_id CHAR(36) NULL,
    exception_type VARCHAR(20) NULL,
    operator_name VARCHAR(60) NULL,
    reservation_id CHAR(36) NULL,
    monthly_pass_id CHAR(36) NULL,
    request_key VARCHAR(100) NOT NULL,
    request_payload VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_active_plate (active_plate),
    UNIQUE KEY uq_entry_request (request_key),
    UNIQUE KEY uq_session_bill (bill_id),
    INDEX idx_entry_time (entry_time)
);

CREATE TABLE IF NOT EXISTS access_request_key (
    request_key VARCHAR(100) PRIMARY KEY,
    operation_name VARCHAR(30) NOT NULL,
    request_signature VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS exit_intent (
    parking_session_id CHAR(36) PRIMARY KEY,
    exit_time DATETIME(6) NOT NULL,
    exception_type VARCHAR(20) NOT NULL,
    operator_name VARCHAR(60),
    benefit_type VARCHAR(20) NOT NULL,
    prepaid_cents BIGINT NOT NULL,
    monthly_pass_id CHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (parking_session_id) REFERENCES parking_session(id)
);
