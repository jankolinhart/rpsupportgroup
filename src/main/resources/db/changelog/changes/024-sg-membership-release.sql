--liquibase formatted sql

-- Membership RELEASE (B6). Until now the claim was ONE-WAY: rpserver could CLAIM a membership into sg_membership
-- (which is what MembershipQuotaService counts groups.max against) but nothing anywhere could give one back. A user
-- who removed a group locally freed their client-side slot while the CLOUD still counted it, so their next add was
-- refused by the backstop while the client believed there was room. The release DELETES the sg_membership row.
--
-- WHY A DELETE AND NOT A FLIP TO NOT_FOLLOWING. Both would drop the row out of the quota count, but they say
-- different things to the Q6 board ring, which reads the same registry three-state: NO ROW -> 404 (the board does
-- not exist for you), NOT_FOLLOWING -> 403 (listed, but withheld) and it still appears in /leaderboard/my-groups
-- with a "withheld" note. A user who deliberately gave a group back must not keep seeing it listed forever, so the
-- row goes. That is also why the release is NOT a self-expiry and does not weaken sg_membership's durability rule:
-- nothing here sweeps, ages out or purges anything -- only an explicit, authenticated act of the row's OWN user.
--
-- ...WHICH IS WHY THE TRACE LIVES HERE. A membership release changes what a plan is being consumed by, and the
-- deleted row cannot record its own deletion. One row per membership ACTUALLY removed, carrying what was removed
-- and what state it was in. Deliberately NOT unique on (user_id, ig_handle, ig_account): release / re-claim /
-- release again is a legitimate sequence and each pass is its own entry, so the table reads as a history. An
-- idempotent repeat release (the row is already gone -- the client WILL retry) succeeds and writes NOTHING, so a
-- retry storm cannot forge a history of releases that never happened.

--changeset rpsupportgroup:024-sg-membership-release
CREATE TABLE sg_membership_release (
    id                    uuid        PRIMARY KEY,
    user_id               uuid        NOT NULL,
    ig_handle             varchar     NOT NULL,
    ig_account            varchar     NOT NULL,
    released_at           timestamptz NOT NULL,
    prior_status          varchar     NOT NULL,
    prior_instantiated_at timestamptz,
    created_at            timestamptz NOT NULL,
    CONSTRAINT ck_sg_membership_release_status CHECK (prior_status IN ('FOLLOWING', 'NOT_FOLLOWING'))
);
CREATE INDEX idx_sg_membership_release_user ON sg_membership_release (user_id);
CREATE INDEX idx_sg_membership_release_natural ON sg_membership_release (user_id, ig_handle, ig_account);
--rollback DROP TABLE sg_membership_release;
