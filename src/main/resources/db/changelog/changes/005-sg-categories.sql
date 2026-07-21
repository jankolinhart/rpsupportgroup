--liquibase formatted sql

-- SG content categories (Cycle 12): a fixed, admin-curated taxonomy. A support group has 0..n categories
-- (many-to-many); the ReelyPops client filters / searches the browse list by them. Categories are config
-- metadata — a third class beside the authoritative jsonb `definition` and the client-local preferences —
-- admin-owned and NOT vet-locked. Removing a category unassigns it from every group (the join FK cascades).

--changeset rpsupportgroup:005-sg-categories
CREATE TABLE sg_category (
    id    uuid        PRIMARY KEY,
    slug  varchar(60) NOT NULL,
    label varchar(80) NOT NULL,
    CONSTRAINT uq_sg_category_slug UNIQUE (slug)
);

CREATE TABLE sg_config_category (
    config_id   uuid NOT NULL REFERENCES support_group_config (id) ON DELETE CASCADE,
    category_id uuid NOT NULL REFERENCES sg_category (id) ON DELETE CASCADE,
    CONSTRAINT pk_sg_config_category PRIMARY KEY (config_id, category_id)
);
CREATE INDEX idx_sg_config_category_category ON sg_config_category (category_id);

-- Seed the 15 curated categories (the admin can add / remove more later).
INSERT INTO sg_category (id, slug, label) VALUES
    (gen_random_uuid(), 'travel',        'Travel'),
    (gen_random_uuid(), 'dark',          'Dark'),
    (gen_random_uuid(), 'photography',   'Photography'),
    (gen_random_uuid(), 'social',        'Social'),
    (gen_random_uuid(), 'dining',        'Dining'),
    (gen_random_uuid(), 'motors',        'Motors'),
    (gen_random_uuid(), 'sensual',       'Sensual'),
    (gen_random_uuid(), 'food',          'Food'),
    (gen_random_uuid(), 'guide',         'Guide'),
    (gen_random_uuid(), 'international',  'International'),
    (gen_random_uuid(), 'glamour',       'Glamour'),
    (gen_random_uuid(), 'club',          'Club'),
    (gen_random_uuid(), 'politics',      'Politics'),
    (gen_random_uuid(), 'lifestyle',     'Lifestyle'),
    (gen_random_uuid(), 'fashion',       'Fashion');
--rollback DROP TABLE sg_config_category;
--rollback DROP TABLE sg_category;
