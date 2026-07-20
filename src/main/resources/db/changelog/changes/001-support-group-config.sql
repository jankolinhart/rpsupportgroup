--liquibase formatted sql

-- rpsupportgroup config registry (Phase 1, F1): the authoritative SG *definition* that both the ReelyPops
-- client (for liking) and the scanner (for analytics) read identically. One row per support group, keyed on
-- the group's Instagram account (ig_account). The rich definition (type, marker owners, opening days, timezone,
-- max tags, safe-tag-removal, liking-end) is held as jsonb so it can evolve without a migration; user-local
-- preferences (auto-like, cadence, notifications) live on the client, never here. `version` is the ETag the
-- client polls for changes (Q1). A config is UNCLAIMED until an owner claims it (§6).

--changeset rpsupportgroup:001-support-group-config
CREATE TABLE support_group_config (
    id          uuid         PRIMARY KEY,
    ig_account  varchar(120) NOT NULL,
    status      varchar(20)  NOT NULL,
    owner_id    uuid,
    definition  jsonb        NOT NULL,
    version     bigint       NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sgconfig_ig_account UNIQUE (ig_account)
);
CREATE INDEX idx_sgconfig_status ON support_group_config (status);
--rollback DROP TABLE support_group_config;
