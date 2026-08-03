--liquibase formatted sql

-- Drift observation (M5 re-vet consumer): the append-per-reporter LEDGER of client-reported drift for a config. Each
-- home client that observes a persistent marker-disagree drift (its per-scan agree/disagree tally) or a new-owner
-- nomination forwards one observation here (via rpenduser). We UPSERT per (config, kind, reporter [+ nominated handle]),
-- bumping occurrence_count + last_seen_at, so a single row per reporter carries "how often" + the latest tally. An
-- unresolved MARKER_DISAGREE observation DERIVES the config's "needs re-vet" flag (a read-side signal — no config
-- mutation, no ETag bump); re-vetting resolves them. NEW_OWNER observations are the admin review-candidate surface.

--changeset rpsupportgroup:015-drift-observation
CREATE TABLE drift_observation (
    id                     uuid         PRIMARY KEY,
    config_id              uuid         NOT NULL REFERENCES support_group_config (id),
    kind                   varchar(20)  NOT NULL,
    reporter_device_id     varchar(128) NOT NULL,
    reporter_user_id       uuid,
    nominated_owner_handle varchar(128),
    agree_pass             integer,
    disagree_pass          integer,
    persistence_count      integer,
    occurrence_count       bigint       NOT NULL DEFAULT 1,
    resolved               boolean      NOT NULL DEFAULT false,
    first_seen_at          timestamptz  NOT NULL DEFAULT now(),
    last_seen_at           timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_drift_obs_natural
    ON drift_observation (config_id, kind, reporter_device_id, COALESCE(nominated_owner_handle, ''));
CREATE INDEX idx_drift_obs_config_kind ON drift_observation (config_id, kind, resolved);
--rollback DROP TABLE drift_observation;
