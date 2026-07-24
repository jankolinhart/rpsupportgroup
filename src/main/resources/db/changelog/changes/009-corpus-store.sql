--liquibase formatted sql

-- SG marker corpus store (P1): the append-only, snapshot-versioned evidence a client deep-scrape ships for vetting.
-- Each deep tagged-grid pass is ONE snapshot (never stitched across clients); items stream in per scroll (metadata +
-- dHash always). Representative thumbnail bytes (bytea) + retention GC land in later slices. Kept in its own tables so
-- the frequently read support_group_config rows stay lean.

--changeset rpsupportgroup:009-corpus-store
CREATE TABLE sg_corpus_snapshot (
    id                  uuid        PRIMARY KEY,
    ig_account          text        NOT NULL,
    source              varchar(16) NOT NULL,
    status              varchar(16) NOT NULL,
    captured_by_account text,
    item_count          int         NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    sealed_at           timestamptz
);
CREATE INDEX idx_sg_corpus_snapshot_ig_account ON sg_corpus_snapshot (ig_account);

CREATE TABLE sg_corpus_snapshot_item (
    id              uuid        PRIMARY KEY,
    snapshot_id     uuid        NOT NULL REFERENCES sg_corpus_snapshot (id) ON DELETE CASCADE,
    shortcode       varchar(64) NOT NULL,
    author_username text        NOT NULL,
    d_hash          varchar(64) NOT NULL,
    posted_at       timestamptz,
    ordinal         int         NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX idx_sg_corpus_snapshot_item_snapshot ON sg_corpus_snapshot_item (snapshot_id);
--rollback DROP TABLE sg_corpus_snapshot_item;
--rollback DROP TABLE sg_corpus_snapshot;
