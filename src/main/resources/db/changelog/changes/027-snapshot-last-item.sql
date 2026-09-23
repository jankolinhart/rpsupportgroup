--liquibase formatted sql

-- WHEN A PASS LAST SAID ANYTHING (23/09/2026, operator: "when the machine goes offline mid-scan we can see
-- it. only the snapshot stays in OPEN, that should be interrupted or stale").
--
-- The stale sweep measured from when a snapshot was OPENED. So a pass whose machine died two minutes in was
-- indistinguishable from one still streaming for the next six hours — and the console, which already knew the
-- machine had stopped reporting, had to sit beside a snapshot insisting it was open.
--
-- AGE WAS THE WRONG QUESTION. A deep scrape legitimately runs for hours, which is why the window had to be
-- six of them; but it appends items the whole time it is alive, pacing 15-45s a page. Silence is therefore
-- decisive where age never could be: a pass that has not been written to in half an hour is abandoned,
-- whether it opened four hours ago or four minutes ago.
--
-- COALESCED WITH created_at, so a snapshot opened and never appended to is swept on the same rule rather than
-- needing a second one. Null here means "nothing has ever been written", which is a real state — a scrape
-- that failed before its first page — and not a missing value to be defaulted away.

--changeset rpsupportgroup:027-snapshot-last-item
ALTER TABLE sg_corpus_snapshot ADD COLUMN last_item_at timestamptz;
COMMENT ON COLUMN sg_corpus_snapshot.last_item_at IS
    'When this pass last had items appended. Null until the first append. The stale sweep reads '
    'COALESCE(last_item_at, created_at): a pass that has gone quiet is abandoned however recently it opened.';
--rollback ALTER TABLE sg_corpus_snapshot DROP COLUMN last_item_at;
