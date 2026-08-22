--liquibase formatted sql

-- SG membership (B6): the AUTHORITATIVE per-user follow record for a support group's Instagram accounts. rpenduser
-- forwards each client's observed follow status here (east-west, ROLE_INTERNAL) and reads it back to decide
-- suppression. The record is DELIBERATE and durable: only an explicit report mutates status, and NOTHING ever
-- self-expires it -- no TTL, no @Scheduled sweep, no purge. following_confirmed_at age is display-only.
--
-- Idempotent UPSERT keyed (user_id, ig_handle, ig_account), case-insensitive on the handles (stored lower-cased):
--   following      -> status=FOLLOWING; following_confirmed_at=now; instantiated_at=now on a new row
--   not_following  -> status=NOT_FOLLOWING (the only flip); status_reported_at=now
--   unknown/requested/blank/null -> NO-OP on an existing row; fail-open to normal behaviour
-- ABSENCE RULE: a handle simply not reported is never deleted or changed (absence != unfollow).

--changeset rpsupportgroup:023-sg-membership
CREATE TABLE sg_membership (
    id                     uuid        PRIMARY KEY,
    user_id                uuid        NOT NULL,
    ig_handle              varchar     NOT NULL,
    ig_account             varchar     NOT NULL,
    status                 varchar     NOT NULL,
    following_confirmed_at timestamptz,
    instantiated_at        timestamptz,
    status_reported_at     timestamptz,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    CONSTRAINT uq_sg_membership_natural UNIQUE (user_id, ig_handle, ig_account),
    CONSTRAINT ck_sg_membership_status CHECK (status IN ('FOLLOWING', 'NOT_FOLLOWING'))
);
CREATE INDEX idx_sg_membership_user ON sg_membership (user_id);
--rollback DROP TABLE sg_membership;
