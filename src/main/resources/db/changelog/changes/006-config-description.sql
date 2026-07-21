--liquibase formatted sql

-- Group description (Cycle 11, R-1): a free-text blurb shown lazily in the ReelyPops client's "i" info card on the
-- "choose a support group" tiles. Authoritative config metadata curated by the admin (editable while PENDING, locked
-- once vetted) but held OUTSIDE the jsonb definition because it is not round truth — so it never bumps the definition
-- version ETag the client polls. Nullable: existing configs have no description until an admin adds one.
-- (openWeekdays — the general opening-days field, R-1 — rides the existing jsonb `definition` column, so no DDL here.)

--changeset rpsupportgroup:006-config-description
ALTER TABLE support_group_config ADD COLUMN description text;
--rollback ALTER TABLE support_group_config DROP COLUMN description;
