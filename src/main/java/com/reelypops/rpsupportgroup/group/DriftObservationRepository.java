package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The per-reporter drift ledger (M5 re-vet consumer). */
public interface DriftObservationRepository extends JpaRepository<DriftObservation, UUID> {

    /** The existing marker-disagree observation for a reporter (natural key: config + reporter, null handle). */
    Optional<DriftObservation> findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandleIsNull(
            UUID configId, DriftKind kind, String reporterDeviceId);

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
