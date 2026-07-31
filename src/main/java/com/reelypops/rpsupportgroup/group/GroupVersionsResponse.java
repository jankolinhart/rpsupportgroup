package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.group.VettedProfileVersion.ChangeNoteEntry;

import java.time.Instant;
import java.util.List;

/**
 * The admin version-history view (M5 A2): a config's vetted-profile snapshots newest-first, plus which snapshot
 * is currently active. Each {@link VersionView} carries the human-readable change note captured when the snapshot
 * was written, so the console can render a "what changed" diff and offer a one-click rollback / activate.
 */
public record GroupVersionsResponse(Long activeSnapshotVersion, List<VersionView> versions) {

    public static GroupVersionsResponse of(SupportGroupConfigService.GroupVersions v) {
        Long active = v.activeSnapshotVersion();
        List<VersionView> views = v.versions().stream()
                .map(row -> new VersionView(row.getSnapshotVersion(), row.getCreatedAt(), row.getChangeNote(),
                        active != null && active == row.getSnapshotVersion()))
                .toList();
        return new GroupVersionsResponse(active, views);
    }

    /** One historical snapshot: its version number, when it was written, what changed, and whether it is live now. */
    public record VersionView(long snapshotVersion, Instant createdAt, List<ChangeNoteEntry> changeNote, boolean active) {
    }
}
