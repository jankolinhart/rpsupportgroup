package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.group.SupportGroupConfigService.RevetStatus;
import com.reelypops.rpsupportgroup.group.VettedProfileVersion.ChangeNoteEntry;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused unit coverage for {@link SupportGroupConfigService#activeChangeNote} (M5.3b) and the M5 re-vet consumer:
 * ingesting {@link DriftObservation drift observations} (upsert per reporter), deriving a config's needs-re-vet status
 * + aggregated reason, the batch derivation for the admin list, and the new-owner nominations query.
 */
class SupportGroupConfigServiceTest {

    private final SupportGroupConfigRepository configs = mock(SupportGroupConfigRepository.class);
    private final VettedProfileVersionRepository versions = mock(VettedProfileVersionRepository.class);
    private final DriftObservationRepository driftObservations = mock(DriftObservationRepository.class);
    private final MarkerImageEnricher markerImageEnricher = mock(MarkerImageEnricher.class);
    private final MarkerImageStore markerImageStore = mock(MarkerImageStore.class);
    private final ClientMarkerImageRepository clientImages = mock(ClientMarkerImageRepository.class);
    private final MarkerCorpusService corpusService = mock(MarkerCorpusService.class);
    private final SupportGroupConfigService service =
            new SupportGroupConfigService(configs, versions, driftObservations, markerImageEnricher, markerImageStore, clientImages, corpusService);

    @Test
    void activeChangeNoteIsEmptyWhenThereIsNoActiveSnapshot() {
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getActiveSnapshotVersion()).thenReturn(null);

        assertThat(service.activeChangeNote(c)).isEmpty();
        verifyNoInteractions(versions);
    }

    @Test
    void activeChangeNoteReturnsTheActiveSnapshotsNote() {
        UUID configId = UUID.randomUUID();
        List<ChangeNoteEntry> note = List.of(new ChangeNoteEntry("description", "old", "new"));
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getActiveSnapshotVersion()).thenReturn(3L);
        when(c.getId()).thenReturn(configId);
        VettedProfileVersion row = mock(VettedProfileVersion.class);
        when(row.getChangeNote()).thenReturn(note);
        when(versions.findByConfigIdAndSnapshotVersion(configId, 3L)).thenReturn(Optional.of(row));

        assertThat(service.activeChangeNote(c)).isEqualTo(note);
    }

    @Test
    void activeChangeNoteIsEmptyWhenTheActiveSnapshotRowIsMissing() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getActiveSnapshotVersion()).thenReturn(5L);
        when(c.getId()).thenReturn(configId);
        when(versions.findByConfigIdAndSnapshotVersion(configId, 5L)).thenReturn(Optional.empty());

        assertThat(service.activeChangeNote(c)).isEmpty();
    }

    // --- M5 re-vet consumer: recordDrift (upsert per reporter) ---

    @Test
    void recordDriftRejectsANewOwnerWithoutAHandle() {
        assertThatThrownBy(() -> service.recordDrift("ig", DriftKind.NEW_OWNER, "dev-1", UUID.randomUUID(),
                "  ", 1, 2, 3))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nominatedOwnerHandle is required");
        verifyNoInteractions(configs, driftObservations);
    }

    @Test
    void recordDriftInsertsTheFirstMarkerDisagreeObservationForAReporter() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(configs.findByIgAccount("ig")).thenReturn(Optional.of(c));
        // A MARKER_DISAGREE tally carries no reference (null role/text), so it still keys to a single row per
        // group — the per-reference key only fans out for the kinds that actually name a reference.
        when(driftObservations.findForReference(configId, DriftKind.MARKER_DISAGREE, "dev-1", null, null))
                .thenReturn(Optional.empty());
        when(driftObservations.save(any(DriftObservation.class))).thenAnswer(inv -> inv.getArgument(0));

        DriftObservation saved = service.recordDrift("ig", DriftKind.MARKER_DISAGREE, "dev-1", null,
                "ignored-for-marker-disagree", 5, 2, 4);

        assertThat(saved.getKind()).isEqualTo(DriftKind.MARKER_DISAGREE);
        assertThat(saved.getReporterDeviceId()).isEqualTo("dev-1");
        assertThat(saved.getNominatedOwnerHandle()).isNull();           // handle ignored for a marker-disagree drift
        assertThat(saved.getAgreePass()).isEqualTo(5);
        assertThat(saved.getDisagreePass()).isEqualTo(2);
        assertThat(saved.getPersistenceCount()).isEqualTo(4);
        assertThat(saved.getOccurrenceCount()).isEqualTo(1L);
        assertThat(saved.isResolved()).isFalse();
    }

    @Test
    void recordDriftBumpsAnExistingMarkerDisagreeObservation() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(configs.findByIgAccount("ig")).thenReturn(Optional.of(c));
        DriftObservation existing = DriftObservation.first(configId, DriftKind.MARKER_DISAGREE, "dev-1", null, null,
                5, 2, 4, Instant.parse("2026-01-01T00:00:00Z"));
        existing.resolve();                                             // a prior re-vet had resolved it
        when(driftObservations.findForReference(configId, DriftKind.MARKER_DISAGREE, "dev-1", null, null))
                .thenReturn(Optional.of(existing));
        when(driftObservations.save(any(DriftObservation.class))).thenAnswer(inv -> inv.getArgument(0));

        DriftObservation saved = service.recordDrift("ig", DriftKind.MARKER_DISAGREE, "dev-1", null, null, 6, 1, 7);

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getOccurrenceCount()).isEqualTo(2L);
        assertThat(saved.getAgreePass()).isEqualTo(6);                  // refreshed to the latest tally
        assertThat(saved.getDisagreePass()).isEqualTo(1);
        assertThat(saved.getPersistenceCount()).isEqualTo(7);
        assertThat(saved.isResolved()).isFalse();                      // a fresh drift re-opens the flag
    }

    @Test
    void recordDriftUpsertsANewOwnerNominationKeyedOnTheHandle() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(configs.findByIgAccount("ig")).thenReturn(Optional.of(c));
        when(driftObservations.findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandle(
                configId, DriftKind.NEW_OWNER, "dev-1", "cand.owner")).thenReturn(Optional.empty());
        when(driftObservations.save(any(DriftObservation.class))).thenAnswer(inv -> inv.getArgument(0));

        DriftObservation saved = service.recordDrift("ig", DriftKind.NEW_OWNER, "dev-1", null, "cand.owner",
                null, null, null);

        assertThat(saved.getKind()).isEqualTo(DriftKind.NEW_OWNER);
        assertThat(saved.getNominatedOwnerHandle()).isEqualTo("cand.owner");
    }

    // --- M5 re-vet consumer: derived re-vet status + aggregated reason ---

    @Test
    void revetStatusIsNoneWhenThereAreNoOpenObservations() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(driftObservations.findByConfigIdAndResolvedFalse(configId)).thenReturn(List.of());

        RevetStatus status = service.revetStatus(c);

        assertThat(status.needsRevet()).isFalse();
        assertThat(status.reasons()).isEmpty();
    }

    @Test
    void revetStatusAggregatesOpenObservationsAcrossReporters() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        // Reporter A: earlier, no persistence count. Reporter B: later (the latest tally), persistence 3.
        DriftObservation a = DriftObservation.first(configId, DriftKind.MARKER_DISAGREE, "dev-a", null, null,
                5, 2, null, Instant.parse("2026-01-01T00:00:00Z"));
        DriftObservation b = DriftObservation.first(configId, DriftKind.MARKER_DISAGREE, "dev-b", null, null,
                7, 3, 3, Instant.parse("2026-01-02T00:00:00Z"));
        when(driftObservations.findByConfigIdAndResolvedFalse(configId)).thenReturn(List.of(a, b));

        RevetStatus status = service.revetStatus(c);

        assertThat(status.needsRevet()).isTrue();
        assertThat(status.reasons()).hasSize(1);
        RevetReason reason = status.reasons().get(0);
        assertThat(reason.kind()).isEqualTo(DriftKind.MARKER_DISAGREE);
        assertThat(reason.distinctReporters()).isEqualTo(2);
        assertThat(reason.totalOccurrences()).isEqualTo(2L);
        assertThat(reason.latestAgreePass()).isEqualTo(7);             // from B, the newest last-seen
        assertThat(reason.latestDisagreePass()).isEqualTo(3);
        assertThat(reason.maxPersistenceCount()).isEqualTo(3);
        assertThat(reason.firstSeenAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(reason.lastSeenAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void revetReasonHasNoMaxPersistenceWhenNoObservationCarriesOne() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        DriftObservation a = DriftObservation.first(configId, DriftKind.MARKER_DISAGREE, "dev-a", null, null,
                5, 2, null, Instant.parse("2026-01-01T00:00:00Z"));
        when(driftObservations.findByConfigIdAndResolvedFalse(configId)).thenReturn(List.of(a));

        assertThat(service.revetStatus(c).reasons().get(0).maxPersistenceCount()).isNull();
    }

    @Test
    void revetStatusesIsEmptyForNoConfigs() {
        assertThat(service.revetStatuses(List.of())).isEmpty();
        verifyNoInteractions(driftObservations);
    }

    @Test
    void revetStatusesDerivesEachConfigIncludingOnesWithoutOpenObservations() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        SupportGroupConfig c1 = mock(SupportGroupConfig.class);
        SupportGroupConfig c2 = mock(SupportGroupConfig.class);
        when(c1.getId()).thenReturn(id1);
        when(c2.getId()).thenReturn(id2);
        DriftObservation obs = DriftObservation.first(id1, DriftKind.MARKER_DISAGREE, "dev-a", null, null,
                5, 2, 1, Instant.parse("2026-01-01T00:00:00Z"));
        when(driftObservations.findByConfigIdInAndResolvedFalse(List.of(id1, id2)))
                .thenReturn(List.of(obs));

        Map<UUID, RevetStatus> statuses = service.revetStatuses(List.of(c1, c2));

        assertThat(statuses.get(id1).needsRevet()).isTrue();
        assertThat(statuses.get(id2).needsRevet()).isFalse();          // no open observation → none
        assertThat(statuses.get(id2).reasons()).isEmpty();
    }

    @Test
    void newOwnerNominationsReturnsTheConfigsOpenNominations() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(configs.findByIgAccount("ig")).thenReturn(Optional.of(c));
        DriftObservation nom = DriftObservation.first(configId, DriftKind.NEW_OWNER, "dev-a", null, "cand.owner",
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
        when(driftObservations.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                configId, DriftKind.NEW_OWNER)).thenReturn(List.of(nom));

        assertThat(service.newOwnerNominations("ig")).containsExactly(nom);
    }

    @Test
    void newOwnerObservationsFlagRevetWithTheDistinctNominatedHandles() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        // Two reporters nominate two different handles (out of order → the reason sorts them).
        DriftObservation n1 = DriftObservation.first(configId, DriftKind.NEW_OWNER, "dev-a", null, "cand.two",
                null, null, null, Instant.parse("2026-01-02T00:00:00Z"));
        DriftObservation n2 = DriftObservation.first(configId, DriftKind.NEW_OWNER, "dev-b", null, "cand.one",
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
        when(driftObservations.findByConfigIdAndResolvedFalse(configId)).thenReturn(List.of(n1, n2));

        RevetStatus status = service.revetStatus(c);

        assertThat(status.needsRevet()).isTrue();
        assertThat(status.reasons()).hasSize(1);
        RevetReason reason = status.reasons().get(0);
        assertThat(reason.kind()).isEqualTo(DriftKind.NEW_OWNER);
        assertThat(reason.nominatedOwnerHandles()).containsExactly("cand.one", "cand.two"); // distinct + sorted
        assertThat(reason.distinctReporters()).isEqualTo(2);
        assertThat(reason.latestAgreePass()).isNull();                 // marker-disagree tally N/A for new-owner
        assertThat(reason.maxPersistenceCount()).isNull();
        assertThat(reason.firstSeenAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(reason.lastSeenAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void revetStatusCarriesOneReasonPerKindWhenBothArePresent() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        DriftObservation md = DriftObservation.first(configId, DriftKind.MARKER_DISAGREE, "dev-a", null, null,
                5, 2, 3, Instant.parse("2026-01-01T00:00:00Z"));
        DriftObservation no = DriftObservation.first(configId, DriftKind.NEW_OWNER, "dev-b", null, "cand.owner",
                null, null, null, Instant.parse("2026-01-02T00:00:00Z"));
        when(driftObservations.findByConfigIdAndResolvedFalse(configId)).thenReturn(List.of(no, md));

        RevetStatus status = service.revetStatus(c);

        assertThat(status.reasons()).hasSize(2);
        assertThat(status.reasons().get(0).kind()).isEqualTo(DriftKind.MARKER_DISAGREE); // marker-disagree first
        assertThat(status.reasons().get(1).kind()).isEqualTo(DriftKind.NEW_OWNER);
        assertThat(status.reasons().get(1).nominatedOwnerHandles()).containsExactly("cand.owner");
    }

    @Test
    void confirmNominationAddsTheOwnerResolvesTheHandlesNominationsAndSaves() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(configs.findByIgAccount("ig")).thenReturn(Optional.of(c));
        when(configs.save(c)).thenReturn(c);
        DriftObservation nom = DriftObservation.first(configId, DriftKind.NEW_OWNER, "dev-a", null, "cand.owner",
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
        when(driftObservations.findByConfigIdAndKindAndNominatedOwnerHandleAndResolvedFalse(
                configId, DriftKind.NEW_OWNER, "cand.owner")).thenReturn(List.of(nom));

        SupportGroupConfig result = service.confirmNomination("ig", "cand.owner");

        assertThat(result).isSameAs(c);
        verify(c).addMarkerOwner("cand.owner");                        // incorporated (idempotent; bumps version)
        assertThat(nom.isResolved()).isTrue();                         // its nomination dropped off the review list
        verify(driftObservations).saveAll(List.of(nom));
        verify(configs).save(c);                                       // persisted (the version bump reaches clients)
    }

    @Test
    void dismissNominationResolvesTheHandlesNominationsWithoutTouchingTheOwnerSetOrVersion() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(configs.findByIgAccount("ig")).thenReturn(Optional.of(c));
        DriftObservation nom = DriftObservation.first(configId, DriftKind.NEW_OWNER, "dev-a", null, "cand.owner",
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
        when(driftObservations.findByConfigIdAndKindAndNominatedOwnerHandleAndResolvedFalse(
                configId, DriftKind.NEW_OWNER, "cand.owner")).thenReturn(List.of(nom));

        SupportGroupConfig result = service.dismissNomination("ig", "cand.owner");

        assertThat(result).isSameAs(c);
        assertThat(nom.isResolved()).isTrue();
        verify(driftObservations).saveAll(List.of(nom));
        verify(c, never()).addMarkerOwner(anyString());               // NOT a marker owner
        verify(configs, never()).save(any());                          // no config mutation / no ETag bump
    }

    @Test
    void recordDriftForAnUnknownConfigIsNotFound() {
        when(configs.findByIgAccount("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordDrift("missing", DriftKind.MARKER_DISAGREE, "dev-1", null, null,
                1, 1, 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no config for missing");
        verify(driftObservations, never()).save(any());
    }

    // --- M5 re-vet consumer: acknowledge (clear-without-re-vet) ---

    @Test
    void acknowledgeRevetResolvesTheConfigsOpenMarkerDisagreeObservations() {
        UUID configId = UUID.randomUUID();
        SupportGroupConfig c = mock(SupportGroupConfig.class);
        when(c.getId()).thenReturn(configId);
        when(configs.findByIgAccount("ig")).thenReturn(Optional.of(c));
        DriftObservation open = DriftObservation.first(configId, DriftKind.MARKER_DISAGREE, "dev-a", null, null,
                5, 2, 1, Instant.parse("2026-01-01T00:00:00Z"));
        when(driftObservations.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                configId, DriftKind.MARKER_DISAGREE)).thenReturn(List.of(open));

        SupportGroupConfig result = service.acknowledgeRevet("ig");

        assertThat(result).isSameAs(c);                                // the config is returned unchanged
        assertThat(open.isResolved()).isTrue();                        // its open observation was resolved
        verify(driftObservations).saveAll(List.of(open));
        verify(configs, never()).save(any());                          // no config mutation / no ETag bump
    }

    @Test
    void acknowledgeRevetForAnUnknownConfigIsNotFound() {
        when(configs.findByIgAccount("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledgeRevet("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no config for missing");
        verify(driftObservations, never()).saveAll(any());
    }
}
