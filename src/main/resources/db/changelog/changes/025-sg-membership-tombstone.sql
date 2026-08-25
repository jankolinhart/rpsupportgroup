--liquibase formatted sql

-- AN EXPLICIT REMOVAL MUST STICK (B6 release, second half).
--
-- 024 gave the user a way to hand a support group back: the sg_membership row is DELETED and traced. What it could
-- not do was make the removal survive the next device report. sg_membership.report() is reached from rpenduser's
-- MembershipForwardingService, which replays the desktop client's raw supportGroups[] out of every M5.1 report --
-- so a `following` element re-INSERTED the row the release had just deleted and silently re-took the slot the user
-- had been given back. An in-flight report queued before the removal, or a second install still running the group,
-- was enough; the slot came back, then went again, which reads to the user as intermittent breakage.
--
-- THE RULE: AN EXPLICIT ACT BEATS AN OBSERVATION. A release is something the user DID; a report is something a
-- client SAW. So a release leaves a tombstone on the natural triple, the report path refuses to create or confirm a
-- membership while that tombstone stands, and only an explicit CLAIM (rpserver's quota-gated route -- the mirror
-- explicit act) clears it. Re-joining therefore works exactly as before: claim, tombstone gone, membership back.
--
-- WHY A SEPARATE TABLE AND NOT sg_membership_release. The release trace is an append-only HISTORY -- nothing ever
-- mutates or sweeps it, and release / re-claim / release again is three legitimate entries. A tombstone is CURRENT
-- STATE: one row per triple, created by a release and destroyed by a claim. Reading the standing state off a
-- history would mean asking "is the newest release newer than the newest claim", which needs claims to be recorded
-- for the sole purpose of answering it -- and would silently give the wrong answer the moment two rows shared a
-- timestamp. One row that exists or does not cannot be misread.
--
-- NOT AN EXPIRY. Nothing here has a TTL, a @Scheduled purge or a sweep; a tombstone is created and removed by
-- explicit, authenticated acts of the row's OWN user, exactly like the release that writes it.

--changeset rpsupportgroup:025-sg-membership-tombstone
CREATE TABLE sg_membership_tombstone (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL,
    ig_handle   varchar     NOT NULL,
    ig_account  varchar     NOT NULL,
    released_at timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT uq_sg_membership_tombstone_natural UNIQUE (user_id, ig_handle, ig_account)
);
--rollback DROP TABLE sg_membership_tombstone;
