CREATE TABLE IF NOT EXISTS titan_graphql_control_jobs (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    job_type VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin,
    payload_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'pending',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until TIMESTAMP(6) NULL,
    result_json LONGTEXT CHARACTER SET utf8mb4,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at TIMESTAMP(6) NULL,
    CONSTRAINT titan_graphql_control_jobs_status
        CHECK (status IN ('pending', 'running', 'succeeded', 'failed')),
    CONSTRAINT titan_graphql_control_jobs_request
        UNIQUE (job_type, request_key),
    INDEX titan_graphql_control_jobs_ready (status, lease_until, created_at, job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
