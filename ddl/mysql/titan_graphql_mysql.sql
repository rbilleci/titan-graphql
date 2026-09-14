-- MySQL variant of ddl/titan_graphql_postgres.sql (completion plan W5.2).
--
-- Dialect mapping notes:
--   * The transpiled kernel qualifies every object with the configured Titan schema
--     ("public"); MySQL schemas are databases, so the demo tables live in an explicit
--     `public` database (created here, mirroring core's scratch-container provisioning in
--     TitanScratchDatabases.prepareSchemas).
--   * MySQL databases are server-global on the shared test container (one container, one
--     `public` database for every test), unlike the per-test PostgreSQL databases — the
--     script is therefore written re-runnable: DROP TABLE IF EXISTS before CREATE, children
--     before parents for the FK chain.
--   * BOOLEAN is TINYINT(1) on MySQL; the transpiled routines compare with TRUE/FALSE
--     literals, which MySQL accepts natively.

CREATE DATABASE IF NOT EXISTS `public`;

DROP TABLE IF EXISTS `public`.`comments`;
DROP TABLE IF EXISTS `public`.`articles`;
DROP TABLE IF EXISTS `public`.`users`;

CREATE TABLE `public`.`users` (
    id BIGINT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(40) NOT NULL
);

CREATE TABLE `public`.`articles` (
    id BIGINT PRIMARY KEY,
    author_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_articles_author FOREIGN KEY (author_id) REFERENCES `public`.`users` (id)
);

CREATE TABLE `public`.`comments` (
    id BIGINT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    CONSTRAINT fk_comments_article FOREIGN KEY (article_id) REFERENCES `public`.`articles` (id),
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES `public`.`users` (id)
);
