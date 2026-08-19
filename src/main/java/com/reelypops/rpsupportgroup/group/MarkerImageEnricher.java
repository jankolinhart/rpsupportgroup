package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stamps a durable, content-addressed display-image {@code imageLocator} onto a to-be-saved {@link VettedProfile}'s
 * marker references (sg-per-weekday-marker-images.md). At vet time each corpus-backed reference's chosen image — the
 * latest corpus snapshot's representative for the reference's {@code shortcode} — is captured into the
 * {@link MarkerImageStore} and the resulting locator recorded on the reference, so the desktop client can fetch + cache
 * + display the canonical per-weekday marker. References with no resolvable corpus image (uploads, a legacy row, or a
 * pruned snapshot) are left unchanged (their {@code imageLocator} stays {@code null}), as are references whose
 * display image came from a client's live capture ({@code source = client-drift}) — see {@link VettedProfileHashAdopter}.
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
        VettedProfile.DetectorArtifacts detector = profile.detector() == null ? null
                : new VettedProfile.DetectorArtifacts(profile.detector().style(),
                        enrichRefs(igAccount, profile.detector().references()));
        WeeklyScheduleDefinition weekly = profile.weeklySchedule() == null ? null
                : new WeeklyScheduleDefinition(profile.weeklySchedule().days().stream()
                        .map(day -> day.withReferences(enrichRefs(igAccount, day.references())))
                        .toList());
        return new VettedProfile(profile.definition(), detector, profile.description(), weekly);
    }

    private List<VettedProfile.TypedMarkerReference> enrichRefs(
            String igAccount, List<VettedProfile.TypedMarkerReference> refs) {
        return refs == null ? refs : refs.stream().map(ref -> enrichRef(igAccount, ref)).toList();
    }

    private VettedProfile.TypedMarkerReference enrichRef(String igAccount, VettedProfile.TypedMarkerReference ref) {
        if (ref.shortcode() == null || ref.shortcode().isBlank()) {
            return ref;
        }
        // A picture a CLIENT delivered (an adopted banner drift) is newer than anything the corpus holds — the
        // corpus snapshot predates the drift by definition. Re-deriving from the shortcode here would silently
        // put the SUPERSEDED image back, so an administrator would adopt the new banner and still be shown the
        // old one. Adoption stamps this source precisely so this pass leaves it alone.
        if (VettedProfileHashAdopter.SOURCE_CLIENT_DRIFT.equals(ref.source())) {
            return ref;
        }
        // ⚠️ Searched ACROSS snapshots, newest-first — never "the newest snapshot" alone. The newest is not
        // necessarily the one the reference was vetted from: on 19/08/2026 an EMPTY, OPEN snapshot (a deep
        // scrape cancelled seconds in) shadowed the sealed 5229-item pass the operator actually used, and every
        // reference lost its picture — grid-picked and AI-accepted alike, because the lookup universe was empty.
        return corpus.findRepresentative(igAccount, ref.shortcode())
                .flatMap(rep -> store.capture(rep.getImage()))
                .map(ref::withImageLocator)
                .orElse(ref);
    }
}
