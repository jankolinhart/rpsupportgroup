package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Stamps a durable, content-addressed display-image {@code imageLocator} onto a to-be-saved {@link VettedProfile}'s
 * marker references (sg-per-weekday-marker-images.md). At vet time each corpus-backed reference's chosen image — the
 * latest corpus snapshot's representative for the reference's {@code shortcode} — is captured into the
 * {@link MarkerImageStore} and the resulting locator recorded on the reference, so the desktop client can fetch + cache
 * + display the canonical per-weekday marker. References with no resolvable corpus image (uploads, a legacy row, or a
 * pruned snapshot) are left unchanged (their {@code imageLocator} stays {@code null}).
 */
@Service
public class MarkerImageEnricher {

    private final MarkerCorpusService corpus;
    private final MarkerImageStore store;

    public MarkerImageEnricher(MarkerCorpusService corpus, MarkerImageStore store) {
        this.corpus = corpus;
        this.store = store;
    }

    /** A copy of {@code profile} whose marker references carry a display-image locator; {@code null} in ⇒ {@code null} out. */
    public VettedProfile enrich(String igAccount, VettedProfile profile) {
        if (profile == null) {
            return null;
        }
        UUID snapshotId = corpus.list(igAccount).stream().findFirst().map(MarkerCorpusSnapshot::getId).orElse(null);
        VettedProfile.DetectorArtifacts detector = profile.detector() == null ? null
                : new VettedProfile.DetectorArtifacts(profile.detector().style(),
                        enrichRefs(snapshotId, profile.detector().references()));
        WeeklyScheduleDefinition weekly = profile.weeklySchedule() == null ? null
                : new WeeklyScheduleDefinition(profile.weeklySchedule().days().stream()
                        .map(day -> day.withReferences(enrichRefs(snapshotId, day.references())))
                        .toList());
        return new VettedProfile(profile.definition(), detector, profile.description(), weekly);
    }

    private List<VettedProfile.TypedMarkerReference> enrichRefs(
            UUID snapshotId, List<VettedProfile.TypedMarkerReference> refs) {
        return refs == null ? refs : refs.stream().map(ref -> enrichRef(snapshotId, ref)).toList();
    }

    private VettedProfile.TypedMarkerReference enrichRef(UUID snapshotId, VettedProfile.TypedMarkerReference ref) {
        if (snapshotId == null || ref.shortcode() == null || ref.shortcode().isBlank()) {
            return ref;
        }
        return corpus.getRepresentative(snapshotId, ref.shortcode())
                .flatMap(rep -> store.capture(rep.getImage()))
                .map(ref::withImageLocator)
                .orElse(ref);
    }
}
