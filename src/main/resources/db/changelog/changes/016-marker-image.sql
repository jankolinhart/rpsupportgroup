--liquibase formatted sql

-- SG durable, content-addressed marker DISPLAY images (per-weekday vetted marker images, sg-per-weekday-marker-images.md).
-- At vet time we capture a small display thumbnail of each marker reference's chosen image and store it here keyed by a
-- CONTENT HASH (sha-256 of the thumbnail bytes) = the `imageLocator` carried on the vetted profile's marker references.
-- Durable + content-addressed on purpose: corpus representatives are snapshot-scoped and pruned, whereas the client must
-- be able to display the canonical per-weekday marker regardless of local capture, and cache it by content (an unchanged
-- image is never re-fetched; a changed image gets a new locator). Deduplicated: identical thumbnails share one row.

--changeset rpsupportgroup:016-marker-image
CREATE TABLE sg_marker_image (
    locator      varchar(64) PRIMARY KEY,
    content_type text        NOT NULL,
    image        bytea       NOT NULL,
    created_at   timestamptz NOT NULL
);
--rollback DROP TABLE sg_marker_image;
