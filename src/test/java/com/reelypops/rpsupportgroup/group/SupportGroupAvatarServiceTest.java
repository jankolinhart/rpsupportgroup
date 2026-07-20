package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test (service in isolation, repository mocked) for the SG avatar upsert/get logic: the insert vs update
 * branches, the default content-type fallback, and the empty-image guard.
 */
@ExtendWith(MockitoExtension.class)
class SupportGroupAvatarServiceTest {

    @Mock
    SupportGroupAvatarRepository avatars;

    @InjectMocks
    SupportGroupAvatarService service;

    @Test
    void putInsertsWhenAbsent() {
        when(avatars.findByIgAccount("g")).thenReturn(Optional.empty());

        service.put("g", new byte[] { 1, 2 }, "image/png");

        ArgumentCaptor<SupportGroupAvatar> captor = ArgumentCaptor.forClass(SupportGroupAvatar.class);
        verify(avatars).save(captor.capture());
        SupportGroupAvatar saved = captor.getValue();
        assertThat(saved.getIgAccount()).isEqualTo("g");
        assertThat(saved.getImage()).containsExactly(1, 2);
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNull(); // set by @UpdateTimestamp at persist, not in this unit test
    }

    @Test
    void putUpdatesWhenPresent() {
        SupportGroupAvatar existing = SupportGroupAvatar.create("g", new byte[] { 0 }, "image/jpeg");
        when(avatars.findByIgAccount("g")).thenReturn(Optional.of(existing));

        service.put("g", new byte[] { 7 }, "image/png");

        verify(avatars).save(existing);
        assertThat(existing.getImage()).containsExactly(7);
        assertThat(existing.getContentType()).isEqualTo("image/png");
    }

    @Test
    void putDefaultsNullContentTypeToJpeg() {
        when(avatars.findByIgAccount("g")).thenReturn(Optional.empty());

        service.put("g", new byte[] { 1 }, null);

        ArgumentCaptor<SupportGroupAvatar> captor = ArgumentCaptor.forClass(SupportGroupAvatar.class);
        verify(avatars).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void putDefaultsBlankContentTypeToJpeg() {
        when(avatars.findByIgAccount("g")).thenReturn(Optional.empty());

        service.put("g", new byte[] { 1 }, "   ");

        ArgumentCaptor<SupportGroupAvatar> captor = ArgumentCaptor.forClass(SupportGroupAvatar.class);
        verify(avatars).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void putRejectsNullImage() {
        assertThatThrownBy(() -> service.put("g", null, "image/png"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void putRejectsEmptyImage() {
        assertThatThrownBy(() -> service.put("g", new byte[0], "image/png"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getReturnsStoredAvatar() {
        SupportGroupAvatar a = SupportGroupAvatar.create("g", new byte[] { 1 }, "image/jpeg");
        when(avatars.findByIgAccount("g")).thenReturn(Optional.of(a));

        assertThat(service.get("g")).containsSame(a);
    }
}
