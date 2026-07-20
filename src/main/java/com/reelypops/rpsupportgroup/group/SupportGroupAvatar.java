package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A ReelyPops-hosted avatar for a support group (Cycle 10, 3.5b-3 follow-up), keyed by the group's Instagram
 * account and served at a URL templatable by that username. Only the reelypops client (which has a residential
 * Instagram exit) contributes the real image bytes; the admin/website create routes leave it empty until a
 * client does. Stored separately from {@link SupportGroupConfig} so config reads never drag the image bytes.
 */
@Entity
@Table(name = "support_group_avatar")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportGroupAvatar {

    @Id
    private UUID id;

    @Column(name = "ig_account", nullable = false, unique = true, updatable = false)
    private String igAccount;

    @Column(name = "image", nullable = false)
    private byte[] image;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private SupportGroupAvatar(String igAccount, byte[] image, String contentType) {
        this.id = UUID.randomUUID();
        this.igAccount = igAccount;
        this.image = image;
        this.contentType = contentType;
    }

    /** Create the first avatar for a group. */
    public static SupportGroupAvatar create(String igAccount, byte[] image, String contentType) {
        return new SupportGroupAvatar(igAccount, image, contentType);
    }

    /** Replace the stored image (a client re-contributes a fresher avatar). */
    public void update(byte[] image, String contentType) {
        this.image = image;
        this.contentType = contentType;
    }
}
