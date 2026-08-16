--liquibase formatted sql

-- Closing the re-vet loop (16/08/2026). Two additions, both nullable — an older row carries neither.
--
-- marker_text: the OCR TEXT of the reference a measured drift was compared against. Without it, adoption can only
-- match by ROLE, and a per-weekday group carries several different banners for one role: `glowbloggeragency` has a
-- generic weekday START and a distinct "START Sonntag". Adopting Sunday's picture into every START reference would
-- teach Monday to match Sunday's banner — worse than doing nothing. With the text, the picture lands on the ONE
-- reference it actually belongs to.
--
-- detail: the human-readable "what is wrong" for a MARKER_REFERENCE_CORRUPT observation — the malformed value and
-- why it is malformed (e.g. "looks like an Instagram post shortcode (39 chars of base64url), not a hash"). The
-- administrator must be able to see the fault without opening a client log.

--changeset rpsupportgroup:019-drift-marker-text-and-detail
ALTER TABLE drift_observation ADD COLUMN marker_text varchar(512);
ALTER TABLE drift_observation ADD COLUMN detail      varchar(1024);

-- kind was varchar(20), which fits MARKER_DISAGREE / NEW_OWNER / MARKER_IMAGE_DRIFT but NOT the 24-character
-- MARKER_REFERENCE_CORRUPT. Widened with room to spare so the next kind does not need a migration of its own.
ALTER TABLE drift_observation ALTER COLUMN kind TYPE varchar(40);
