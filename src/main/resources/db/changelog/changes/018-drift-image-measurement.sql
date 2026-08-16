--liquibase formatted sql

-- MEASURED banner drift (16/08/2026). The existing agree/disagree columns record a FAILURE tally — how many of a
-- marker owner's posts the image contradicted. That signal over-reported the owner's ordinary non-marker posts and
-- fell silent entirely once TEXT began deciding the marker's role on the client, because a rescued marker is never
-- demoted: glow's Sunday banner sat 15 bits from its reference and reported nothing at all.
--
-- These columns record the MEASUREMENT instead: the CONFIRMED marker's picture sits image_distance bits from the
-- reference that names it, whose own tolerance is image_threshold. evidence_image_locator points at the picture the
-- owner is actually posting — uploaded BY THE CLIENT (directive B1: no cloud service ever contacts Instagram), which
-- is what lets an administrator adopt the newer banner without a full duty scrape.
--
-- All nullable: a row raised by the legacy demotion path carries no measurement and must not be made to invent one.

--changeset rpsupportgroup:018-drift-image-measurement
ALTER TABLE drift_observation ADD COLUMN marker_role            varchar(20);
ALTER TABLE drift_observation ADD COLUMN image_distance         integer;
ALTER TABLE drift_observation ADD COLUMN image_threshold        integer;
ALTER TABLE drift_observation ADD COLUMN evidence_post_id       varchar(64);
ALTER TABLE drift_observation ADD COLUMN evidence_image_locator varchar(128);
