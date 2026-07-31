--liquibase formatted sql

-- Versioned vetted profile (M5 A2): an append-only HISTORY of every confirmed VettedProfile snapshot per config, an
-- active-version POINTER on the config so the operator can ROLL BACK to a prior version (the kill switch), and a
-- per-config MODE flag (liking / scrape-only / paused). Each Vetting-Portal save appends a snapshot + repoints the
-- pointer; a rollback only repoints (no new row) so history is never lost. `change_note` is the structured field-level
-- diff vs the prior active snapshot (the client renders it as an i18n announcement, #5.3). The client-facing `version`
-- ETag still bumps on save + rollback + mode so polling clients re-fetch.

--changeset rpsupportgroup:014-vetted-profile-versioning
CREATE TABLE vetted_profile_version (
    id               uuid        PRIMARY KEY,
    config_id        uuid        NOT NULL REFERENCES support_group_config (id),
    snapshot_version bigint      NOT NULL,
    vetted_profile   jsonb       NOT NULL,
    change_note      jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_vpv_config_version UNIQUE (config_id, snapshot_version)
);
CREATE INDEX idx_vpv_config ON vetted_profile_version (config_id);

ALTER TABLE support_group_config ADD COLUMN active_snapshot_version bigint;
ALTER TABLE support_group_config ADD COLUMN mode varchar(20) NOT NULL DEFAULT 'LIKING';
--rollback DROP TABLE vetted_profile_version;
--rollback ALTER TABLE support_group_config DROP COLUMN active_snapshot_version;
--rollback ALTER TABLE support_group_config DROP COLUMN mode;
