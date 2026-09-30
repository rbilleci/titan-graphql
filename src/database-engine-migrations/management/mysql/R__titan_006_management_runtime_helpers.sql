DELIMITER $$

DROP FUNCTION IF EXISTS management_graphql.titan_rt_java_int_div$$
CREATE FUNCTION management_graphql.titan_rt_java_int_div(a BIGINT, b BIGINT)
RETURNS BIGINT
DETERMINISTIC
BEGIN
  IF b = 0 THEN
    SIGNAL SQLSTATE '22012' SET MESSAGE_TEXT = 'division by zero';
  END IF;
  RETURN a DIV b;
END$$

DELIMITER ;

DROP FUNCTION IF EXISTS management_graphql.titan_graphql_job_payload_sha256;
CREATE FUNCTION management_graphql.titan_graphql_job_payload_sha256(payload_text LONGTEXT)
RETURNS CHAR(64)
DETERMINISTIC
NO SQL
RETURN SHA2(payload_text, 256);
