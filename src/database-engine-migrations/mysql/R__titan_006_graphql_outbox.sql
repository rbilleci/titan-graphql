CREATE TABLE IF NOT EXISTS titan_graphql_outbox (
    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payload_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'pending',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    delivered_at TIMESTAMP(6) NULL,
    CONSTRAINT titan_graphql_outbox_status CHECK (status IN ('pending', 'leased', 'delivered')),
    INDEX titan_graphql_outbox_ready (status, lease_until, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
