package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The per-reporter drift ledger (M5 re-vet consumer). */
public interface DriftObservationRepository extends JpaRepository<DriftObservation, UUID> {

    /**
     * The existing observation for a reporter and ONE REFERENCE — natural key: config + kind + reporter + the
     * reference's role and text, with no nominated handle.
     *
     * <p>⚠️ <strong>The reference belongs in the key, and leaving it out lost data.</strong> A per-weekday group
     * carries several banners per kind — `glowbloggeragency` has an ENDE, a generic weekday START and a distinct
     * Sunday START — and they drift independently. The WEEKDAY joined the key on 19/08/2026: the per-weekday
     * profile makes the same role+text on another day a different reference (a Monday flower and a Tuesday monkey
     * can both read "START"), so the day is identity, not an attribute. Keyed on the reporter alone, all of them upserted into the SAME
     * row and the last one reported won. Measured live 18/08/2026: one scan delivered three drifting references
     * (ENDE 17 bits, weekday START 26, Sunday START 21) and the administrator was shown one of them — not the
     * worst. It also bumped {@code persistenceCount} ("consecutive drifting scans") three times in a single scan.</p>
     *
     * <p>Role AND text, never role alone: one role covers several banners. Blank role/text match the rows that
     * carry neither — a MARKER_DISAGREE tally, a NEW_OWNER nomination — so those keep their single-row behaviour.</p>
     */
    @Query("select o from DriftObservation o where o.configId = :configId and o.kind = :kind"
            + " and o.reporterDeviceId = :reporterDeviceId and o.nominatedOwnerHandle is null"
            + " and coalesce(o.markerWeekday, -1) = coalesce(:markerWeekday, -1)"
            + " and coalesce(o.markerRole, '') = coalesce(:markerRole, '')"
            + " and coalesce(o.markerText, '') = coalesce(:markerText, '')")
    Optional<DriftObservation> findForReference(@Param("configId") UUID configId,
                                                @Param("kind") DriftKind kind,
                                                @Param("reporterDeviceId") String reporterDeviceId,
                                                @Param("markerWeekday") Integer markerWeekday,
                                                @Param("markerRole") String markerRole,
                                                @Param("markerText") String markerText);

    /** The existing new-owner nomination for a reporter + handle (natural key: config + reporter + handle). */
    Optional<DriftObservation> findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandle(
            UUID configId, DriftKind kind, String reporterDeviceId, String nominatedOwnerHandle);

    /** Unresolved observations of a kind for one config, newest-seen first — powers the derivation + nominations view. */
    List<DriftObservation> findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(UUID configId, DriftKind kind);

    /** Unresolved observations of several kinds for one config, newest-seen first — the marker-reference health view. */
    List<DriftObservation> findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(
            UUID configId, Collection<DriftKind> kinds);

    /** Unresolved observations of a kind across many configs — powers the batch re-vet derivation for the admin list. */
    List<DriftObservation> findByConfigIdInAndKindAndResolvedFalse(Collection<UUID> configIds, DriftKind kind);

    /** ALL unresolved observations (every kind) for one config — the re-vet derivation now spans marker-disagree + new-owner. */
    List<DriftObservation> findByConfigIdAndResolvedFalse(UUID configId);

    /** ALL unresolved observations (every kind) across many configs — the batch re-vet derivation for the admin list. */
    List<DriftObservation> findByConfigIdInAndResolvedFalse(Collection<UUID> configIds);

    /** The open observations of a kind nominating a specific handle (every reporter) — resolved by confirm/dismiss. */
    List<DriftObservation> findByConfigIdAndKindAndNominatedOwnerHandleAndResolvedFalse(
            UUID configId, DriftKind kind, String nominatedOwnerHandle);
}
