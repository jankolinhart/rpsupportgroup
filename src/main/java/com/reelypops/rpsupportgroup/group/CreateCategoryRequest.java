package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotBlank;

/** Admin request to create a new content category (Cycle 12). {@code slug} is normalised (lower-cased) by the service. */
public record CreateCategoryRequest(@NotBlank String slug, @NotBlank String label) {
}
