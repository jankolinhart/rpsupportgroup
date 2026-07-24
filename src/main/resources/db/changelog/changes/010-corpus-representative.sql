--liquibase formatted sql

-- SG corpus representative thumbnails (P1, slice 3b): the actual image bytes (bytea) for a snapshot's cluster
-- representatives — a handful of posts per snapshot that Tier-2 vision / OCR reads. Keyed by (snapshot, shortcode)
-- and upserted. Kept in its own table (avatar pattern) so the metadata item reads never drag image bytes.

--changeset rpsupportgroup:010-corpus-representative
CREATE TABLE sg_corpus_representative (
    id           uuid        PRIMARY KEY,
    snapshot_id  uuid        NOT NULL REFERENCES sg_corpus_snapshot (id) ON DELETE CASCADE,
    shortcode    varchar(64) NOT NULL,
    image        bytea       NOT NULL,
    content_type text        NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT uq_sg_corpus_representative UNIQUE (snapshot_id, shortcode)
);
--rollback DROP TABLE sg_corpus_representative;
