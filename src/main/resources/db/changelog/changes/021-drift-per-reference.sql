--liquibase formatted sql

-- ONE DRIFT OBSERVATION PER REFERENCE, NOT PER GROUP (19/08/2026).
--
-- The natural key was (config, kind, reporter, handle). A per-weekday group carries SEVERAL references per kind —
-- `glowbloggeragency` has an ENDE banner, a generic weekday START and a distinct Sunday START — and they drift
-- independently. Every one of them upserted into the SAME row, so the last one reported won and the rest were
-- silently discarded.
--
-- Measured live 18/08/2026: one scan reported three drifting references (ENDE 17 bits, weekday START 26, Sunday
-- START 21). The administrator was shown ONE of them — and not the worst. The 26-bit weekday START, the most
-- broken of the three, never reached the surface at all.
--
-- It also corrupted `persistence_count`, documented as "consecutive drifting scans": three reports in a single
-- scan bumped it three times, so the re-vet strength signal measured how many references drifted rather than how
-- long any of them had been drifting.
--
-- The reference is identified the same way it is everywhere else in this loop — by ROLE **and** TEXT, never role
-- alone, because one role covers several banners. COALESCE keeps the key stable for the kinds that carry neither
-- (MARKER_DISAGREE tallies, NEW_OWNER nominations), so their behaviour is unchanged.
--changeset rpsupportgroup:021-drift-obs-per-reference
DROP INDEX IF EXISTS uq_drift_obs_natural;
CREATE UNIQUE INDEX uq_drift_obs_natural
    ON drift_observation (config_id, kind, reporter_device_id, COALESCE(nominated_owner_handle, ''),
                          COALESCE(marker_role, ''), COALESCE(marker_text, ''));
--rollback DROP INDEX IF EXISTS uq_drift_obs_natural;
--rollback CREATE UNIQUE INDEX uq_drift_obs_natural ON drift_observation (config_id, kind, reporter_device_id, COALESCE(nominated_owner_handle, ''));
