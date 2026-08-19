--liquibase formatted sql

-- THE WEEKDAY IS PART OF A DRIFT'S IDENTITY (19/08/2026).
--
-- The vetted profile is per-weekday BY DESIGN (weeklySchedule.days[].references[]): a Tuesday banner and a
-- Wednesday banner are different references even when role and text agree — an owner may post a flower on Monday
-- and a monkey on Tuesday, both reading "START". Without the weekday in the observation, adoption could only
-- write a picture onto EVERY day sharing the role+text; observed 18/08/2026 as all seven START slots stamped
-- with Wednesday's banner, and the adopt/report loop oscillating between two banners fighting over one slot.
--
-- marker_weekday is the JS index (0=Sun … 6=Sat), resolved by the CLIENT from the marker's own postedOn through
-- the schedule's day-offsets (a CROSS_DAY end posted Wednesday belongs to Tuesday's slot; a handoff start posted
-- Saturday evening belongs to Sunday's). NULL = flat/legacy group or an old client; the client FAILS CLOSED on an
-- unresolvable slot, so a wrong value never arrives at all.
--changeset rpsupportgroup:022-drift-weekday-slot
ALTER TABLE drift_observation ADD COLUMN marker_weekday integer;
DROP INDEX IF EXISTS uq_drift_obs_natural;
CREATE UNIQUE INDEX uq_drift_obs_natural
    ON drift_observation (config_id, kind, reporter_device_id, COALESCE(nominated_owner_handle, ''),
                          COALESCE(marker_weekday, -1), COALESCE(marker_role, ''), COALESCE(marker_text, ''));
--rollback DROP INDEX IF EXISTS uq_drift_obs_natural;
--rollback CREATE UNIQUE INDEX uq_drift_obs_natural ON drift_observation (config_id, kind, reporter_device_id, COALESCE(nominated_owner_handle, ''), COALESCE(marker_role, ''), COALESCE(marker_text, ''));
--rollback ALTER TABLE drift_observation DROP COLUMN marker_weekday;
