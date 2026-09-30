CREATE DATABASE IF NOT EXISTS management_graphql;
CREATE DATABASE IF NOT EXISTS management;

CREATE TABLE IF NOT EXISTS management.graphql_artifact_requests (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    draft_id VARCHAR(191) NOT NULL,
    generation_profile VARCHAR(128) NOT NULL,
    enable_introspection BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS management.graphql_validation_requests (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    draft_id VARCHAR(191) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS management.graphql_import_requests (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id VARCHAR(191) NOT NULL,
    yaml_source LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS management.graphql_observed_operations (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    model_id VARCHAR(191) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    role VARCHAR(128) NOT NULL,
    client VARCHAR(191) NOT NULL,
    operation_hash VARCHAR(128) NOT NULL,
    operation_name VARCHAR(191) NOT NULL,
    document LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    depth INTEGER NOT NULL,
    estimated_cost INTEGER NOT NULL,
    field_usage_json LONGTEXT NOT NULL,
    first_seen_at VARCHAR(64) NOT NULL,
    last_seen_at VARCHAR(64) NOT NULL,
    observed_count INTEGER NOT NULL,
    operation_json LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS management.graphql_operation_registries (
    id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    model_id VARCHAR(191) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    operations_json LONGTEXT NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    registry_json LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS management.graphql_registry_operations (
    registry_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_position INTEGER NOT NULL,
    operation_hash VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_name LONGTEXT NOT NULL,
    document LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    roles_json LONGTEXT NOT NULL,
    clients_json LONGTEXT NOT NULL,
    PRIMARY KEY (registry_id, operation_id),
    INDEX graphql_registry_operations_match (registry_id, operation_hash),
    INDEX graphql_registry_operations_document_match (registry_id, document_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS management.graphql_review_mutex (
    id INTEGER PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO management.graphql_review_mutex (id) VALUES (1);

CREATE TABLE IF NOT EXISTS management.graphql_review_requests (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    observed_operation_id VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
