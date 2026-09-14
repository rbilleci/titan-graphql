CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(40) NOT NULL
);

CREATE TABLE articles (
    id BIGINT PRIMARY KEY,
    author_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE comments (
    id BIGINT PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id),
    author_id BIGINT NOT NULL REFERENCES users(id),
    body TEXT NOT NULL
);
