--liquibase formatted sql

-- SG avatar store (Cycle 10, 3.5b-3 follow-up): a ReelyPops-hosted avatar for a support group, keyed by its
-- Instagram account and served at a URL templatable by that username. Only the reelypops client (which has a
-- residential Instagram exit) contributes the real image bytes via the BFF; the admin/website "create support
-- group" routes have no residential exit, so they leave it empty and the serve endpoint 404s (the client then
-- renders a placeholder) until a client later contributes the real image. Kept in its own table so the frequently
-- read support_group_config rows stay lean.

--changeset rpsupportgroup:004-support-group-avatar
CREATE TABLE support_group_avatar (
    id           uuid PRIMARY KEY,
    ig_account   text NOT NULL UNIQUE,
    image        bytea NOT NULL,
    content_type text NOT NULL,
    updated_at   timestamptz NOT NULL
);
--rollback DROP TABLE support_group_avatar;
