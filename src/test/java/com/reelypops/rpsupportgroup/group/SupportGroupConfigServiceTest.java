package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.group.VettedProfileVersion.ChangeNoteEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused unit coverage for {@link SupportGroupConfigService#activeChangeNote} (M5.3b) — its three branches:
 * no active snapshot, the active snapshot's note, and the defensive missing-row fallback (unreachable via the API,
 * so exercised here in isolation).
 */
class SupportGroupConfigServiceTest {

    private final SupportGroupConfigRepository configs = mock(SupportGroupConfigRepository.class);
    private final VettedProfileVersionRepository versions = mock(VettedProfileVersionRepository.class);
    private final SupportGroupConfigService service = new SupportGroupConfigService(configs, versions);

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
}
