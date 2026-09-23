--liquibase formatted sql

-- WHERE A SNAPSHOT CAME FROM (23/09/2026, operator: "I cannot tell which machine/user/ighandle each snapshot
-- came from. that would be useful information.").
--
-- A snapshot already recorded captured_by_account — the Instagram handle whose session walked the grid. That is
-- one third of the answer. A customer can have several machines and several handles, and the console's
-- deep-scrape picker exists precisely because those are different things: an operator starts a scrape on a
-- NAMED computer. When the evidence arrives, nothing said which computer produced it.
--
-- It matters beyond curiosity. Two passes of one group can disagree — different handles see different subsets
-- of a tagged grid, and a machine with a stale session sees less than one with a fresh one. Reading "REQUEST
-- SEALED 5238 items" beside "ADMIN SEALED 51 items" and being unable to say which machine or which handle
-- produced either leaves the difference unexplainable.
--
-- THE USER ID IS STAMPED BY THE SERVER, NOT CLAIMED BY THE CLIENT. rpserver knows who a caller is — it
-- validated their token and holds the subject — so the id is taken from there and the body is never asked. A
-- client that could name its own owner could name somebody else's, and provenance a caller can choose is not
-- provenance. The DEVICE is client-reported, because only the machine knows its own fingerprint; it is a
-- label on evidence rather than an authorisation, and nothing is granted on the strength of it.
--
-- Both are nullable, and stay nullable. Every snapshot that already exists predates this, and a build too old
-- to report either is still a build whose passes are perfectly good evidence — the columns say "not recorded",
-- which is honest, where a default would invent a machine that never ran anything.

--changeset rpsupportgroup:026-snapshot-provenance
ALTER TABLE sg_corpus_snapshot ADD COLUMN captured_by_device text;
ALTER TABLE sg_corpus_snapshot ADD COLUMN captured_for_user uuid;
COMMENT ON COLUMN sg_corpus_snapshot.captured_by_device IS
    'The machine fingerprint that ran this pass, as the client reported it. Null on a snapshot from before '
    'provenance was recorded, or from a client too old to send it — never a guess.';
COMMENT ON COLUMN sg_corpus_snapshot.captured_for_user IS
    'The customer whose machine ran this pass, taken from the validated token by rpserver and never from the '
    'request body: provenance a caller could choose would not be provenance.';
--rollback ALTER TABLE sg_corpus_snapshot DROP COLUMN captured_for_user;
--rollback ALTER TABLE sg_corpus_snapshot DROP COLUMN captured_by_device;
