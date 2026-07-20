--liquibase formatted sql

-- Admin attribution (Cycle 9): the ReelyPops operator may attribute an unclaimed config to an owner WITHOUT the
-- normal claim (verify + subscribe) flow — for comping access and for operator / E2E testing. `admin_attributed`
-- flags such configs so they are distinguishable from properly-claimed ones (auditable, revocable).

--changeset rpsupportgroup:002-admin-attribution
ALTER TABLE support_group_config ADD COLUMN admin_attributed boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE support_group_config DROP COLUMN admin_attributed;
