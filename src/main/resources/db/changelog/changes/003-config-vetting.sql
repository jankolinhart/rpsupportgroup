--liquibase formatted sql

-- Config vetting (Cycle 10): a client-uploaded config is PENDING (vetted=false) — invisible in the public browse
-- list — until a ReelyPops operator sanity-checks (and, if needed, corrects) it and marks it vetted. Vetting is a
-- quality gate distinct from claiming; only vetted configs are offered to other users in "choose a support group".
-- Existing configs default to false: they must be vetted before they become publicly browsable.

--changeset rpsupportgroup:003-config-vetting
ALTER TABLE support_group_config ADD COLUMN vetted boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE support_group_config DROP COLUMN vetted;
