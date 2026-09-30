CREATE OR REPLACE FUNCTION management_graphql.titan_graphql_job_payload_sha256(payload_text TEXT)
RETURNS CHAR(64)
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT encode(sha256(convert_to(payload_text, 'UTF8')), 'hex');
$$;
