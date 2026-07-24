--liquibase formatted sql

-- Name-only requests (P1): a support group can be requested by handle alone — the admin/AI fills the definition
-- during vetting — so `definition` becomes nullable. A config in UNDER_VERIFICATION may therefore have no definition
-- yet; vet() asserts a non-null definition before approval. Existing rows already have a definition, so are unaffected.

--changeset rpsupportgroup:008-nullable-definition
ALTER TABLE support_group_config ALTER COLUMN definition DROP NOT NULL;
--rollback ALTER TABLE support_group_config ALTER COLUMN definition SET NOT NULL;
