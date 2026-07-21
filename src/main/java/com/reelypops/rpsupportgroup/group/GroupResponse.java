package com.reelypops.rpsupportgroup.group;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An SG config as returned by the API — the shared {@link GroupDefinition} + lifecycle + the {@code version}
 * ETag the client polls (Q1) + the admin-curated content-category slugs (Cycle 12). Both the JWT client surface
 * and the internal scanner/BFF surface return this.
 */
public record GroupResponse(
        UUID id,
        String igAccount,
        ConfigStatus status,
        UUID ownerId,
        boolean adminAttributed,
        boolean vetted,
        GroupDefinition definition,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<String> categories) {

    static GroupResponse of(SupportGroupConfig c) {
        return new GroupResponse(c.getId(), c.getIgAccount(), c.getStatus(), c.getOwnerId(),
                c.isAdminAttributed(), c.isVetted(), c.getDefinition(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getCategories().stream().map(SgCategory::getSlug).sorted().toList());
    }
}
