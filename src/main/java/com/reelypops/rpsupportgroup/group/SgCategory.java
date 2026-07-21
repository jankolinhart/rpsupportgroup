package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A content category (Cycle 12, §13.11) — an entry in the fixed, admin-curated taxonomy used to classify support
 * groups so the ReelyPops client can filter / search the browse list. {@code slug} is the stable key (immutable);
 * {@code label} is the human display name. Categories are config metadata (a third class beside the authoritative
 * jsonb {@code definition} and the client-local preferences) — admin-owned and not vet-locked.
 */
@Entity
@Table(name = "sg_category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SgCategory {

    @Id
    private UUID id;

    @Getter
    @Column(name = "slug", nullable = false, unique = true, updatable = false)
    private String slug;

    @Getter
    @Column(name = "label", nullable = false)
    private String label;

    private SgCategory(String slug, String label) {
        this.id = UUID.randomUUID();
        this.slug = slug;
        this.label = label;
    }

    /** Create a new category (admin curation). */
    public static SgCategory of(String slug, String label) {
        return new SgCategory(slug, label);
    }
}
