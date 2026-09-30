CREATE SCHEMA IF NOT EXISTS management_graphql;
CREATE SCHEMA IF NOT EXISTS management;

CREATE TABLE IF NOT EXISTS management.graphql_artifact_requests (
    job_id CHAR(36) PRIMARY KEY,
    draft_id VARCHAR(191) NOT NULL,
    generation_profile VARCHAR(128) NOT NULL,
    enable_introspection BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS management.graphql_validation_requests (
    job_id CHAR(36) PRIMARY KEY,
    draft_id VARCHAR(191) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS management.graphql_import_requests (
    job_id CHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(191) NOT NULL,
    yaml_source TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS management.graphql_observed_operations (
    id VARCHAR(64) PRIMARY KEY,
    model_id VARCHAR(191) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    role VARCHAR(128) NOT NULL,
    client VARCHAR(191) NOT NULL,
    operation_hash VARCHAR(128) NOT NULL,
    operation_name VARCHAR(191) NOT NULL,
    document TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    depth INTEGER NOT NULL,
    estimated_cost INTEGER NOT NULL,
    field_usage_json TEXT NOT NULL,
    first_seen_at VARCHAR(64) NOT NULL,
    last_seen_at VARCHAR(64) NOT NULL,
    observed_count INTEGER NOT NULL,
    operation_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS management.graphql_operation_registries (
    id VARCHAR(512) PRIMARY KEY,
    model_id VARCHAR(191) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    operations_json TEXT NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    registry_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS management.graphql_registry_operations (
    registry_id VARCHAR(512) NOT NULL,
    operation_id VARCHAR(512) NOT NULL,
    operation_position INTEGER NOT NULL,
    operation_hash VARCHAR(512) NOT NULL,
    document_hash VARCHAR(64) NOT NULL,
    operation_name TEXT NOT NULL,
    document TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    roles_json TEXT NOT NULL,
    clients_json TEXT NOT NULL,
    PRIMARY KEY (registry_id, operation_id)
);

CREATE INDEX IF NOT EXISTS graphql_registry_operations_match
    ON management.graphql_registry_operations (registry_id, operation_hash);

CREATE INDEX IF NOT EXISTS graphql_registry_operations_document_match
    ON management.graphql_registry_operations (registry_id, document_hash);

CREATE TABLE IF NOT EXISTS management.graphql_review_mutex (
    id INTEGER PRIMARY KEY
);

INSERT INTO management.graphql_review_mutex (id) VALUES (1)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS management.graphql_review_requests (
    job_id CHAR(36) PRIMARY KEY,
    observed_operation_id VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
