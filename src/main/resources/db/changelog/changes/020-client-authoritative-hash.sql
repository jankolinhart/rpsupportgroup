--liquibase formatted sql

-- THE CLIENT'S HASH IS THE ONLY ONE THAT COUNTS (18/08/2026).
--
-- Measured 16/08/2026: hashing identical bytes with this service's Java ImageDHash (ImageIO/AWT) and with the
-- desktop client's lib/dhash.js (sharp/libvips) gives fingerprints 15-34 bits apart — as far apart as unrelated
-- images. Clients match live posts at a 4-10 bit tolerance, so a hash computed HERE looks healthy and can never
-- match. Cross-PLATFORM agreement is proven (0 bits on ubuntu/windows/macos with real Instagram images); it is
-- crossing IMPLEMENTATIONS that breaks. So the client sends its own fingerprint and we store it verbatim.

--changeset rpsupportgroup:020-drift-client-hash
ALTER TABLE drift_observation ADD COLUMN evidence_image_hash varchar(64);

-- A corpus item whose dHash is NOT a perceptual hash is UNUSABLE as a marker reference: it can never match a post
-- and, worse, it can be picked in the Vetting Portal and written into a vetted profile — which is exactly how
-- `glowbloggeragency` came to carry a post shortcode where its Sunday START hash belongs. Flagged rather than
-- deleted: the post is still real evidence of the group's activity, it just must never be offered as a reference.
--changeset rpsupportgroup:020-corpus-item-unusable
ALTER TABLE sg_corpus_snapshot_item ADD COLUMN unusable boolean NOT NULL DEFAULT false;
ALTER TABLE sg_corpus_snapshot_item ADD COLUMN unusable_reason varchar(512);

-- A DURABLE catalogue of the marker pictures CLIENTS have delivered, each with the client's own fingerprint.
--
-- Drift observations get resolved and pruned; this outlives them. It is what lets an operator pick "the banner
-- that was actually being posted on 16/08" during a later vetting without ordering a duty scrape — and, because
-- the hash came from a client, it is a fingerprint every client can actually match.
--
-- Deduplicated by (config, hash): the same picture observed on fifty scans is one candidate, not fifty.
--changeset rpsupportgroup:020-client-marker-image
CREATE TABLE client_marker_image (
    id              uuid PRIMARY KEY,
    config_id       uuid NOT NULL REFERENCES support_group_config(id) ON DELETE CASCADE,
    d_hash          varchar(64) NOT NULL,
    image_locator   varchar(128) NOT NULL,
    marker_role     varchar(20),
    marker_text     varchar(512),
    evidence_post_id varchar(64),
    first_seen_at   timestamptz NOT NULL,
    last_seen_at    timestamptz NOT NULL,
    times_seen      bigint NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX uidx_client_marker_image ON client_marker_image (config_id, d_hash);

-- A pass that streamed even ONE malformed fingerprint is voided WHOLE rather than salvaged, and this records why.
-- Rationale (user's call, 16/08/2026): a deep scrape builds the corpus that vetted references are cut from, and
-- those references are matched by every OTHER client. Admitting one bad row risks a reference nothing can match,
-- and the failure is silent — it presents as a marker owner having changed their picture. Re-running a scrape is
-- cheap; a poisoned reference shipped to every client is not.
--changeset rpsupportgroup:020-snapshot-rejected-reason
ALTER TABLE sg_corpus_snapshot ADD COLUMN rejected_reason varchar(512);
