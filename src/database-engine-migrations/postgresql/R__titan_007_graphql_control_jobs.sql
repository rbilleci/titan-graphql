CREATE TABLE IF NOT EXISTS public.titan_graphql_control_jobs (
    job_id CHAR(36) PRIMARY KEY,
    job_type VARCHAR(128) NOT NULL,
    request_key VARCHAR(128),
    payload_json TEXT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_token CHAR(36),
    lease_until TIMESTAMPTZ,
    result_json TEXT,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT titan_graphql_control_jobs_status
        CHECK (status IN ('pending', 'running', 'succeeded', 'failed')),
    CONSTRAINT titan_graphql_control_jobs_request
        UNIQUE (job_type, request_key)
);

CREATE INDEX IF NOT EXISTS titan_graphql_control_jobs_ready
    ON public.titan_graphql_control_jobs (status, lease_until, created_at, job_id);
