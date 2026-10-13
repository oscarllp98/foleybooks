-- CA-03 / ADR-003: catalog_db schema — categories, books.
-- Availability (D-09/FR-07) is never stored: stock_quantity is the single
-- mutable source of truth and the response layer derives
-- OUT_OF_STOCK / LOW_STOCK / IN_STOCK from it. No search or sort index at
-- seed scale (NFR-02): the escalation path is a *new* migration adding
-- pg_trgm/B-tree indexes, never an edit here (C16).

CREATE TABLE categories (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_categories_name UNIQUE (name)
);

CREATE TABLE books (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID          NOT NULL,
    title          VARCHAR(300)  NOT NULL,
    author         VARCHAR(200)  NOT NULL,
    isbn           VARCHAR(20)   NOT NULL,
    price          NUMERIC(10,2) NOT NULL,
    stock_quantity INTEGER       NOT NULL,
    cover_url      VARCHAR(500)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_books_category FOREIGN KEY (category_id) REFERENCES categories (id)
                                 ON DELETE RESTRICT,
    CONSTRAINT uk_books_isbn     UNIQUE (isbn),
    CONSTRAINT ck_books_price    CHECK (price >= 0),
    CONSTRAINT ck_books_stock    CHECK (stock_quantity >= 0)
);

-- Serves the FR-08/FR-09 categoryId filter and keeps the FK check cheap on insert.
CREATE INDEX ix_books_category ON books (category_id);
