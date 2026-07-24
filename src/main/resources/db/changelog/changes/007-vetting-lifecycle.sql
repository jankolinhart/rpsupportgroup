--liquibase formatted sql

-- Vetting lifecycle (P1): a config's vetting becomes a first-class state machine
--   UNDER_VERIFICATION -> { VETTED | REJECTED(reason, cooldown) | BLOCKED }
-- superseding the plain `vetted` boolean (Cycle 10, migration 003). The boolean is KEPT as a stored projection of
-- (vetting_state = VETTED) so the existing browseVetted query + the client lock logic stay untouched. Existing rows
-- are backfilled: vetted=true -> VETTED, else UNDER_VERIFICATION (the client-uploaded, not-yet-approved configs).

--changeset rpsupportgroup:007-vetting-lifecycle
ALTER TABLE support_group_config ADD COLUMN vetting_state varchar(32) NOT NULL DEFAULT 'UNDER_VERIFICATION';
ALTER TABLE support_group_config ADD COLUMN reject_reason  text;
ALTER TABLE support_group_config ADD COLUMN cooldown_until timestamptz;
ALTER TABLE support_group_config ADD COLUMN rejected_at    timestamptz;
UPDATE support_group_config SET vetting_state = 'VETTED' WHERE vetted = true;
--rollback ALTER TABLE support_group_config DROP COLUMN rejected_at;
--rollback ALTER TABLE support_group_config DROP COLUMN cooldown_until;
--rollback ALTER TABLE support_group_config DROP COLUMN reject_reason;
--rollback ALTER TABLE support_group_config DROP COLUMN vetting_state;
