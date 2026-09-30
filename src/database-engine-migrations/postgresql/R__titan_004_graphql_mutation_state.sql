CREATE TABLE IF NOT EXISTS public.titan_graphql_mutation_receipts (
    model_hash CHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    actor_key VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    package_identity CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (model_hash, tenant_id, actor_key, idempotency_key)
);

CREATE TABLE IF NOT EXISTS public.titan_graphql_mutation_audit (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_hash CHAR(64) NOT NULL,
    package_identity CHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    actor_key VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
