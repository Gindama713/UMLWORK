CREATE TABLE IF NOT EXISTS traffic_report (
    id CHAR(36) PRIMARY KEY,
    range_start DATETIME(6) NOT NULL,
    range_end DATETIME(6) NOT NULL,
    granularity VARCHAR(8) NOT NULL,
    total_entries INT NOT NULL,
    peak_start DATETIME(6) NULL,
    generated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS traffic_bucket (
    report_id CHAR(36) NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    entry_count INT NOT NULL,
    PRIMARY KEY (report_id, bucket_start),
    CONSTRAINT fk_bucket_report FOREIGN KEY (report_id) REFERENCES traffic_report(id)
);
