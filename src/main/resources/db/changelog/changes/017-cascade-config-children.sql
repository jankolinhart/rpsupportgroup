--liquibase formatted sql

-- FK cascade fix (2026-08-11): vetted_profile_version (changeset 014) and drift_observation (015) reference
-- support_group_config(id) WITHOUT ON DELETE CASCADE — unlike the category (005) and corpus (010) FKs, which do
-- cascade. A VETTED group always has >=1 vetted_profile_version row (each Vetting-Portal save appends one), so
-- deleting its config FK-violates and the admin SG-config DELETE returns 502. Re-point both FKs to ON DELETE
-- CASCADE so removing a config also removes its version history + drift ledger — the codebase convention that a
-- config's dependent rows go with it. Postgres inline column FKs are named <table>_<column>_fkey.

--changeset rpsupportgroup:017-cascade-config-children
ALTER TABLE vetted_profile_version DROP CONSTRAINT vetted_profile_version_config_id_fkey;
ALTER TABLE vetted_profile_version ADD CONSTRAINT vetted_profile_version_config_id_fkey
    FOREIGN KEY (config_id) REFERENCES support_group_config (id) ON DELETE CASCADE;
ALTER TABLE drift_observation DROP CONSTRAINT drift_observation_config_id_fkey;
ALTER TABLE drift_observation ADD CONSTRAINT drift_observation_config_id_fkey
    FOREIGN KEY (config_id) REFERENCES support_group_config (id) ON DELETE CASCADE;
--rollback ALTER TABLE vetted_profile_version DROP CONSTRAINT vetted_profile_version_config_id_fkey;
--rollback ALTER TABLE vetted_profile_version ADD CONSTRAINT vetted_profile_version_config_id_fkey FOREIGN KEY (config_id) REFERENCES support_group_config (id);
--rollback ALTER TABLE drift_observation DROP CONSTRAINT drift_observation_config_id_fkey;
--rollback ALTER TABLE drift_observation ADD CONSTRAINT drift_observation_config_id_fkey FOREIGN KEY (config_id) REFERENCES support_group_config (id);
