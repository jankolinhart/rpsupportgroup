--liquibase formatted sql

-- The single authoritative CONFIRMED SG profile (M3a): the admin-corrected VettedProfile (round-truth + confirmed
-- detector + description) that ships to clients. Stored as jsonb so its attribute set can grow without a migration.
-- The legacy `definition` column is kept as a ONE-WAY PROJECTION of vetted_profile.definition (regenerated on save,
-- never edited independently) so today's client + scanner keep working; M5 retires the projection (they read
-- vetted_profile directly) — see rpdocu TODO [Arch/SG]. Nullable: a config has none until the admin first saves.

--changeset rpsupportgroup:012-vetted-profile
ALTER TABLE support_group_config ADD COLUMN vetted_profile jsonb;
--rollback ALTER TABLE support_group_config DROP COLUMN vetted_profile;
