CREATE TABLE IF NOT EXISTS public.titan_graphql_outbox (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_token CHAR(36),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT titan_graphql_outbox_status CHECK (status IN ('pending', 'leased', 'delivered'))
);

CREATE INDEX IF NOT EXISTS titan_graphql_outbox_ready
    ON public.titan_graphql_outbox (status, lease_until, event_id);
