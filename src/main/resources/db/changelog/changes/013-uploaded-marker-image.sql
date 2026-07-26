--liquibase formatted sql

-- SG uploaded marker images (M3 follow-up): a marker image an operator uploads by hand in the Vetting Portal when
-- the auto-detected corpus missed a marker (e.g. a brand-new group, or a marker style the scrape did not cluster).
-- We store the raw bytes plus a best-effort perceptual dHash (same 9x8 horizontal-gradient format the scraper emits)
-- so a vetted profile can reference the upload the same way it references a detected cluster. Standalone (no snapshot
-- FK): an upload is not tied to any single scrape pass.

--changeset rpsupportgroup:013-uploaded-marker-image
CREATE TABLE sg_uploaded_marker_image (
    id           uuid        PRIMARY KEY,
    d_hash       varchar(64) NOT NULL,
    image        bytea       NOT NULL,
    content_type text        NOT NULL,
    created_at   timestamptz NOT NULL
);
--rollback DROP TABLE sg_uploaded_marker_image;
