package com.reelypops.rpsupportgroup.group;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * SG avatar store (Cycle 10, 3.5b-3 follow-up). Client-contributed images are upserted per group (keyed on the
 * Instagram account) and served back at a templatable-by-username URL; a group with no contributed avatar simply
 * 404s so the client renders a placeholder. Only the reelypops client contributes bytes (it has the residential
 * exit); the admin/website create routes never call this.
 */
@Service
public class SupportGroupAvatarService {

    private static final String DEFAULT_CONTENT_TYPE = "image/jpeg";

    private final SupportGroupAvatarRepository avatars;

    public SupportGroupAvatarService(SupportGroupAvatarRepository avatars) {
        this.avatars = avatars;
    }

    /** Upsert the avatar image for a group (a client contributes the bytes it fetched via its residential exit). */
    @Transactional
    public void put(String igAccount, byte[] image, String contentType) {
        if (image == null || image.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "avatar image is required");
        }
        String type = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        SupportGroupAvatar avatar = avatars.findByIgAccount(igAccount)
                .map(existing -> {
                    existing.update(image, type);
                    return existing;
                })
                .orElseGet(() -> SupportGroupAvatar.create(igAccount, image, type));
        avatars.save(avatar);
    }

    /** The stored avatar for a group, or empty when none has been contributed yet. */
    @Transactional(readOnly = true)
    public Optional<SupportGroupAvatar> get(String igAccount) {
        return avatars.findByIgAccount(igAccount);
    }
}
