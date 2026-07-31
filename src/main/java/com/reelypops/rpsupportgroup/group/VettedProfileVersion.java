package com.reelypops.rpsupportgroup.group;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One append-only snapshot in a config's vetted-profile HISTORY (M5 A2). Every Vetting-Portal save appends a row; the
 * config's {@code active_snapshot_version} points at the live one, so the operator can ROLL BACK by repointing (the
 * kill switch) without ever losing history. {@code changeNote} is the structured field-level diff vs the prior active
 * snapshot (the client renders it as an i18n announcement, #5.3); {@code null}/empty for the first snapshot.
 */
@Entity
@Table(name = "vetted_profile_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VettedProfileVersion {

    @Id
    private UUID id;

    @Column(name = "config_id", nullable = false, updatable = false)
    private UUID configId;

    @Column(name = "snapshot_version", nullable = false, updatable = false)
    private long snapshotVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vetted_profile", columnDefinition = "jsonb", nullable = false, updatable = false)
    private VettedProfile vettedProfile;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_note", columnDefinition = "jsonb", updatable = false)
    private List<ChangeNoteEntry> changeNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private VettedProfileVersion(UUID configId, long snapshotVersion, VettedProfile vettedProfile,
                                 List<ChangeNoteEntry> changeNote) {
        this.id = UUID.randomUUID();
        this.configId = configId;
        this.snapshotVersion = snapshotVersion;
        this.vettedProfile = vettedProfile;
        this.changeNote = changeNote;
    }

    /** Build a new append-only history snapshot for {@code configId} at {@code snapshotVersion}. */
    public static VettedProfileVersion snapshot(UUID configId, long snapshotVersion, VettedProfile vettedProfile,
                                                List<ChangeNoteEntry> changeNote) {
        return new VettedProfileVersion(configId, snapshotVersion, vettedProfile, changeNote);
    }

    /**
     * One structured field-level change (a JSON field PATH + its before/after values, stringified). The client renders
     * it into the user's language (#5.3), so we carry the path + raw values, never a pre-composed sentence.
     *
     * @param field the changed field's path, e.g. {@code weeklySchedule.days[1].maxTaggedPosts}
     * @param from  the prior value (stringified), or {@code null} when the field was absent/added
     * @param to    the new value (stringified), or {@code null} when the field was removed
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeNoteEntry(String field, String from, String to) {
    }
}
