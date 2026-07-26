--liquibase formatted sql

-- The admin-only vetting ADVISORY (M3a): the DetectedProfile the pipeline detected from a group's corpus, stored as
-- jsonb so its facet set can grow (M3b adds group-type / round-timing / opening-day facets) without a migration. It is
-- NOT round truth — it never ships to clients and never bumps the definition `version`; it only pre-fills the admin
-- Vetting Portal, and is regenerated on every (re-)vet. Nullable: a config has none until it is analysed.

--changeset rpsupportgroup:011-detected-profile
ALTER TABLE support_group_config ADD COLUMN detected_profile jsonb;
--rollback ALTER TABLE support_group_config DROP COLUMN detected_profile;
