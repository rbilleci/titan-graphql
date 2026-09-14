-- titan-rollback for titan.generated-sql.fixture (postgresql)
-- Generated-shape fixture mirroring titanPackage output: drops every Titan-generated
-- object in reverse dependency order.

DROP FUNCTION IF EXISTS "public"."execute_graphql_request_with_compact_context"(TEXT);
DROP FUNCTION IF EXISTS "public"."__titan_internal_demo_helper"(TEXT, TEXT);
DROP TABLE IF EXISTS "titan_runtime"."telemetry";
